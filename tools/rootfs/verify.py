#!/usr/bin/env python3
"""Verify a built rootfs.tar.gz without extracting it.

Catches the failures that are invisible until first run on the device: a missing shell, an engine
dependency that never made it in, a symlink chain that does not resolve, or a leftover APK control
file. Run it after `build.py`; it is the difference between "the tarball exists" and "the tarball
works".

  python -I tools/rootfs/verify.py dist/rootfs/rootfs.tar.gz
"""
import posixpath
import sys
import tarfile

# DT_NEEDED / PT_INTERP of the shipped engine, from docs/06-artifact-verification.md §3.
ENGINE_DEPS = [
    "lib/ld-musl-aarch64.so.1",
    "lib/libc.musl-aarch64.so.1",
    "usr/lib/libstdc++.so.6",
]

# The engine resolves tools through PATH; `rg` must be real or it downloads a glibc build (R-16).
REQUIRED = [
    "bin/sh",
    "bin/busybox",
    "usr/bin/rg",
    "usr/bin/git",
    "bin/bash",
    "etc/ssl/certs/ca-certificates.crt",
    "etc/resolv.conf",  # rewritten from netd on every start
]

# Bind-mounted at runtime by the launcher, so their absence from the image is correct.
RUNTIME_MOUNTS = ("proc/", "sys/", "dev/", "etc/mtab")

# APK package metadata. Never part of a root filesystem.
CONTROL_FILES = {
    "PKGINFO", "post-install", "pre-install", "post-upgrade", "pre-upgrade",
    "post-deinstall", "pre-deinstall", "trigger", "datalist",
}


def main(path: str) -> int:
    tf = tarfile.open(path)
    members = {m.name: m for m in tf.getmembers()}
    names = set(members)
    problems = []

    for required in REQUIRED:
        if required not in names:
            problems.append(f"missing: {required}")

    for dep in ENGINE_DEPS:
        if dep not in names:
            problems.append(f"engine dependency missing: {dep}")

    def resolve(name, depth=0):
        if depth > 12 or name not in members:
            return None
        m = members[name]
        if not m.issym():
            return name
        target = m.linkname
        target = target.lstrip("/") if target.startswith("/") else \
            posixpath.normpath(posixpath.join(posixpath.dirname(name), target))
        return resolve(target, depth + 1)

    links = [m for m in members.values() if m.issym()]
    for m in links:
        if not resolve(m.name) and not m.name.startswith(RUNTIME_MOUNTS):
            problems.append(f"broken symlink: {m.name} -> {m.linkname}")

    # The builder skips APK control members, but a normalisation bug once stripped their leading
    # dot and shipped them. Check by name, not by prefix, so that bug cannot come back silently.
    for name in names:
        base = name.rsplit("/", 1)[-1]
        if base in CONTROL_FILES or base.startswith("SIGN.") or name.startswith("."):
            if name != ".ok":
                problems.append(f"apk control file leaked: {name}")

    if "etc/alpine-release" not in names:
        problems.append("not an Alpine rootfs (no etc/alpine-release)")

    # RootfsReader.kt parses this format without tarfile's tolerance. Anything that does not fit a
    # plain USTAR header would need a PAX or GNU extension the extractor does not implement, so a
    # long name must fail here rather than on the device.
    for m in members.values():
        if len(m.name.encode()) > 100:
            problems.append(f"name too long for USTAR: {m.name}")
        if m.linkname and len(m.linkname.encode()) > 100:
            problems.append(f"link target too long for USTAR: {m.name} -> {m.linkname}")
        if m.type not in (b"0", b"1", b"2", b"5"):
            problems.append(f"unsupported member type {m.type!r}: {m.name}")

    print(f"{path}: {len(members):,} members, {len(links)} symlinks")
    for problem in problems:
        print(f"  FAIL {problem}")
    if problems:
        return 1
    print("  ok — all checks passed")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    sys.exit(main(sys.argv[1]))
