#!/system/bin/sh
# Host-side driver: push P0 artifacts and run the spike stages.
# Usage: bash _p0/run.sh [stage ...]   (default: env shell net apk engine chat)
set -euo pipefail

ADB="${ADB:-/c/Users/Reza/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
HERE="$(cd "$(dirname "$0")" && pwd)"
P=/data/local/tmp/ocp0
D=_p0/downloads
X=_p0/extract
TARBALL=$D/alpine-minirootfs-3.24.2-aarch64.tar.gz
BIN=_artifacts/musl/package/bin/opencode
PUSR=$X/proot_5.1.107.98_aarch64/data/data/com.termux/files/usr

need_device() {
  "$ADB" shell true >/dev/null 2>&1 || { echo "no device"; "$ADB" devices -l; exit 1; }
}

push_all() {
  need_device
  "$ADB" shell "rm -rf $P && mkdir -p $P/bin $P/lib $P/tmp $P/rootfs/usr/local/bin"
  echo "pushing rootfs tarball; extracting on device (symlinks need Linux)"
  "$ADB" push "$TARBALL" "$P/rootfs.tar.gz" >/dev/null
  "$ADB" shell "tar xzf $P/rootfs.tar.gz -C $P/rootfs && rm $P/rootfs.tar.gz && ls $P/rootfs/etc/os-release"
  echo "pushing proot + libs"
  "$ADB" push "$PUSR/bin/proot"        "$P/bin/proot" >/dev/null
  "$ADB" push "$X/libtalloc_2.5.0_aarch64/data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0" "$P/lib/libtalloc.so.2" >/dev/null
  "$ADB" push "$X/libandroid-shmem_0.7_aarch64/data/data/com.termux/files/usr/lib/libandroid-shmem.so" "$P/lib/libandroid-shmem.so" >/dev/null
  echo "pushing opencode engine (~198 MB, one time)"
  "$ADB" push "$BIN" "$P/rootfs/usr/local/bin/opencode" >/dev/null
  "$ADB" shell "chmod 0755 $P/bin/proot $P/rootfs/usr/local/bin/opencode; ls -l $P/bin $P/rootfs/usr/local/bin"
  "$ADB" push "$HERE/device/spike.sh" "$P/spike.sh" >/dev/null
}

run_stage() {
  echo; echo "########## $1 ##########"
  "$ADB" shell "sh $P/spike.sh $1" 2>&1
}

main() {
  local stages=("$@")
  [ ${#stages[@]} -eq 0 ] && stages=(env shell net apk engine chat)
  [ "${stages[0]}" = "push" ] && { push_all; stages=("${stages[@]:1}"); [ ${#stages[@]} -eq 0 ] && return; }
  need_device
  for s in "${stages[@]}"; do run_stage "$s"; done
}

main "$@"
