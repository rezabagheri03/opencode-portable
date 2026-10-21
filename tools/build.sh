#!/usr/bin/env bash
# Build the APK from a clean checkout.
#
# This is the whole build. GitHub Actions runs exactly this script, so a green CI run and a working
# local build cannot drift apart — which is the failure mode that cost real time here (a stale
# ANDROID_SDK_ROOT and a missing Docker made the local and CI environments disagree).
#
# Assumes Python 3.9+, a JDK 21, and ANDROID_HOME or local.properties pointing at an SDK with
# platform 36 and build-tools 36. Everything else is fetched and hash-checked.
#
#   bash tools/build.sh                 # debug APK
#   bash tools/build.sh assembleRelease # release APK
set -euo pipefail
cd "$(dirname "$0")/.."

TASK="${1:-assembleDebug}"
OUT=dist/rootfs
GRADLE=./gradlew
[ -x "$GRADLE" ] || GRADLE=gradle

# Windows ships a `python3` app-execution stub that exits non-zero, so probe rather than assume.
PY=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'pass' >/dev/null 2>&1; then
    PY="$candidate"
    break
  fi
done
[ -n "$PY" ] || { echo "no working Python 3 interpreter found"; exit 1; }

step() { printf '\n=== %s ===\n' "$1"; }

step "native binaries (engine, proot, proot deps)"
"$PY" -I tools/fetch_binaries.py

step "rootfs"
"$PY" -I tools/rootfs/build.py --out "$OUT"
"$PY" -I tools/rootfs/verify.py "$OUT/rootfs.tar.gz"

step "stage assets"
# `.dat` not `.gz`: aapt decompresses a `.gz` asset and renames it, so the APK would carry
# different bytes than the verified artifact. See the androidResources note in app/build.gradle.kts.
mkdir -p app/src/main/assets
cp "$OUT/rootfs.tar.gz" app/src/main/assets/rootfs.dat

step "unit tests"
"$GRADLE" :app:testDebugUnitTest --console=plain

step "assemble ($TASK)"
"$GRADLE" ":app:$TASK" --console=plain

step "verify the APK carries the verified rootfs"
# `[ … ] && x=…` is not used here: under `set -e` a false test makes the AND-list non-zero,
# which aborts the script silently right before the checks that actually matter.
if [ "$TASK" = "assembleRelease" ]; then
  APK="app/build/outputs/apk/release/app-release.apk"
else
  APK="app/build/outputs/apk/debug/app-debug.apk"
fi
"$PY" -I tools/rootfs/check_apk_asset.py "$APK" "$OUT/MANIFEST.json"

step "size"
"$PY" -I - "$APK" <<'PY'
import sys, zipfile
from pathlib import Path
apk = Path(sys.argv[1])
with zipfile.ZipFile(apk) as z:
    rows = sorted(z.infolist(), key=lambda i: -i.file_size)
    for i in rows:
        if i.file_size > 200_000 and not i.filename.startswith("META-INF"):
            print(f"  {i.filename:44} {i.file_size:>13,} raw  {i.compress_size:>13,} stored")
    print(f"  {'APK total':44} {apk.stat().st_size:>13,}")
PY

step "done"
echo "$APK"
