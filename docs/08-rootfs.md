# 08 — Rootfs

How the Alpine image inside the APK is built, what is in it, and which traps it avoids.

[03-platform.md](03-platform.md#rootfs-preparation-host-side-once) gives the recipe. This records the
implementation, because building it surfaced three things that recipe does not say and that are each
silent failures.

---

## The pipeline

```
tools/build.sh                   # the whole build, local and CI: binaries → rootfs → tests → APK
  └─ fetch_binaries.py           downloads and hash-checks the engine, proot, proot deps
  └─ rootfs/build.py             resolves packages, assembles rootfs.tar.gz + MANIFEST.json
  └─ rootfs/verify.py            inspects the archive without extracting it
  └─ rootfs/check_apk_asset.py   asserts the APK carries those exact bytes
```

Everything is offline after the first run; downloads are cached under `.cache/rootfs/`.

**Reproducible across machines, with one caveat [V].** A Windows build and an Ubuntu CI build
produce **byte-identical tar payloads**: `tar_sha256 a95a5a2b…`, 1,636 members, same order, same
modes, uid/gid and mtimes, and identical file contents (verified member by member).

The compressed `.tar.gz` bytes are **not** identical between hosts, and cannot be pinned meaningfully:
they differ because Python links whatever zlib it was built against — Windows CPython 3.14 reports
`1.3.1.zlib-ng`, Ubuntu's uses stock zlib — and the two emit different but equally valid deflate
streams for identical input (14,455,551 vs 14,504,416 bytes). `MANIFEST.json` therefore records
`tar_sha256` as the authoritative hash and `gz_sha256` as informational. Pinning the `.gz` would have
failed CI on a build that was correct.

Within one machine, two consecutive builds are byte-identical. That required writing the gzip header
through a `GzipFile` with an explicit `mtime`; setting it on the `TarFile` instead does nothing,
because tarfile only controls the header of the `GzipFile` it is given.

## What is in it

36 packages resolved from Alpine's signed `APKINDEX`, including the full dependency closure:

| Package | Version | Why it is there |
|---|---|---|
| `musl` | 1.2.6-r2 | provides `/lib/ld-musl-aarch64.so.1` — the engine's `PT_INTERP` |
| `libstdc++` | 15.2.0-r5 | the engine's other `DT_NEEDED`; the failure it prevents looks like a proot bug |
| `ripgrep` | 15.1.0-r0 | so `which rg` wins offline, avoiding the glibc-download trap (R-16) |
| `ca-certificates` | 20261020-r0 | TLS to the model API, and for `git` |
| `git` | 2.54.0-r0 | the agent commits and diffs (P2-4) |
| `bash` | 5.3.9-r1 | `ShellSelect` probes `bash` before `sh` |
| `busybox` | 1.37.0-r31 | the applet symlinks that make up `/bin` and `/usr/bin` |

**Measured:** 14,455,551 bytes compressed, 1,636 members, 520 symlinks, `tar_bytes` 36,823,040.
[03](03-platform.md) estimated ~16 MB compressed and ~45 MB unpacked; both hold.

`verify.py` asserts the load-bearing members and every symlink chain, so a package that silently
stops pulling in `libstdc++` fails the build instead of the device.

## The three traps

### 1. A rootfs cannot be built by extracting to a filesystem on Windows

A rootfs is mostly symlinks, and they are the kind Windows will not create: `/bin/sh -> /bin/busybox`
is absolute, and busybox's applets point outside the extraction root. Windows either refuses or
rewrites the target. The failure is **silent** — Linux's `tar` reports it, Windows' does not — and
the result is a plausible-looking tree with no shell in it.

The builder therefore never extracts anything. `apk_members()` streams each package's payload tar
directly into the output archive, so host filesystem semantics (umask, case folding, permission
bits, symlink policy) are removed from the build entirely. This is a correctness fix, not a
convenience: it is why the hash is stable across platforms.

### 2. `.apk` files have three different internal shapes

An `.apk` is a signature section, a control section and a data section concatenated into one file,
each independently a tar — and Alpine compresses them inconsistently:

- some packages store the data section **uncompressed**, so its members appear inline in the first
  stream and `tarfile` sees them directly;
- others store it as a `data.tar.gz` or `data.tar.xz` member needing a second pass;
- Python's `tarfile` reads **one** stream and stops at its end-of-archive marker, so a naive
  `getmembers()` sees the signature section and nothing else.

`tar_streams()` walks the concatenated streams one at a time. Getting this wrong is also silent:
packages resolve, download, and contribute zero files.

Control members are dot-prefixed (`.PKGINFO`, `.SIGN.*`, install scripts) and are skipped, or they
land in the rootfs as garbage.

### 3. aapt rewrites an asset based on its extension

This one would have been found on the device, at first run, as a corrupt extraction.

`assets/rootfs.tar.gz` comes out of the APK as **`assets/rootfs.tar`** — aapt special-cases `.gz`,
decompresses it, and renames it. The APK then carries 36.8 MB of uncompressed tar that is *not* the
artifact `verify.py` checked, so the pinned hash is a lie.

Adding the suffix to `noCompress` does not help: aapt still decompresses and still renames. The fix
is a **neutral extension** — the asset is `assets/rootfs.dat`, stored uncompressed, and the APK
carries byte-identical content (`sha256` matches, verified on every rebuild by
`check_apk_asset.py`).

Worth stating plainly: this is a case where the build tool silently substitutes a different artifact
than the one under test. Any asset that is *already* compressed and needs to survive a hash check has
the same problem.

## Re-verifying

```bash
bash tools/rootfs/rebuild.sh
```

prints the artifact hash, the six load-bearing member probes, the symlink check, and the APK asset
comparison. A failure at any step exits non-zero.

To check a rootfs someone else built:

```bash
python -I tools/rootfs/verify.py path/to/rootfs.tar.gz
```

## Extraction on device

Android ships no `tar` and the rootfs lives *inside* the APK, so [P1-4](../TODO.md) unpacks it in
Kotlin: `app/src/main/java/com/rs/ocportable/RootfsReader.kt` (archive parsing) and
`RootfsManager.kt` (when to run, and stamping).

The archive is written as **USTAR explicitly**. Python's default is PAX, which injects per-member
extended headers when a name or link target is long — headers a narrow reader would have to
implement. Every member fits USTAR (names ≤97 bytes, link targets ≤54), and `verify.py` now rejects
anything that would not, so a growing rootfs fails the build rather than the device.

Only member types `0` (file), `1` (hard link), `2` (symlink) and `5` (directory) occur. There are
520 symlinks and 254 hard links — the latter almost entirely timezone aliases, materialised after the
tree is complete so their targets exist regardless of member order.

Extraction writes to `rootfs.staging/` and only moves it into place once complete, so an interrupted
run can never leave a half-written tree that the next launch trusts. The stamp holds the rootfs
`tar_sha256` from `MANIFEST.json`, compiled into `BuildConfig`, so a rebuilt rootfs replaces a stale
one instead of being silently kept.

### Three traps in the extractor

1. **`str.lstrip("./")` strips a character *set*, not a prefix.** In the builder it removed the
   leading dot from APK control members, so `.PKGINFO`, `.post-install` and `.SIGN.*` shipped into
   the rootfs as `PKGINFO`, `post-install` and `SIGN.*` — and `verify.py` missed them because it
   checked for a leading dot that no longer existed. Seven files were affected; the check now matches
   on name.
2. **Java's Path API is host-parsed.** `Files.createSymbolicLink` rewrites the guest target
   `/bin/busybox` to `\bin\busybox` on Windows, which would ship a rootfs with a dangling shell. A
   Linux-only test asserts the exact archive text, because Linux is the host the device resembles.
3. **`File.walkTopDown()` follows symlinks.** An absolute guest link resolves to the host's own
   `/bin` on Linux, so a symlink-integrity walk escaped the rootfs and wandered the build machine.
   That is exactly how a broken check passed on Windows (where the guest path does not exist) and
   failed in CI. The walk now uses `listFiles` and treats a symlink as a leaf.

Taken together these are one lesson: **a Windows host cannot verify a Linux filesystem.** The two
bugs that mattered were both invisible locally and only surfaced on the Ubuntu runner.

## Still open

- **Does a build from `alpine:3.24` via Docker match `build.py` byte-for-byte?** It will not, because
  `apk add` resolves at a different time and picks up different package versions. The two are kept as
  alternative paths rather than claimed equivalent; `MANIFEST.json` is the authority for what was
  actually shipped.
- **The image has not run yet.** Everything here is verified structurally — member presence, symlink
  resolution, engine dependencies. Whether `proot` boots it and whether `apk`-free operation holds on
  the device is P0-1, and it is blocked on hardware.
- **`zstd` payloads are unsupported.** Alpine 3.24 does not use them for these packages, and the
  builder fails loudly rather than silently skipping if that changes.
