#!/usr/bin/env python3
"""Fetch the native binaries the APK ships, and verify each against a pinned hash.

The engine is 198 MB and cannot live in git (GitHub's limit is 100 MB per file), so it is fetched
and verified instead. That is also the point: a build that silently picks up a different opencode
version than the one every claim in docs/ was measured against would invalidate those claims, so a
hash mismatch fails the build rather than warning.

  python -I tools/fetch_binaries.py            # stage into app/src/main/jniLibs + assets
  python -I tools/fetch_binaries.py --check    # verify what is already staged, download nothing

Outputs:
  app/src/main/jniLibs/arm64-v8a/libopencode.so   exec'd from nativeLibraryDir
  app/src/main/jniLibs/arm64-v8a/libproot.so      same; proot is a Bionic binary
  app/src/main/assets/proot-deps.dat              gzipped tar of proot's .so deps (talloc, shmem)

The deps go to assets, not jniLibs, for two reasons: AGP only accepts a `.so` suffix in jniLibs and
`libtalloc.so.2` is not one, and proot resolves them through LD_LIBRARY_PATH at exec time rather than
through the app's native library search path.
"""
import argparse
import gzip
import hashlib
import io
import json
import re
import sys
import tarfile
import urllib.request
from pathlib import Path


def fail(message: str):
    """Exit non-zero with a message.

    `raise SystemExit("text")` looks like a failure but exits 0, which CI reads as success.
    """
    print(message, file=sys.stderr)
    raise SystemExit(1)

# Pinned in docs/06-artifact-verification.md §1. If opencode is ever re-pinned, change it there
# first — that document is the authority for every measurement taken against this binary.
ENGINE = {
    "package": "@opencode/cli-linux-arm64-musl",
    "version": "2.1.1",
    "member": "package/bin/opencode",
    "sha256": "e07ad6ddf4a2ea62210b4fa11df47e78a8070c9382cdb8919e36bd4f7b2cf7d7",
    "bytes": 207162576,
}

TERMUX = "https://packages.termux.dev/apt/termux-main"
TERMUX_PACKAGES = ["proot", "libtalloc", "libandroid-shmem"]

# Which .so files each Termux package contributes, and the name the loader will ask for.
PROOT_LIBS = {"libtalloc.so.2.5.0": "libtalloc.so.2", "libandroid-shmem.so": "libandroid-shmem.so"}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fetch(url: str, cache: Path) -> bytes:
    dest = cache / sha256(url.encode())[:16]
    if dest.exists():
        return dest.read_bytes()
    with urllib.request.urlopen(url, timeout=300) as resp:
        data = resp.read()
    dest.write_bytes(data)
    return data


def termux_index(cache: Path) -> dict:
    """package name -> {version, filename, sha256} from Termux's signed index."""
    text = fetch(f"{TERMUX}/dists/stable/main/binary-aarch64/Packages", cache).decode()
    out, cur = {}, {}
    for line in text.splitlines() + [""]:
        if not line:
            if "Package" in cur:
                out[cur["Package"]] = cur
            cur = {}
        elif ": " in line:
            key, _, value = line.partition(": ")
            cur[key] = value
    return out


def stage_engine(cache: Path, dest: Path, check_only: bool) -> str:
    if check_only:
        if not dest.exists():
            fail(f"missing: {dest}")
        digest = sha256(dest.read_bytes())
        if digest != ENGINE["sha256"]:
            fail(f"{dest} sha256 {digest} != pinned {ENGINE['sha256']}")
        return digest

    url = f"https://registry.npmjs.org/{ENGINE['package']}/-/{ENGINE['package'].split('/')[-1]}-{ENGINE['version']}.tgz"
    data = fetch(url, cache)
    with tarfile.open(fileobj=io.BytesIO(data)) as tf:
        binary = tf.extractfile(ENGINE["member"]).read()

    digest = sha256(binary)
    if digest != ENGINE["sha256"]:
        fail(
            f"engine sha256 {digest}\n  != pinned {ENGINE['sha256']}\n"
            f"npm returned a different artifact for {ENGINE['package']}@{ENGINE['version']}. "
            "Every claim in docs/06 was measured against the pinned binary; do not proceed."
        )
    if len(binary) != ENGINE["bytes"]:
        fail(f"engine is {len(binary)} bytes, expected {ENGINE['bytes']}")

    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(binary)
    dest.chmod(0o755)
    return digest


def stage_proot(cache: Path, jni: Path, assets: Path, check_only: bool) -> dict:
    index = termux_index(cache)
    out = {}
    libs = {}

    for name in TERMUX_PACKAGES:
        entry = index.get(name)
        if entry is None:
            fail(f"{name} not in the Termux index")
        if check_only:
            out[name] = entry["Version"]
            if name == "proot" and not (jni / "libproot.so").exists():
                fail(f"missing: {jni / 'libproot.so'}")
            continue

        data = fetch(f"{TERMUX}/{entry['Filename']}", cache)
        digest = sha256(data)
        if digest != entry["SHA256"]:
            fail(f"{name} sha256 {digest} != index {entry['SHA256']}")
        out[name] = entry["Version"]

        # An .apk-style .deb: ar archive holding data.tar.xz.
        with tarfile.open(fileobj=io.BytesIO(_ar_member(data, "data.tar.xz")), mode="r:xz") as tf:
            for member in tf.getmembers():
                if not member.isfile():
                    continue
                base = member.name.rsplit("/", 1)[-1]
                if base == "proot":
                    (jni / "libproot.so").write_bytes(tf.extractfile(member).read())
                    (jni / "libproot.so").chmod(0o755)
                elif base in PROOT_LIBS:
                    libs[PROOT_LIBS[base]] = tf.extractfile(member).read()

    if not check_only:
        with open(assets / "proot-deps.dat", "wb") as raw:
            with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=1_700_000_000) as gz:
                with tarfile.open(fileobj=gz, mode="w") as tf:
                    for name, body in sorted(libs.items()):
                        info = tarfile.TarInfo(name)
                        info.size = len(body)
                        info.mode = 0o755
                        info.mtime = 1_700_000_000
                        tf.addfile(info, io.BytesIO(body))
    return out


def _ar_member(data: bytes, want: str) -> bytes:
    """Pull one member out of a Unix ar archive (debian .deb)."""
    if data[:8] != b"!<arch>\n":
        fail("not an ar archive")
    offset = 8
    while offset < len(data):
        header = data[offset:offset + 60]
        name = header[:16].decode().strip()
        size = int(header[48:58].decode().strip())
        body = data[offset + 60:offset + 60 + size]
        if name.rstrip("/") == want:
            return body
        offset += 60 + size + (size % 2)
    fail(f"{want} not found in archive")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=".cache/binaries")
    ap.add_argument("--check", action="store_true", help="verify staged files, download nothing")
    args = ap.parse_args()

    cache = Path(args.cache)
    cache.mkdir(parents=True, exist_ok=True)
    jni = Path("app/src/main/jniLibs/arm64-v8a")
    assets = Path("app/src/main/assets")
    assets.mkdir(parents=True, exist_ok=True)

    engine = stage_engine(cache, jni / "libopencode.so", args.check)
    print(f"libopencode.so  {ENGINE['version']}  {engine[:16]}…")

    versions = stage_proot(cache, jni, assets, args.check)
    print(f"libproot.so     {versions['proot']}")
    if not args.check:
        print(f"proot-deps.dat  {(assets / 'proot-deps.dat').stat().st_size:,} bytes "
              f"({', '.join(PROOT_LIBS.values())})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
