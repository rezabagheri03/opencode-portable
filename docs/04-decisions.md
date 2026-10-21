# 04 — Decisions

Why this architecture and not the obvious alternatives. Each record is short on purpose: context, decision, consequence. If a decision is reversed, its record gets superseded rather than deleted — the reversal is the interesting part.

---

<a id="adr-001"></a>
## ADR-001 — Run opencode in a proot'd Alpine userspace

**Status:** Accepted

**Context.** Android cannot run the opencode binary at all. Two independent blockers, both measured from the shipped artifact ([06-artifact-verification.md](06-artifact-verification.md)): the binary is **not static** — it declares `PT_INTERP = /lib/ld-musl-aarch64.so.1` and needs `libc.musl-aarch64.so.1` plus `libstdc++.so.6` — so it cannot even exec without a musl tree at that path; and DNS resolution differs at the libc level, because musl reads `/etc/resolv.conf` while Bionic asks `netd` over a socket. It fails at the loader before it reaches the DNS problem.

**Decision.** Ship a proot binary and an Alpine rootfs inside the APK. Launch `opencode serve` inside it. prctl/ptrace-based, works with no root.

**Consequence.** +~16 MB compressed rootfs, a ~5–15 s first-run extraction, and a GPL-2.0 component (proot) in the APK. In exchange: real DNS, `/etc`, `/tmp`, POSIX paths, working `git`, `bash`, `ripgrep` and the `libstdc++.so.6` that the engine itself links against — every one of which the agent needs and none of which Android provides to a bare binary. This is the decision the whole project rests on.

---

<a id="adr-002"></a>
## ADR-002 — WebView for the phone GUI, not native Kotlin

**Status:** Accepted, with a documented escape hatch

**Context.** The web UI is brotli-embedded in the CLI and served on every non-`/api` path (`packages/cli/src/services/web-ui.ts:20-44`, `packages/cli/src/app-assets.ts:13`). It is the same GUI the desktop app renders. A native Kotlin client would mean rebuilding the session list, streaming chat, diff viewer, file tree, permission prompts, model picker, and settings — and maintaining that surface forever as upstream moves.

**Decision.** Ship the web UI in a WebView. Patch it only for mobile layout and the mic hook.

**Consequence.** Zero UI development, and the GUI is correct by construction. Cost: a ~125 MiB APK for a page that a native client would not need, and WebView scroll physics that are close to native but not identical. The escape hatch is real: the engine and the watch do not care what renders the chat, so a native client can be added later — or for only the chat screen — without touching anything else.

---

<a id="adr-003"></a>
## ADR-003 — The watch talks over the Wearable Data Layer, Bluetooth-first

**Status:** **Accepted — supersedes the LAN-HTTP-over-Wi-Fi decision made earlier in this project.** That version is preserved in the Consequence section so the reversal stays legible.

**Context.** The first instinct was to run `opencode serve` on the LAN and have the watch act as an ordinary HTTP client over Wi-Fi. The appeal was that every hard part of Bluetooth disappears: raw audio becomes a trivial POST body, and no codec is needed. That reasoning holds *only if both devices share a Wi-Fi network* — and it assumes watch Wi-Fi is free, which it is not.

Two facts break it:

1. **The phone may be on cellular.** No shared network, no LAN path, and the watch feature silently stops working exactly when the user most wants it — away from the house.
2. **Watch Wi-Fi is the most expensive radio on a 247 mAh battery.** Turning it on to serve one socket is a bad trade, and it must be on for the whole session, not just the moment of use.

There is also a hard platform constraint. Google's Wear OS documentation is explicit that the Data Layer is not one option among several:

