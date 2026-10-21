> **Superseded.** This was the first feasibility write-up. The current documentation set starts at [README.md](README.md). Kept because the original reasoning, the measured binary sizes, and the three dead ends are still worth reading — but two of its conclusions changed: the userland is now proot + Alpine (not a minimal rootfs), and 16 KB page alignment is no longer treated as a project gate.

# Custom opencode for Android — single-APK, self-contained

**Goal.** One APK. User installs it, opens it, pastes an API key, works. No external server, no Termux, no SSH, no companion app.

**Verdict.** Achievable — but not by porting the desktop app, and not by running the shipped binary natively. It requires shipping a small Linux userland *inside* the APK. That is invisible to the user and satisfies every stated requirement, but it is a real constraint you should accept knowingly rather than discover in month two.

Target tree: `anomalyco/opencode` @ `0718864d` (v2.1.1, MIT).

---

## 1. The one decision that shapes everything

opencode is not a native app. It is a TypeScript engine (`packages/core`) plus an HTTP server (`packages/server`) plus a SolidJS UI (`packages/app`), executed on **Bun**. Everything else in this document follows from that.

The good news, verified in source: **you do not have to write any UI.**

`packages/cli/src/services/web-ui.ts:20-44` serves the full SPA on every non-`/api` path, with SPA fallback to `index.html`. And `packages/cli/src/app-assets.ts:13` loads it from `virtual:opencode-app-assets`, which `packages/cli/script/build.ts:73-88` builds as a **brotli archive embedded as file bytes inside the CLI executable**.

So `opencode serve` is already a self-hosting product: one binary containing engine + HTTP API + web UI. The APK does not need Electron. It needs a WebView.

That reduces the entire problem to a single question: **how do we execute that binary on Android?**

---

## 2. Three approaches that do not work

Worth stating explicitly, because each looks reasonable and each fails for a different reason.

### 2a. Port the Electron desktop app
Electron has no Android target. Dead immediately. The desktop renderer is also the *web* UI, so this buys nothing anyway.

