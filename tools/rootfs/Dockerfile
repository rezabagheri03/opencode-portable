# Reference build of the same rootfs, for hosts that have Docker.
# tools/rootfs/build.py is the portable path and produces the pinned MANIFEST.json;
# this exists so the package set is expressed once, in Alpine's own resolver.
FROM alpine:3.24

RUN apk add --no-cache \
      bash busybox ca-certificates coreutils findutils git libstdc++ musl ripgrep tzdata \
 && rm -rf /var/cache/apk/* \
 && mkdir -p /workspace /home \
 && printf 'nameserver 1.1.1.1\n' > /etc/resolv.conf \
 && touch /.ok

# apk info is the inventory 05-risks.md#licensing asks us to keep current.
RUN apk info -vv > /THIRD_PARTY_LICENSES.txt

CMD ["tar", "-czf", "/out/rootfs.tar.gz", "-C", "/", \
     "--exclude=proc", "--exclude=sys", "--exclude=dev", "--exclude=out", "."]
