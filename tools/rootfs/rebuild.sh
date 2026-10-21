#!/usr/bin/env bash
# Rebuild the rootfs and stage it where Gradle expects it.
# Docker if available, otherwise the portable Python builder.
set -euo pipefail
cd "$(dirname "$0")/../.."

OUT=dist/rootfs
mkdir -p "$OUT"

if [ "${USE_DOCKER:-}" = "1" ] && command -v docker >/dev/null; then
  docker build -t ocportable-rootfs tools/rootfs
  docker run --rm -v "$PWD/$OUT:/out" ocportable-rootfs
  docker create --name ocp-lic ocportable-rootfs >/dev/null
  docker cp ocp-lic:/THIRD_PARTY_LICENSES.txt "$OUT/THIRD_PARTY_LICENSES.md"
  docker rm ocp-lic >/dev/null
else
  python -I tools/rootfs/build.py --out "$OUT"
fi

python -I tools/rootfs/verify.py "$OUT/rootfs.tar.gz"

# `.dat` not `.gz`: aapt decompresses a `.gz` asset and renames it, which breaks the pinned hash.
# See the androidResources note in app/build.gradle.kts.
mkdir -p app/src/main/assets
rm -f app/src/main/assets/rootfs.tar app/src/main/assets/rootfs.tar.gz
cp "$OUT/rootfs.tar.gz" app/src/main/assets/rootfs.dat
echo "staged app/src/main/assets/rootfs.dat ($(du -h app/src/main/assets/rootfs.dat | cut -f1))"

# Prove the APK carries the exact verified bytes, not a re-compressed or renamed variant.
./gradlew :app:assembleDebug --console=plain -q >/dev/null
python -I tools/rootfs/check_apk_asset.py app/build/outputs/apk/debug/app-debug.apk "$OUT/MANIFEST.json"
