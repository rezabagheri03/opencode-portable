# 02 — Architecture

How the pieces fit, what starts in what order, and how the watch stays in sync.

---

## Three principles

1. **Don't port opencode. Run it.** The binary already exists, compiled and tested, for `linux-arm64-musl` (`opencode-src/packages/cli/script/build.ts:37-55`). Ship it as-is.
2. **Don't build a UI. Serve the one that's already inside the binary.** The web UI is brotli-embedded into the CLI at build time (`packages/cli/script/build.ts:73-88`, consumed via `virtual:opencode-app-assets` at `packages/cli/src/app-assets.ts:13`, served by `packages/cli/src/services/web-ui.ts:20-44`). Any HTTP client pointed at the server gets the identical desktop GUI.
3. **The watch is a remote control, not a peripheral.** It drives the agent through the Kotlin bridge over the Wearable Data Layer — Bluetooth first, because the phone may be on cellular and watch Wi-Fi is the most expensive radio on a 247 mAh battery.

---

## System diagram

```
┌───────────────────────────── Galaxy S20 FE (Android 13) ─────────────────────────────┐
│                                                                                      │
│  ┌───────────────── Kotlin app process ─────────────────┐   ┌──── WebView process ─┐ │
│  │                                                      │   │                      │ │
│  │  ForegroundService (specialUse)                      │   │  the opencode web    │ │
│  │    │                                                 │   │  GUI, served from    │ │
│  │    ├─ unpack rootfs (first run only)                 │   │  the binary.         │ │
│  │    ├─ spawn proot ──────────────────────────┐        │   │                      │ │
│  │    ├─ health-check + restart the child      │        │   │  127.0.0.1:4096      │ │
│  │    ├─ wake lock                             │        │   └──────────▲───────────┘ │
│  │    ├─ subscribe to engine events            │        │              │             │
│  │    ├─ SpeechRecognizer → composer bridge    │        │              │ HTTP/SSE    │ │
│  │    └─ watch bridge ◀── Data Layer ──────────┼───┐    │              │ (loopback)  │
│  └─────────────────────────────────────────────┼───┼────┼──────────────┘             │
│                                                │   │    │                            │
│                                   ┌────────────▼───┴────┴───────────┐                 │
│                                   │  proot (ptrace, no root)        │                 │
│                                   │   └─ Alpine rootfs (musl, /etc) │                 │
│                                   │       └─ opencode serve ────────┼──▶ model API    │
│                                   │           └─ bash, git, rg      │   (free tier)   │
│                                   └─────────────────────────────────┘                 │
└──────────────────────────────────────────────▲───────────────────────────────────────┘
                                               │  Wearable Data Layer
                                               │  Bluetooth preferred · Play services
                                    ┌──────────┴───────────┐
                                    │  Galaxy Watch 4      │
                                    │  Wear OS, native     │
                                    │  Kotlin/Compose      │
                                    │  status · approve ·  │
                                    │  push-to-talk        │
                                    └──────────────────────┘
```

Note what is **not** in that diagram: a listener the network can reach. `opencode serve` binds `127.0.0.1` and nothing else, and the watch reaches the phone through Google's Data Layer rather than over the network — so there is no LAN listener, no router configuration, and no Wi-Fi pairing step to get wrong.

**Loopback is not private on Android, though.** Any app on the device can connect to `127.0.0.1`, and the server answers unauthenticated unless a password is configured (`packages/server/src/auth.ts:28-29`). The shell therefore generates a password once, keeps it in app-private storage, exports it as `OPENCODE_PASSWORD`, and loads the WebView with it in the URL. That is also why the port is **fixed** rather than chosen per launch: the page's origin is its port, and settings, drafts and the service worker are all origin-scoped. See [ADR-012](04-decisions.md#adr-012).

---

## Components

| # | Component | Language | Role | Why it exists |
|---|---|---|---|---|
| C1 | Phone app shell | Kotlin | Lifecycle, permissions, FGS, WebView host, **watch bridge** | Android requires a real app |
| C2 | proot | C (prebuilt) | ptrace-based chroot | Gives a real root, `/etc`, and **DNS** with no root |
| C3 | Alpine rootfs | Linux userland | musl libc, busybox, `/etc/resolv.conf`, git, bash | The reason the binary's children work at all |
| C4 | opencode binary | Bun-compiled TypeScript | The entire agent engine + HTTP server + web UI | The product |
| C5 | WebView | Chromium (system) | Renders the GUI | Free, identical to desktop |
| C6 | Watch app | Kotlin / Compose for Wear | Status, approve/deny, voice | The control surface |

