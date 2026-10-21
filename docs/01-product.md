# 01 — Product

What this is, who it is for, and exactly where v1 stops.

---

## The problem

opencode is a desktop coding agent. Using it requires a laptop, and using it *driven by voice, hands free* is impossible. The two things missing on a phone are the platform plumbing to run it, and a way to stay in control of an autonomous agent without sitting at a keyboard.

## The product

A single Android app that runs the agent locally, and a watch app that lets you **see and steer** the agent from your wrist.

The watch is not a microphone accessory. It is the control surface. The value is:

> The agent is working on your machine in your pocket. You glance at your wrist, see what it is doing, say "go ahead", and walk away.

## Who it is for

One person: the owner of a Galaxy S20 FE and a Galaxy Watch 4. Single-user, personal, sideloaded. This is a deliberate constraint — it removes app-store review, multi-tenancy, onboarding, account management, and support burden from v1.

It also relaxes the licensing story: GPL-2.0 components (proot, git) impose redistribution obligations only when you share the APK. See [05-risks.md](05-risks.md#licensing).

---

## User stories

Each is testable. `P1`/`P2`/`P3` maps to the phase in [TODO.md](../TODO.md).

| ID | Story | Phase |
|---|---|---|
| US-1 | I install one APK and open it. I am chatting with an agent within 60 seconds, with no terminal, no setup screen, and no API key. | P1 |
| US-2 | I open a folder on my phone and the agent can read, write, and search inside it. | P1 |
| US-3 | I ask the agent to make a change and I see the diff in the GUI before it lands. | P1 |
| US-4 | The agent asks permission to run a command. I approve or deny it in the GUI. | P2 |
| US-5 | I hold a mic button, speak my request, release, and the text appears in the composer. | P2 |
| US-6 | I close the app, come back an hour later, and my session is still there and still running. | P2 |
| US-7 | The agent is working while my phone is in my pocket. My watch buzzes when it needs a decision. | P3 |
| US-8 | I look at my watch and see what the agent is currently doing — which file, which command. | P3 |
| US-9 | I approve or deny the pending action from my watch, without taking out my phone. | P3 |
| US-10 | I hold the watch face, speak, and the request is sent to the running session. | P3 |
| US-11 | My phone is on mobile data with Wi-Fi off, and the watch still shows what the agent is doing and can approve a decision. | P3 |
| US-12 | The watch goes out of range. It says so, reconnects on its own, and the phone is completely unaffected. | P3 |

---

## Zero-config models

A first-run experience with no account and no API key is a hard requirement, and it is **satisfied by opencode upstream**. Verified in `opencode-src/packages/core/src/model.ts:290-297`:

```ts
// Signed out, only free OpenCode models are enabled. Clients default to the first available model, so keep
// these free models ahead of each new free launch instead of letting release order pick the default.
const PREFERRED_FREE = [
  "big-pickle",
  "deepseek-v4.1-flash-free",
  "glm-5.3-flash-free",
  "muse-spark-1.3-contributor-free",
]
```

**[V]** Signed out, the free model set is enabled and one of them is auto-selected. The user does not configure anything.

**[I]** These models are served by opencode's own inference endpoint, so the app requires a network connection even signed out. Free tier is rate-limited and can change without notice upstream. This is the app's only external dependency, and it is the same dependency the desktop app already has.

Consequences for the product:

- No onboarding wizard. First launch goes straight to a working chat.
- An account (opencode Console) and paid providers remain *available* through the normal UI, just never required.
- Because the app needs the network anyway for the model, cloud speech-to-text adds no new class of dependency — but the default STT is still on-device (see below), so voice keeps working while the model is thinking on a flaky connection.

---

## Voice input

| Priority | Engine | Why |
|---|---|---|
| 1 | Android's `SpeechRecognizer` | 0 MB, instant, streams partial words, handles Bluetooth headsets and the watch for free |
| 2 | Bundled offline model (whisper.cpp or sherpa-onnx) | Deterministic, no speech service required, ~30–80 MB |
| 3 | *(opt-in, not v1)* Cloud STT — **already implemented upstream** | Best accuracy; five providers are wired into `@opencode/ai` and the server already brokers realtime voice sessions. It costs one API key, so it cannot be the default |

Behaviour: hold mic → speak → partial text streams into the composer → release → text settles and you can edit before sending. Nothing auto-sends in v1; an autonomous agent should not act on a mis-transcription.

**What upstream already brings [V].** Two things this plan does not have to build:

- `packages/ai/src/transcription.ts` — a complete transcription subsystem (OpenAI, Google, Deepgram, AssemblyAI, ElevenLabs) with streaming, word and segment timestamps, and diarization. It is *not* exposed over HTTP yet, so reusing it means adding one protocol group.
- `packages/protocol/src/groups/voice.ts` — `POST /api/experimental/voice/session`, which brokers a realtime voice session server-side and hands back a WebRTC SDP answer or a websocket token, so the provider key never reaches the client.

**And the shipped product direction is the reverse of dictation.** The **iOS app already treats voice as delegation**: it sends the spoken request to the coding agent as a prompt headed `Voice delegation.`, and this very app already renders those messages specially (`packages/app/src/session/timeline/voice-delegation.ts`). That is a second voice mode worth offering *after* v1 — the display half already exists, so it is mostly wiring. It is not the v1 mode, because it needs a provider key and v1 promises zero config.

**[I]** On a phone without a speech service, detection happens at runtime and the app falls back to the bundled model.

**For the watch, the bundled model is mandatory rather than a fallback.** Android's `SpeechRecognizer` only listens to the local microphone — it has no API for transcribing an audio buffer — so watch audio arriving over Bluetooth must be transcribed by a model we ship. See [ADR-005](04-decisions.md#adr-005). That promotes the bundled model from "nice to have" to a hard prerequisite for the watch (P2-3), not a P3 nicety.

---

## Watch companion

The watch mirrors decisions, not the whole app. Design rule: **anything that fits on one glance is on the watch; everything else is on the phone.**

### Screens

| Screen | Shows | Actions |
|---|---|---|
| **Status** | Current session, one-line activity ("editing `src/auth.ts`", "running `bun test`"), elapsed time | Scroll to detail, stop |
| **Decision** *(appears on demand)* | The pending request in full: command text, file diff summary, question | **Approve** / **Deny** / speak a reason |
| **Voice** | Hold-to-talk, partial transcript | Send |

### Interaction

- Haptic buzz when the agent needs a decision — the only thing that should ever wake you.
- Approve/deny is a deliberate two-step: the button requires a firm press, not a brush, because approving a shell command by accident is expensive.
- One-handed: everything reachable with a thumb; no small targets.

### Trust rules

An approval from the wrist carries the same weight as one from the phone. Therefore:

1. Every decision screen shows the *actual* command or diff, never a summary the agent wrote.
2. Denying is always available and is the safe default on timeout.
3. The watch never has credentials of its own and never reaches the engine directly. It talks only to the phone app, over the OS-level Bluetooth bond, and is revoked by unpairing.

---

## v1 scope boundary

**In:**

- One Android phone, one watch, one user.
- Engine, GUI, voice, watch companion.
- Sideloaded APK.
- Workspace anywhere the app can read.

**Out (deliberately):**

| Not doing | Why |
|---|---|
| Play Store release | Adds targetSdk 36, 16 KB alignment, FGS declarations, large-app review. Revisit once it works |
| Multi-user / multi-device | No accounts, no sync, no sharing |
| Live audio streaming from watch | Fragile, and push-to-talk is enough for the actual use case |
| Native Kotlin GUI for the phone | The web GUI is free, correct, and identical to desktop. Only revisit if it feels bad on glass (see [04-decisions.md](04-decisions.md#adr-002)) |
| Local LLM on device | Different product with different constraints. See [05-risks.md](05-risks.md#open-questions) |
| iOS | No |
| x86 emulator support | Test on the emulator if convenient, ship arm64 only |
| TUI | Explicitly excluded by requirement |

---

## Definition of done — v1

The release is done when all of these are true on the real S20 FE + Watch 4 pair:

- [ ] Fresh install → first message answered, under 60 seconds, zero configuration
- [ ] Agent edits a file in a real project and the diff renders correctly
- [ ] A permission prompt appears in the GUI and in the watch, and either one can resolve it
- [ ] Voice dictation produces usable text for a 10-second request, three times in a row
- [ ] App survives 30 minutes in the background with the screen off and the session still works
- [ ] Watch shows live status within 2 seconds of the agent changing activity
- [ ] Phone on mobile data with Wi-Fi off → watch still shows status and can approve
- [ ] Bluetooth off → watch says so and recovers on its own, phone unaffected
- [ ] Uninstall leaves no files behind
- [ ] Total install under 350 MB
