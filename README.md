# opencode for Android

A **self-contained Android app that runs the full opencode agent on a phone** — engine, desktop-grade GUI, and voice input — plus a **Wear OS companion** for glance-and-approve control without touching the phone.

No Termux. No external server. No root. One APK.

**Status:** design complete; toolchain and build pipeline working, app code not started.
**As of:** 2026-10-21

```bash
bash tools/build.sh     # fetches, verifies, tests, assembles → 121 MB debug APK
```

---

## The 30-second version

opencode's desktop app is a thin shell around two things: a background engine that exposes an HTTP API, and a web UI that talks to it. The UI is embedded inside the engine binary itself, served over localhost.

So there is no fancy porting work. You ship:

- a Linux userspace (proot + Alpine) so the engine has a real filesystem and real DNS,
- the stock opencode binary, unmodified,
- a WebView pointed at `127.0.0.1` showing the **same GUI the desktop app shows**,
- a native Kotlin watch app that talks to the same HTTP API.

The user opens the app, picks a model, and works. They never see a terminal.

---

## What v1 delivers

| Capability | Detail |
|---|---|
| **Full agent** | Same opencode core as desktop: tools, permissions, sessions, file editing, git |
| **Real GUI** | The web GUI this project already ships, in a WebView. Not a TUI, not a remote control |
| **Zero-config models** | Free models work signed out — no account, no API key ([verified in source](docs/01-product.md#zero-config-models)) |
| **The desktop app's actual UI** | The phone renders `@opencode/app` — the same UI package the Electron app wraps — not a lookalike ([verified](docs/06-artifact-verification.md#9-the-gui-requirement-verified)) |
| **Voice input** | Mic in the composer; phone's built-in STT, bundled offline model as fallback |
| **Watch companion** | Live status, approve/deny remote, stop, and push-to-talk from the wrist — **over Bluetooth, so it works on mobile data** with watch Wi-Fi off |
| **Works offline from the phone's side** | No cloud dependency except the model call itself |

---

## Hardware targets

| Device | Role | OS | Constraint that matters |
|---|---|---|---|
| Galaxy S20 FE | Runs the engine + GUI | Android 13 (API 33) | 4 KB pages → the 16 KB Play requirement does **not** apply. Must ship arm64-v8a |
| Galaxy Watch 4 | Companion client | Wear OS 3.x | 1.5 GB RAM, 247 mAh → thin client, push-to-talk only, no continuous audio |

Development targets arm64-v8a Android 13+ and Wear OS 3+. Any modern Android phone should work; the Watch 4 shapes the companion app's limitations.

---

## Cost

| | Size |
|---|---|
| Download | ~130 MB, or ~170 MB with the bundled offline voice model |
| Installed | ~330 MB |
| Where it goes | 197.6 MiB opencode binary (deflated to ~96 MiB inside the APK) + ~16 MiB Alpine rootfs + ~12 MiB proot, WebView and Kotlin code + ~40 MiB optional offline STT model |
| Runtime RAM | **[I]** ~400–600 MB while idle (engine + WebView) — measured in P0-4 |

Big, and unavoidably so: 69.6 MB of the binary is Bun/JavaScriptCore native code that no build flag removes, and the remaining 132.8 MB is the app's own bundled JavaScript. The optional "lean build" is a **source rebuild with a narrowed entrypoint** — there is no Skia to strip, and surgery on the shipped binary is impossible ([measured](docs/06-artifact-verification.md#4-where-the-bytes-are-v)).

---

## Timeline

| Phase | What | Estimate |
|---|---|---|
| **P0** Spikes | Prove the binary runs under proot on the device | 1–2 weeks |
| **P1** MVP | Install → open → chat with a model | 3–5 weeks |
| **P2** Usable | Files, git, permissions, voice, background survival | 3–4 weeks |
| **P3** Watch | Status + approve + mic | 2–3 weeks |
| **P4** Lean | Strip dead weight, optional native UI work | open-ended |

**~10–14 weeks** to the full v1 including the watch. P0 is the only real gate.

---

## Documents

Read in this order.

| # | Document | Answers |
|---|---|---|
| 1 | [docs/01-product.md](docs/01-product.md) | What are we building, for whom, and where does v1 stop? |
| 2 | [docs/02-architecture.md](docs/02-architecture.md) | How do the pieces fit? Startup, data flow, watch protocol |
| 3 | [docs/03-platform.md](docs/03-platform.md) | What does Android block, and what is the fix for each? |
| 4 | [docs/04-decisions.md](docs/04-decisions.md) | Why this way and not the obvious alternatives? |
| 5 | [docs/05-risks.md](docs/05-risks.md) | What can kill this, and what is the abort trigger? |
| 6 | [docs/06-artifact-verification.md](docs/06-artifact-verification.md) | What the shipped binary actually *is* — measured, not assumed |
| 6 | [docs/07-engine-api.md](docs/07-engine-api.md) | What the engine's HTTP surface actually is — auth, sessions, the event stream, and the permission reply |
| 7 | [docs/08-rootfs.md](docs/08-rootfs.md) | How the Alpine image is built, and the three silent traps in doing so |
| 8 | [docs/09-build.md](docs/09-build.md) | How to build it — one script, local and CI, with what is fetched and hash-checked |
| — | [TODO.md](TODO.md) | The actual work, phased, with acceptance criteria |
| — | [opencode-research.md](opencode-research.md) | Background: how opencode v2.1.1 works internally |
| — | [opencode-android-plan.md](opencode-android-plan.md) | Superseded by this doc set — kept for the original reasoning |

---

## Verified vs assumed

Design docs are worthless if nobody can tell which claims were checked. Every non-obvious statement carries a `path:line` or a URL. Claims are labelled:

- **[V]** verified in the opencode source or official Android documentation
- **[I]** inferred or from general knowledge — needs confirmation
- **[?]** open question, listed in [05-risks.md](docs/05-risks.md#open-questions)

The claims most likely to sink the project are the ones marked `[I]`, and P0 exists to convert them to `[V]` or `[X]`.

---

## Repository layout

```
opencode portable/
├─ README.md              ← you are here
├─ TODO.md                ← the work
├─ docs/                  ← design documents
├─ opencode-src/          ← shallow clone of opencode v2.1.1, read-only reference
├─ findings/              ← raw research notes from the survey phase
└─ _recon/                ← raw command output used as evidence
```

`opencode-src/` is never modified. The Android app is a separate project that consumes the published opencode binaries.