### 2b. `nodejs-mobile`
The standard "Node inside an Android app" library. It ships **Node 18.20.4** (released 2024-10-07, Node 18 EOL'd 2025-04). opencode's Node build pins **`NODE_VERSION = "26.4.0"`** (`packages/cli/script/build-node.ts:19`) and the `node:sqlite` adapter calls `setReturnArrays`, `allowExtension`, `loadExtension` (`packages/core/src/database/sqlite.node.ts:48,65,85`) — late APIs, not present in 18. Its only recent activity is a `release18-20-4+16kb-fix` branch. **Not viable at any version.**

### 2c. Run the shipped `linux-arm64` binary directly on Android
This is the tempting one: `@opencode/cli-linux-arm64-musl` is a published, self-contained 197.6 MB binary. Just `exec` it, right? Two hard failures:

**No libc.** It is a Linux ELF (glibc or musl). Android uses Bionic. Neither links.

**No DNS.** This is the subtler and more fatal one. musl's resolver reads `/etc/resolv.conf` (`src/network/resolvconf.c`). Android has no such file — Bionic asks **netd** over a socket and passes a netid (`bionic/libc/dns/net/getaddrinfo.c`, `dnsOpenProxy()` / `netIdForResolv()`). A musl-static process on Android can be `exec`'d successfully and still be unable to resolve a single hostname. An AI coding agent with no DNS is a text editor.

**Conclusion: without a Linux userland, there is no product.** The APK must carry one.

---

## 3. The architecture that works

`proot` — a `ptrace`-based `chroot` that needs no root — plus a minimal Alpine rootfs. This is exactly how Termux's `proot-distro` works and how UserLAnd ships a distro inside one APK (Apache-2.0, open source, best structural precedent). The user never sees it: no terminal, no second app, no setup.

```
┌─────────────────────────────── APK (arm64-v8a) ───────────────────────────────┐
│                                                                               │
│  lib/arm64-v8a/                                                               │
│    libocbin.so       ← stock @opencode/cli-linux-arm64-musl  (197 MB)         │
│    libproot.so       ← NDK-built proot, 16 KB-aligned        (~2 MB)          │
│                                                                               │
│  assets/                                                                      │
│    rootfs.tar.zst    ← Alpine + bash + git + ca-certificates (~50 MB)         │
│                                                                               │
│  Kotlin app: FGS · WebView · SAF import/export · Keystore · NSC               │
└───────────────────────────────────────────────────────────────────────────────┘
                                    │
                      first run: extract rootfs
                                    ▼
      /data/data/<pkg>/files/{alpine, workspace, state}
                                    │
   exec from nativeLibraryDir (exec-permitted, read-only, apk-owned):
                                    ▼
   proot -r files/alpine -0 -w /work \
         -b /dev -b /proc -b /system:/system \
         -b files/workspace:/work \
         /opt/opencode/opencode2 serve --hostname 127.0.0.1 --port 4097
                                    │
                          HTTP + SSE on loopback
                                    ▼
                    WebView → http://127.0.0.1:4097
                    (the full opencode UI, brotli-served)
```

**Why this is the right shape**

- **Zero patches to opencode.** You ship v2.1.1 as published. Upgrade path is a version bump.
- **Everything works.** Inside the rootfs it is a normal Linux: `git`, `sh`, `tar`, symlinks, hardlinks, inotify, `chmod`. The `#sqlite` / `#pty` / `#fff` adapters all resolve to their `node` variants and behave normally. Even ripgrep's runtime download works, because `packages/core/src/ripgrep/binary.ts:19` already has an `arm64-linux` triple and Alpine provides `tar`.
- **The `.bun.ts` / `.node.ts` question is moot.** `packages/core/package.json` resolves the seven `#`-specifiers through conditional exports, and the CLI already forwards `--conditions` to its self-spawned server (`packages/cli/src/util/process.ts:13-27`). Adding an `android` condition is cheap *if* you ever need it — but this architecture doesn't need it.
- **Terminal panel survives.** `@lydell/node-pty` has no musl-arm64 prebuild, so the TUI/terminal panel may not attach; the agent's `shell` tool does not use a PTY at all (`packages/core/src/shell.ts:310-311` — `ChildProcess.make` with pipes, output captured to a file). Losing the interactive terminal is acceptable for v1.

---

## 4. Component manifest and real sizes

| Component | Source | Size | Notes |
|---|---|---|---|
| `@opencode/cli-linux-arm64-musl` | npm, v2.1.1 | **197.6 MB** unpacked | measured from registry `dist.unpackedSize` |
| Alpine minirootfs + bash + git | alpinelinux.org | ~45–55 MB | pre-bake git so no first-run `apk` |
| `proot` (arm64) | termux/proot-distro build | ~2 MB | must be rebuilt 16 KB-aligned |
| ripgrep (arm64 static) | BurntSushi releases | ~5 MB | pre-bake; avoids first-run download |
| Kotlin/AndroidX shell | — | ~5 MB | |
| **Total install** | | **~255–270 MB** | |
| **APK download** | | **~150–190 MB** | with `extractNativeLibs=true` the 197 MB `.so` ships uncompressed, so expect closer to the high end |

The `cli-linux-arm64-musl` figure is verified. Everything else is an estimate — **do not quote the total without a real build.**

**Size optimisation available later:** the published binary bundles `@opentui/skia`, the TUI renderer, which is the dominant native blob (tens of MB) and is *entirely unused* by a WebView client. A custom build with `@opentui/*` externalised and the TUI command stripped should recover 40–70 MB. Do this in M3, not M0.

---

## 5. What the user experiences

1. Install APK (~1 min, 200 MB).
2. Open. Progress: *"Preparing… 1 of 3"* — rootfs extraction, 20–60 s, once.
3. Paste API key. Stored via Keystore; written into the rootfs config.
4. The normal opencode UI appears — same SolidJS app as desktop.
5. Pick or clone a project into the workspace; start a session.

Nothing else. No terminal, no server setup, no SSH, no account required (unless they use opencode Zen).

---

## 6. The six platform problems, and their fixes

These are the things that will actually cost you days. All verified.

### 6a. Exec from app storage is banned — use `nativeLibraryDir`
Since Android 10, targetSdk ≥ 29 apps "cannot invoke `execve()` directly on files within the app's home directory" — trusted code must come from the APK. Fix: ship ELFs as `lib/<abi>/lib*.so` with **`android:extractNativeLibs="true"`**, and they land in a read-only, exec-permitted, apk-owned directory. Proven by youtubedl-android (ships python + yt-dlp + aria2c this way, invoked as `libaria2c.so`).

Termux's alternative (pin `targetSdkVersion=28` and exec from data dir) is **dead for store distribution**: Play requires targetSdk 36 for new apps and updates from **2026-08-31**.

### 6b. WebView cannot reach loopback over cleartext by default
`usesCleartextTraffic` defaults to false at targetSdk ≥ 28, and docs never exempt loopback — assume `ERR_CLEARTEXT_NOT_PERMITTED`. Fix is a Network Security Config, not the flag (the flag is deprecated and ignored at API 38+):

```xml
<domain-config cleartextTrafficPermitted="true">
  <domain includeSubdomains="false">127.0.0.1</domain>
  <domain includeSubdomains="false">localhost</domain>
</domain-config>
```
Ship it regardless; it is six lines.

### 6c. Foreground service type: `dataSync` will be killed
`dataSync` is capped at **6 hours per 24 hours** at targetSdk 35+, `onTimeout` throws if you don't stop, and it cannot be started from `BOOT_COMPLETED`. `shortService` is ~3 minutes. The only honest type for a persistent local server is **`specialUse`** with `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` and a Play Console declaration. Sideloading avoids the review.

Also: **Doze suspends network**, so the agent loses internet in deep idle unless the user exempts the app from battery optimisation. Loopback keeps working, so the UI won't look broken — it will just hang on the next model call. Prompt for the exemption on first run.

### 6d. 16 KB page size is a hard gate
Android 15+ devices with 16 KB pages cannot map 4 KB-aligned ELF segments at all. Every ELF under `lib/<abi>/` needs `p_align >= 0x4000` on all `LOAD` segments, built with NDK r28+ / `-Wl,-z,max-page-size=16384`, and the APK must pass `zipalign -c -P 16 -v 4`. Play blocks non-compliant updates from **2027-02-01**.

**This is the top technical risk**, because the 197 MB `cli-linux-arm64-musl` is built by upstream Bun and its alignment is unknown. If it is 4 KB-aligned it simply will not start on a Pixel 9. M0 must test this on 16 KB hardware before anything else is built.

### 6e. DNS only exists inside the rootfs
Restated because it is the load-bearing reason for proot: write `/etc/resolv.conf` into the rootfs at first run from Android's `ConnectivityManager` DNS servers, and update it on network change. Without this the agent cannot reach any provider.

### 6f. Storage: workspace must be app-private
`/data/data/<pkg>/files` is real ext4/f2fs — symlinks, `chmod`, hardlinks, inotify all work; git is happy. `/sdcard` (even with `MANAGE_EXTERNAL_STORAGE`, which is a restricted permission needing a Play declaration) is FUSE: no exec, no symlinks, no real `chmod`, case-insensitive. `packages/core/src/git.ts:634` uses `fs.link` for the snapshot index and `:604-616` relies on mtime — both break on sdcard. SAF is a document API, not a POSIX tree, so it cannot host a git working tree.

Design accordingly: **workspace always app-private**; SAF is only an import/export door (copy in, copy out).

---

## 7. Phased plan

| Phase | Deliverable | Effort | Kill criterion |
|---|---|---|---|
| **M0 — Spike** | On a real device: `proot` + Alpine + stock musl binary → `serve` boots → WebView shows the UI → one agent turn completes | 1–2 wks | **If the 197 MB binary is not 16 KB-aligned and cannot be made so, this architecture dies on Android 15+ and you must go to M3-NDK first** |
| **M1 — MVP** | Single APK: `extractNativeLibs`, rootfs extraction with progress, `specialUse` FGS, NSC, health-gated WebView, API-key onboarding, app-private workspace | 3–5 wks | — |
| **M2 — Usable** | Project import/export via SAF, pre-baked git + ripgrep, battery-exemption prompt, process supervision + auto-restart, crash reporting, first-run benchmarks | 3–4 wks | — |
| **M3 — Lean** | Custom opencode build with TUI/skia stripped; `#android` condition adapters; optionally investigate a true Bionic NDK build of Node 26 + `#android` adapters to drop proot entirely | 4–8 wks | Only worth it if 250 MB or ptrace overhead is unacceptable |

**~8–11 weeks to a usable product** for one experienced Android + Node developer. M3 is optional.

---

## 8. Risk register

| # | Risk | Sev | Mitigation |
|---|---|---|---|
| 1 | Bun binary not 16 KB-aligned → won't run on Android 15+ 16 KB devices | **High** | M0 gate. Fallback: rebuild Bun/Node for Android with `-z max-page-size=16384`, or ship 4 KB-only and document device limits |
| 2 | proot `ptrace` interference on Android 14/15/16 SELinux | **High** | M0 on 3 device generations; reuse termux/proot-distro's maintained patches rather than a stale proot |
| 3 | ~200 MB download is a product-level rejection | Med | Trim in M3; consider feature-module / asset-pack if distributing via AAB |
| 4 | Play rejects `specialUse` FGS declaration | Med | Sideload for v1; declare honestly for store |
| 5 | proot causes 2–5× syscall overhead → slow first boot and file ops | Med | Measure in M0; warm-start keeps the daemon resident, so cost is per-boot not per-turn |
| 6 | Android kills the service anyway (OEM battery managers) | Med | `specialUse` FGS + battery exemption + supervised restart + "resume last session" on relaunch |
| 7 | First-run extraction needs ~500 MB free transiently | Med | Stream-extract; check `StatFs` and warn before starting |
| 8 | A 250 MB single APK cannot be published as a legacy APK (100 MB cap) | Med | Sideload (unlimited) or publish as AAB (base module limit 500 MB) |
| 9 | Upstream ships breaking changes each minor (V2 is young) | Low | You are shipping an unmodified binary — version pinning is trivial |

---

## 9. What is genuinely uncertain, and how to close it

Ordered by how much they change the plan:

1. **Alignment of `cli-linux-arm64-musl`.** Inspect the npm tarball with `llvm-objdump -p | grep LOAD` (want `align 2**14`). Cheap, and it is the M0 gate.
2. **Does `opencode serve` boot under proot at all?** Bun's JIT and `/proc` use under `ptrace`. Test before building UI.
3. **Does the `login`/auth flow work with `--hostname 127.0.0.1`?** The UI is served pre-auth by design (`web-ui.ts:22-33`), so the WebView gets a sign-in screen rather than a 404 — but confirm the password bootstrap is scriptable.
4. **Is `@lydell/node-pty` needed at all for the web UI?** Expected: no. If the terminal panel hard-requires it, the panel is unavailable on musl-arm64.
5. **Node 18/20 compatibility** — only relevant if you pursue M3's NDK path instead of proot. Not needed for M0–M2.

---

## 10. Alternatives considered

**Rebuild opencode for Android natively (no proot).** Node upstream explicitly does not support Android ("Android is not a supported platform… no testing… in CI") but does provide `android-configure`; it builds, untested. Add the seven `#android` conditional adapters, drop `#pty`, point `#sqlite` at a Bionic-safe adapter, ship `git` and `rg` as `lib*.so`, use `/system/bin/sh` for the shell tool. Result: ~60–90 MB, no ptrace overhead, no rootfs. This is the *better* product and the *worse* first project — Node 26 on Bionic is an unmaintained frontier. Keep it as M3.

**Reimplement the UI natively in Compose.** Strictly worse: you already get a first-class, mobile-responsive UI for free — `max-width: 767px` breakpoints, `mobile-drawer` with `env(safe-area-inset-*)`, a virtual-keyboard adapter (`runtime/platform/keyboard.ts`), a PWA manifest, and a QR-pairing screen. Do not rebuild it.

**Remote-only client.** Contradicts the requirement (needs an external server). It remains the right product for a different use case.

---

## 11. Licensing

MIT. Forking, rebranding and redistributing is permitted; retain the copyright notice and licence text. Bundling Alpine (MIT), proot (GPL-2.0 — **check**: GPL obligations attach to the proot binary you distribute, not to opencode), git (GPL-2.0, invoked as a separate process, not linked), and ripgrep (MIT/Apache-2.0) is all compatible with a proprietary app *provided* you ship the GPL sources you redistribute. The proot and git licences are the two to verify with counsel before shipping a closed-source product.