> "Because the data layer APIs are designed for communication between handhelds and wearables, **these are the only APIs you can use** to set up communication between these devices. For example, don't try to open low-level sockets to create a communication channel."
> — [Data Layer API overview](https://developer.android.com/training/wearables/data/overview)

So a hand-rolled RFCOMM or L2CAP channel is off the table, and the Data Layer is the sane path regardless.

**Decision.** Use the Wearable Data Layer API (`play-services-wearable`), which is Bluetooth-preferred and falls back to Google's relay node over the watch's own LTE or Wi-Fi when Bluetooth is unavailable:

- `MessageClient` — live status, commands, acknowledgements.
- `DataClient` `DataItem` — pending decisions, so they survive a disconnect.
- `ChannelClient` — audio from the watch. Google's docs name *"voice data from the microphone"* as its use case and state it handles payloads **larger than 100 KB**.

**Consequence.**

Good:

- **Works on cellular.** The Bluetooth link between the phone and watch already exists and is maintained by the OS; using it costs no additional radio.
- **Watch Wi-Fi is never enabled.** The battery argument that motivated this decision disappears entirely.
- **No pairing code, no NSD, no IP addresses.** The devices are bonded by the OS; `CapabilityClient` finds the node that runs our app. The Watch 4's lack of a camera stops mattering.
- **No router dependency.** The client-isolation risk that was R-05 is gone.
- **A better security posture fell out of it.** The engine's HTTP port stays on `127.0.0.1` and never reaches the network: no LAN listener, no router dependency, nothing to forward. The one credential lives in app-private storage and in the loopback URL, and never crosses a wire to the internet ([ADR-012](#adr-012)).
- **It removed an upstream patch.** Because the bridge re-exposes a compact state object, the engine needs no new endpoint.

Costs, stated plainly:

- **Google Play services becomes mandatory** on both devices. Both target devices have it; a de-Googled phone cannot pair with a Wear watch for any purpose, so this is not unique to this app.
- **Audio must be compressed.** Raw 16 kHz PCM is 32 KB/s, which is not a safe assumption over a Bluetooth channel of unknown throughput. This is the subject of ADR-011.
- **`MessageClient` has no persistence and no retry** — a disconnected send returns `TARGET_NODE_NOT_CONNECTED`. Handled by keeping decisions in a `DataItem`.
- **Not a real-time channel.** Latency is sub-second typical, but it is an eventually-consistent pipe, not a socket.

---

<a id="adr-004"></a>
## ADR-004 — Push-to-talk only; no live audio streaming

**Status:** Accepted — reinforced by ADR-003

**Context.** Live transcription while the user speaks is a nicer demo and a worse product here. It requires continuous capture, continuous encode, continuous transport, and continuous ASR — all on hardware that is either a watch with a 247 mAh battery or a phone simultaneously running a language-model agent. Over Bluetooth the transport argument is even stronger.

**Decision.** v1 is hold → speak → release → send → transcribe → edit → send. Audio travels as one complete clip, not a stream of frames.

**Consequence.** No jitter buffer, no packet loss handling, no adaptive bitrate, no clock drift. The channel is used for exactly the shape of data it is good at — a burst, not a stream. And for an agent that executes commands, a mis-transcription gets seen and edited before it becomes an action.

---

<a id="adr-005"></a>
## ADR-005 — Phone microphone uses system STT; **watch audio requires a bundled model**

**Status:** Accepted — **amended twice.** The first amendment made the bundled model mandatory rather than optional. The second adds what upstream already ships, which changes the *shape of the work* rather than the decision.

**Context.** Three STT options: the OS `SpeechRecognizer` (0 MB, instant, quality depends on the device), a bundled offline model (+30–80 MB, deterministic), or a cloud API (best quality, another provider).

The constraint that decides it: **`SpeechRecognizer` only listens to the local microphone.** It has no API for "transcribe this audio buffer." So audio arriving from the watch over Bluetooth cannot be passed to it at all.

**What upstream already ships [V].** Less has to be invented here than the earlier drafts assumed:

| Capability | Where | What it means for us |
|---|---|---|
| Transcription, five providers | `packages/ai/src/transcription.ts`, `protocols/{openai,google,deepgram,assemblyai,elevenlabs}-*` | `generate`/`stream`/`start`/`resume`, word and segment timestamps, diarization |
| Real-time voice sessions | `packages/protocol/src/groups/voice.ts` → `POST /api/experimental/voice/session` | The server holds the provider key and returns a WebRTC SDP answer or a websocket token |
| A shipped voice *product* | `packages/app/src/session/timeline/voice-delegation.ts` | The **iOS app** sends a spoken request as a prompt headed `Voice delegation.`; this app already renders those |
| Microphone capture in the UI | nothing — only `camera.ts` calls `getUserMedia`, and only for QR pairing | The mic button is ours to build, in all three clients |
| Transcription over HTTP | nothing — only `voice.session` is exposed | Reusing cloud transcription means adding one protocol group |

So "voice" is not one thing upstream: **dictation into the composer does not exist, but server-brokered voice does**, with a client that already exists on iOS. The decision below is unchanged; the estimate behind P2-2 is smaller than it looked, and the realtime path is now an option we know exists rather than one we would have had to design.

**Decision.** Three tiers, one bridge:

| Source | Engine | Why |
|---|---|---|
| Phone microphone, offline | System `SpeechRecognizer` first, bundled model as fallback | 0 MB and instant when the device has a speech service |
| **Watch microphone** | **Bundled offline model, always** | The only path that needs no key and no buffer API |
| Optional cloud | The existing `voice.session` route, or a new transcription group | Better accuracy; free to skip |

**Consequence.** The bundled model is still a hard dependency of the watch feature, so P2-3 remains a prerequisite for P3 and the 30–80 MB belongs in the size budget from the start. What changes is the claim around it: cloud STT is no longer *excluded*, it is kept **out of the default path**. It costs one key, it is already implemented server-side, and the app is fully usable without it — which is the property that actually matters for a zero-config install.

**[?]** If the watch turns out to have a usable on-device speech recognizer of its own, the watch sends **text** and this entire path disappears. That is the first thing P3 tests.

---

<a id="adr-006"></a>
## ADR-006 — Workspace in app-private storage

**Status:** Accepted

**Context.** Users expect to point the agent at a folder they can see in their file manager. `/sdcard` is a FUSE filesystem: it does not support hardlinks, and opencode's git layer uses hardlinks for its snapshot index (`packages/core/src/util/git.ts:634`) and deliberately preserves mtimes (`:604-616`).

**Decision.** The workspace lives in app-private storage (`/data/data/…/files/workspace/`). Import from `/sdcard` by copying in.

**Consequence.** The agent's change-tracking works correctly. The cost is an import step and no SAF integration — accepted, because an agent editing files through document-provider URIs rather than paths is a materially worse agent, not merely a less convenient one.

---

<a id="adr-007"></a>
## ADR-007 — Free models as the default, no account required

**Status:** Accepted

**Context.** Zero-configuration is a product requirement, not a nicety. Verified upstream at `packages/core/src/model.ts:290-297`: signed out, only free OpenCode models are enabled, and `PREFERRED_FREE` puts `big-pickle` and the `*-free` variants first so a sensible default is auto-selected.

**Decision.** Do not add any onboarding, key entry, or account step. Launch into a working chat. Leave the normal provider UI intact for anyone who wants better models.

**Consequence.** First-run is one tap. The dependency is a rate-limited free tier that can change upstream without notice — recorded as risk R-07, with the mitigation being that paid providers are already implemented upstream and only need a key, so the failure mode is "degraded", not "broken".

---

<a id="adr-008"></a>
## ADR-008 — Sideload first; publishing is a later project

**Status:** Accepted

**Context.** Publishing would require targetSdk 36, 16 KB-aligned native code, an FGS declaration with justification, a data-safety form, a GPL disclosure review, and a large-app user warning. None of it improves the app for its one user.

**Decision.** Debug-signed sideloaded APK. Keep choices Play-compatible where it is free to do so; do not spend effort on it.

**Consequence.** Weeks saved. The main thing this buys is that **16 KB alignment stops being a project kill criterion** — on 4 KB-page hardware it never blocks installation. Publishing later remains possible, and [03-platform.md](03-platform.md#compliance-checklist) lists exactly what it would cost.

---

<a id="adr-009"></a>
## ADR-009 — Patch upstream, never fork the core

**Status:** Accepted

**Context.** The product needs a mic button in the composer and a mobile CSS pass. It does not need a different engine.

**Decision.** Two bounded patches (documented in [02-architecture.md](02-architecture.md#upstream-patches-required)), each small enough to upstream. Core engine, tools, permissions, and providers stay unmodified.

**Consequence.** Upstream updates stay cheap to absorb, and a patch that turns out to be unnecessary can be dropped. The tripwire is explicit: **if a change requires forking the core, the architecture is wrong and this document should be revisited**, not the patch expanded.

---

<a id="adr-010"></a>
## ADR-010 — Reject Electron and Node.js embedding

**Status:** Accepted (recording the dead ends so they are not re-litigated)

| Alternative | Why it fails | Evidence |
|---|---|---|
| Reuse the Electron desktop shell | Electron has no Android target. None has ever existed. | upstream |
| `nodejs-mobile` | Ships Node **18.20.4** (2024-10-07, EOL). opencode pins **26.4.0** and uses `node:sqlite` APIs that arrived later. The Node 22 Android branch stalled in 2024 and was never released. | project releases; `packages/cli/script/build-node.ts:19` |
| Build Node 26 for Android ourselves | Explicitly unsupported by upstream Node; a full NDK port is a multi-month project that then still needs a JS engine adapter for `#sqlite`, `#pty`, and five other runtime-conditional imports | Node `BUILDING.md` |
| Run the binary with no rootfs | **It does not execute.** The artifact is dynamically linked with `PT_INTERP = /lib/ld-musl-aarch64.so.1`; and even given a loader it still cannot resolve DNS. See ADR-001 | `06-artifact-verification.md` §3 |
| Termux as the runtime | A user-visible dependency (install, configure, keep alive) and relies on a targetSdk-28 quirk that Play's targetSdk 36 requirement eliminates | Termux `gradle.properties` |
| Cloud-hosted engine + thin client | Violates the core requirement: no external server | — |

**Consequence.** None of these get reconsidered on a whim. If one is revisited, this table is where the argument has to restart.

---

<a id="adr-011"></a>
## ADR-011 — Watch audio: record a clip, compress it, measure before optimising

**Status:** Accepted

**Context.** ADR-003 forces audio through `ChannelClient` over Bluetooth. Two unknowns decide the encoding:

1. **Throughput is unmeasured.** The Data Layer prefers Bluetooth, but the practical bitrate of this specific phone–watch pair is not documented anywhere, and guessing it is how projects ship a feature that takes 30 seconds to upload a sentence.
2. **Options differ wildly in cost.** Raw 16 kHz 16-bit PCM is 32 KB/s, so a 10-second clip is 320 KB. `ChannelClient` will accept it, but the transfer time is the question. µ-law companding at 8 kHz cuts that to 80 KB for one lookup table and twenty lines of code. Opus at 16–24 kbps would be ~20 KB but needs a bundled encoder, because Android MediaCodec has no guaranteed Opus *encoder* — only a decoder. **[I]**

**Decision.**

1. **Record-then-send**, never stream (ADR-004).
2. **Measure first.** P3-0 sends a 320 KB payload over `ChannelClient` on the real pair and records the wall-clock time, plus `MessageClient` round-trip latency. The codec decision is made from that number, not from this document.
3. **Start with µ-law 8 kHz** unless the measurement says otherwise. It is one table, no dependency, and 4× smaller than raw. Speech at 8 kHz is what telephony used for a century, and the ASR model resamples it happily.
4. **Escalate to Opus** only if µ-law transcripts are visibly worse in use. Bundling `libopus` by NDK is ~500 KB and a real, if small, build dependency.

**Consequence.** The feature ships without a codec dependency and without a guess. The known cost is fidelity: narrowband 8 kHz audio loses sibilance, which may show up as misheard words. That is a measurable, reversible decision — and a much better failure mode than an unmeasured throughput assumption that makes the watch feel broken.

If P3-0 measures comfortable throughput (say, a 320 KB clip in under a second), raw or 16 kHz µ-law becomes the default and transcription quality improves for free.

---

<a id="adr-012"></a>
## ADR-012 — One fixed loopback port, and a password generated once

**Status:** Accepted

**Context.** Two properties of the upstream app collide with a mobile process model.

1. **All client state is origin-scoped.** The web build persists drafts, settings and the last
   route in `localStorage` (`platform.tsx`: *"Storage mechanism, defaults to localStorage"*;
   `pwa.ts:4`), and in production it talks to `location.origin`
   (`runtime/platform/web.ts`, `getCurrentServerUrl`). The origin is `http://127.0.0.1:<port>`.
2. **A loopback port is not a private channel on Android.** Any app on the device can open a
   socket to `127.0.0.1`. By default `ServerAuth.required` is false when no password is configured
   (`packages/server/src/auth.ts:28-29`), so the engine would answer anyone who found the port.

On the desktop neither problem exists: the renderer is served through a registered custom scheme and
routes in memory (`packages/desktop/src/main/windows/`, `window/router.tsx`), so its origin is stable
and port-independent. On the phone, the page origin *is* the port.

A careless implementation picks a free port per launch. That silently resets every setting on every
launch, because a new port is a new origin with empty `localStorage` and a fresh service-worker
scope, and it looks like a data-loss bug rather than a porting mistake.

**Decision.**

1. **One fixed port**, chosen once and never varied. `4096` is the natural choice, matching the
   default already hard-coded in `runtime/platform/web.ts`.
2. **Bind `127.0.0.1` only.** Never `0.0.0.0`; the phone is not a server.
3. **Generate one 32-byte password on first launch**, store it in app-private storage, and pass it
   to the engine as `OPENCODE_PASSWORD` — the variable the CLI already reads
   (`packages/cli/src/env.ts:10`) and already generates in service mode
   (`packages/cli/src/services/standalone.ts:37`).
4. **Load the WebView at `http://127.0.0.1:4096/?auth_token=<base64("opencode:PASSWORD")>`** on
   every launch. The server accepts that query parameter
   (`packages/server/src/middleware/authorization.ts:12`, `decodeCredential`), and the app consumes it and strips
   it from the URL before rendering (`packages/app/src/entry.tsx:44-50`).
5. If the port cannot be bound, retry once, then show an error screen naming the port. Do not
   silently migrate to another one.

**Consequence.** Settings, drafts, the last session and the service worker survive every launch, and
the loopback engine is no longer reachable by other apps. Both costs are one-time and small: a fixed
port can collide with another app, and the token has to be re-supplied on every launch because the
WebView may lose its stored credential. Neither is a design risk — they are the price of a stable
origin, and the alternative is a class of bug that only shows up on real devices.

**Verified 2026-10-21 [V].** Each mechanism this ADR depends on was confirmed in source:

- `Authorization: Basic` and `?auth_token=` both decode through the same function, and the username
  must be exactly `opencode` (`packages/server/src/middleware/authorization.ts:32-39`,
  `packages/server/src/auth.ts:33`).
- `serve` takes the password from `OPENCODE_PASSWORD`, and **generates a random one when it is
  absent** (`packages/cli/src/server-process.ts:84-94`). Point 3 is therefore not optional: without
  it the password changes every launch.
- Because the password is ours and stable, `issueSession` tokens remain valid across restarts
  (`auth.ts:40-55`), so the WebView could hold a session instead of re-reading the password. The
  chosen mechanism stays `auth_token`; the alternative is noted, not adopted.

See [07-engine-api.md](07-engine-api.md#1-authentication) for the full auth contract.

**Note on the `auth_token` ordering.** `clearAuthToken()` runs before `restorePwaRoute()`, so the
route restore does not see the parameter and is not suppressed by the guard at `pwa.ts:22`. That
ordering is load-bearing; if the shell ever injects the token later, route restoration stops working.
