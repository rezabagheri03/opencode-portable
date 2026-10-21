#!/system/bin/sh
# P0 spike: proot + Alpine + opencode on the real device.
# Runs on-device via: adb shell sh /data/local/tmp/ocp0/spike.sh <stage>
# Stages: env | shell | net | apk | engine | chat
set -u
P=/data/local/tmp/ocp0
R=$P/rootfs
PORT=4096
say() { echo "--- $* ---"; }

dns_servers() {
  # Whatever netd is using is reachable by construction.
  dumpsys connectivity 2>/dev/null \
    | grep -o 'DnsAddresses: \[[^]]*\]' \
    | head -1 \
    | sed 's/DnsAddresses: \[//; s/\]//' \
    | tr ',' '\n' \
    | sed 's/^ *//; s/ *$//' \
    | grep -E '^[0-9]+\.'
}

write_resolv() {
  { for ns in $(dns_servers); do echo "nameserver $ns"; done; } > "$R/etc/resolv.conf"
  cat "$R/etc/resolv.conf"
}

PR="env LD_LIBRARY_PATH=$P/lib PROOT_TMP_DIR=$P/tmp PROOT_NO_SECCOMP=1 $P/bin/proot -0 --link2symlink -r $R -b /dev -b /proc -b /sys -b /data/local/tmp -w /root"

stage_env() {
  say "device"
  getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk
  getprop ro.product.cpu.abi; getconf PAGESIZE
  say "dns from netd"
  dns_servers || echo "(none parsed)"
}

stage_shell() {
  say "P0-1 shell"
  $PR /bin/sh -c 'cat /etc/os-release | head -3; apk --version; echo "uid=$(id -u)"'
  say "P0-1 libstdc++"
  $PR /bin/sh -c 'apk add --no-cache libstdc++ >/dev/null 2>&1; ls -l /usr/lib/libstdc++.so.6 || echo MISSING'
}

stage_net() {
  say "P0-3 dns config"
  write_resolv
  say "P0-3 wget"
  $PR /bin/sh -c 'wget -q -T 20 -O /dev/null https://example.com && echo WGET_OK || echo WGET_FAIL'
  say "P0-3 apk update"
  $PR /bin/sh -c 'apk update 2>&1 | tail -3'
}

stage_apk() {
  say "R-16 and runtime deps"
  $PR /bin/sh -c 'apk add --no-cache ripgrep ca-certificates bash git >/dev/null 2>&1; echo "rg:$(command -v rg)"; rg --version | head -1; git --version; bash --version | head -1'
  say "certs"
  $PR /bin/sh -c 'apk add --no-cache ca-certificates >/dev/null 2>&1; [ -s /etc/ssl/certs/ca-certificates.crt ] && echo CERTS_OK || echo CERTS_MISSING'
}

stage_engine() {
  say "P0-2 version"
  $PR /bin/sh -c '/usr/local/bin/opencode --version 2>&1 | tail -3'
  say "P0-2 ldd"
  $PR /bin/sh -c 'ldd /usr/local/bin/opencode 2>&1 || echo "(no ldd)"'
}

stage_chat() {
  say "P0-2 serve"
  $PR /bin/sh -c "cd /root && nohup /usr/local/bin/opencode serve --hostname 127.0.0.1 --port $PORT > /root/serve.log 2>&1 & echo started"
  i=0
  while [ $i -lt 60 ]; do
    if $PR /bin/sh -c "wget -q -T 2 -O /dev/null http://127.0.0.1:$PORT/ 2>/dev/null"; then
      echo "HTTP_UP after ${i}s"; break
    fi
    i=$((i+1)); sleep 1
  done
  say "serve.log"
  $PR /bin/sh -c 'tail -20 /root/serve.log'
  say "root page"
  $PR /bin/sh -c "wget -q -T 5 -O - http://127.0.0.1:$PORT/ | head -c 300; echo"
}

case "${1:-}" in
  env)    stage_env ;;
  shell)  stage_shell ;;
  net)    stage_net ;;
  apk)    stage_apk ;;
  engine) stage_engine ;;
  chat)   stage_chat ;;
  *) echo "usage: $0 env|shell|net|apk|engine|chat" ;;
esac
