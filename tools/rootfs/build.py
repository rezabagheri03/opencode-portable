#!/usr/bin/env python3
"""Build the Alpine rootfs shipped inside the APK.

Assembles `rootfs.tar.gz` by streaming tar members from Alpine's signed APKINDEX-selected packages
straight into the output archive. Nothing is extracted to the host filesystem, which matters for two
reasons:

  * a Windows host cannot create the symlinks a Linux rootfs is made of (`/bin/sh -> busybox`), so
    extract-then-repack silently produces a rootfs with no shell;
  * it removes host filesystem semantics (umask, case folding, permissions) from the build, so the
    same inputs give byte-identical output anywhere.

No Docker, no root, no qemu.

Usage:
  python -I tools/rootfs/build.py --out dist
  python -I tools/rootfs/build.py --out dist --refresh   # ignore the download cache
"""
import argparse
import gzip
import hashlib
import io
import json
import lzma
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

ALPINE = "3.24"
ARCH = "aarch64"
BASE = f"https://dl-cdn.alpinelinux.org/alpine/v{ALPINE}"
RELEASE = f"{BASE}/releases/{ARCH}"
REPOS = [f"{BASE}/main", f"{BASE}/community"]

# Everything the engine needs, plus the tools its own tools assume.
#   libstdc++        DT_NEEDED on the engine binary (06-artifact-verification.md §3)
#   ripgrep          makes `which rg` win inside the rootfs, avoiding the glibc-download trap (R-16)
#   ca-certificates  TLS to the model API, and for git
#   git              the agent commits and diffs (P2-4)
#   bash             ShellSelect probes bash before sh
PACKAGES = [
    "bash",
    "busybox",
    "ca-certificates",
    "coreutils",
    "findutils",
    "git",
    "libstdc++",
    "musl",
    "ripgrep",
    "tzdata",
]

# Deterministic archives: fixed mtime, fixed ownership. The rootfs is read-only in practice —
# the app binds a separate workspace over it — so nothing here depends on the build clock.
EPOCH = 1_700_000_000

