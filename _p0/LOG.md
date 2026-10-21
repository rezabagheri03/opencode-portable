# P0 spike log

Running log for the P0 gate. One section per task in [TODO.md](../TODO.md#p0--spikes).

## Artifacts staged (host, 2026-10-21)

| Artifact | Source | Hash | Status |
|---|---|---|---|
| `opencode` engine | `_artifacts/musl/package/bin/opencode` | `e07ad6dd…cf7d7` | matches [06 §1](../docs/06-artifact-verification.md#1-artifact-identity) |
| Alpine rootfs 3.24.2 aarch64 | dl-cdn.alpinelinux.org | `9bf70a7f…ce773` | 3.8 MB |
| proot 5.1.107.98 aarch64 | `packages.termux.dev` | `29de40ef…ef7f3b` | matches Termux index |
| libtalloc 2.5.0 | Termux main | — | proot dep |
| libandroid-shmem 0.7 | Termux main | — | proot dep |

**proot `DT_NEEDED`** (measured with NDK r30 `llvm-readelf`):

```
libtalloc.so.2  libandroid-shmem.so  libc.so
```

Only Bionic `libc.so` is supplied by Android; the other two ship alongside it. `PT_INTERP` is
`/system/bin/linker64`, so proot is a *Bionic* binary — it runs bare on the device, not inside the
Alpine rootfs.

**CLI flags confirmed** from `opencode-src/packages/cli/src/commands/commands.ts:550-563`:
`serve --hostname --port --cors --service --stdio`.

## Tooling

- `_p0/run.sh` — host driver: push + run stages.
- `_p0/device/spike.sh` — on-device runner (env, shell, net, apk, engine, chat).

Rootfs is pushed **as a tarball and extracted on the device**: Windows cannot create the rootfs's
`/lib/libc.musl-aarch64.so.1` and busybox symlinks, and on-device extraction is the shipping path
anyway.

DNS is derived on-device from `dumpsys connectivity` → `DnsAddresses`, which is the prototype of the
`LinkProperties.getDnsServers()` mechanism in [06 §6.2](../docs/06-artifact-verification.md#62-dns-name-the-mechanism).

## Results

### P0 spikes — blocked on hardware

- [ ] P0-1 proot + Alpine shell
- [ ] P0-2 stock binary runs
- [ ] P0-3 DNS and TLS
- [ ] P0-4 RSS measurement
- [ ] P0-5 GUI in the phone's browser
- [ ] P0-6 background survival
- [ ] P0-7 watch link

**Blocked:** no device attached as of 2026-10-21.

### P1-1 Gradle project skeleton — **done** 2026-10-21

`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (2.4 MB, no engine yet).

Toolchain as built:

| Component | Version | Note |
|---|---|---|
| Gradle | 9.3.0 | pinned in the wrapper |
| AGP | 9.0.1 | **AGP 8.11.1 fails**: it requires Gradle ≥ 8.13 |
| JDK | 21 | |
| compileSdk / targetSdk | 36 | |
| minSdk | 31 | |
| ABI | `arm64-v8a` only | |
| NDK | 30.0.14904198 | present, unused so far |

Config that matters:

- `app/build.gradle.kts` sets `packaging.jniLibs.useLegacyPackaging = true`, which is what puts
  extracted `.so` files in `nativeLibraryDir` — the only place Android ≥ 10 will exec from.
- `local.properties` pins `sdk.dir`. The machine has a **stale `ANDROID_SDK_ROOT=E:\AppData\Android`**
  that conflicts with the real SDK; the project-level pin makes the build independent of it.
- `res/xml/network_security_config.xml` permits cleartext **only** for `127.0.0.1`/`localhost`.
  Android 9+ blocks cleartext by default and the engine is plain HTTP on loopback.

The Activity is a placeholder that reports ABI, SDK and `nativeLibraryDir` — it is the probe for
P1-2 (exec from `nativeLibraryDir`).

### P1-2 Engine launcher — written, needs hardware

`app/src/main/java/com/rs/ocportable/EngineLauncher.kt` builds the proot command line and spawns it.
6 unit tests cover the command shape (`./gradlew :app:testDebugUnitTest`); they pass.

The launcher deliberately splits pure command construction from the process spawn, so everything
except the actual `exec` is asserted off-device. What remains for the phone is the one thing that
cannot be faked: does `nativeLibraryDir/libopencode.so` execute, and does proot's `ptrace` work.

### P1-3 Rootfs build pipeline — **done** 2026-10-21

`bash tools/rootfs/rebuild.sh` builds, verifies, stages and checks the image in one command.

**Measured:** 14,455,551 bytes compressed, 1,636 members, 36 packages, 520 symlinks, and
byte-identical across rebuilds (`sha256 bd22ef82187327e5…`). All six load-bearing probes pass,
including `usr/lib/libstdc++.so.6` — the one whose absence looks like a proot failure.

Three silent traps found and fixed; all three are recorded in
[08-rootfs.md](../docs/08-rootfs.md) because none of them fails loudly:

1. **Windows cannot build a rootfs by extraction.** The symlinks are absolute and busybox's applets
   point outside the root; Windows refuses them without an error that Linux's tar would have given,
   producing a shell-less tree. The builder never extracts — it streams package members into the
   output tar.
2. **`.apk` files have three internal shapes.** Some store data uncompressed inline, some as a
   `data.tar.gz`/`.xz` member, and Python's `tarfile` stops at the first concatenated stream's
   end marker. Naively read, packages resolve, download, and contribute nothing.
3. **aapt rewrites an asset based on its extension.** `assets/rootfs.tar.gz` ships as
   `assets/rootfs.tar` — decompressed and renamed — so the APK carried a different artifact than the
   one verified, making the pinned hash meaningless. `noCompress` does not prevent it. The asset is
   now `rootfs.dat`, stored uncompressed, and `check_apk_asset.py` proves byte-identity every build.

Toolchain note: no Docker on this host, so `build.py` is the working path and the Dockerfile is kept
as the reference implementation. Docker was never required in principle.

### P1-4 First-run extraction — **done** 2026-10-21

`RootfsReader.kt` parses the archive in Kotlin (Android has no tar, and the rootfs is inside the
APK); `RootfsManager.kt` decides when to run and stamps the result. Extraction goes to a staging
directory and is only moved into place once complete, so an interrupted run cannot leave a tree the
next launch trusts. The stamp carries the rootfs `tar_sha256` in `BuildConfig`, so a rebuilt rootfs
replaces a stale one.

Real test coverage: 10 tests, one of which extracts the actual 1,629-member archive and checks
loader, `libstdc++`, `rg`, certs and every relative symlink.

The archive is now written **USTAR explicitly** — Python's default (PAX) injects extended headers a
narrow reader would have to implement, and every member fits USTAR anyway.

Three bugs found, two of them invisible on Windows:

1. **`str.lstrip("./")` strips a character set, not a prefix.** It stripped the leading dot from APK
   control members, shipping `.PKGINFO`, `.post-install` and `.SIGN.*` into the rootfs as
   `PKGINFO`, `post-install`, `SIGN.*`. `verify.py` missed it because it tested for a leading dot
   that had already been removed. Seven files; fixed at the source and the check now matches by name.
2. **`Files.createSymbolicLink` rewrites guest paths.** `/bin/busybox` became `\bin\busybox` on
   Windows. A rootfs built on a Windows host would have a dangling shell.
3. **`File.walkTopDown()` follows symlinks**, so an integrity walk escaped the rootfs into the
   host's own `/bin` on Linux.

Bugs 2 and 3 only appear on Linux, which is why CI caught the third one after it passed locally. The
general lesson is recorded in [08-rootfs.md](../docs/08-rootfs.md#three-traps-in-the-extractor): a
Windows host cannot verify a Linux filesystem, so the symlink exactness assertion runs only on POSIX
and CI is the real check.

### Build pipeline + CI — `bash tools/build.sh` 2026-10-21

One script does everything (fetch → verify → test → assemble → check the APK), and
[.github/workflows/build.yml](../.github/workflows/build.yml) calls that same script rather than
reimplementing it, so local and CI cannot drift.

Binary sourcing settled, since the 198 MB engine is over GitHub's 100 MB per-file limit and cannot be
committed:

| Binary | Source | Check |
|---|---|---|
| `libopencode.so` | npm 2.1.1 (not `latest`, which is 2.1.2) | sha256 vs the [06 §1](../docs/06-artifact-verification.md#1-artifact-identity) pin |
| `libproot.so` | Termux apt | sha256 vs the signed `Packages` index |
| `proot-deps.dat` | Termux apt | same |

A hash mismatch fails the build. Cold fetch: ~50 s. **Measured APK: 121,180,572 bytes**, with the
engine deflating 207 MB → 104 MB inside it and the rootfs stored (`noCompress`).

Two bugs found while wiring it, both of the "green build that is actually broken" kind:

1. **`raise SystemExit("message")` exits 0.** Twelve call sites across `fetch_binaries.py` and
   `rootfs/build.py` — including the engine hash mismatch — would print an error and let CI pass. Fixed
   with an explicit `fail()` that exits 1, and negative-tested by corrupting the engine.
2. **`set -e` plus `[ … ] && x=…`** silently aborted `build.sh` right before the APK verification
   step, so the most important check never ran. Replaced with an `if`.

Still unproven: the workflow has not executed (this is not yet a git repo, and `gh` is unauthenticated
here), and `ndk;30.0.14904198` in `sdkmanager` is unverified — the NDK is unused so far and can be
dropped if it is unavailable.

### Engine API contract — answered from source

[07-engine-api.md](../docs/07-engine-api.md) is new. It closes open questions #3 and #4 and confirms
assumption A-4, all from `opencode-src` with `path:line` citations. The headline results:

- **Basic auth works everywhere; Bearer does not.** CORS never blocks a client with no `Origin`.
- **`serve` generates a random password unless `OPENCODE_PASSWORD` is set** — confirms ADR-012 point 3
  is load-bearing, not defensive.
- **Watch state comes from `session.execution.*` + `session.tool.called` + `permission.asked`.**
  `session.status`/`session.idle` exist in the schema but have no publisher — do not use them.
- **Reconnect = `server.connected` → `GET /api/session/active`**, the same sequence the web GUI uses.
- **No engine patch is needed for the watch**, so ADR-009 ("never fork the core") holds.

Note: docs were read at the **pinned** version. npm `latest` is now 2.1.2 while the clone and the
staged ARM binary are 2.1.1; the pin in [06 §1](../docs/06-artifact-verification.md#1-artifact-identity)
still governs.
