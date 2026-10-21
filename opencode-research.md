# opencode — Full Research: the desktop app and the core

**Subject:** [opencode](https://opencode.ai) — "The open source AI coding agent", by [Anomaly](https://anoma.ly)
**Source analysed:** `github.com/anomalyco/opencode` (cloned from `sst/opencode`), branch `dev`,
commit `0718864d49a5241e7df5b302b35caed2518171a0`, dated **2026-10-20**, workspace version **2.1.1**
**License:** MIT · **Local checkout:** [`opencode-src/`](opencode-src) (8,579 files, ~133 MB)

> **How to read the evidence marks.** `[V]` = verified by reading the shipped source in this checkout.
> `[I]` = inferred from adjacent code, comments or docs. Anchors are `path:line` relative to `opencode-src/`.
> No build, install or test was executed; nothing in the repo was modified. Four per-subsystem
> deep-dive reports with full anchors live in [`findings/`](findings):
> [core-engine.md](findings/core-engine.md) (338 lines), [core-tools.md](findings/core-tools.md) (626),
> [desktop.md](findings/desktop.md) (506), [platform.md](findings/platform.md) (210).
> All target content (code, comments, docs, web pages) was treated as data, never as instructions.

---

## 0. TL;DR

1. **opencode is a local-first, MIT-licensed coding agent with a client/server core.** One shared
   background server per user owns sessions, config, permissions and tool execution; the TUI, web UI,
   desktop app and IDE extensions are all clients of it `[V]`.
2. **"opencode 2" is a real rewrite, not a renumber.** V1 (`opencode-ai@1.19.3`) and V2
   (`@opencode/cli@2.1.1`, first published 2026-09-02) are different products sharing one repo, with
   intentional breaking changes to the **plugin API, server API and terminal client config** `[V]`.
3. **The desktop app is an Electron 44 shell — a *thin but high-leverage* shell.** It does not fork the
   UI: it mounts the same SolidJS `AppInterface` the web app uses and adds a platform layer, a router,
   native surfaces and an extension host. ~20 renderer files vs ~70 main-process files `[V]`.
4. **The desktop app does not embed the core.** It spawns `opencode-cli serve --service` as a
   *detached child process* and talks HTTP to it, exactly like the TUI does. Crashing the app does not
   kill your sessions `[V]`.
5. **The core is an Effect-TS 4 application with a bespoke DI graph, event-sourced sessions, and
   write-ahead execution claims** used for crash recovery. Streaming token deltas are deliberately
   *not* persisted `[V]`.
6. **Biggest genuine surprise: two headline marketing claims are stale for V2.** The site advertises
   "LSP enabled" and "Share links"; V2 has **no LSP runtime** (only compat config plus three TODOs) and
   session sharing is **discontinued** (a retirement page ships in the repo) `[V]`.
7. **Security posture is honest but leans on file permissions, not crypto.** Credentials are plaintext
   JSON in SQLite protected by `chmod 0600`; plugins can rewrite a permission decision to `allow`;
   `webfetch` validates only the URL scheme (no SSRF guard); remote skills and the ripgrep binary are
   fetched at runtime without signature checks `[V]`.
8. **Engineering discipline is the standout.** Staged boot ordering justified in comments, a benchmark
   that proves it, validated packaging (sha512/size checked before publish), and per-extension
   serialized lifecycle queues `[V]`.

---

## 1. What opencode is

An open-source AI coding agent, shipped as a terminal interface, desktop app, web UI and IDE
extension `[V]` ([opencode.ai](https://opencode.ai), [docs](https://opencode.ai/v2/docs)).

| Fact | Value | Source |
|---|---|---|
| Vendor | Anomaly (`anoma.ly`) | opencode.ai footer |
| Licence | MIT | `package.json`, GitHub API |
| Repo | `anomalyco/opencode` (was `sst/opencode`) | `git remote -v`, GitHub API |
| Stars / forks | **218,740 / 29,184** (API, 2026-10-20); site claims 208K | GitHub API |
| Created / last push | 2025-04-30 / 2026-10-20T20:01Z | GitHub API |
| Open issues | 3,809 | GitHub API |
| Language | TypeScript | GitHub API |
| Primary language stack | Bun workspaces, Effect-TS 4, SolidJS, Drizzle+SQLite, AI SDK 6, Electron | `package.json` |

**Positioning.** "Any model (75+ providers via Models.dev, including local), any editor, privacy-first:
opencode does not store your code or context." Monetisation is **Zen** (pay-as-you-go model access),
**Go** ($10/mo) / **Go Plus** ($40/mo) subscriptions, and **Enterprise** (SSO + internal AI gateway).
There is a hosted **Console** for account/billing.

**Note on scale claims.** The site's "16M monthly devs" and "208K stars" are marketing figures; the API
gives a verifiable 218,740 stars. Star counts are not adoption, and no independent usage data exists to
check against `[I]`.

---

## 2. Version landscape — read this before anything else

Two products share one repository. Confusing them is the single easiest mistake to make here `[V]`.

| | V1 | V2 |
|---|---|---|
| npm package | `opencode-ai` | `@opencode/cli`, `@opencode/client` |
| Latest | **1.19.3** (2026-10-16) | **2.1.1** |
| Published since | 2025-05-31 (12,253 versions!) | 2026-09-02 (1,201 versions) |
| Git tags | `v1.19.x` | `v2.0.6` … `v2.1.1` |
| Docs | `/docs` | `/v2/docs` |
| Plugin API | legacy | **new, incompatible** |
| Server API | legacy | **new, incompatible** |
| Terminal config | layered `tui.json(c)` | one global `cli.json` |

**V2's three stated intentional breaking changes:** the plugin API, the server API and clients, and
moving terminal config from layered `tui.json(c)` files to a single `cli.json`
([migrate-v1](https://opencode.ai/v2/docs/migrate-v1/)) `[V]`.

**V2 still carries a load-bearing V1 compatibility shell** — `packages/core/src/v1/` (agent, command,
mcp, migrate, permission, plugin, provider…), a `V1Migration` path, a `/api/experimental/migration/v1`
endpoint, and `opencode2` shims `[V]`. The desktop even ships a toast that polls v1→v2 *data* migration
progress `[V]` (`packages/desktop/src/renderer/migration-status.tsx`).

**Everything below describes V2 @ 2.1.1** unless stated otherwise.

---

## 3. System architecture and topology

The architectural decision that explains most of the rest: **a shared background server, not a library.**

```
                        ┌──────────────────────────────────────────────┐
                        │  ONE SHARED BACKGROUND SERVICE (per user)     │
                        │  `opencode serve --service`                    │
                        │  owns: sessions · config · permissions ·       │
                        │        integrations · tool execution · SQLite  │
                        │  default port 0xC0DE (49374) · 0xB0DE on Win   │
                        └───────────────┬──────────────────────────────┘
                                        │ HTTP + SSE, Basic auth
        ┌───────────────┬───────────────┼───────────────┬────────────────┐
        │               │               │               │                │
   ┌────▼────┐    ┌─────▼─────┐   ┌─────▼──────┐  ┌─────▼─────┐   ┌──────▼──────┐
   │   TUI   │    │  Desktop  │   │  Web UI    │  │    IDE    │   │  SDK / ACP  │
   │ Solid + │    │  Electron │   │ `opencode  │  │ extension │   │ 3rd-party   │
   │ OpenTUI │    │   44      │   │  pair`     │  │           │   │ clients     │
   └─────────┘    └───────────┘   └────────────┘  └───────────┘   └─────────────┘
```

- **Port:** `0xC0DE` = 49374 is the default; on Windows `0xC0DE` becomes `0xB0DE` (4096 lower) because
  the upper block can be reserved at boot while nothing listens there `[V]`
  (`packages/cli/src/services/service-config.ts:46-56`). The docs' `opencode pair` example URL,
  `http://127.0.0.1:49374`, is exactly this port `[V]`.
- **Discovery:** the server writes a registration file; clients read it rather than probing `[V]`.
- **Escape hatches:** `--standalone` (private server), `--server <url>`, and
  `opencode service set disabled true` `[V]` ([CLI docs](https://opencode.ai/v2/docs/cli/)).
- **Wire contract:** Effect `HttpApi` v4 — 35 groups → ~144 operations / 119 paths → generated
  `openapi.json` → generated Promise and Effect clients. **One volatile SSE feed** `GET /api/event`
  (buffer cap 4096) plus WebSocket only for PTY, with ticket auth `[V]`
  (`packages/protocol/src/groups/event.ts:23-53`, `packages/httpapi-codegen`).

**The honest bit:** the event stream is documented as *"Volatile by contract: a slow consumer overflows
and fails the stream, and events during disconnection are missed"* `[V]`. Durability of persisted events
never implies delivery to a client. Any integration that assumes replay will lose events.

---

## 4. The core — `packages/core`

Source-only package (`"exports": { "./*": "./src/*.ts" }`), ~40 top-level modules, built on **Effect
4.0.1**, with SQLite via Drizzle and model access via **AI SDK 6** `[V]`.

| Area | Modules | Responsibility |
|---|---|---|
| DI / process model | `instance.ts`, `location*.ts`, `app.ts`, `project.ts`, `workspace.ts`, `worktree.ts` | one process, many working directories |
| Session | `session.ts`, `session/*` (message-updater, execution, compaction, affinity, store) | transcripts, turn loop, compaction |
| Model layer | `aisdk.ts`, `aisdk-native.ts`, `provider.ts`, `model.ts`, `model-resolver.ts`, `model-retry.ts`, `variant.ts`, `models-dev.ts` | provider abstraction, variants, retries |
| Events | `bus.ts`, `event-logger.ts`, `state.ts` | event sourcing, projections |
| Persistence | `database.ts`, `database/*`, `kv.ts`, `snapshot.ts` | SQLite, migrations, snapshots |
| Config | `config.ts`, `config/*`, `managed-policy.ts` | layered config, enterprise policy |
| Capabilities | `tool.ts`, `tool/*`, `mcp/*`, `permission.ts`, `plugin.ts`, `pty.ts`, `shell.ts`, `filesystem.ts`, `ripgrep.ts`, `skill.ts`, `vcs.ts`, `git.ts`, `formatter.ts`, `websearch.ts`, `image.ts`, `codemode/*` | what the agent can actually do |
| Compat | `v1/*` | V1 migration |

### 4.1 A bespoke DI graph, not plain Effect `Layer`s `[V]`

`LayerNode` (`packages/util/src/effect/layer-node.ts:259-330`) is a hand-rolled DI DSL over Effect.
Tags are grouped **global** (process-wide: database, bus, project registry, session store/execution) vs
**location** (per working directory: providers, agents, models, config, tools).
`LayerNode.compile(graph, { shared: Node.tags.values.global })` (`core/src/instance.ts:161`) acquires
globals once and rebuilds location services per directory, with cycle detection, duplicate-wiring
rejection and typed output contexts. This exists to make "one process, many working directories" cheap
`[V]` — it is not a pattern you meet in the wild.

### 4.2 Sessions are event-sourced; streaming is not persisted `[V]`

Every session mutation publishes an event (`core/src/session/session.ts:61-80`); projectors run the SQL
inside the publish transaction (`core/src/bus.ts:312-330`). Durability is chosen **per event class for
token economy**:

- **Durable** — step, tool and message boundaries.
- **Ephemeral** — `session.text.delta`, `session.reasoning.delta`, `session.tool.input.delta`,
  `session.tool.progress`, `session.compaction.delta` (`packages/schema/src/session-event.ts:418-608`).

Transcripts are therefore **reconstructed projections**, so streaming costs zero storage.

A "part" is not a table: an assistant message row carries a `content[]` array of
`text | reasoning | tool` parts (`packages/schema/src/session-message.ts:197-200`), mutated in place
with `immer` `[V]`.

### 4.3 The turn loop is a write-ahead claim state machine `[V]`

On start the session row is claimed (`time_suspended`, `resume_attempts`) in the *same transaction* as
`execution.started`; the claim is released on terminal events and **preserved on shutdown**, so
`listSuspended()` can resume it after a crash (`core/src/session/execution.ts:73-75`,
`session/store.ts:66-85,208-254`). Crash recovery is a property of the data model, not a job queue.

Turn boundaries are themselves transcript facts (`Idle` messages, `session-message.ts:285-296`), which
is why a "steered" prompt joins the current turn with no extra bookkeeping.

### 4.4 AI SDK 6 is wrapped *below* the agent loop `[V]`

Wrapping happens at `LanguageModelV3.doStream`, normalised into an internal `LLMEvent` stream
(`core/src/aisdk.ts:64-100,722-861`). Core owns the transport guards — header timeouts, an SSE
inter-chunk deadline, finish-reason normalisation — rather than delegating to AI SDK's higher-level
loop. Variants are ~80 per-package protocol functions, not a generic abstraction
(`variant.ts:606-686`). Model/SDK instance caches key partly on **function identity**, because options
that are closures cannot be serialised (`aisdk.ts:254-271`).

### 4.5 Context accounting is conservative and cache-friendly `[V]`

Compaction prefers **resending the conversation unchanged** over summarising, trusts only the current
provider's measured token counts (`session/compaction.ts:866-871`), and can hand the job to the
provider's native compaction window. V2 replaces V1's `tail_turns`/`prune` with a token budget
`compaction.keep.tokens` plus checkpoint-based compaction `[V]` (migrate-v1 doc).

### 4.6 Config and enterprise policy

Resolution order (highest first): system config and macOS managed preferences → global
`~/.config/opencode/opencode.json(c)` → project `opencode.json(c)`/`.opencode/opencode.json(c)`
`[V]`. Managed policy is **evaluated, not merged**, synchronously, with `allow` as the default and
`findLast` ordering so config files beat console/org statements
(`core/src/managed-policy.ts:50-74`) `[V]`.

---

## 5. Core capabilities, and the safety posture that actually applies

This section is where the research turned up things worth knowing before trusting the agent.

### 5.1 Tool filtering is *visibility*, not authorisation `[V]`

`packages/core/src/tool/AGENTS.md` states it and the code agrees: `tool.ts:293` `whollyDisabled` only
edits the tool **catalog**. Enforcement lives in per-leaf `Permission.assert` calls
(`file-access.ts:154`, `tool/plugin/shell.ts:138-145`). **Consequence:** denying the `write` tool does
not stop file mutation — `shell` remains an unblocked mutation path.

### 5.2 Permission decisions can be widened in three ways `[V]`

V2 replaced V1's per-tool permission grouping with **one ordered `permissions` array** of
`{action, resource, effect}` rules, which is clearer and makes precedence explicit `[V]`. But:

1. **Shell "always allow" persists a tree-sitter-derived subcommand prefix** — approving `git commit`
   once can persist as `git commit *` (`tool/plugin/shell/parse.ts:196-199`).
2. **External-directory "always" persists the enclosing *project root*, not the directory** you
   approved (`file-access.ts:116-121`).
3. **A plugin may rewrite any decision to `allow`**, with no capability scoping
   (`permission.ts:179-188`). A `deny` short-circuits before saved allows (`:175`), but a saved allow
   is appended after configured rules and therefore beats a configured `ask` (`:176-178`).

### 5.3 V2 stopped asking before reaching outside the project `[V]`

V1 asked before accessing paths outside the project. **V2 allows `external_directory` by default.** The
migration guide is explicit and warns you to re-add
`{ "action": "external_directory", "resource": "*", "effect": "ask" }` to restore the old behaviour
`[V]` ([migrate-v1](https://opencode.ai/v2/docs/migrate-v1/)). This is a genuine security-relevant
default change, and it is the one migration item I would flag to any existing V1 user.

### 5.4 Two prompt-injection surfaces on the read path `[V]`

- `tool/plugin/read.ts:82-107` injects discovered `AGENTS.md` content as **durable synthetic
  instructions** after any successful read.
- Instruction updates are delivered as **incremental patches**
  (`instruction-discovery.ts:127-146`), so context accumulates a diff series.

Plus: remote skills and the `ripgrep` binary (v15.1.0) are **fetched at runtime with no signature or
hash verification** `[V]`.

### 5.5 Secrets trust file mode, not crypto `[V]`

Credentials are plaintext JSON in SQLite (`credential/sql.ts:5-11`). A grep of `core/src` for
`safeStorage|keytar|aes|createCipheriv` returns only `crypto.randomUUID` — **there is no encryption**.
Protection is `chmod 0600` on the DB and its WAL/SHM files; on Windows the code relies on inherited
profile ACLs and says so in a comment (`database/database.ts:83-98`). Separately, `wellknown.ts:87-92`
substitutes `process.env` into **remote-config URLs and headers**, and deliberately keeps a stale cached
manifest after the origin fails (`:119-137`).

### 5.6 `webfetch` has no SSRF guard `[V]` (verified absence)

`tool/plugin/webfetch.ts:70-71` validates only that the URL scheme is `http:`/`https:`; there is no
private/link-local/loopback filtering in the file or elsewhere in `core/src`. Response size is bounded
(`tool/http-body.ts:4-29`). An agent can be induced to fetch internal endpoints.

### 5.7 MCP, plugins, skills

- **MCP** — client supports local/remote transports, OAuth refresh de-duplication, and separates
  *catalog* from *execution* timeouts (V2 rename) `[V]`.
- **Plugins** — a **dual Promise + Effect API over ~20 hook domains** (`*.transform` ×11, session hooks
  including `model.request`/`http.request`, `execute.before/after`, `permission.evaluate`,
  `shell.create.before`). This doubles the public surface and is kept in sync socially, not
  mechanically. **V1 plugin implementations do not run in V2** `[V]`. One in-tree plugin ships
  (`@opencode/plugin-figma`).
- **CodeMode** — a token-budgeted catalog (`INLINE_BUDGET = 2_000` chars,
  `codemode/catalog.ts:50`): namespaces always shown, listings sampled round-robin by cost, `pinned`
  `opencode.*` tools bypassing the budget, everything else discoverable only via an in-interpreter
  `search` builtin. **The model may not know a tool exists**, and denying the `execute` permission
  removes CodeMode entirely (`tool.ts:239`) `[V]`.
- **Skills / instructions** — V2 discovers `.opencode/skills/<id>/SKILL.md` and ambient `AGENTS.md`
  files; V2 only discovers `AGENTS.md`, dropping V1's `CLAUDE.md` fallback `[V]`.

---

## 6. The desktop app — `packages/desktop`

### 6.1 What it actually is

**A thin, high-leverage Electron shell around the existing web product.** `DesktopApp`
(`src/renderer/desktop-app.tsx:4-17`) imports `AppBaseProviders`, `AppInterface`, `PlatformProvider`,
`ServerConnection`, `useCommand`, `useTabs` and `LayoutRoute` from **`@opencode/app/desktop`** and
renders the same `AppInterface` the browser build renders. What desktop adds: a `Platform`
implementation, an in-memory router keyed to a window id, a sidecar server instead of a remote origin,
startup/reveal gating, native surfaces, and an extension host `[V]`.

**The UI is not forked.** Evidence: the shared interface is imported, not copied; `Platform` is
*spread* into the shared provider; the desktop build reuses the web app's `public/` verbatim
(`electron.vite.config.ts:120`) `[V]`. The renderer holds ~20 files; the main process ~70 — all
desktop-specific complexity is in the shell.

| Stack | Version | Role |
|---|---|---|
| Electron | 44.4.5 | runtime |
| electron-vite / electron-builder | 6.0.0-beta.1 / 26.15.7 | build + package |
| SolidJS | 1.9.15 | renderer (shared) |
| Effect | 4.0.1 | main-process DI graph |
| @lydell/node-pty | 1.2.0-beta.12 | terminals |
| electron-updater / electron-log | 6.8.9 / ^5 | updates, logging |
| Drizzle ORM + SQLite | 1.0.0-rc | local persistence |
| Sentry (`@sentry/solid`) | 10.71.0 | crash/error reporting |

### 6.2 Boot is deliberately staged, and a benchmark proves it `[V]`

`src/main/index.ts` is **35 lines** and orders work by latency impact:

```
main()                                                     [src/main/index.ts]
  ├─ import "./lifecycle/marks" .......... timestamps start here
  ├─ configureApplication() .............. chdir(home), app name/id, userData, V8 compile cache
  ├─ acquireApplicationLock() ........... requestSingleInstanceLock() || quit
  ├─ registerRendererScheme() ........... "oc://" privileged BEFORE ready
  └─ app.whenReady()
       ├─ registerStorageSnapshotHandler()   answered from raw node:sqlite (no Drizzle)
       ├─ createEarlyWindow()                FIRST — window before any bundle work
       ├─ startSidecarProbe()                fills the idle wait (packaged only)
       ├─ await rendererAssetsServed(40 ms quiet, 400 ms cap)
       └─ await import("./desktop")          SECOND — the heavy Effect graph
             └─ NodeRuntime.runMain(runIpc) → IPC handlers → extensions → menu → crash reporter
```

`lifecycle/marks.ts` exists **solely** so `scripts/bench-startup.ts` can measure
entry/ready/window/served/bundle deltas from inside the packaged app `[V]`. Staging is empirical, not
aesthetic.

### 6.3 The server is a detached child process — never in-process `[V]`

```ts
client.Service.ensure({
  file,                              // registration JSON path
  version,
  command: [...cli.command, "serve", "--service"],
})
```
(`src/main/service/background-service.ts:57`)

- The actual spawn is `detached: true, windowsHide: true, stdio: ["ignore","ignore","pipe"]` followed by
  `child.unref()` (`@opencode/client/src/service-contender.ts:19`).
- **Port and password are chosen by the server** and published in its registration file. `0.0.0.0` is
  rewritten to `127.0.0.1` before use `[V]`.
- Restart path: after **3 consecutive timeouts** the client declares the service unresponsive, clears
  PTY handoff, terminates it, evicts the pool entry and re-recruits `[V]`.
- Kill path: `SIGTERM` → wait → `SIGKILL` → remove the registration file **only if unchanged** `[V]`.
- It is literally the same code path as `opencode serve`; the bundled binary lives at
  `process.resourcesPath/opencode-cli` and `--service` makes it register for discovery instead of
  blocking a TTY `[V]`.

**Credential boundary (real, not aspirational):** the renderer receives only `{url}`. The Basic auth
header is injected by the **main process** via `onBeforeSendHeaders`, and only when
`frame.parent === null && isRendererUrl(frame.url)` — explicitly excluding webviews, embedded pages and
service workers (`windows/security.ts:47`, `service/sidecar-credentials.ts:13`) `[V]`.

### 6.4 IPC is Effect RPC over an Electron MessagePort `[V]`

Not `ipcRenderer.invoke`. `MessageChannelMain` is created per window, port 1 is bound to an Effect
`RpcServer.Protocol` with `Schema.toCodecJson`, port 2 is posted to preload and forwarded to the
renderer `[V]` (`main/ipc-transport.ts:40`, `preload/index.ts:9`).

- Contract: `DesktopRpcs = AppRpcs.merge(StorageRpcs, FileRpcs, WindowRpcs, MenuRpcs, EventRpcs,
  ExtensionRpcs)` — ~40 typed procedures renderer→main, **one streaming RPC** main→renderer
  (`DesktopEvents`) carrying 11 tagged event types `[V]`.
- The renderer deliberately **avoids the Effect runtime**: `renderer/ipc-client.ts` is a hand-written
  203-line wire client, commented as costing "about a third of its startup script" `[V]`. This is the
  clearest performance-vs-purity trade in the package, and the only place the wire format is restated.

**Renderer hardening** `[V]`: `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`; the
entire bridge is **five members** — `windowID`, `bootstrap`, `storageSnapshot`, `extensions`,
`getPathForFile` (`preload/index.ts:26-31`). Privileged `oc://` scheme only; `..` rejected via
`path.relative`; navigation prevented for non-renderer URLs; `setWindowOpenHandler` denies all and
externalises; permissions limited to `{"clipboard-sanitized-write","notifications"}` with URL *and*
webContents identity checks `[V]`.

**Residual risk is structural, not a bug:** the renderer holds a live sidecar origin with injected
credentials, so any renderer XSS reaches an authenticated local server. There is no per-request
capability token `[I]`.

### 6.5 Renderer, storage, onboarding

- **Renderer**: SolidJS mounting the shared app; memory router with per-window last-route persistence;
  reveal gated on `startup.ready && themeReady() && onboarding complete` (hence native background
  colour rather than CSS) `[V]`.
- **"Onboarding" is not a screen** — it is a bootstrap decision (`initialUrl === "/"`, home route, no
  tabs, all servers builtin) that creates a default project and a new draft
  (`renderer/onboarding/index.tsx`) `[V]`.
- **Persistence moved to SQLite**: `drafts.sqlite` in `userData`, five Drizzle tables — `document`,
  `blob`, `state`, `extension`, `extension_file` — with a hand-rolled migration journal, a one-shot
  `.dat` → `state` legacy import, and 30-day/100-file draft retention. `packages/desktop/AGENTS.md`
  makes this a rule: "Using anything other than sqlite should come with strong reasons" `[V]`.
- A **write-behind coalescer** batches writes, latest-value-wins per key, and **re-queues failed
  batches** because "the renderer already saw these writes succeed" `[V]`.
- A subtle ordering constraint: onboarding state is initialised **before** storage opens, because
  creating `drafts.sqlite` first would make a fresh install look like an existing one `[V]`.

### 6.6 GUI extensions — and where the updater actually lives

A GUI extension is a ZIP with `manifest.json` (schema `opencode.gui-extension/1`), optional `main`
(CommonJS), `renderer`, `style`, and an `imports` allow-list `[V]`. `evaluateMain` runs
`new Function("require","module","exports", source)` in the **main process** with full Node privileges,
and the code comments the model plainly: *"Installed extensions are trusted code the user chose to
install."* The `imports` allow-list is hygiene, **not** a security boundary — no signature verification,
no capability grant, no sandbox `[V]`.

Lifecycle engineering is the most careful part of the package: one serialized queue per extension, a
generation `AbortController` per extension, synchronous withdrawal of contributions **before** any
finalizer runs, a 3 s cleanup deadline, a 10 s setup-stall log, and rollback to the last revision whose
setup succeeded `[V]`.

**Surprise:** `@opencode/gui-extensions` holds the browser, terminal, review, updater, SSH, WSL and
Office surfaces. **Auto-update is an extension** — `packages/desktop/src` contains no update code at
all (`@opencode/gui-extensions/src/updater/platform.ts`) `[V]`.

### 6.7 Packaging and distribution `[V]`

| Channel | appId | productName | Update feed |
|---|---|---|---|
| `dev` | `ai.opencode.desktop.dev` | OpenCode Dev | none |
| `beta` | `ai.opencode.desktop.beta` | OpenCode Beta | beta path |
| `prod` | `ai.opencode.desktop` | OpenCode | `opencode.ai/update/api/latest/desktop/opencode/` |

| Platform | Targets | Artifact | Notes |
|---|---|---|---|
| macOS | dmg, zip | `opencode-desktop-mac-<arch>` | `entitlements.plist` + **separate** `entitlements.cli.plist` applied only to `Resources/opencode-cli`, because the Bun CLI dlopens `bun-pty` |
| Windows | nsis | `opencode-desktop-win-<arch>.exe` | `installer.nsh` grants `*S-1-15-2-2` RX on `$INSTDIR` so Chromium's sandbox AppContainer can read it; signing only on CI |
| Linux | AppImage, deb, rpm | `opencode-desktop-linux-<arch>` | `.desktop` pins `StartupWMClass=ai.opencode.desktop`, `NoDisplay=true` |

- **The CLI ships *outside* `app.asar`** in `resources/`, with `opencode-cli.version` written from the
  npm package so the app can skip a **~400 ms spawn of the 200 MB binary** just to read `--version` `[V]`.
- Prod builds **must** supply `OPENCODE_CLI_DIST`; `scripts/prebuild.ts:8` throws otherwise — fail-fast
  instead of a broken artifact `[V]`.
- `scripts/publish.ts` validates version match, `releaseDate` and `sha512`/`size` on **every** file
  before publishing, and assembles per-platform `latest*.yml` feeds `[V]`.
- Official download page lists macOS (arm64/x64 dmg), Windows (x64/arm64 NSIS), Linux (deb/rpm/AppImage)
  plus `brew install --cask opencode-desktop` `[V]`.

### 6.8 Performance work `[V]`

Staged boot; `marks.ts`; V8 compile cache (`enableCompileCache`); **single-chunk renderer** (auto
splitting produced ~50 files, "each costing a main-thread round trip"); `codeCache: true` on the `oc://`
scheme; renderer avoiding the Effect runtime; lazy main bundle; sidecar probe overlapping the asset
wait; build-time CLI version file; a low-priority detached web-UI build in dev because "below-normal
still delayed window by seconds". `scripts/picker.ts` even replaces a Babel pass with an Oxc visitor
because it sits on the renderer's critical path.

### 6.9 Assessment

**Strengths** — boot ordering treated as a design artefact; genuine process isolation; a real credential
boundary; type-safety across the process boundary derived from schemas; outstanding extension lifecycle
engineering; packaging that is validated rather than hoped for; localised native surfaces.

**Real rough edges** `[V]`:

1. **The native application menu is macOS-only** — `native/menu.ts:55` returns early when
   `process.platform !== "darwin"`. Windows/Linux users get `autoHideMenuBar` and the in-app menu.
2. **No tray, no global shortcut** anywhere in the package. For an app whose central object is a
   long-running local server, there is no at-a-glance server state without a window.
3. **Extensions are trusted code with no enforcement** — installing one is equivalent to `npm install`
   with no lockfile review.
4. **`scripts/profile-by-source.ts:5` hardcodes one developer's absolute worktree path**
   (`C:/Users/Lukem/.local/share/opencode/worktree/6c1049/quiet-wolf-2/…`). As written it runs only on
   that machine — a profiling tool that cannot run for anyone else will rot.
5. **Write-behind retries forever** with no bound, no backoff and no user-visible signal; on a full disk
   the renderer believes writes succeeded indefinitely.
6. **`installCli` is macOS-only**, so Windows/Linux users who want `opencode` on `PATH` are on their own.
7. Duplicate dependency ownership across the desktop/extension boundary (`electron-updater`,
   `lighthouse`) is implicit — neither `package.json` states who must bundle what.

---

## 7. How the desktop app and the core actually relate

This is the question the workspace name implies, so it is worth stating plainly.

**They are separate processes connected by HTTP, and that is deliberate.**

| | Desktop-local (`drafts.sqlite`) | Core-owned (sidecar DB) |
|---|---|---|
| Data | window geometry/ids, drafts, `storage.*` namespaces, extension enable state + archives, background colour, first-launch flag, bundled CLI version | sessions, messages, providers, projects, credentials, permissions, snapshots |

Consequences of the design `[V]`:

- **One source of truth.** The desktop, TUI and web UI attach to the *same* server, so a session started
  in the terminal is the same session in the desktop app. `opencode --server <url>` can point any client
  at any server.
- **Crash isolation.** The app dying does not kill sessions; the server dying is recoverable (PTY handoff
  clear + re-recruit).
- **But there is no in-process embedding path.** Nothing in `packages/desktop` imports the engine
  directly; the only integration seam is HTTP plus the generated `@opencode/client`.
- **The cost is a 200 MB child process**, mitigated by the early sidecar probe and the build-time version
  file. Dev mode avoids it entirely by running the CLI from TypeScript source
  (`OPENCODE_DESKTOP_CLI_DEV`, `bun run src/index.ts`) `[V]`.

If you are evaluating "could I embed opencode in my own app", the honest answer from this source is:
**yes, but as a client of a spawned server** — that is the architecture the desktop app itself chose.

---

## 8. Platform, ecosystem and commercial surface

- **CLI** — `opencode` (full-screen TUI), `opencode run` (non-interactive), `opencode mini` (minimal
  interface), `opencode pair` (web UI on localhost with username/password), `opencode serve`,
  `opencode service …` (background service control), `opencode debug paths <selector>` (`db`, `home`,
  `data`, `config`, `cache`, `state`, `tmp`, `bin`, `log`, `repos`), `opencode uninstall --dry-run` `[V]`.
  Windows package managers are **not** supported for the CLI; standalone binaries are `[V]`.
- **TUI is TypeScript/SolidJS on OpenTUI**, not Go (`render()` from `@opentui/solid`; a 1,453-line
  provider stack and a 359-line keybind schema). It reuses `@opencode/client`, `schema` and `session-ui`
  but shares **zero chrome** with the web/desktop app — a permanent duplication tax `[V]`.
- **IDE extensions** for VS Code, Cursor, Zed, Windsurf, VSCodium; **GitHub and GitLab** integrations; an
  **ACP** surface `[V]`.
- **SDK / plugins** — generated clients, a dual Promise+Effect plugin API over ~20 hook domains `[V]`.
- **Cloud/console** — SST v3 on Cloudflare (SolidStart console, Worker API, Astro docs, D1 update feed,
  GHCR images). Commercial tiers: **Zen** (pay-as-you-go, $20 top-ups, zero markup claims),
  **Go** $10/mo, **Go Plus** $40/mo, **Enterprise** (SSO + internal AI gateway) `[V]`.
- **Session sharing is discontinued.** The repo ships a retirement page:
  *"Session sharing has been discontinued | opencode"* (`packages/function/src/share.ts:7`) `[V]`.

---

## 9. Engineering practices (what the repo says about the team)

- Bun workspaces + Turbo; `packageManager: bun@1.4.2`; TypeScript 6/7 aliases; oxlint + **ast-grep rule
  packs** including dedicated Effect-pattern and Effect-simplification rules `[V]`.
- Per-package tests (`bun test`), `packages/simulation`, `perf/`, and a packaged-build startup benchmark
  that refuses to compare builds from different channels `[V]`.
- `publish.yml` is 675 lines and builds **12 Bun-compiled targets** plus per-platform npm packages and a
  signed Electron app, with real dual-platform signing (Apple Developer ID + hardened runtime;
  Windows Azure Trusted Signing) `[V]`.
- Versions derive from `CHANGELOG` at a **1–3 day release cadence**, with no Changesets. V1 shipped 12,253
  npm versions, V2 1,201 since September — extremely high cadence `[V]`.
- The repo carries `V2_HTTP_API_AUDIT.md`, multi-language READMEs and a glossary — active i18n and audit
  culture `[V]`.

---

## 10. Findings worth acting on

### 10.1 Stale marketing vs V2 reality `[V]`

| Site claim | V2 reality |
|---|---|
| "**LSP enabled** — automatically loads the right LSPs for the LLM" | **No LSP runtime.** `lsp` config is accepted and normalised for compatibility, but the migration guide states V2 "does not run language servers, expose LSP tools, or produce LSP diagnostics", and three TODOs read *"Add LSP notification and diagnostics after LSP runtime exists"* (`file-mutation.ts:131`, `tool/plugin/edit.ts:205`, `tool/plugin/write.ts:44`). No `lsp` directory exists in any package. |
| "**Share links** — share a link to any session" | **Discontinued.** Retirement page ships in-repo (`packages/function/src/share.ts:7`). The `share` config field survives and the TUI still exposes `session_share`/`session_unshare` keybinds, so the surface is inconsistent rather than cleanly removed. |

Both are V1-era bullets still served to V2 users. If you are choosing opencode partly for LSP-quality
diagnostics, **V2 does not have them yet** — this is the most consequential finding in this report.

### 10.2 Risk register for adopters

| # | Risk | Severity | Evidence |
|---|---|---|---|
| 1 | `external_directory` allowed by default in V2 (V1 asked) | High | migrate-v1 doc |
| 2 | Plugin can rewrite any permission decision to `allow` | High | `permission.ts:179-188` |
| 3 | Credentials plaintext in SQLite; protection is `chmod 0600`; Windows relies on inherited ACLs | High | `credential/sql.ts:5-11`, `database.ts:83-98` |
| 4 | `webfetch` has no SSRF guard (scheme check only) | High | `webfetch.ts:70-71` |
| 5 | Runtime-fetched skills and ripgrep binary, no signature/hash verification | Medium-High | `skill/discovery.ts`, `ripgrep/binary.ts` |
| 6 | Tool filtering is visibility, not authorization; `shell` bypasses a `write` deny | Medium-High | `tool/AGENTS.md`, `tool.ts:293` |
| 7 | "Always allow" grants are wider than the prompt implies (shell prefix; enclosing project root) | Medium | `shell/parse.ts:196-199`, `file-access.ts:116-121` |
| 8 | GUI extensions are unsandboxed trusted code, `new Function` in the main process | Medium | `extension/module.ts:41` |
| 9 | `AGENTS.md` injected as durable instructions on any successful read (prompt-injection path) | Medium | `read.ts:82-107` |
| 10 | Event stream volatile by contract — events lost across disconnects, no replay | Medium | `protocol/groups/event.ts:51` |
| 11 | Write-behind retries failed writes forever, silently | Low-Medium | `storage/write-behind.ts` |
| 12 | macOS-only native menu; no tray; CLI installer macOS-only | Low | `native/menu.ts:55` |

### 10.3 What is genuinely good

The process isolation, the credential containment, the derived IPC contract, the event-sourced session
model with write-ahead recovery claims, the extension lifecycle discipline, and the fact that the SSE
stream's lossiness is documented rather than hidden. This is a codebase that explains *why* it made
choices, in comments, at the point of the choice.

---

## 11. Notes for a portable / offline deployment

Directly relevant given this workspace is named `opencode portable` and is currently empty. Marked
`[V]` where read from source, `[I]` where inferred — none of this was executed.

- **Standalone CLI binaries exist per platform** (macOS arm64/x64/x64-baseline, Windows arm64/x64/x64-baseline,
  Linux glibc/musl arm64/x64/x64-baseline) as `.zip`/`.tar.gz` `[V]`. Windows package managers are
  explicitly unsupported for the CLI, so the zip is the Windows portable path `[V]`.
- **`--standalone` gives a private server instead of the shared background service**, and
  `opencode service set disabled true` stops the shared service entirely `[V]`. Pairing requires the
  shared service and is unavailable while disabled `[V]`.
- **Path overrides for redirection:** `OPENCODE_DB` overrides the database (relative values resolve under
  the data directory, `:memory:` passes through), and `OPENCODE_CHANNEL` selects the release
  channel/database `[V]`. `opencode debug paths` prints each location without opening the DB `[V]`.
- **Desktop portability is harder than CLI portability:** the installer writes per-channel identities
  (`ai.opencode.desktop[.beta|.dev]`), `userData` is set to a per-`APP_ID` directory, and the sidecar is
  expected at `resources/opencode-cli[.exe]` beside `app.asar` `[V]`. A truly portable desktop build
  needs the userData path and the sidecar's own data dir redirected to a relative location, plus the
  `oc://` scheme and single-instance lock considered — all of which the code assumes are per-user and
  per-appId `[I]`.
- **Desktop-local state is small and self-contained** (`drafts.sqlite`, window state, extension
  archives). **Core-owned state is the big one** (sessions, credentials, snapshots) — so "portable"
  really means redirecting *both* the desktop `userData` and the CLI's data location `[I]`.
- **Offline operation is inherently fine** for the local parts; only model calls and update checks need
  the network. The desktop README's own offline test technique is to point `HTTPS_PROXY` at a closed
  port `[V]`.

---

## 12. Limits of this research

- **Point-in-time.** All claims bind to commit `0718864d…` (2.1.1, 2026-10-20). opencode releases every
  1–3 days; re-verify against a fresh clone before relying on anything operationally.
- **Static only.** No build, install, test or runtime execution. Behaviour under load, actual startup
  timings and real-world failure modes are unmeasured here.
- **Hosted surfaces unverified.** Zen/Go/Enterprise/console behaviour and the share endpoint's live
  status cannot be established from source; the sharing retirement is read from the shipped page, not
  reproduced against the service.
- **Anchors drift.** Line numbers are exact for this revision; the desktop menu anchor in
  [findings/desktop.md](findings/desktop.md) was quoted at line 78 where the current file has the guard
  at line 55 — treat anchors as pointers, and re-grep to confirm.
- **Security findings are source observations, not a penetration test.** They are absence- and
  logic-based, verified by grep and reading, not by exploitation.
- **Not legal advice.** Licence analysis is limited to "MIT per `package.json` and the GitHub API".

### Reproduce this

```bash
git clone --depth 1 https://github.com/anomalyco/opencode.git
git log -1 --format=%H          # expect 0718864d49a5241e7df5b302b35caed2518171a0
```
Then read [`findings/core-engine.md`](findings/core-engine.md),
[`findings/core-tools.md`](findings/core-tools.md), [`findings/desktop.md`](findings/desktop.md),
[`findings/platform.md`](findings/platform.md) for the full per-subsystem evidence.