# Created at bundle time so first run has them even if extraction is interrupted.
EXTRA_FILES = {
    "etc/resolv.conf": b"nameserver 1.1.1.1\n",  # rewritten from netd on every start
    ".ok": b"",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def norm(name: str) -> str:
    """Normalise an archive member path.

    `str.lstrip("./")` was wrong here: lstrip takes a *character set*, so it stripped every leading
    dot and slash. That turned the APK control files `.PKGINFO`, `.post-install` and `.SIGN.*` into
    `PKGINFO`, `post-install` and `SIGN.*`, which then shipped in the rootfs because the ". control
    file" filter had nothing left to match.
    """
    while name.startswith("./"):
        name = name[2:]
    name = name.lstrip("/")
    return "" if name in (".", "") else name


def fetch(url: str, cache: Path) -> bytes:
    dest = cache / sha256(url.encode())[:16]
    if dest.exists():
        return dest.read_bytes()
    with urllib.request.urlopen(url, timeout=120) as resp:
        data = resp.read()
    dest.write_bytes(data)
    return data


def fetch_index(repo: str, cache: Path) -> dict:
    raw = gzip.decompress(fetch(f"{repo}/{ARCH}/APKINDEX.tar.gz", cache))
    with tarfile.open(fileobj=io.BytesIO(raw)) as tf:
        text = tf.extractfile("APKINDEX").read().decode()
    entries, cur = {}, {}
    for line in text.splitlines():
        if not line:
            if "P" in cur:
                entries[cur["P"]] = cur
            cur = {}
        elif len(line) > 1 and line[1] == ":":
            cur[line[0]] = line[2:]
    if "P" in cur:
        entries[cur["P"]] = cur
    return entries


def providers(indexes):
    """Map virtual names (`so:libfoo.so.1`, `cmd:sh`) to the package that supplies them."""
    out = {}
    for idx in indexes:
        for entry in idx.values():
            for provided in entry.get("p", "").split():
                out.setdefault(provided.split("=")[0], entry["P"])
    return out


def resolve(names, indexes):
    """Flat closure over DEPENDS, honouring `so:`/`cmd:` provides and skipping `!conflicts`."""
    virtual = providers(indexes)
    found, queue = {}, list(names)
    while queue:
        name = queue.pop()
        if name in found:
            continue
        entry = next((idx[name] for idx in indexes if name in idx), None)
        if entry is None and name in virtual:
            real = virtual[name]
            entry = next((idx[real] for idx in indexes if real in idx), None)
        if entry is None:
            fail(f"package not found in any repo: {name}")
        found[entry["P"]] = entry
        for dep in entry.get("D", "").split():
            if dep.startswith("!"):
                continue
            dep = re.split(r"[=<>~]", dep)[0]
            if dep not in found:
                queue.append(dep)
    return found


def apk_url(entry):
    """APKINDEX carries no filename; packages sit flat under <repo>/<arch>/."""
    return f"{entry['_repo']}/{ARCH}/{entry['P']}-{entry['V']}.apk"


def decompress(payload: bytes, name: str) -> bytes:
    """Alpine embeds a control section plus a payload tar; only the payload is compressed."""
    if name.endswith(".gz"):
        return gzip.decompress(payload)
    if name.endswith(".xz"):
        return lzma.decompress(payload)
    if name.endswith(".zst"):
        fail("zstd apk payload unsupported; use tools/rootfs/Dockerfile on this host")
    return payload


def tar_streams(data: bytes):
    """Yield each concatenated tar stream in a buffer.

    An .apk is a signature section, a control section and a data section appended to one another,
    each independently a tar — sometimes gzipped, sometimes not. `tarfile` reads one stream and
    stops at its end-of-archive marker, so the sections are walked one at a time; `tarfile.open`
    auto-detects the compression of whichever section it is given.
    """
    offset = 0
    while offset < len(data):
        rest = data[offset:]
        if len(rest) < 512 or rest[:512].strip(b"\0") == b"":
            return
        try:
            archive = tarfile.open(fileobj=io.BytesIO(rest))
            members = archive.getmembers()
        except tarfile.ReadError:
            return
        if not members:
            return
        yield archive, members
        offset += archive.offset


def apk_members(data: bytes):
    """Yield (name, TarInfo, bytes) for the payload members of one .apk.

    Control members are dot-prefixed (`.PKGINFO`, `.SIGN.*`, install scripts) and must never reach
    the rootfs.
    """
    for archive, members in tar_streams(data):
        for item in members:
            key = norm(item.name)
            if not key or key.startswith("."):
                continue
            body = archive.extractfile(item).read() if item.isfile() else b""
            yield key, item, body


def build_tree(entries, cache: Path, order):
    """path -> (TarInfo, bytes). Later writers win, except directories never replace files."""
    tree = {}
    for name in order:
        for key, item, body in apk_members(fetch(apk_url(entries[name]), cache)):
            if key in tree and item.isdir() and tree[key][0].isfile():
                continue
            tree[key] = (item, body)
    return tree


def pack(tree, dest: Path):
    # gzip stamps the archive with the wall clock unless told otherwise, so identical inputs would
    # produce different bytes and the pinned hash in MANIFEST.json would be meaningless. tarfile
    # controls mtime only through the GzipFile it is handed.
    with open(dest, "wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=EPOCH) as gz:
            # USTAR, explicitly. The default (PAX) adds per-member extended headers when a name or
            # link target is long, and the on-device extractor has to parse this format in Kotlin —
            # the tar lives *inside* the archive and Android ships no tar. Every member here fits
            # USTAR (names <=97 bytes, link targets <=54), and verify.py enforces that it stays so.
            with tarfile.open(fileobj=gz, mode="w", format=tarfile.USTAR_FORMAT) as tf:
                for key in sorted(tree):
                    item, body = tree[key]
                    info = tarfile.TarInfo(key)
                    info.type = item.type
                    info.mode = item.mode & 0o7777
                    info.linkname = item.linkname
                    _write(tf, info, body)
                for key, body in EXTRA_FILES.items():
                    info = tarfile.TarInfo(key)
                    info.mode = 0o644
                    _write(tf, info, body)


def _write(tf, info, body):
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    info.mtime = EPOCH
    if info.isreg():
        info.size = len(body)
        tf.addfile(info, io.BytesIO(body))
    else:
        tf.addfile(info)


def base_members(release_yaml: str, cache: Path):
    """The minirootfs tarball's members, plus its pinned identity."""
    name = digest = None
    for block in re.split(r"^-\s*$", release_yaml, flags=re.M):
        found = re.search(r"^\s*file: (alpine-minirootfs-\S+\.tar\.gz)$", block, re.M)
        if not found:
            continue
        name = found.group(1)
        digest = re.search(r"^\s*sha256: (\w+)$", block, re.M).group(1)
        break
    if not name:
        fail("could not parse the minirootfs release metadata")
    data = fetch(f"{RELEASE}/{name}", cache)
    if sha256(data) != digest:
        fail(f"base minirootfs hash mismatch: {sha256(data)} != {digest}")
    return data, {"file": name, "sha256": digest}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="dist")
    ap.add_argument("--cache", default=".cache/rootfs")
    ap.add_argument("--refresh", action="store_true")
    args = ap.parse_args()

    out, cache = Path(args.out), Path(args.cache)
    if args.refresh and cache.exists():
        for f in cache.iterdir():
            f.unlink()
    cache.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)

    indexes = []
    for repo in REPOS:
        idx = fetch_index(repo, cache)
        for entry in idx.values():
            entry["_repo"] = repo
        indexes.append(idx)

    entries = resolve(PACKAGES, indexes)
    with urllib.request.urlopen(f"{RELEASE}/latest-releases.yaml", timeout=60) as resp:
        base_data, base_info = base_members(resp.read().decode(), cache)

    with tarfile.open(fileobj=io.BytesIO(base_data)) as tf:
        tree = {}
        for item in tf.getmembers():
            key = norm(item.name)
            if not key:
                continue
            tree[key] = (item, tf.extractfile(item).read() if item.isfile() else b"")
    if "etc/alpine-release" not in tree:
        fail(f"{base_info['file']} did not contain a root filesystem — refusing to continue")

    # busybox last: it owns the /bin and /usr/bin applet links and must not be overwritten.
    order = sorted(entries, key=lambda n: (n == "busybox", n))
    tree.update(build_tree(entries, cache, order))

    tar_path = out / "rootfs.tar.gz"
    pack(tree, tar_path)

    manifest = {
        "alpine": ALPINE,
        "arch": ARCH,
        "base": base_info,
        "packages": {
            n: {"version": entries[n]["V"], "license": entries[n].get("L", "unknown"),
                "file": apk_url(entries[n]).rsplit("/", 1)[1],
                "sha256": sha256(fetch(apk_url(entries[n]), cache))}
            for n in sorted(entries)
        },
        "rootfs": {
            "file": tar_path.name,
            # The hash that matters, and the only one that is stable across platforms. The .tar.gz
            # bytes are NOT reproducible between hosts: Python links whatever zlib it was built
            # against (Windows CPython 3.14 ships zlib-ng, Ubuntu's ships stock zlib), and the two
            # produce different, equally valid deflate streams from identical input. Verified:
            # same tar payload, different compressed length.
            "tar_sha256": sha256(gzip.decompress(tar_path.read_bytes())),
            "gz_sha256": sha256(tar_path.read_bytes()),  # informational; host-dependent
            "bytes": tar_path.stat().st_size,
            "tar_bytes": len(gzip.decompress(tar_path.read_bytes())),
            "members": len(tree),
        },
    }
    (out / "MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (out / "THIRD_PARTY_LICENSES.md").write_text(licenses(manifest, order), encoding="utf-8")

    print(f"rootfs   {tar_path}  {manifest['rootfs']['bytes']:,} bytes  "
          f"tar {manifest['rootfs']['tar_sha256'][:16]}…  {len(tree):,} members")
    print(f"packages {len(entries)}")
    for probe in ("bin/sh", "bin/busybox", "usr/bin/rg", "usr/lib/libstdc++.so.6",
                  "usr/bin/git", "etc/ssl/certs/ca-certificates.crt"):
        print(f"  {'ok ' if probe in tree else 'MISSING'} {probe}")


def licenses(manifest, order):
    rows = manifest["packages"]
    copyleft = [n for n in order if "GPL" in rows[n]["license"]]
    lines = [
        "# Third-party licenses — Alpine rootfs",
        "",
        f"Alpine {manifest['alpine']} / {manifest['arch']}. Generated by `tools/rootfs/build.py`; do not edit.",
        "",
        "Redistribution notes are in [docs/05-risks.md](../docs/05-risks.md#licensing). If you share an",
        "APK built from this rootfs, the copyleft packages below oblige you to offer their corresponding",
        "source — for Alpine packages that is the public upstream tarball at the version recorded here.",
        "",
        "## Copyleft packages (GPL / LGPL)",
        "",
        "| Package | Version | License |",
        "|---|---|---|",
    ]
    lines += [f"| {n} | {rows[n]['version']} | {rows[n]['license']} |" for n in copyleft] or ["| _none_ | | |"]
    lines += ["", "## All packages", "", "| Package | Version | License |", "|---|---|---|"]
    lines += [f"| {n} | {rows[n]['version']} | {rows[n]['license']} |" for n in order]
    return chr(10).join(lines) + chr(10)


if __name__ == "__main__":
    sys.exit(main())
