#!/usr/bin/env python3
"""Assert the rootfs inside a built APK is byte-identical to the verified artifact.

aapt will silently decompress or rename an asset depending on its extension, which would mean the
file extracted on first run is not the file `verify.py` checked. This makes that failure loud.

  python -I tools/rootfs/check_apk_asset.py <apk> <manifest.json>
"""
import gzip
import hashlib
import json
import sys
import zipfile

ASSET = "assets/rootfs.dat"


def main(apk_path: str, manifest_path: str) -> int:
    expected = json.load(open(manifest_path))["rootfs"]
    with zipfile.ZipFile(apk_path) as apk:
        candidates = [n for n in apk.namelist() if n.startswith("assets/rootfs")]
        if ASSET not in candidates:
            print(f"FAIL: {ASSET} not in APK; found {candidates or 'nothing'}")
            return 1
        info = apk.getinfo(ASSET)
        data = apk.read(ASSET)

    digest = hashlib.sha256(data).hexdigest()
    problems = []
    # Compare against the hash of the *uncompressed* tar. The .gz bytes legitimately differ
    # between hosts because Python links different zlib builds (zlib-ng on Windows CPython,
    # stock zlib on Ubuntu), so pinning them would fail CI on a correct build.
    if digest != expected["gz_sha256"]:
        problems.append(f"sha256 {digest} != pinned {expected['gz_sha256']}")
    tar_digest = hashlib.sha256(gzip.decompress(data)).hexdigest()
    if tar_digest != expected["tar_sha256"]:
        problems.append(f"tar sha256 {tar_digest} != pinned {expected['tar_sha256']}")
    if info.compress_type != 0:
        problems.append(f"asset is compressed (method {info.compress_type}); expected STORED")
    if len(data) != expected["bytes"]:
        problems.append(f"size {len(data)} != pinned {expected['bytes']}")

    for problem in problems:
        print(f"FAIL: {problem}")
    if problems:
        return 1
    print(f"ok — APK carries the verified rootfs ({info.file_size:,} bytes, {digest[:16]}…)")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
