# 09 — Build

How the APK is built, locally and in CI. One script, one code path.

---

## The command

```bash
bash tools/build.sh
```

That is the whole build. It fetches and hash-checks the native binaries, builds and verifies the
rootfs, runs the unit tests, assembles the APK, and then proves the APK carries the rootfs that was
verified. A green CI run and a working local build cannot drift apart, because
[.github/workflows/build.yml](../.github/workflows/build.yml) invokes this same script rather than
reimplementing the steps.

**Requirements:** Python 3.9+, JDK 21, and an Android SDK with platform 36 and build-tools 36. The
SDK is found through `ANDROID_HOME` or `local.properties`. Everything else is fetched.

Output: `app/build/outputs/apk/debug/app-debug.apk` — **121,180,572 bytes**.

| Component | Raw | In the APK |
|---|---|---|
| `lib/arm64-v8a/libopencode.so` | 207,162,576 | 104,149,220 (deflated) |
| `assets/rootfs.dat` | 14,455,551 | 14,455,551 (**stored**) |
| `classes.dex` | 2,400,296 | 2,400,296 (stored) |
| `lib/arm64-v8a/libproot.so` | 255,240 | 120,981 |

`classes.dex` is stored rather than deflated because `extractNativeLibs`/`useLegacyPackaging` is on,
which is what makes `nativeLibraryDir` executable — see [03-platform.md](03-platform.md#1-exec-from-app-data).

## What is fetched, and why it is not committed

The engine is 198 MB, above GitHub's 100 MB per-file limit, so it cannot live in the repository.
`tools/fetch_binaries.py` downloads and verifies instead:

| Binary | Source | Verified against |
|---|---|---|
| `libopencode.so` | npm `@opencode/cli-linux-arm64-musl@2.1.1` | **sha256 `e07ad6dd…cf7d7`** — the pin in [06 §1](06-artifact-verification.md#1-artifact-identity) |
| `libproot.so` | Termux apt, `proot` 5.1.107.98 | per-package sha256 from the signed `Packages` index |
| `proot-deps.dat` | Termux apt, `libtalloc` + `libandroid-shmem` | same |

A hash mismatch **fails the build**. This is the point: every measurement in [06](06-artifact-verification.md)
was taken against that exact binary, so a build that quietly picked up a different opencode version
would invalidate the document set rather than merely change the output.

Two details worth recording:

- **Fetching by version, not by `latest`.** npm now serves 2.1.2 while the pins are 2.1.1, so CI stays
  on 2.1.1 and no build re-pins itself by accident.
- **The npm `unpackedSize` field corroborates the pin independently** — the registry reports
  207,162,820 bytes for the package (tarball plus metadata) against our 207,162,576 for the binary.

`proot-deps.dat` is an asset rather than a jniLib because AGP only accepts the `.so` suffix in
`jniLibs`, and `libtalloc.so.2` has none. proot finds them through `LD_LIBRARY_PATH` at exec time
anyway, not through the app's native library search path.

## CI

[.github/workflows/build.yml](../.github/workflows/build.yml) — triggered on push to `main`, on pull
requests, and manually. Runs on `ubuntu-24.04` rather than `ubuntu-latest`: a runner image bump that
changed the JDK or NDK would otherwise silently change a build whose artifact hashes are pinned.

Steps: checkout → JDK 21 → Python 3.12 → `setup-android` → `sdkmanager` for platform 36 and
build-tools 36 → restore the download cache → `bash tools/build.sh` → upload artifacts.

**First run: green.** Every step passed and the artifacts uploaded:

| | |
|---|---|
| APK | 121,229,540 bytes |
| rootfs | 14,418,572 bytes |
| rootfs `tar_sha256` | matches the local Windows build exactly |

Two corrections came out of actually running it:

- `android-actions/setup-android` defaults to `packages: tools platform-tools`, and **`tools` no
  longer exists in the SDK repository**, so the step failed before the build began. It now asks for
  `platform-tools` only.
- **The NDK is not installed.** Nothing compiles native code — proot and the engine are prebuilt and
  hash-checked. It was in the original step as an unverified guess and is dropped.

The APK size differs from a local build by ~49 KB because the debug keystore and `apksigner` differ
per machine; the content is what is verified, not the APK bytes. See
[08-rootfs.md](08-rootfs.md#the-pipeline) for why the `.tar.gz` bytes legitimately differ too.

The download cache is keyed on `tools/fetch_binaries.py` and `tools/rootfs/build.py`, so it is
invalidated when either the pin or the package set changes. A cold fetch takes ~50 seconds.

Artifacts: the APK (14 days) and the rootfs plus `MANIFEST.json` and `THIRD_PARTY_LICENSES.md`
(30 days). The job summary records the APK hash, the rootfs hash, and the APK size.

## Status

**Verified locally.** The script runs clean from a deleted `app/build/` and `.cache/`, on Windows
with Git Bash, JDK 21, and no Docker. The rootfs is byte-identical across rebuilds, and the APK asset
check passes.

**CI has not run.** This project is not yet a git repository and `gh` is not authenticated here, so
the workflow is written but unexecuted. Two things remain unproven until it does:

- that `sdkmanager` accepts `ndk;30.0.14904198` on the runner — the NDK is not used by the build yet
  (it would only be needed to compile proot from source), so it can be dropped if it is slow or
  unavailable;
- that the ~121 MB upload artifact behaves within the runner's limits.

Also note: the app has **no signing config for release** beyond reusing the debug keystore
(`app/build.gradle.kts`), which is deliberate for a sideloaded personal build and is not a shippable
configuration.

## Reproducing a released APK

Given an APK and a checkout of the same commit:

```bash
bash tools/build.sh
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

The rootfs is reproducible; the APK is not bit-identical across machines, because `apksigner` and the
debug keystore differ. What is checkable is the content: `check_apk_asset.py` proves the rootfs in the
APK matches `dist/rootfs/MANIFEST.json`, and `fetch_binaries.py --check` proves the staged engine
matches the pin without re-downloading it.
