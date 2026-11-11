# 09 — Build

How the phone and watch APKs are built, locally and in CI. One script, one code path.

---

## The command

```bash
bash tools/build.sh
```

That is the whole build. It fetches and hash-checks the native binaries, builds and verifies the
rootfs, fetches and builds whisper.cpp, runs the unit tests, assembles **both** APKs, and then proves
the phone APK carries the rootfs and engine that were verified. A green CI run and a working local
build cannot drift apart, because [.github/workflows/build.yml](../.github/workflows/build.yml)
invokes this same script rather than reimplementing the steps.

**Requirements:** Python 3.9+, JDK 21, an Android SDK with platform 36 and build-tools 36, **NDK
30.0.14904198**, and **CMake 3.22.1**. The SDK is found through `ANDROID_HOME` or `local.properties`.
Everything else is fetched.

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk` — **154,812,205 bytes**
- `wear/build/outputs/apk/debug/wear-debug.apk` — **5,073,985 bytes**

| Component | Raw | In the phone APK |
|---|---|---|
| `lib/arm64-v8a/libopencode.so` | 207,162,576 | 104,149,220 (deflated) |
| `assets/whisper-model.bin` | 32,166,155 | 29,694,287 (deflated) |
| `assets/rootfs.dat` | 14,455,553 | 14,455,553 (**stored**) |
| `classes.dex` | 4,576,512 | 4,576,512 (stored) |
| `lib/arm64-v8a/libwhisper_jni.so` | 2,791,472 | 1,169,118 |
| `lib/arm64-v8a/libproot.so` | 255,240 | 120,981 |

`classes.dex` is stored rather than deflated because `extractNativeLibs`/`useLegacyPackaging` is on,
which is what makes `nativeLibraryDir` executable — see
[03-platform.md](03-platform.md#1-exec-from-app-data).

### Do not strip the engine

AGP's `stripDebugDebugSymbols` runs by default and rewrote `libopencode.so` to 207,096,264 bytes,
which is not the pinned artifact. `app/build.gradle.kts` keeps debug symbols for
`**/libopencode.so` only. Keeping `libproot.so` as well produced a 260 MB APK with a 104 MB hole in
the zip, so proot is allowed to be stripped (its size did not change). `check_apk_asset.py` now
asserts the engine inside the APK still matches the size and sha256 pin, so this cannot regress
silently.

## What is fetched, and why it is not committed

The engine is 198 MB, above GitHub's 100 MB per-file limit, so it cannot live in the repository.
`tools/fetch_binaries.py` downloads and verifies instead:

| Binary | Source | Verified against |
|---|---|---|
| `libopencode.so` | npm `@opencode/cli-linux-arm64-musl@2.1.1` | **sha256 `e07ad6dd…cf7d7`** — the pin in [06 §1](06-artifact-verification.md#1-artifact-identity) |
| `libproot.so` | Termux apt, `proot` 5.1.107.98 | per-package sha256 from the signed `Packages` index |
| `proot-deps.dat` | Termux apt, `libtalloc` + `libandroid-shmem` | same |
| `whisper.cpp/` source | GitHub tag `v1.9.5` | sha256 `ff1a9053…` |
| `whisper-model.bin` | Hugging Face, `ggml-tiny.en-q5_1.bin` | sha256 `c77c5766…`, 32,166,155 bytes |

A hash mismatch **fails the build**. This is the point: every measurement in
[06](06-artifact-verification.md) was taken against that exact engine, so a build that quietly
picked up a different opencode version would invalidate the document set rather than merely change
the output.

Two details worth recording:

- **Fetching by version, not by `latest`.** npm now serves 2.1.2 while the pins are 2.1.1, so CI stays
  on 2.1.1 and no build re-pins itself by accident.
- **The npm `unpackedSize` field corroborates the pin independently** — the registry reports
  207,162,820 bytes for the package (tarball plus metadata) against our 207,162,576 for the binary.

`proot-deps.dat` is an asset rather than a jniLib because AGP only accepts the `.so` suffix in
`jniLibs`, and `libtalloc.so.2` has none. proot finds them through `LD_LIBRARY_PATH` at exec time
anyway, not through the app's native library search path.

`whisper.cpp` is built by CMake into one `libwhisper_jni.so`; the model is copied from assets to
app-private storage on first use. The source and model are gitignored and fetched by the same script.

## CI

[.github/workflows/build.yml](../.github/workflows/build.yml) — triggered on push to `main`, on pull
requests, and manually. Runs on `ubuntu-24.04` rather than `ubuntu-latest`: a runner image bump that
changed the JDK or NDK would otherwise silently change a build whose artifact hashes are pinned.

Steps: checkout → JDK 21 → Python 3.12 → `setup-android` → `sdkmanager` for platform 36,
build-tools 36, **NDK 30.0.14904198 and CMake 3.22.1** → restore the download cache →
`bash tools/build.sh` → upload both APKs and the rootfs.

The workflow has completed successfully on the current native+wear code (run `43075957178`,
3m43s). The CI phone APK is 154,748,379 bytes versus 154,812,205 locally; the debug keystore and
signer differ, and `check_apk_asset.py` passes on both. The CI rootfs `tar_sha256` also differs from
the local one because `build.py` resolves a package index cached at a different time; that is the
documented caveat, not a mismatch inside an APK. Two earlier corrections are already in:

- `android-actions/setup-android` defaults to `packages: tools platform-tools`, and **`tools` no
  longer exists in the SDK repository**, so the step failed before the build began. It now asks for
  `platform-tools` only.
- The NDK was previously not installed because nothing compiled native code. whisper.cpp changes
  that; the workflow now installs it and CMake.

The APK size differs from a local build by a few tens of KB because the debug keystore and
`apksigner` differ per machine; the content is what is verified, not the APK bytes. See
[08-rootfs.md](08-rootfs.md#the-pipeline) for why the `.tar.gz` bytes legitimately differ too.

The download cache is keyed on `tools/fetch_binaries.py` and `tools/rootfs/build.py`, so it is
invalidated when a pin or the package set changes. A cold fetch is about 50 seconds for the engine;
the whisper model adds ~30 MB.

Artifacts: both APKs (14 days) and the rootfs plus `MANIFEST.json` and
`THIRD_PARTY_LICENSES.md` (30 days). The job summary records both APK hashes, the rootfs tar hash,
and the APK sizes. The earlier `['rootfs']['sha256']` key was wrong — the manifest has
`tar_sha256`/`gz_sha256`; the step now reads `tar_sha256` and no longer reports blank hashes.

## Status

**Verified locally.** The script runs clean from a deleted `app/build/` and `.cache/`, on Windows
with Git Bash, JDK 21, and no Docker. The rootfs is byte-identical across same-index rebuilds, the
APK asset check passes, and the engine inside the APK is still the pinned binary. 43 unit tests pass
(1 POSIX-only skip).

**Not verified on hardware.** No device was attached during this pass. P0 is still 0/8.

**Signing is debug-only.** The app has no release signing config beyond reusing the debug keystore
(`app/build.gradle.kts`), which is deliberate for a sideloaded personal build and is not a shippable
configuration. P4-4 is the release-signing task.

## Reproducing a released APK

Given an APK and a checkout of the same commit:

```bash
bash tools/build.sh
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

The **engine** is bit-reproducible from its npm pin. The rootfs tar is reproducible across hosts at a
given Alpine package index, but `build.py` resolves `APKINDEX`, so a package refresh changes it;
`dist/rootfs/MANIFEST.json` records what was actually shipped. The APK is not bit-identical across
machines because `apksigner` and the debug keystore differ. What is checkable is the content:
`check_apk_asset.py` proves the rootfs and engine in the APK match their pins, and
`fetch_binaries.py --check` proves the staged files match without re-downloading.
