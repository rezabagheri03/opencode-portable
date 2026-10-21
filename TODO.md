# TODO — opencode for Android

The work, in order. Every task has an acceptance criterion, because "done" that cannot be checked is not done.

**Estimates** assume one person at roughly 15–20 h/week. "1 day" = one focused 6-hour session. They are planning aids, not commitments.

---

## How to use this

1. Work top to bottom. Later phases depend on earlier ones existing.
2. A task is done when **every** "done when" line is true on the real S20 FE / Watch 4 — not the emulator.
3. When a task converts an assumption into evidence, **update the `[V]`/`[I]` label** in the design docs in the same commit. Stale labels are worse than none.
4. If a **kill criterion** trips, stop the phase and re-read [05-risks.md](docs/05-risks.md#what-would-make-me-stop-recommending-this) before writing more code.

**Legend:** `[V]` verified · `[I]` inferred · `[?]` open question · `→` depends on

---

## Do this next

The first four actions, in order:

1. **P0-1** — proot + Alpine shell on the device
2. **P0-2** — stock opencode binary answers HTTP inside it
3. **P0-3** — DNS and TLS work inside the rootfs
4. **P0-7** — one message round-trips to the watch over Bluetooth

Everything else waits.

---

## Milestones

| Gate | Passes when | Unlocks |
|---|---|---|
| **G0** | P0-2, P0-3, P0-6 all pass | Committing to the architecture |
| **G1** | P1-10 — chat works from the app, 60 s, zero config | The project is real |
| **G2** | P2-9 — voice, permissions, background survival | Daily-usable |
| **G3** | P3-9 — watch status + approve + talk | The product |
| **G4** | P4-1 — lean build lands | Shippable to others |

---

# P0 — Spikes

**1–2 weeks. Throwaway code is correct here.** The output is knowledge, not an app.

### P0-1 · proot + Alpine shell on the device
- **Do:** Get a proot binary and an arm64 Alpine rootfs onto the phone (`/data/local/tmp` via `adb push` is fine — this is a spike, not the shipping path). Run `proot -r ./rootfs /bin/sh`.
- **Done when:**
  - [ ] A shell prompt appears inside Alpine
  - [ ] `cat /etc/os-release` reports Alpine
  - [ ] `apk --version` runs
  - [ ] `libstdc++.so.6` resolves inside the rootfs (`apk add libstdc++`) — the engine cannot start without it
  - [ ] No root, no Termux, no unlocked bootloader involved
- **Kill if:** `ptrace` is denied. Then the whole architecture is wrong — see R-01/A-2.

### P0-2 · Stock opencode binary runs inside it
- **Do:** Download `@opencode/cli-linux-arm64-musl` (207,162,576 bytes / 197.6 MiB), verify its SHA-256 is `e07ad6dd…cf7d7` ([06 §1](docs/06-artifact-verification.md#1-artifact-identity)), place it inside the rootfs, `chmod 0755`, run it.
- **Done when:**
  - [ ] `./opencode --version` prints a version
  - [ ] `./opencode serve` starts and `curl 127.0.0.1:PORT` returns 200 from a second shell
  - [ ] The binary's `ldd` output is recorded — **it will not be static**: expect
        `libc.musl-aarch64.so.1` and `libstdc++.so.6` ([06 §3](docs/06-artifact-verification.md#3-is-it-self-contained-no-v))
  - [ ] `rg --version` succeeds inside proot **using the same environment the app will spawn**, not from an `adb shell` (R-16)
- **Kill if:** It needs shared libraries that cannot be added to the rootfs, or dies on startup with no diagnosable cause after a week.

### P0-3 · DNS and TLS inside the rootfs
- **Done when:**
  - [ ] `wget -qO- https://example.com` succeeds inside proot
  - [ ] A real model call completes — free model, no account
  - [ ] `/etc/resolv.conf` is present and correct, and a note exists on how it gets written from the app
- **Kill if:** Resolution works for some hosts and not others in a way that tracks Bionic/netd rather than the rootfs.

### P0-4 · Confirm the measured headers on-device *(the hard part is already answered)*
- **Known from the shipped artifact** ([06](docs/06-artifact-verification.md)): `p_align = 0x10000`
  on every `PT_LOAD` (16 KB-ready), `ET_EXEC` non-PIE, `PT_INTERP = /lib/ld-musl-aarch64.so.1`,
  `DT_NEEDED = libc.musl-aarch64.so.1, libstdc++.so.6`.
- **Do:** confirm those numbers on the exact file you push, then run `zipalign -c -P 16 -v 4 app.apk`
  once an APK exists.
- **Done when:**
  - [ ] The pushed binary's SHA-256 matches the value pinned in [06 §1](docs/06-artifact-verification.md#1-artifact-identity)
  - [ ] The APK passes `zipalign -c -P 16`
  - [ ] Peak RSS while idle and during a task recorded — **the only real question left in this task**
- **Note:** the alignment question is closed. RSS is not, and it feeds R-06.

### P0-5 · Chat from the phone's browser
- **Do:** Load `http://127.0.0.1:PORT` in Chrome on the phone, send a message, get an answer. No app yet.
- **Done when:**
  - [ ] The GUI renders
  - [ ] A message round-trips through a free model
  - [ ] A tool call executes (ask it to `ls` or read a file)
  - [ ] Screenshots captured for the mobile CSS task
- **This is the moment to judge the GUI on glass** — it determines whether R-04 is a real risk.

### P0-6 · Background survival smoke test
- **Done when:**
  - [ ] With the screen off for 15 minutes, the session is still alive and responsive
  - [ ] Behaviour recorded for both plugged-in and on-battery
  - [ ] The exact failure mode, if any, is written down (killed? suspended? network dropped?)
- **Kill if:** The engine cannot survive backgrounding by any means — this is R-02 and it is fatal.

### P0-7 · Prove the watch link
- **Do:** A throwaway app on both devices exchanging one `MessageClient` message. No real UI.
- **Done when:**
  - [ ] Play services present and current on both devices
  - [ ] `CapabilityClient` finds exactly one phone node from the watch
  - [ ] A message round-trips phone → watch and back
  - [ ] Round-trip latency recorded
  - [ ] The same test passes with **the phone on mobile data and Wi-Fi off**
- **Kill if:** No node is found on a device that has Play services — this is R-13, and it removes the watch from the product.

### P0-8 · Fold findings back into the docs
- **Done when:**
  - [ ] Every `[I]` touched by P0 is now `[V]` or corrected
  - [ ] Answered items are struck from [05-risks.md](docs/05-risks.md#open-questions)
  - [ ] Any architecture change written as a new ADR, not silently edited in

---

# P1 — MVP

**3–5 weeks.** Gate G1: one APK, install, open, chat.

| ID | Task | Done when | → | Est |
|---|---|---|---|---|
| P1-1 ✅ | Gradle project skeleton | `assembleDebug` produces an APK; `minSdk` 31, `arm64-v8a` only, `extractNativeLibs=true` | — | 1 d |
| P1-2 ◐ | Package binaries as native libs | `libopencode.so` and `libproot.so` in `jniLibs/`; both exec successfully from `nativeLibraryDir` on device | P1-1 | 1 d |
| P1-3 ✅ | Rootfs build pipeline | A Docker script emits `rootfs.tar.gz` reproducibly; `apk info` output is saved as `THIRD_PARTY_LICENSES.md` | P0-1 | 2 d |
| P1-4 ✅ | First-run extraction | Fresh install extracts the rootfs with visible progress; second launch skips it; a corrupted extraction is detected and redone | P1-3 | 1 d |
| P1-5 | FGS + engine launch | `specialUse` FGS spawns proot→`opencode serve` **on the fixed port 4096 with a generated `OPENCODE_PASSWORD`** ([ADR-012](docs/04-decisions.md#adr-012)); health poll reports ready; child stderr is visible in the UI on failure | P1-2, P1-4 | 3 d |
| P1-6 | WebView host | GUI loads on device at `http://127.0.0.1:4096/?auth_token=…`, so settings and drafts survive relaunch; dark background before load (no white flash); no cleartext error; Android back navigates the UI; the [three hooks](docs/03-platform.md#three-webview-hooks-a-browser-does-not-need) are wired (file chooser, permission grant, media gesture) | P1-5 | 3 d |
| P1-7 | Lifecycle | App backgrounded for 30 min → foreground → still working; engine death → auto-restart + WebView reload; explicit Quit stops the child cleanly | P1-5 | 2 d |
| P1-8 | Workspace | Default private workspace exists; "import a folder from storage" copies it in; the agent can read/write inside it | P1-7 | 2 d |
| P1-9 | Mobile CSS pass | The [six tells](docs/03-platform.md#webview-feel) are fixed in a patch; composer usable with the keyboard open; nothing under the notch | P1-6 | 3 d |
| P1-10 | **MVP acceptance** | Fresh install → first answer in **60 s** → ask it to edit a file → diff renders → no config, no dialogs at launch | all | 1 d |

---

# P2 — Usable

**3–4 weeks.** Gate G2: it is the thing you actually use.

| ID | Task | Done when | → | Est |
|---|---|---|---|---|
| P2-1 | Permissions in the GUI | A command needing approval prompts in the UI; approve runs it; deny reports cleanly; the prompt is legible on a phone | P1-10 | 1 d |
| P2-2 | Voice bridge + mic button | A composer mic button starts native STT; partial words stream in; release settles the text; **nothing auto-sends**. Only the capture half is new — upstream already renders voice-originated prompts and already exposes a server-brokered realtime voice route ([ADR-005](docs/04-decisions.md#adr-005)) | P1-9 | 3 d |
| P2-3 | Offline STT model | The bundled model transcribes a 10 s utterance in under 5 s, driven from a PCM buffer rather than the microphone. **Hard prerequisite for P3, not an optional fallback** — Android's recognizer cannot transcribe a buffer ([ADR-005](docs/04-decisions.md#adr-005)) | P2-2 | 4 d |
| P2-4 | Git works | The agent makes a commit inside the workspace; the diff view renders; the snapshot index does not corrupt (no `/sdcard`) | P1-8 | 2 d |
| P2-5 | Battery-optimisation flow | A single explained prompt offers the exemption; declining leaves the app functional but warns about backgrounding | P1-7 | 1 d |
| P2-6 | Session persistence | Kill the app, relaunch, previous session is intact and continues where it left off | P1-7 | 2 d |
| P2-7 | Decision notifications | A pending decision raises a local notification even when the app is backgrounded; tapping opens the prompt | P2-1 | 1 d |
| P2-8 | Stability pass | 1 hour of mixed use: no ANR, no OOM, no orphaned proot process after Quit; peak RSS recorded | P2-6 | 3 d |
| P2-9 | **Usable acceptance** | All of [v1's definition-of-done rows 1–6 and 8](docs/01-product.md#definition-of-done--v1) pass on device | all | 1 d |

---

# P3 — Watch companion

**2–4 weeks.** Gate G3: the product. Audio is the risky half, so it is measured before it is built.

| ID | Task | Done when | → | Est |
|---|---|---|---|---|
| **P3-0** | **Measure the transport** | On the real pair: (a) a 320 KB payload crosses `ChannelClient` and the wall-clock seconds are recorded; (b) `MessageClient` round-trip latency recorded; (c) **tested whether the watch has a usable third-party speech recognizer**. Results written into [05-risks.md](docs/05-risks.md#open-questions) as answers 7 and 8 | P0-7 | 1 d |
| P3-1 | Enumerate the engine API from traffic | Every request the web GUI makes is documented in `docs/06-api.md`: method, path, payload, and how permission responses and event streaming work. Questions 3 and 4 answered | P1-10 | 2 d |
| P3-2 | Wear module skeleton | A `wear` module builds a standalone Wear APK that installs on the Watch 4 over ADB and runs | P1-1 | 2 d |
| P3-3 | Node discovery | `CapabilityClient` finds exactly one phone node; ambiguity is resolved by a chooser; **no pairing code, no IP address, nothing to type** | P3-2 | 2 d |
| P3-4 | Status screen | Watch shows session activity within 2 s of the agent changing activity; status messages throttled to ≤1 Hz; scrolling shows recent history | P3-3 | 3 d |
| P3-5 | Decision screen + approve/deny | A pending command appears with its **full text**; Approve and Deny both work via `MessageClient`; whichever surface answers first dismisses the other; the pending decision survives a watch reconnect via `DataItem` | P3-4 | 3 d |
| P3-6 | Push-to-talk | Hold records; release sends a compressed clip over `ChannelClient`; the transcript lands in the phone's composer for review. Encoding chosen from P3-0's measurement | P3-0, P2-3 | 4 d |
| P3-7 | Haptics | A pending decision produces one haptic buzz and never repeats; completion is a distinct, lighter pattern | P3-5 | 1 d |
| P3-8 | Reconnect and degrade | Bluetooth lost or watch out of range: the watch says "disconnected" rather than showing stale state, reconnects automatically, and the phone is entirely unaffected. Works with the phone on cellular | P3-3 | 2 d |
| P3-9 | **Watch acceptance** | [User stories 7–12](docs/01-product.md#user-stories) pass with the phone in a pocket **and Wi-Fi off** | all | 1 d |

---

# P4 — Lean build and polish

**Open-ended.** Not required for the product to work.

| ID | Task | Done when | Est |
|---|---|---|---|
| P4-1 | Narrow the build entrypoint | A `bun build --compile` from source without the TUI/desktop entrypoints works and the GUI is unaffected; size delta measured against the 197.6 MiB baseline. **Ceiling is the 132.8 MB `.bun` section, floor is 69.6 MB of native Bun/JSC** — there is no Skia to strip ([06 §4](docs/06-artifact-verification.md#4-where-the-bytes-are-v)) | 5–8 d |
| P4-2 | Rootfs slimming | Rootfs under 10 MB compressed with no loss of function; `apk info` inventory still accurate | 1 d |
| P4-3 | *(optional)* Native chat screen | Only if R-04 fires: a Kotlin chat screen talking to the same API, WebView retained for settings and diffs | 3–4 wk |
| P4-4 | Release signing + update path | A repeatable signed build and a documented upgrade procedure that preserves sessions and workspace | 2 d |
| P4-5 | *(only if publishing)* Play readiness | targetSdk 36, 16 KB alignment, FGS declaration, data-safety form, GPL disclosure | 1–2 wk |

---

## Backlog — considered, not scheduled

| Idea | Why deferred |
|---|---|
| Live audio streaming from watch | [ADR-004](docs/04-decisions.md#adr-004) — push-to-talk first, always |
| Opus encoding for watch audio | [ADR-011](docs/04-decisions.md#adr-011) — only if µ-law quality proves insufficient |
| LAN/Wi-Fi transport as an accelerator | Would help throughput when both devices share a network, but adds a second transport to maintain for a feature that works without it |
| Cloud STT option | Contradicts zero-config; easy to add behind the existing bridge later |
| Wear tile / complication showing agent status | Nice glanceability, but the app already buzzes. Add after G3 |
| Multiple workspaces / recent projects | v1 has one workspace. Revisit when it annoys you |
| Phone widget with agent status | Same |
| Local LLM on device | Different product. Watch [05-risks.md](docs/05-risks.md#open-questions) |
| iOS | No |

---

## Progress

| Phase | Tasks | Done |
|---|---|---|
| P0 Spikes | 8 | 0 |
| P1 MVP | 10 | 3 (P1-1 ✅, P1-3 ✅, P1-4 ✅, P1-2 ◐ launcher written, needs device) |
| P2 Usable | 9 | 0 |
| P3 Watch | 10 | 0 |
| P4 Lean | 5 | 0 |
| **Total** | **42** | **0** |

Update this table when a phase gate passes, not per task.