**Not present, deliberately:** Electron (no Android target), Node.js runtime (wrong version), Termux (needs root or an unsupported targetSdk, and is a user-visible dependency), any custom Bluetooth socket (the platform forbids it — see [ADR-003](04-decisions.md#adr-003)).

---

## Why the rootfs is not optional

This is the single most important architectural fact in the document.

Android cannot run this binary at all, for two independent reasons, both measured from the shipped
artifact ([06-artifact-verification.md](06-artifact-verification.md)).

**It is not static, and it wants a loader at an absolute path.** `PT_INTERP` is
`/lib/ld-musl-aarch64.so.1` and `DT_NEEDED` lists `libc.musl-aarch64.so.1` and `libstdc++.so.6`.
It cannot start unless that exact path exists and resolves — and Android gives a third-party app
no writable `/lib`.

**And even with a loader, it cannot resolve a hostname.** musl reads `/etc/resolv.conf`; Android's
Bionic asks `netd` over a socket with a network ID instead (`dnsOpenProxy()`, `netIdForResolv()` in
`bionic/libc/dns/net/getaddrinfo.c`). There is no `/etc/resolv.conf` on Android and nothing
listening for a musl resolver.

The rootfs supplies both: `/lib` for the loader and `/etc/resolv.conf` for DNS. The second half is
not automatic — something has to write those nameservers. See
[06 §6.2](06-artifact-verification.md#62-dns-name-the-mechanism).

The agent without DNS is a tool that can read local files and nothing else. So:

> **No rootfs ⇒ no DNS ⇒ no product.**

Once proot+Alpine is in place, this also fixes a long list of smaller problems for free: `/tmp`, `/bin/sh`, `git` expecting a POSIX tree, `HOME`, and file modes.

---

## Process model

| Process | Started by | Lifetime | Killed by |
|---|---|---|---|
| Kotlin app | Launcher | Until force-stopped | Android |
| FGS | App | Survives backgrounding | Android (FGS eviction), user |
| proot → `opencode serve` | FGS | Until FGS stops | FGS on shutdown, or crash |
| WebView | App | App lifetime | App |
| Watch app | Launcher | Independent | Android |

Constraint: **the WebView is a client, not the engine.** If the engine dies, the WebView must be reloaded, not repaired. The FGS owns restarting the child and telling the WebView to reload.

---

## Startup sequence

1. **App launched.** `Application.onCreate` pre-warms the WebView (a cold WebView costs 300–800 ms) and reads persisted state.
2. **Permissions gate.** `RECORD_AUDIO` requested lazily on first mic use, **not** at launch. Startup must not show a dialog (US-1).
3. **Rootfs check.** If `/data/data/…/files/rootfs/.ok` is absent, extract the bundled tarball. Show progress. Once only, ~5–15 s.
4. **Binary check.** Extract the packaged engine binary and `chmod 0755`. Done once. **The binary ships as `lib/<abi>/libopencode.so`** and is executed from `nativeLibraryDir`, because Android ≥10 refuses to exec from the app data directory at targetSdk ≥29 (see [03-platform.md](03-platform.md#1-exec-from-app-data)).
5. **FGS starts** (`specialUse`), acquires `PARTIAL_WAKE_LOCK`.
6. **proot launches** `opencode serve --port 4096 --hostname 127.0.0.1` inside the rootfs, with the workspace bind-mounted and `OPENCODE_PASSWORD` set from app-private storage.
7. **Health poll** on `http://127.0.0.1:4096/` until 200, with exponential backoff and a 60 s ceiling. On failure: surface the child's stderr in the UI — never a blank screen.
8. **WebView loads** `http://127.0.0.1:4096/?auth_token=<base64(user:password)>`. The app consumes the parameter, strips it from the URL and keeps it as the connection credential ([ADR-012](04-decisions.md#adr-012)). Its background colour is set to the app's dark background first, so there is no white flash.
9. **Watch bridge connects.** `NodeClient` finds the paired node; `CapabilityClient` confirms it runs our watch app; `MessageClient` begins pushing derived state.

**[I]** Step 6's exact CLI flags need confirming against `opencode serve --help` on-device. `packages/cli/src/server-process.ts:71` shows the resolution order `hostname ?? config.hostname ?? "127.0.0.1"`, so a config file can also pin it.

---

## Data flows

### Chat

```
composer → WebView → POST /api (session message)
                      ↓
             engine → tool calls → child processes in rootfs
                      ↓
             SSE stream → WebView renders tokens, diffs, tool cards
```

Nothing custom. This is exactly the desktop path.

### Voice, phone microphone

```
mic button (patched into web UI)
   │ window.AndroidSTT.start()   ← JS bridge
   ▼
Kotlin: SpeechRecognizer, EXTRA_PARTIAL_RESULTS
   │ partial results → onPartial(text)
   ▼
window.__oc_setComposerText(text)  ← injected back into the web UI
   │
   ▼
user edits (never auto-sends)
```

The system recognizer only listens to the phone's own microphone, so this path cannot serve the watch. See below.

### Watch status

```
engine emits events (message parts, tool calls, permission requests)
   │ SSE (loopback)
   ├─▶ WebView (renders)
   └─▶ FGS bridge → derive compact state → MessageClient → watch
```

The FGS keeps a small derived object — `{phase, activity, file, elapsed, pendingDecision}` — because the watch must not parse a full event log, and a Bluetooth message should be well under 1 KB. Updating it is O(1) per event; sending it is throttled to **at most 1 Hz**, because radio wake-ups cost the watch battery.

Pending decisions also go into a `DataItem`, so a watch that reconnects after a dropout still sees the outstanding prompt rather than a blank screen.

### Approve

```
engine blocks on a permission prompt
   ├─▶ WebView shows the prompt
   └─▶ FGS pushes pendingDecision → DataItem + MessageClient → haptic
            │
            ├─ watch taps Approve → MessageClient → bridge → loopback POST → engine unblocks
            ├─ watch taps Deny    → MessageClient → bridge → loopback POST → engine unblocks
            └─ no answer in 5 min → treat as deny, tell both surfaces
```

Both surfaces are equal. Whichever answers first wins; the other dismisses.

### Watch voice

```
watch: AudioRecord 16 kHz mono → compress → ChannelClient
                                                    ↓ (Bluetooth)
phone FGS: receive → decode → PCM → bundled STT model → text
                                                    ↓
                              composer (for review, never auto-sent)
```

**This is the one path that forces a bundled STT model.** Android's `SpeechRecognizer` has no API for "transcribe this buffer" — it only listens to the local microphone. So watch audio must be transcribed by a model we ship. See [ADR-005](04-decisions.md#adr-005).

**[?]** Before building any of this: the watch may have its own usable speech recognizer, in which case the watch sends **text** and this entire pipeline disappears. That is a 30-minute test, and it is the first task in P3.

---

## Watch protocol

### Transport

The **Wearable Data Layer API** (`play-services-wearable`), which is part of Google Play services and is the only sanctioned way for a phone and watch app to talk:

> "Because the data layer APIs are designed for communication between handhelds and wearables, **these are the only APIs you can use** to set up communication between these devices. For example, don't try to open low-level sockets to create a communication channel."
> — [Data Layer API overview](https://developer.android.com/training/wearables/data/overview) **[V]**

Transport selection is automatic and **Bluetooth-preferred** **[V]**:

> "Data is transferred ... **Directly**, when there is an established Bluetooth connection between the Wear OS device and another device. Over an available network, such as LTE or Wi-Fi, using a network node on Google's servers as an intermediary."

Three client types, three jobs **[V]**:

| Job | Client | Why |
|---|---|---|
| Live status, commands, acks | `MessageClient` | Small JSON, RPC-shaped, `sendRequest()` gives a response |
| Pending decision durability | `DataClient` `DataItem` | Persists across disconnects; the watch sees it after reconnecting |
| Watch audio | `ChannelClient` | Stream-oriented; supports payloads **larger than 100 KB**; the docs name *"voice data from the microphone"* as its use case |

Why this is the right transport for a phone on cellular:

- The Bluetooth link between the phone and the watch **already exists** and is maintained by the OS. Using it costs no additional radio.
- Watch Wi-Fi is the single most expensive thing the watch can turn on. Nothing here needs it.
- If Bluetooth is unavailable, the Data Layer falls back to Google's relay node over the watch's own LTE or Wi-Fi — degraded, but not broken.

Costs, stated plainly:

- **Google Play services becomes mandatory** on both devices. Both target devices have it.
- `MessageClient` has **no persistence and no retry**; a disconnected send returns `TARGET_NODE_NOT_CONNECTED` and you must handle it. This is why pending decisions live in a `DataItem`.
- Message latency is typically sub-second when connected, but is **not** a real-time channel; treat it as "eventually, quickly". **[I]**

### Discovery and pairing

There is **no pairing step and no code to type.** The devices are already bonded by the OS, and the Data Layer addresses them as nodes:

1. `CapabilityClient` advertises a capability string from the phone app.
2. The watch queries for nodes that hold it. Exactly one should.
3. If more than one phone is found, the watch asks which — the only user-visible choice in the flow.

This replaces the six-digit code entirely. It also removes the Watch 4's lack of a camera from being a problem, and removes the NSD/mDNS discovery that would have depended on the router.

### Event → screen mapping

| Bridge state | Watch screen | Text |
|---|---|---|
| idle | Status | "idle · last task 3 m ago" |
| streaming | Status | last ~80 chars |
| tool running | Status | "running `bun test`" / "editing `auth.ts`" |
| decision pending | **Decision** + haptic | full command text or diff stat |
| error | Status | short error, tap for detail |
| complete | Status + light haptic | "done in 2 m 14 s" |

**[V]** The engine events behind this mapping are now enumerated in
[07-engine-api.md](07-engine-api.md#4-event-stream): `session.execution.started` / `.succeeded` /
`.failed` / `.interrupted` drive the phase, and `session.tool.called` carries `name` plus the tool's
`input` — which is what turns `edit` + `{path}` into "editing `auth.ts`" and `shell` + `{command}`
into "running `bun test`". `permission.asked` carries the full request. All confirmed from source, so
this mapping is a contract rather than a guess.

The watcher still consumes only the bridge's derived state, not raw engine events, so the watch does
not break if the engine's event schema changes.

**Reconnect sequence [V].** `GET /api/event` is volatile by contract — no replay, and a slow consumer
fails the stream. The bridge therefore resyncs on every `server.connected` by calling
`GET /api/session/active`, which is exactly what the web GUI does
(`packages/client/src/solid/data.ts:638-645`). This is the implementation of US-12.

---

## Filesystem layout

```
/data/data/<pkg>/                      ← app-private, not /sdcard
├─ files/
│  ├─ rootfs/                          Alpine, ~45 MB unpacked, writable
│  ├─ bin/libopencode.so               packaged engine binary, copied out and chmod'd
│  ├─ workspace/                       default project directory
│  └─ tmp/
├─ no_backup/                          excluded from Android auto-backup
└─ shared_prefs/                       port, model choice, first-run flags
```

**Why app-private and not `/sdcard`:** `/sdcard` is a FUSE filesystem. It does not support hardlinks, which opencode's git layer needs for its snapshot index (`packages/core/src/util/git.ts:634`), and it mangles mtimes, which the same layer preserves deliberately (`:604-616`). A workspace on external storage would silently corrupt the agent's notion of "what changed". Users can still *import* from `/sdcard` into the private workspace.

---

## Background survival

Android will try to kill this app. The mitigations, in order of importance:

1. **`specialUse` foreground service** with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` — the only FGS type that plausibly fits "runs a local agent server" and is not time-capped.
2. **`PARTIAL_WAKE_LOCK`** so the CPU does not sleep mid-task. Release when idle for >5 min.
3. **Battery optimisation exemption** requested with a clear explanation, not silently.
4. **`onTaskRemoved` / process-death handler** that restarts the child and reloads the WebView rather than showing a dead UI.
5. **Watch degrades cleanly.** Losing the watch must never affect the phone.

Note what is no longer needed: a `WifiLock`. The Bluetooth link to the watch is maintained by the OS whether or not the app asks, so there is no radio lease to hold.

Not available: `BOOT_COMPLETED` autostart of a `dataSync` service, and `dataSync` itself is capped at 6 h/24 h at targetSdk ≥35. This is why the type choice is load-bearing rather than cosmetic.

---

## Upstream patches required

The rule: **every patch must be small, and every patch must be upstreamable.**

| # | Change | Size | Why |
|---|---|---|---|
| P-1 | Mic button in the composer + JS bridge hooks | ~50 lines | Native STT cannot reach a React-controlled field cleanly without a hook |
| P-3 | Mobile layout pass: `100dvh`, safe-area insets, no hover states, 16px inputs | CSS only | See [03-platform.md](03-platform.md#webview-feel) |

Bluetooth routing **removed** a previously planned patch. Because the Kotlin bridge consumes the engine's existing SSE stream and re-exposes a compact state object to the watch, the engine needs no new endpoint and no public API surface. The watch never speaks HTTP.

Everything else — engine, tools, permissions, providers — is **unmodified upstream**. If a change requires forking the core, the plan is wrong.

---

## Artifacts we produce

| Artifact | Contents | Size |
|---|---|---|
| `app` APK | Kotlin shell, WebView, assets, `lib/*/libopencode.so`, `lib/*/libproot.so`, rootfs tarball | ~125 MB download / ~250 MB installed |
| `wear` APK | Watch client only (no engine, no rootfs) | ~6 MB |
| `opencode-src` build | Stock `bun build --compile --target=bun-linux-arm64-musl`, optionally with P-1/P-3 | 197.56 MiB / 207.16 MB |

Distribution is a debug-signed sideload in v1. Signing, versioning, and release procedure are in [03-platform.md](03-platform.md#build-and-release).
