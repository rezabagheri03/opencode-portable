# 05 — Risks

What can kill this, what happens if it does, and what is still unknown.

The register is ordered by how much it would hurt, not by likelihood. Each risk has an explicit **abort trigger** — the observation that means stop and rethink rather than push on.

---

## Risk register

| ID | Risk | Likely | Impact | Mitigation | Abort trigger |
|---|---|---|---|---|---|
| **R-01** | The engine binary will not run under proot (missing shared libs, seccomp, SELinux) | Low | **Fatal** | P0 spike on real hardware with `strace` inside the rootfs | Binary fails to start or dies on first network call, with no fix in one week |
| **R-02** | The engine is killed constantly in the background, making the app useless | Medium | **Fatal** | `specialUse` FGS, wake lock, process-death restart, battery-optimisation exemption | Cannot keep a session alive 30 min with the screen off, after all mitigations |
| **R-03** | DNS or TLS fails inside the rootfs (missing `ca-certificates`, resolv.conf not injected) | Medium | High | Bake `ca-certificates` into the rootfs; write `/etc/resolv.conf` on every start | Model calls fail with certificate errors that a rootfs rebuild does not fix |
| **R-04** | WebView GUI is unusable on a phone screen — layout, keyboard, diffs | Low–Medium | High | Mobile CSS pass in P1, tested on glass before P2 starts. Lowered: the web build is already a first-class browser target — keyboard insets via `visualViewport` (`packages/app/src/runtime/platform/keyboard.ts`), `safe-area-inset-bottom` handling, standalone detection and route restore (`pwa.ts`) | The GUI cannot be made usable with CSS-only patches, which forces a native client and a plan rewrite |
| **R-05** | Bluetooth throughput is too low to move watch audio in a usable time | Medium | **High** | P3-0 measures it before any audio code is written; µ-law 8 kHz (80 KB/clip) already assumes a poor link; Opus as a further step | A 10-second clip takes more than ~5 s to transfer at µ-law, with no cheaper encoding left |
| **R-06** | Memory pressure: engine + WebView + model streaming exceeds available RAM | Medium | Medium | Measure in P1; reduce WebView weight; avoid keeping large diffs rendered | App is killed by the LMK during ordinary use even when foregrounded |
| **R-07** | The free model tier changes or disappears upstream | Medium | Medium | Providers are already implemented upstream; a key is the whole migration path | Free models removed *and* no zero-cost alternative exists, breaking US-1 |
| **R-08** | ~~16 KB page alignment is not achievable for the Bun binary~~ **Resolved by measurement** — the shipped 2.1.1 binary declares `p_align = 0x10000` on all three `PT_LOAD` segments | — | — | Only `zipalign -c -P 16` on the finished APK is left | — |
| **R-09** | Upstream opencode changes break the patches | Medium | Low | Keep patches ≤50 lines each, upstreamable, applied as a documented diff | A required change would need to modify the core engine |
| **R-10** | Watch battery or thermal throttling makes the companion feel bad | Medium | Low | Push-to-talk bursts only, no continuous capture, status throttled to 1 Hz | The watch cannot complete a 30 s session without a warning |
| **R-11** | APK size makes install or update painful | High | Low | Expected, accepted. Optional lean build in P4 | Install fails or the app cannot update at all |
| **R-12** | GPL obligations triggered by sharing the APK | Low *(personal use)* | Medium | See [Licensing](#licensing) | The APK is distributed without the corresponding source offer |
| **R-13** | Play services absent or outdated on the phone, so the Data Layer cannot connect | Very Low | **High** | Detect at startup; show a clear message. Both target devices ship with it | `CapabilityClient` finds no node on a device that has Play services |
| **R-14** | Bluetooth drops mid-session and the watch shows stale state | Medium | Medium | Decisions persist in a `DataItem`; status re-syncs on reconnect; watch shows an explicit "disconnected" state rather than a stale one | Reconnection takes longer than ~10 s in normal use, making the watch feel dead |
| **R-15** | Watch audio transcribes badly at 8 kHz narrowband | Medium | Medium | Measure with real speech in P3-6; escalate to 16 kHz µ-law or Opus — a contained change | Transcripts are unusable at every encoding that fits the measured throughput |
| **R-16** | `rg` resolves to a downloaded **glibc** build that cannot run in the musl rootfs, then gets cached and fails on every search with a "file exists but is not found" error | Medium | **High** | `apk add ripgrep` so the `PATH` lookup at `packages/core/src/ripgrep/binary.ts:90` always wins; pass a full `PATH` when spawning proot; assert `rg --version` from inside the spawn environment at startup | `which rg` still finds nothing inside the rootfs after all three mitigations |

---

## The one gate that mattered

The original plan treated **16 KB page alignment as the project kill criterion**. That is now wrong, and the correction matters:

- The target phone has **4 KB pages**.
- 16 KB alignment is a **Play Store submission rule**, enforced from 2027-02-01.
- It does not block installing or running a sideloaded APK on the S20 FE.

So R-08 is measured in P0 for information — it decides whether publishing is ever possible, not whether the product works. **R-01 and R-02 are the real gates.** Everything else is ordinary engineering.

A second correction, from the Bluetooth decision: the risk that the watch simply could not reach the phone (router client isolation, no shared network, phone on cellular) is **gone**. It was R-05 and it was rated the most likely watch-phase failure. Using the platform's own Bluetooth transport removed it rather than mitigating it. The remaining watch risk is throughput, not reachability.

---

## Assumptions worth stating

These are believed but not yet proven. Each one has a task in [TODO.md](../TODO.md) that converts it into evidence or refutes it.

| # | Assumption | If wrong |
|---|---|---|
| A-1 | The stock `bun-linux-arm64-musl` binary runs inside an Alpine proot without modification | R-01 fires; a rebuild or a different run strategy is needed |
| A-2 | proot's `ptrace` use is permitted for an app tracing its own children on this device | Root would be required — the project becomes a different, much smaller product |
| A-3 | The free model tier works without an account from a fresh install | The zero-config first run fails; onboarding returns |
| A-4 | ~~The Kotlin bridge can derive watch-worthy state from the engine's existing SSE stream~~ **Confirmed from source** 2026-10-21 — `session.execution.*`, `session.tool.called`, `permission.asked` carry everything the watch screens need. See [07-engine-api.md](07-engine-api.md#4-event-stream) | Not needed; no upstream patch to the engine is required for the watch |
| A-5 | `ChannelClient` throughput between this phone and this watch is enough for a compressed clip | R-05 fires; audio must be reduced further or the watch becomes text-only |
| A-6 | ~~A 270 MB debug APK installs via `adb install` without timeout or storage issues~~ **Dissolved** — the APK is ~125 MiB, because the binary is stored deflated inside the APK and the installer extracts it | Not needed; no AAB and no split-install path |
| A-7 | The watch has no usable on-device speech recognizer, so audio must be shipped to the phone | The entire audio pipeline disappears — which would be a *good* surprise |

---

## Open questions

Not yet answerable from documentation. Each becomes a P0 or P3 task.

1. ~~What is the ELF `p_align` of the Bun-compiled binary?~~ **Answered** — `0x10000` (64 KB) on all three `PT_LOAD` segments. 16 KB-page compatible as shipped; no rebuild needed. [06 §2](06-artifact-verification.md#2-is-it-16-kb-page-compatible-v)
2. ~~Which shared libraries does the binary dynamically link against?~~ **Answered** — it is *not* self-contained: `PT_INTERP = /lib/ld-musl-aarch64.so.1`, `DT_NEEDED = libc.musl-aarch64.so.1, libstdc++.so.6`. The rootfs must therefore provide a `libstdc++` package, and the binary cannot run outside a musl tree at all. [06 §3](06-artifact-verification.md#3-is-it-self-contained-no-v)
3. ~~What are the exact HTTP/API endpoints the web GUI uses for session state, event streaming, and permission responses?~~ **Answered** — read from source rather than observed: the GUI uses the generated typed client (`packages/client/src/promise/generated/client.ts`), and the watch-relevant subset is in [07-engine-api.md](07-engine-api.md). The permission reply is `POST /api/session/:id/permission/:requestID/reply` with `once|always|reject`.
4. ~~Does the API support a non-browser client cleanly?~~ **Answered — yes.** `Authorization: Basic` works on every endpoint, and CORS allows any request with no `Origin` header (`packages/server/src/cors.ts:11-12`). Bearer tokens are *not* supported. [07 §1](07-engine-api.md#1-authentication)
5. **How much RAM does the engine actually use** while idle and during a task on this phone?
6. **Does the device's `SpeechRecognizer` exist and work offline** on the S20 FE, and where is its offline model configured?
7. **Does the Watch 4 have a usable third-party speech recognizer?** If yes, the whole audio pipeline is unnecessary — the highest-value question in the project.
8. **What is the real `ChannelClient` throughput** on this pair, and how does it behave with the phone on cellular versus Wi-Fi?
9. **Does Android MediaCodec expose an Opus encoder** on these devices, or must `libopus` be bundled?
10. **Can the TUI renderer (`@opentui/*`) be excluded** from a Bun compile without breaking the build? Determines whether the P4 lean build is real.
11. **Does the watch stay connected when the phone screen is off and the app is backgrounded?** The most likely P3 surprise.

---

## Licensing

Short version: **personal use is fine; sharing needs care.**

| Component | License | Obligation while you keep the APK to yourself | Obligation if you share it |
|---|---|---|---|
| opencode | MIT | none beyond the notice | include the MIT notice |
| **proot** | **GPL-2.0** | none | provide corresponding source for the proot binary, or a written offer |
| **git** *(if bundled in the rootfs)* | **GPL-2.0** | none | same as above |
| Alpine packages | mixed (mostly MIT/BSD, some GPL) | none | per-package |
| Bun runtime *(inside the binary)* | MIT | none | include the notice |
| Play services client library | proprietary | none (normal app dependency) | normal app dependency terms |
| Android app code | yours | — | — |

Two practical notes:

1. **GPL-2.0 obligations attach to distribution of the binaries, not to use.** Building an APK for your own phone does not trigger them. Sending that APK to a friend does — and the clean way to satisfy it is to publish the exact corresponding source for proot and git alongside the APK, which for both is a public upstream tarball plus a note on which version and patches you used.
2. **Keep the license inventory current as the rootfs changes**, because "which packages are in the rootfs" is easy to lose track of. A build step that emits `apk info` into a `THIRD_PARTY_LICENSES.md` is enough.

If this ever becomes a paid or closed-source product, get a lawyer rather than reasoning from a table in a project doc.

---

## What would make me stop recommending this

Written down so it is falsifiable later:

- **R-01 fires and cannot be fixed in a week.** Then the engine cannot run, and the honest response is to reconsider the product, not to grind.
- **R-02 fires.** An agent that dies whenever you put the phone down is not the product described in [01-product.md](01-product.md). Everything downstream depends on background survival.
- **R-04 fires.** If the WebView GUI cannot be made pleasant, the whole "free UI" premise collapses into a native-client project — a different plan with a different timeline, which should be re-planned rather than patched.
- **R-05 fires after the measurement and after Opus.** If a compressed 10-second clip still takes more than a few seconds to cross Bluetooth, the watch cannot carry voice, and the honest product is a status-and-approval companion with voice only on the phone. That is a worse product but still a real one — and it must be said out loud rather than discovered at the end.
