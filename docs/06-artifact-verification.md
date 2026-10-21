# 06 — Artifact verification

The rest of this doc set was written from source and documentation. Source tells you what the
code *intends* to do; it does not tell you what the shipped binary *is*. Four assumptions in
[02-architecture.md](02-architecture.md), [03-platform.md](03-platform.md) and
[05-risks.md](05-risks.md) were load-bearing and unverified. This document measures them against
the real artifact and records what changed.

Every number here is reproducible from the commands in [§7](#7-how-to-re-verify).

---

## 1. Artifact identity

| | |
|---|---|
| Package | `@opencode/cli-linux-arm64-musl` |
| Version | `2.1.1` (`dist-tags.latest`) |
| Tarball | <https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.1.1.tgz> |
| Tarball bytes | `97,103,742` — **92.61 MiB / 97.10 MB** |
| Contents | `package/package.json` (244 B) + `package/bin/opencode` |
| Binary bytes | `207,162,576` — **197.56 MiB / 207.16 MB** |
| Binary SHA-256 | `e07ad6ddf4a2ea62210b4fa11df47e78a8070c9382cdb8919e36bd4f7b2cf7d7` |
| Compression ratio | 46.9 % |

This is the only file in the whole project that cannot be regenerated from source in an
afternoon, so it gets pinned. **If the SHA-256 changes, every claim below is void** and §7 must
be re-run.

Note the units, because earlier drafts of this plan mixed them: the binary is **197.6 MiB** but
**207.2 MB**. "197 MB" is wrong in the other direction. All sizes in this doc set are now given
in both, and the distinction matters when comparing against Play's 100 MB / 500 MB limits, which
are decimal MB.

---

## 2. Is it 16 KB page compatible? **[V]**

```
type              flags     offset      vaddr       filesz        memsz         align
LOAD                  4          0 0x00200000     20908536     20908536  0x00010000  (64 KB)
LOAD                  5   20908544 0x01600a00     51350256     51350256  0x00010000  (64 KB)
LOAD                  6   72258800 0x047094f0    134834960    134834960  0x00010000  (64 KB)
```

All three `PT_LOAD` segments declare `p_align = 0x10000` = **65536 = 64 KB**, four times the
16384 the rule requires.

- Was: `[?]` "not yet known; P0-4 measures it" ([03-platform.md](03-platform.md#5-16-kb-page-alignment))
- Now: **compatible with no rebuild, no LLD flag.** The `-Wl,-z,max-page-size=16384` fallback and
  the "rebuild with a newer Bun" branch are both dead. P0-4 ([TODO.md](../TODO.md)) collapses to
  the `zipalign -c -P 16` check on the finished APK.

Also from the header: `e_type = ET_EXEC`, which is **non-PIE**, loaded at a fixed base of
`0x00020000` with entry `0x0001600a00`. There is no relocation problem for proot — it is a
separate process with its own address space, and 2 MB is above `mmap_min_addr` on any Android
kernel.

---

## 3. Is it self-contained? **No.** **[V]**

This is the finding that changes the architecture story.

```
PT_INTERP  = /lib/ld-musl-aarch64.so.1
DT_NEEDED  = libstdc++.so.6
             libc.musl-aarch64.so.1
```

Three separate conclusions:

**3.1 The binary is dynamically linked, against an absolute interpreter path.** It cannot be made
to start without a `/lib/ld-musl-aarch64.so.1` reachable at that exact path. Not "should have a
rootfs" — *cannot start without one*.

**3.2 `libstdc++.so.6` is a mandatory rootfs package.** Bun's musl build links against the GNU C++
runtime (WebKit/JSC is C++). Alpine's base image does **not** include it. A rootfs built from
`alpine:latest` alone will fail at `exec` with:

```
Error loading shared library libstdc++.so.6: No such file or directory
```

The Dockerfile in [03-platform.md](03-platform.md#build-and-release) now installs it. This is the
single most valuable line in this document: without it, P0-1 fails on the first command and looks
like a proot problem rather than a missing package.

**3.3 The "no rootfs" argument gets stronger, not weaker.** [04-decisions.md](04-decisions.md#adr-001--run-opencode-in-a-prootd-alpine-userspace)
originally argued "a static musl binary *executes* and then cannot resolve a hostname". The real
artifact does not execute at all. So the claim "run the binary with no rootfs — it executes, but
DNS fails" is too generous: it fails at step 0, at the dynamic loader. Two independent,
individually fatal reasons now exist, and both are measured rather than inferred.

---

## 4. Where the bytes are **[V]**

| Section | Bytes | Share | What it is |
|---|---|---|---|
| `.bun` | 132,757,338 | 64.08 % | The app: transpiled JS module graph, UI assets, `@opentui`, provider plugins |
| `.text` | 51,343,584 | 24.79 % | Bun / JavaScriptCore / WebKit native code |
| `.rodata` | 18,211,912 | 8.79 % | Native read-only data |
| `.bun_builtins` | 2,614,194 | 1.26 % | Bun's own builtin modules |
| `.bss` | 1,810,936 | 0.87 % | — |
| everything else | <300 KB | — | `.plt`, `.got`, `.dynsym`, `.symtab`, `.strtab` |

**Native floor ≈ 72 MB (68.8 MiB).** That is Bun + JSC and it is not negotiable at any build flag.

### 4.1 There is no Skia. The "strip @opentui/Skia" plan was wrong.

[03-platform.md](03-platform.md#size-budget) claimed the TUI renderer was "dominated by a Skia
build" and estimated a 40–70 MB **[I]** saving from removing it. Measured:

| Marker | Count in `.bun` | Count in whole file |
|---|---|---|
| `skia` / `Skia` | 0 | **0** |
| `opentui` | 399 | 399 |
| `wOF2` (woff2) | 14 | 14 |
| `OTTO` (ttf) | 11 | 11 |
| `\x89PNG` | 6 | 8 |
| `\xFF\xD8\xFF\xE0` (jpeg) | 4 | 4 |
| `\0asm` (wasm) | 23 | 30 |

No Skia. `@opentui` is **JavaScript inside `.bun`**, and the bundled assets (fonts, images, wasm)
are *negligible* — dozens of files, not tens of megabytes. So there is no native library to
delete and no fat asset directory to prune.

The payload is code and UTF-16 metadata: `.bun` is only 15–25 % ASCII, and its most-repeated
24-byte runs are UTF-16LE strings (`release_date`, `"ties":{"input":["text"` — JSON schema text
stored as UTF-16). That is what 132 MB of bundled TypeScript plus schemas looks like.

**Consequence for P4.** The lean build is a **source rebuild with a narrowed entrypoint**
(`bun build --compile` without the TUI/desktop targets), not surgery on the shipped binary.
Binary surgery is not merely hard, it is impossible: `.bun` is a Bun-private container whose
offsets are load-bearing. And the realistic ceiling is the `.bun` share — bounded above by
132.8 MB, floored by 72 MB of native code that no build removes.

### 4.2 The UI is confirmed inside the binary **[V]**

`assets/index` appears twice, at offset `152,995,352` — inside `.bun`. Alongside `brotli` ×67 and
`.br` ×533 this confirms `packages/cli/src/app-assets.ts:13` +
`packages/cli/src/services/web-ui.ts:20-44`: `opencode serve` serves the prebuilt SPA from the
executable. The GUI is free; there is nothing to build for it. SPA payload size is not separately
measurable from the outside, but §4.1 bounds it as small.

`big-pickle` appears twice at offset `86,722,800`, so the free-model list
(`packages/core/src/model.ts:290-297`) ships in the binary and needs no configuration.

---

## 5. Corrections this forces

| # | Claim that was wrong | Now | Where fixed |
|---|---|---|---|
| **C-1** | 16 KB `p_align` unknown, may force a rebuild | `0x10000` on all `PT_LOAD`; compatible as shipped | [03 §5](03-platform.md#5-16-kb-page-alignment), R-08 |
| **C-2** | Rootfs needs `bash git ripgrep ca-certificates tzdata` | **plus `libstdc++`** — without it nothing starts | [03 · Build and release](03-platform.md#build-and-release) |
| **C-3** | `ripgrep/binary.ts:96-98` throws `unsupported platform for ripgrep: arm64-android`, blocking the agent | **Unreachable under proot** — the key resolves to `arm64-linux`, which is in the table. See §6.1 | [05 R-16](05-risks.md#risk-register), §6.1 |
| **C-4** | — (not previously noticed) | New failure mode: a missing `PATH` downloads a **glibc** `rg` that can never run, then caches it permanently. See §6.1 | [05 R-16](05-risks.md#risk-register), §6.1 |
| **C-5** | "DNS only inside rootfs" with no mechanism | Mechanism specified: `LinkProperties.dnsServers` → `/etc/resolv.conf`. See §6 | [02](02-architecture.md#why-the-rootfs-is-not-optional) |
| **C-6** | Sizes stated in bare "MB", mixing MiB and MB | Both units, one table | all |
| **C-7** | APK ~270 MB, needing an AAB or split install (A-6) | **APK ≈ 125 MB.** A deflated `.so` inside the APK is legitimate with `extractNativeLibs="true"`; the installer extracts it. No AAB needed | [03 · Build and release](03-platform.md#build-and-release) |
| **C-8** | P4 saving "40–70 MB by stripping Skia" | No Skia exists. P4 is a source rebuild; ceiling is `.bun`, floor is 72 MB native | [03 · Size budget](03-platform.md#size-budget) |

---

## 6. The two new actionable findings

### 6.1 ripgrep: the documented blocker is imaginary; a worse one is real

`packages/core/src/ripgrep/binary.ts` resolves `rg` in this order:

1. `:90` — `which("rg")` on **`PATH`**. If found and it is a file → return it. Done.
2. `:94` — a previously downloaded copy at `global.bin/rg`.
3. `:96-98` — look up `${process.arch}-${process.platform}` in a 7-entry table, else throw.

**Why C-3 is wrong.** The `arm64-android` key that [05-risks.md](05-risks.md) cited only occurs if
`process.platform === "android"`. Under proot we run a **Linux** musl binary, so Bun reports
`process.platform === "linux"` and `process.arch === "arm64"` → key `arm64-linux` → **present in
the table** (`:17`). The throw at `:98` is unreachable. There is no Android patch to write.

**Why C-4 is real and worse.** `packages/core/src/util/which.ts:5-6` builds its search path from
`process.env.PATH ?? ""`. If the spawned engine inherits an empty or minimal `PATH`:

- step 1 finds nothing,
- step 2 finds nothing on first run,
- step 3 resolves `arm64-linux` → downloads `ripgrep-15.1.0-aarch64-unknown-linux-**gnu**.tar.gz`,
- `tar` extracts it happily (tar does not care that the payload is unlinkable),
- `:93-94` then caches it at `global.bin/rg`, and `isFile` returns true forever after.

Result: a `rg` that exists, is executable-flagged, and dies with `No such file or directory` (its
glibc interpreter is missing) on **every** search, for the life of the install, with an error that
names a file which is plainly there. That is a week of debugging.

**Fix, in order of preference:**

1. `apk add ripgrep` in the rootfs image, so step 1 always wins offline.
2. Always pass a complete `PATH` (`/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`)
   when spawning proot.
3. P0 asserts `rg --version` *from inside the spawn environment*, not from an adb shell. If it
   fails, fail loudly at startup rather than at first search.

### 6.2 DNS: name the mechanism

[02-architecture.md](02-architecture.md#why-the-rootfs-is-not-optional) says "no rootfs ⇒ no DNS"
and stops there. The rootfs is necessary but not sufficient — `/etc/resolv.conf` inside it must
contain nameservers that actually answer.

Android's own resolver reaches the network's DNS servers over the same interface the app uses.
Therefore **whatever `LinkProperties.getDnsServers()` reports is reachable by construction**,
because netd is querying the same addresses. So:

```
ConnectivityManager.registerDefaultNetworkCallback(...)
  onAvailable / onLinkPropertiesChanged
    → activeNetwork.capabilities, activeNetwork.linkProperties.dnsServers
    → write "nameserver <ip>" lines to <rootfs>/etc/resolv.conf
```

Refreshed on every network change, which matters here specifically because switching between
Wi-Fi and cellular changes DNS servers, and this app is expected to do that mid-conversation.

Two caveats to keep honest `[?]`: Private DNS in *strict* mode can make `dnsServers` report
something that is not directly queryable, and a VPN's DNS is a different path again. Both are
edge cases for a personal device; neither invalidates the design.

---

## 7. How to re-verify

```bash
# 1. fetch and hash
curl -sSL -o musl.tgz \
  https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.1.1.tgz
tar -xzf musl.tgz -C ./
sha256sum package/bin/opencode     # expect e07ad6dd…cf7d7

# 2. headers (p_align, INTERP, NEEDED) — no toolchain needed beyond binutils
llvm-readelf -l -d package/bin/opencode | grep -E 'LOAD|INTERP|NEEDED'

# 3. section sizes
llvm-readelf -S package/bin/opencode | sort -k6 -r | head
```

On a machine with no `llvm-readelf`, `_artifacts/elfinfo.py` in this repo parses the same fields
from raw bytes with the standard library, and `_artifacts/bunpayload.py` reproduces §4.1.

Note for future work: REA's `inspect_binary_layout` refuses aarch64 (`unsupported_provider`,
"supports Linux x64 only"), so it cannot be used for this artifact. Header parsing is sufficient
for everything in this document; disassembly would need `open_binary` with a Ghidra provider and
is not required by any open question.

---

## 8. What this does **not** establish

- Whether the binary **runs** under proot on the S20 FE. This is R-01, still the top risk, still
  requires P0 on hardware. A perfectly-formed ELF can still die on seccomp, SELinux, or a kernel
  restriction proot cannot emulate.
- Peak or idle RAM. R-05.
- Whether background survival works. R-02.
- `ChannelClient` throughput. R-05, P3-0.
- The SPA's own byte count — bounded as small by §4.1, not measured.
- Anything about the **watch**. This artifact is the phone engine only.

The honest summary: **the static-analysis phase is now complete and the plan has no unmeasured
assumption left that can be measured from a desk.** Every remaining unknown needs the phone.

---

## 9. The GUI requirement, verified

Included here because it retires the last requirement that was being met by assertion rather than
evidence: *"a GUI like the desktop app, not a TUI."* Unlike §2–§5, the structure below is read from **source**; §9.1 then measures the GUI
in the artifact itself.

The desktop app is not a separate UI that the phone would merely resemble. It is a thin Electron
shell over the **same UI package**:

| Evidence | What it shows |
|---|---|
| `packages/app/package.json:2` | `"name": "@opencode/app"` — the UI package |
| `packages/app/package.json:7` | `"."` → `./src/index.ts` — the application itself |
| `packages/app/package.json:8` | `"./desktop"` → `./src/desktop.ts` — a **platform adapter**, not a second app |
| `packages/app/package.json:11` | `"./vite"` → `./vite.js` — the build plugin, so each host builds the same source |
| `packages/desktop/src/renderer/platform/*.ts`, `startup/locale.ts` | The Electron renderer imports its storage, notifications, menu, files and locale **from `@opencode/app/desktop`** |
| `packages/desktop/package.json:53` | Electron `44.4.5` — used only as a browser shell |

So `opencode serve` embeds the **web build of `@opencode/app`**, and the desktop app loads the
**desktop build of the same package**. The phone's WebView therefore runs the desktop app's UI
code, not a lookalike.

### 9.1 And it is inside the artifact, not next to it

The paragraph above was source-derived. It does not have to stay that way: the UI is **embedded in
the same 207,162,576-byte binary** whose sha256 is pinned in §1, so the face we validated for
`exec` is also the face that draws the screens.

`packages/cli/src/app-assets.ts:13` resolves `virtual:opencode-app-assets` →
`AppArchive.decode(...)` → a map of path → **brotli bytes**, and
`packages/cli/src/services/web-ui.ts:20-44` serves those bytes straight back with
`content-encoding: br`. Scanning the artifact for that manifest's key prefix finds it in full:

| Measured from the binary | Value |
|---|---|
| Unique `_assets/…` manifest keys | **1,665** |
| First `_assets/` occurrence | offset 89,098,328 |
| JS chunks | 1,471 |
| Fonts | 85 `.ttf`, 20 `.woff`, 20 `.woff2` |
| Stylesheets | 31 `.css` |
| UI sound files | 26 `.aac` |
| WASM engines | 4 — `docx_edit_bg`, `docx_parse_bg`, `pptx_wasm_bg`, `xlsx_wasm_bg` |
| Other | 7 `.svg`, 1 `.png` |
| PWA shell keys | `registerSW.js`, `oc-theme-preload`, `icons/` present |

Two consequences for the plan:

1. **Nothing is left to build for the GUI.** The screens, the fonts, the WASM document previews and
   the service-worker registration are all in the engine we already verified for Android. Android
   WebView supports WASM, so the Office previews work there too — they are simply not needed for
   v1 and can stay lazy.
2. **The 26 `.aac` UI sounds need one WebView setting.** Media playback in a WebView is gated on a
   user gesture by default (`WebSettings.setMediaPlaybackRequiresUserGesture`), so without clearing
   that flag the interface's own sounds are silently dropped. That is the kind of detail that reads
   as "the app feels broken" rather than "a setting is missing".

**The only delta is the `src/desktop.ts` adapter**: native notifications, the application menu,
native file dialogs, and desktop storage. A WebView does not have those, and the web build already
does without them — they are not part of the chat surface. This is what makes
[03-platform.md](03-platform.md#webview-feel) a CSS task rather than a UI project, and it is the
strongest justification for
[ADR-002](04-decisions.md#adr-002): there is nothing to reimplement, only to restyle.

**One caveat, kept honest.** "Same code" is not "same pixels". The desktop shell contributes window
chrome, menu shortcuts and a native titlebar that the WebView will not have, and the responsive
layout has to be checked at phone width — which is exactly R-04 and the P1 CSS pass. The claim this
section supports is that the *interface* is correct by construction; the *fit* still has to be
tested on glass.
