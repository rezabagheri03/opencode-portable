# 03 — Platform

Every way Android blocks this app, the fix for each, and how it gets built and installed.

Android is not hostile to this design; it is indifferent to it. Each obstacle below has a known, boring fix. What matters is that none of them are discovered at week six.

---

## Device matrix

| Device | OS | API | ABI | Page size | Role |
|---|---|---|---|---|---|
| Galaxy S20 FE | Android 13 (One UI 5) | 33 | arm64-v8a | **4 KB** | Host |
| Galaxy Watch 4 | Wear OS 3.x | 30+ | arm64 | — | Client |

Two consequences of the S20 FE's Android 13 + 4 KB pages:

- **The 16 KB alignment requirement does not apply to you.** It is a Play Store submission rule for Android 15+ hardware; it never blocks installation on this phone. It becomes real only if you later publish.
- Android 13 supports everything in this design, including `specialUse` foreground services.

Minimum supported: `minSdk` 31 (Android 12). Target `targetSdk` 34 for the sideload build — high enough for modern behaviour, low enough to avoid the FGS and page-size pressure that only matters for distribution.

---

## 1. Exec from app data

**What Android does.** Since Android 10 (API 29), apps targeting API 29+ cannot execute a binary stored in their writable data directory, even if the file is `chmod 0755`. W^X enforcement. The process gets `EACCES`.

**The fix.** Ship executables as native libraries and execute them from `nativeLibraryDir`, which is always executable:

```kotlin
// in the APK: lib/arm64-v8a/libopencode.so, lib/arm64-v8a/libproot.so
val bin = File(applicationInfo.nativeLibraryDir, "libopencode.so")
// exec bin directly — it is already executable, no copy needed
```

```xml
<application android:extractNativeLibs="true" ...>
```

`extractNativeLibs="true"` is required: with `false`, libraries are mapped straight out of the APK and there is no real file to `exec`.

Prior art that proves this works: youtubedl-android, UserLAnd. This is not speculative.

Note the naming trap: the file is a `.so` so the package manager will extract it, but it is an **ELF executable**, not a shared object. Nothing links against it.

---

## 2. libc and DNS

**What Android does.** Bionic is not glibc and not musl. A musl binary runs, then resolves nothing, because musl reads `/etc/resolv.conf` while Android asks `netd` over a socket with a network ID.

**The fix.** Run inside a proot'd Alpine rootfs. Full reasoning in [02-architecture.md](02-architecture.md#why-the-rootfs-is-not-optional).

**proot needs `ptrace`.** Android allows an app to trace its own children. No root required. This is how UserLAnd and proot-distro work today.

**[I]** SELinux permits same-domain ptrace; cross-domain is denied. Since proot traces its own descendants, this is fine, but the first P0 run on hardware is the proof.

---

## 3. Cleartext to localhost

**What Android does.** Since API 28, cleartext HTTP is blocked by default. The WebView loading `http://127.0.0.1:4096` fails silently or shows `net::ERR_CLEARTEXT_NOT_PERMITTED`.

**The fix.** A network security config with an explicit loopback exemption:

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="true">127.0.0.1</domain>
    <domain includeSubdomains="true">localhost</domain>
    <!-- LAN IP for the watch, added at runtime or as a permitted range -->
  </domain-config>
</network-security-config>
```

```xml
<application android:networkSecurityConfig="@xml/network_security_config" ...>
```

Do **not** use `android:usesCleartextTraffic="true"` — it is a blunt instrument and is deprecated in favour of the config file for API 38+.

---

## 4. Foreground service type

**What Android does.** A long-running local server must live in a foreground service. The type is not cosmetic:

- `dataSync` is capped at **6 hours per 24 hours** for apps at targetSdk ≥35.
- `specialUse` is not time-capped, but requires a declared subtype.
- `BOOT_COMPLETED` cannot start a `dataSync` FGS.

**The fix.** `specialUse` with an honest subtype string:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".EngineService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
  <property
      android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
      android:value="local coding agent engine and HTTP server" />
</service>
```

If the app is ever published, this must be declared and justified in the Play Console. Sideloaded, it does not.

---

## 5. 16 KB page alignment

**Does not apply to the S20 FE** (4 KB pages) and does not block a sideload on any device.

It matters the day you publish, because Play rejects non-compliant updates from **2027-02-01**. When that matters:

```bash
# every shipped .so must declare p_align >= 0x4000
llvm-readelf -l lib/arm64-v8a/libopencode.so | grep LOAD

# the packaged APK must be 16 KB aligned
zipalign -c -P 16 -v 4 app.apk
```

**Measured: this is a non-issue.** The shipped 2.1.1 binary declares `p_align = 0x10000` (64 KB)
on all three `PT_LOAD` segments — four times the requirement. No rebuild, no
`-Wl,-z,max-page-size=16384`, no newer Bun. The measurements are in
[06-artifact-verification.md](06-artifact-verification.md#2-is-it-16-kb-page-compatible-v).

What remains is the packaging half only: `zipalign -c -P 16` on the finished APK. P0-4 is now a
five-minute check, not a feasibility question. See
[05-risks.md](05-risks.md#the-one-gate-that-mattered).

---

## 6. Storage

**What Android does.** `/sdcard` is a FUSE filesystem: no hardlinks, unreliable mtimes, no POSIX permissions, and SAF gives you a tree of opaque URIs rather than paths.

**The fix.** Keep the workspace in app-private storage. Import from `/sdcard` by copy. Details and the exact breakage in [02-architecture.md](02-architecture.md#filesystem-layout).

This also means "no SAF integration in v1" — a deliberate scope cut, because an agent that edits files through a document-provider abstraction is a different and much worse product.

---

## WebView feel

The GUI is a web page. On a phone, six specific tells give that away. All are CSS, all are cheap, and skipping them is what makes a WebView app feel like a bookmark.

```css
html {
  -webkit-tap-highlight-color: transparent;  /* no grey flash on tap */
  overscroll-behavior: none;                 /* no pull-to-refresh hijack */
  -webkit-text-size-adjust: 100%;
}
button, a, [role="button"] {
  touch-action: manipulation;                /* kill the 300 ms click delay */
  user-select: none;
}
input, textarea { font-size: 16px; }         /* stop iOS/Android zoom-on-focus */
.app { height: 100dvh; }                     /* not 100vh — survives the keyboard */
@media (hover: hover) and (pointer: fine) { /* all :hover rules live here */ }
```

Plus, on the native side:

- `<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content">`
- `env(safe-area-inset-*)` padding so nothing hides under the notch or the gesture bar
- WebView background colour set to the app's dark background **before** load, to avoid a white flash
- Pre-warm the WebView at launch
- `WebView.setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)`

Expect this to get you most of the way. Scroll physics and gesture-driven sheets stay subtly web-ish; that is the honest cost of not building a native GUI, and [04-decisions.md](04-decisions.md#adr-002) explains why it is the right trade.
### Three WebView hooks a browser does not need

The web build is designed to run in a plain browser and is careful about what it asks for. Three of
those asks land on the Android host, and each is a single override.

| The page asks for | Without the hook | Hook |
|---|---|---|
| A file to attach | Attachments do nothing. The web platform has no `openAttachmentPickerDialog` at all, so this is the *only* route files get in | `WebChromeClient.onShowFileChooser` |
| Camera or microphone | `getUserMedia` rejects and no prompt appears | `WebChromeClient.onPermissionRequest`, then `request.grant(request.getResources())` |
| To play a sound | Silently dropped — 26 `.aac` UI sounds ship inside the engine, and media playback is gesture-gated by default | `settings.mediaPlaybackRequiresUserGesture = false` |

The QR pairing screen states the first two in one line: it is gated on
`platform.platform === "web" && window.isSecureContext && navigator.mediaDevices.getUserMedia`
(`packages/app/src/servers/connect/camera.ts:6`). Loopback satisfies `isSecureContext`, so the gate
passes; the permission grant is the only missing piece.

**Do not patch `index.html` to add a bridge.** The served CSP hashes the one inline script that page
carries (`oc-theme-preload-script`, see `packages/cli/src/services/web-ui.ts`), so editing that file
invalidates the hash and the UI fails to boot under its own policy. The native bridge goes in with
`addJavascriptInterface`, which exposes `window.*` to every page without touching a byte of the
served HTML.


---

## Permissions

| Permission | When requested | Why |
|---|---|---|
| `INTERNET` | install | Model API |
| `ACCESS_NETWORK_STATE` | install | Detect connectivity loss |
| `FOREGROUND_SERVICE` + `..._SPECIAL_USE` | install | Keep the engine alive |
| `WAKE_LOCK` | install | Survive screen-off during a task |
| `POST_NOTIFICATIONS` | second launch | FGS notification, watch-free decisions |
| `RECORD_AUDIO` | first mic tap | Voice input |
| `BLUETOOTH_CONNECT` | install (API 31+) | Data Layer to the watch, both apps |
| `QUERY_ALL_PACKAGES` | **never** | Not needed, and a Play landmine |

Startup must never show a dialog. US-1 depends on it.

Note there is **no** `ACCESS_FINE_LOCATION` and no mDNS/NSD permission: the watch is discovered through `CapabilityClient` over the Wearable Data Layer, not by scanning the network. And there is no `usesCleartextTraffic` LAN exemption for the watch, because the engine's HTTP port never leaves loopback.

The one non-obvious dependency: **Google Play services must be present on both devices** for the Data Layer. Both target devices ship with it. A de-Googled phone cannot pair with a Wear watch for any purpose, so this is not a constraint this app introduces — but the app should detect it and say so clearly rather than failing silently.

---

## Build and release

### Toolchain

| Need | Version | Note |
|---|---|---|
| JDK | 21 | Gradle requirement |
| Android SDK | API 34 platform + build-tools | Studio or cmdline-tools |
| Gradle / AGP | current stable | Kotlin DSL |
| NDK | **only if** building proot from source | A prebuilt proot is fine for P0 |
| Docker or WSL2 | any | To build the Alpine rootfs reproducibly |

### Rootfs preparation (host-side, once)

Do **not** build the rootfs on the phone — it would need `apk` and network access at first run, both of which are exactly what the design avoids.

```bash
docker run --rm -v "$PWD:/out" alpine:3.21 sh -c '
  apk add --no-cache bash git ripgrep ca-certificates tzdata coreutils findutils \
    libstdc++
  rm -rf /var/cache/apk/*
  tar -czf /out/rootfs.tar.gz -C / --exclude=proc --exclude=sys --exclude=dev .
'
```

`libstdc++` is not optional and is easy to miss: the binary declares
`DT_NEEDED = libc.musl-aarch64.so.1, libstdc++.so.6` ([06 §3](06-artifact-verification.md#3-is-it-self-contained-no-v)).
Alpine's base image has no `libstdc++`, so without that package the engine dies at `exec` with
`Error loading shared library libstdc++.so.6` — which looks like a proot failure and is not.

Result: ~16 MB compressed, ~45 MB unpacked. Shipped as an asset, extracted on first run to `files/rootfs/`.

**Built and verified 2026-10-21.** The pipeline is `tools/rootfs/rebuild.sh`; see
[08-rootfs.md](08-rootfs.md) for what it does and the two aapt traps it works around. Measured:
**14,455,551 bytes compressed, 1,636 members, 36 packages, 520 symlinks**, byte-identical across
rebuilds. `ripgrep` 15.1.0 resolves at `usr/bin/rg`.

Three points the recipe above gets wrong or leaves implicit:

- **Docker is not required.** `tools/rootfs/build.py` assembles the tar on any host with Python 3.9+
  by streaming package members straight into the output archive. The Dockerfile is kept as the
  reference path and to make the package set reviewable in Alpine's own resolver.
- **Extracting to a Windows host is not possible.** A rootfs is largely symlinks (`/bin/sh`,
  `libc.musl-aarch64.so.1`), and Windows cannot create `/bin/busybox`-style absolute links or
  Windows-style targets. Extract-then-repack silently produces a rootfs with no shell. This is why
  the builder never touches the host filesystem.
- **Do not ship the asset as `.gz`.** aapt special-cases that suffix, decompresses the payload, and
  renames it to `rootfs.tar`, so the bytes in the APK stop matching the verified artifact. The
  asset is `assets/rootfs.dat` with `androidResources { noCompress += "dat" }`; `.dat` is also
  stored uncompressed, so the APK carries the exact pinned bytes.
  `tools/rootfs/check_apk_asset.py` asserts this on every rebuild.

**[I]** `ca-certificates` must be present or every HTTPS call to the model API fails with a certificate error, inside the rootfs and outside it (Bun bundles its own trust store, but the rootfs's `git` does not). This is on the P0 checklist because it is the classic first-run failure.

### The engine binary

Stock release artifact, or built from source with the patches in [02-architecture.md](02-architecture.md#upstream-patches-required):

```bash
bun build --compile --target=bun-linux-arm64-musl packages/cli/src/index.ts --outfile opencode
```

Then renamed to `libopencode.so` and placed at `app/src/main/jniLibs/arm64-v8a/`. It is 207,162,576 bytes (197.6 MiB) — the dominant term — but stored **deflated** (~96 MiB) inside the APK, because `extractNativeLibs="true"` lets the installer extract it on install.

### Packaging

| Setting | Value | Why |
|---|---|---|
| `minSdk` | 31 | Modern APIs, no legacy paths |
| `targetSdk` | 34 | See device matrix |
| `abiFilters` | `arm64-v8a` only | The device is arm64; emulator builds are separate debug variants |
| `extractNativeLibs` | `true` | Required to exec |
| ABI splits | Yes, for the watch | Watch APK must not carry the 197.6 MiB binary |
| `useLegacyPackaging` | unset | Leave modern packaging alone |

### Signing and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug-signed is correct for a single-user sideload. Create a release keystore only if you ever share the APK — and remember that doing so triggers the GPL obligations in [05-risks.md](05-risks.md#licensing).

### Installing on the watch

The Watch 4 is sideloaded over Wi-Fi:

1. On the watch: Settings → About → Software → tap build number ×5 → developer options.
2. Enable **ADB debugging** and **Debug over Wi-Fi**; note the IP and port.
3. `adb connect <watch-ip>:5555`
4. `adb install -r wear/build/outputs/apk/debug/wear-debug.apk`

The watch app declares:

```xml
<uses-feature android:name="android.hardware.type.watch" />
<meta-data android:name="com.google.android.wearable.standalone" android:value="true" />
```

`standalone=true` matters: it lets the watch app launch and run on its own, which is exactly the P3 failure mode you want to survive — "phone app backgrounded, watch still useful".

### Size budget

| Item | In the APK | Installed |
|---|---|---|
| opencode binary, deflated inside `lib/arm64-v8a/libopencode.so` | ~96 MiB | 197.6 MiB |
| Alpine rootfs incl. `libstdc++`, `git`, `ripgrep` (compressed) | ~16 MiB | ~45 MiB |
| proot | <1 MiB | ~1 MiB |
| Kotlin app + WebView + resources | ~12 MiB | ~12 MiB |
| Offline STT model *(optional, but required for the watch)* | ~40 MiB | ~40 MiB |
| **Total** | **~125 MiB / ~130 MB** | **~295 MiB / ~310 MB** |

A `.so` may be stored **deflated** in the APK as long as `extractNativeLibs="true"`; the installer
extracts it to `nativeLibraryDir` on install. So the 197.6 MiB binary costs ~96 MiB of download and
costs zero extra code — no custom extraction step. That is what makes a ~125 MiB APK possible
instead of the ~270 MB earlier drafts assumed, and it retires assumption A-6.

The 197.6 MiB breaks down as `.bun` 132.8 MB (the app's own JavaScript) + `.text`/`.rodata`
≈ 69.6 MB (Bun/JSC, irreducible at any build flag) + ~3 MB of everything else — measured in
[06 §4](06-artifact-verification.md#4-where-the-bytes-are-v).

An earlier draft claimed the TUI renderer was "dominated by a Skia build" and worth 40–70 MB **[I]**.
**That was wrong.** The binary contains no Skia at all, and `@opentui` is JavaScript living inside
`.bun`. There is no native library to delete. P4 therefore means **rebuilding from source with a
narrowed entrypoint** (no TUI, no desktop targets), because binary surgery is impossible: `.bun` is a
Bun-private container with load-bearing offsets. Ceiling: the 132.8 MB `.bun`. Floor: 69.6 MB.

---

## Compliance checklist

Sideload-only, so most of this is future-proofing rather than a gate.

| Item | Now (sideload) | Before publishing |
|---|---|---|
| targetSdk 36 | not required | required for new apps from 2026-08-31 |
| 16 KB page alignment | not required | required; Play blocks non-compliant from 2027-02-01 |
| FGS subtype declaration | works without | required in Play Console |
| Large-app warning (>200 MB) | n/a | expected; user sees a warning |
| Legacy APK 100 MB cap | n/a | use an AAB — 500 MB base module |
| Data safety form | n/a | declare model API traffic |
| License disclosure | n/a | GPL-2.0 components must be disclosed |
