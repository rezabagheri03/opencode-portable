# opencode v2.1.1 — the surrounding platform

**Repo** `E:\Projects\opencode portable\opencode-src` · **HEAD** `0718864d49a5241e7df5b302b35caed2518171a0` (2026-10-20) · **root version** `2.1.1` · **scope** everything except `packages/core` internals and `packages/desktop`.

Evidence notation: `path:line` was read in this checkout. **[V]** verified in source · **[I]** inferred from source structure · **[U]** unknown / not in this repo.

---

## Executive summary

1. The platform is **local-first client/server**, not a hosted product: one managed background process owns durable state, and every surface talks to it over HTTP.
2. That process is elected/launched by the CLI (`packages/cli/src/services/server-connection.ts`) and serves `/api/*`, `/auth/*`, `/openapi.json` **and the web app** on one port (`packages/cli/src/services/web-ui.ts`).
3. The wire contract is an **Effect `HttpApi`** definition, not hand-written routing; OpenAPI 3.1 is generated from it (`packages/protocol/src/api.ts` → `packages/protocol/openapi.json`, 609,763 bytes).
4. Transport is deliberately narrow: one SSE feed `GET /api/event` (subscriber cap 4096, volatile by contract) plus WebSockets **only** for PTY attach.
5. The TUI is **TypeScript + SolidJS on OpenTUI** — `render()` from `@opentui/solid` (`packages/tui/src/app.tsx`); there are no `.go` files in the repo.
6. Distribution is **12 Bun-compiled CLI targets + per-platform npm packages + signed Electron desktop**, all from one pipeline (`.github/workflows/publish.yml`).
7. `packages/app` is not a marketing page: it is the bundle the CLI serves at `/`, the desktop renderer, and an installable PWA — one artifact, three hosts.
8. Plugins are a **dual API** (Promise and Effect) over ~20 transform/hook domains; `@opencode/plugin` is a public compatibility commitment.
9. Cloud is **Cloudflare-first with SST v3** (`sst.config.ts`): SolidStart console, Worker API, Astro docs, D1 update feed.
10. Session **sharing is discontinued** — the share Worker returns HTTP 410 for every path and stubs the V1 share API to keep old clients green.
11. Quality is enforced by **ast-grep rules + two custom Effect linters + turbo affected matrices**; `bun test` from the repo root is explicitly forbidden.
12. Honest read: v2 is a real re-architecture (Effect, SSE+RPC, in-process SDK), but it still carries a large compatibility shell — v1 config migration, a legacy `packages/opencode` build path, and a very fat `packages/cli` hub.

---

## Topology

```
                       ┌──────────────────────────────────────────────┐
  opencode  (TUI) ────▶│                                              │
  opencode --standalone│        ONE local "service" process           │
  opencode serve ─────▶│  packages/cli  ──serve--▶ packages/server    │
  opencode --server ──▶│        │                       │             │
  desktop (Electron) ─▶│        │              Effect HttpApi         │
  browser at :port/ ──▶│        │        ┌──────┴───────┐             │
  VS Code terminal ───▶│        │      /api/*        /  (web app)     │
  @opencode/client ───▶│        │        │         embedded assets    │
  @opencode/sdk (in-   │        │        │                            │
    process, no socket)│        ▼        ▼                            │
                       │  SSE /api/event · WS /api/pty/:id/connect    │
                       │  Basic auth + HMAC session cookie            │
                       └───────┬──────────────────────────────────────┘
                               │
                  packages/core (SQLite, sessions, tools, plugins)
                               │
                  ~/.config/opencode, ~/.local/share/opencode
```

Registration/discovery is a **file**, not a daemon: `service.json` in the global config dir, with a channel-derived default port — `0xC0DE` on POSIX, `0xB0DE` on Windows, `0xC0DF` for `local`, otherwise `10000 + hash%50000` (`packages/cli/src/services/service-config.ts`). [V]

---

## Q1 — Platform topology, state ownership, client/server

**One shared server, many clients.** The CLI has exactly three connection modes (`packages/cli/src/services/server-connection.ts`) [V]:

| Mode | Trigger | Behaviour |
| --- | --- | --- |
| remote | `--server <url>` | requires `OPENCODE_PASSWORD`; calls `client.server.info()` with a 5 s timeout; warns on version mismatch between client and server |
| standalone | `--standalone`, or config `disabled: true` | `Standalone.start()` — private in-process server, no service registration |
| managed | default | `Service.ensure()` — adopt or launch the background service; mismatch policy `replace \| ignore \| error` |

Failure text is diagnostic and versioned: `"did not provide a compatible V2 health response"` (`server-connection.ts`). [V]

**State ownership is server-side and explicit.** `packages/server/src/routes.ts` enumerates the application service graph that the HTTP layer composes: Global, Database, Bus, EventLogger, Job, Project (+sweep), Worktree, Session, Instance, SessionTransfer, SdkPlugins, PluginUpdate, PermissionSaved, PtyTicket, PersistentPty, Credential, WellKnown, PtyEnvironment, ServerPairing, LocationServiceMap, LocationActivity, SessionRestart, Workspace. Sessions, transcripts, PTY state, plugin registry and credentials all live behind that graph; clients hold presentation state only (TUI `context/`, app `TabsProvider`/drafts/namespace storage). [V]

**Events are delivery, not ownership.** `packages/protocol/src/groups/event.ts` documents the stream as *"Volatile by contract: a slow consumer overflows and fails the stream"*, matching `EventFeed`'s fixed subscriber capacity of 4096 (`packages/server/src/event-feed.ts`). `AGENTS.md` states the same law from the other side: replay ownership is kept separate from session-execution ownership. Consequence: **the SSE feed is not a durable log** — reconnect + `server.connected` marker + client-side refetch is the recovery model (`packages/client/src/shared-events.ts`). [V]

**Multi-location.** Nearly every operation is location-scoped (`{directory}`), enforced by middleware rather than by handlers: `locationMiddleware`, `formLocationMiddleware`, `sessionLocationMiddleware` are bound once in `makeDefaultApi` (`packages/protocol/src/api.ts`, `packages/server/src/api.ts`) and `LocationServiceMap` resolves per-directory service graphs. `FormLocationMiddleware` carries an acknowledged hack: a temporary `sessionID === "global"` special case for MCP elicitation (`packages/server/src/api.ts`). [V]

**Dependency direction is law, not convention** (`AGENTS.md`): Schema → Core/Protocol; Core and Protocol → Server; Client may depend on Schema and Protocol but *never* Core or Server; `sdk` composes Client + Core + Server. This is what makes the in-process SDK and the workerd target possible. [V]

---

## Q2 — The CLI: surface, launch paths, auth, upgrade, distribution

### Command surface (`packages/cli/src/commands/commands.ts`, 567 lines) [V]

| Command | Subcommands / flags | Notes |
| --- | --- | --- |
| *(default)* | `--standalone`, `--server`, `--auto`/`--yolo`/`--dangerously-skip-permissions`, `[directory]`, `-c/--continue`, `-s/--session`, `--prompt` | launches the TUI against the managed service |
| `serve` | `--hostname --port --cors --service --stdio` | "Start the v2 API and web server"; `--service` and `--stdio` mutually exclusive (`server-process.ts`) |
| `run` | — | non-interactive/headless execution |
| `acp` | `--login` | Agent Client Protocol server (`@agentclientprotocol/sdk 1.6.0`) |
| `api` | `<operation\|method path>`, `-d data`, `-H header`, `--param` | generic generated-client caller — the API is a first-class CLI verb |
| `auth` | `list\|login\|logout\|export\|import\|switch`, `--server`, `--standalone`, `--method`, `--answer` | `auth login` is **integration/credential oriented**, not per-provider: it picks a connect method and answers a form (`handlers/auth/login.ts`), with priority `opencode-go, opencode, openai, github-copilot, anthropic, google` |
| `mcp` | `list\|add\|auth\|logout` | |
| `plugin` | `list\|add\|check\|update\|remove` | |
| `session` | `list\|delete\|export\|import` | |
| `service` | `start\|restart\|status\|stop\|get\|set\|unset` | reads/writes `service.json`; `get password` + `status` are used by root `dev:live` |
| `debug` | `agents\|config\|paths [db\|home\|data\|config\|cache\|state\|tmp\|bin\|log\|repos]` | |
| `upgrade`/`update` | `[target]`, `-m <method>` | plus a **10-minute re-check loop** (`Schedule.spaced("10 minutes")`) wired in the default handler |
| `uninstall` | `--dry-run`, `-f` | |
| `pair` | `--url --remote -y` | pairing code flow (`server.pair` / `GET /auth/connect/:code`) |
| `models`, `stats`, `mini`, `reload` | — | |

Implementation shape: `packages/cli/src/index.ts` is a **lazy handler map** over `Runtime.handlers(Commands, …)` with `ensurePluginRuntime()`, telemetry from `OTEL_EXPORTER_OTLP_*`, an ssh-askpass fork on `OPENCODE_SSH_ASKPASS_PORT`, and a Node entry (`runMain`). Handlers are only loaded when their command runs — this is why the CLI binary starts fast despite depending on `@opencode/tui` + `@opencode/server`. [V]

### Auth model (two layers, easily confused)

1. **Transport auth** for the local server: HTTP Basic with username `opencode` and the server password, upgraded to an **HMAC-SHA256 session cookie** — key `HMAC(password, "opencode-session-v1")`, 30-day TTL, cookie name `opencode_session_<port>` so parallel channels do not collide (`packages/server/src/auth.ts`). [V]
2. **Integration credentials**: `credential` / `integration` domains in the protocol plus `auth export|import` for moving them between machines; `credential.activate` is exposed to plugins (`packages/plugin/src/promise/plugin.ts`). [V]

PTY attach does not reuse the cookie: `pty.connectToken` issues a **short-lived, single-use ticket** for the WebSocket upgrade (`packages/protocol/src/groups/pty.ts`). This is the only sane way to authenticate a browser `WebSocket` with no custom headers. [V]/[I]

### Install & upgrade

- `install` (467 lines of bash): OS/arch/baseline/musl detection, metadata from `https://opencode.ai/update/api/latest/cli/npm`, npm tarball pull, `chmod 755`, **legacy shim** writing `opencode2` / `opencode2.cmd`, PATH edits for bash/zsh/fish and `$GITHUB_PATH`.
- Documented install dir priority in `README.md` is `$OPENCODE_INSTALL_DIR` → `$XDG_BIN_DIR` → `$HOME/bin` → `$HOME/.opencode/bin`; the script itself only implements `$HOME/.opencode/bin` plus `-b/--binary`. **This is a real doc/script divergence.** [V]
- `upgrade` asks the update Worker (`services/update`) and can switch install method (`-m`).

### Binary distribution

`packages/cli/script/build.ts` (308 lines) compiles Bun single-file executables with build-time defines `OPENCODE_VERSION`, `OPENCODE_CHANNEL`, `OPENCODE_ARTIFACT`, `OPENCODE_RELEASE_HIGHLIGHTS` (`packages/cli/src/version.ts`), embeds the app archive as `virtual:opencode-app-assets`, applies `@opentui/solid/bun-plugin` for TSX, and resolves plugin runtime specifiers via `discoverPluginRuntimeSpecifiers()`. Targets:

| OS | Targets |
| --- | --- |
| linux | `arm64`, `x64`, `x64-avx2-off`, `arm64-musl`, `x64-musl`, `x64-musl-baseline` |
| darwin | `arm64`, `x64`, `x64-baseline` |
| win32 | `arm64`, `x64`, `x64-baseline` |

Twelve targets, mirrored 1:1 into npm packages named `@opencode/cli-<os>-<arch>[-baseline|-musl]` with `bin/opencode.cjs` / `bin/opencode2.cjs` shims. There is also a *separate* Node-targeted build (`build-node.ts`, `build-node-app-archive`, `build-node-cli` jobs with Node 26.4.0) — evidence that Bun-compiled binaries are the happy path but not the only supported runtime. [V]

---

## Q3 — The TUI

**Language/stack: TypeScript + SolidJS + OpenTUI.** `packages/tui/src/app.tsx` (1453 lines) calls `render()` from `@opentui/solid` over `createCliRenderer`, with `@opentui/core|keymap|solid@0.6.6`. The package is private with no `bin`, i.e. it is a library consumed by `packages/cli`, not a standalone program. There is no Go anywhere in this repo — any statement that the TUI is Go describes v1. [V]

**Architecture** (`packages/tui/src/app.tsx`): a provider stack — Route, Theme, Dialog, Data, Client, Local, Permission, SessionTabs, Location, Clipboard, Log, Exit, Epilogue, PromptHistory/Frecency/Stash, Toast — then overlays (`MigrationOverlay`, `Reconnecting`, `StartupLoading`), home/routes, ~14 dialogs (model, mcp, status, config, debug, pair, highlights, theme, help, agent, session-list, open), pane resize and link-click handling. Directory layout: `component, config, context, devtools, feature-plugins (storybook), mini, plugin, prompt, routes, simulation, theme, ui, util`. [V]

**Render model:** OpenTUI owns layout/paint (flexbox-ish, cell grid); Solid owns reactivity. No virtual-DOM reconciliation to a TTY, no blessed/ink. `@opencode/tui` also ships conditional-import variants for `#attention-sounds`, `#string-width`, `#zed-sqlite`, `#runtime-plugin-support` (bun vs node) — the TUI is expected to run under both. [V]

**Transport:** the TUI is *just another client*. `packages/tui/src/context/client.tsx` builds a `createGlobalEmitter<ClientEventMap>()` and feeds it from `onEvent(event){ events.emit(event.type, event) }`, typed by `OpenCodeEvent`; it consumes the same generated client and the same single SSE feed as the browser app. There is no TUI-private RPC. [V]

**Keybindings:** `packages/tui/src/config/keybind.ts` (359 lines) — a schema of `{name, ctrl, shift, meta, super, hyper, default, description}` plus a leader-key concept (`LeaderDefault`), user-configurable via `@opencode/tui/config/keybind`, with `@opencode/tui/config/v1` kept for v1 config compatibility. [V]

**TUI vs desktop:** they share `@opencode/client`, `@opencode/schema`, `@opencode/session-ui` (markdown/diff/mermaid rendering) and the protocol — and share **nothing** about layout. Desktop is the Solid web app in an Electron shell; the TUI re-implements the interaction model in terminal primitives (dialogs, panes, keymaps). That is a deliberate two-renderer design and the main source of duplicated UI work. [V]/[I]

---

## Q4 — HTTP API, OpenAPI, auth, streaming, versioning

**Definition is Effect `HttpApi`.** `packages/protocol/src/api.ts` (257 lines) calls `makeDefaultApi({locationMiddleware, formLocationMiddleware, sessionLocationMiddleware})` and composes 35 groups from `packages/protocol/src/groups/*.ts` (agent, command, config, credential, debug, event, form, fs, generate, integration, location, mcp, message, migration, model, permission, persistent-pty, plugin, project, provider, pty, reference, rpc, server, session, shell, skill, vcs, voice, websearch, worktree, …). Re-counting the group files gives ≈**144 operations over 119 paths** (GET 66, POST 52, DELETE 15, PATCH 6, PUT 5); `V2_HTTP_API_AUDIT.md` records **139** endpoints as of 2026-09-13, so ~5 operations of drift in five weeks. [V]

Representative operations:

| Operation | Route | Notes |
| --- | --- | --- |
| `server.info` | `GET /api/info` | `{version, pid, urls, paths.tmp, capabilities.persistentPty}` — the health probe used by `--server` |
| `server.pair` | `POST /api/pair` | pairing codes |
| `server.connect` | `GET /auth/connect/:code` | `PAIRING_CONNECT_PATH` |
| `event.subscribe` | `GET /api/event` | `HttpApiSchema.StreamSse({data: EventSchema})`; union of all domain events + `rpc.*` + `server.connected` |
| `pty.connect` | `WS /api/pty/:ptyID/connect` | declared with `"x-websocket": true`; ticket-gated |
| `session.*`, `message.*` | `/api/session…` | lifecycle, history |
| `fs.*`, `worktree.*`, `vcs.*` | `/api/…` | filesystem/worktrees/git |
| `pty.*`, `shell.*`, `persistent-pty.*` | `/api/…` | terminals (24 endpoints per the audit) |
| many | `/api/experimental/…` | explicitly unstable namespace |

**OpenAPI generation:** `packages/protocol/script/generate-openapi.ts` (21 lines) → `OpenApi.fromApi(...)` → `openapi-stabilize.ts` → pretty-printed `packages/protocol/openapi.json` (609,763 bytes, `openapi: 3.1.0`, title `opencode HttpApi`, version `0.0.1`). `check:generated` fails CI on drift; the docs site imports the same file. Note the declared API version is still `0.0.1` while the product is `2.1.1` — the spec is treated as a build artifact, not a semver commitment. [V]

**Auth:** authorization middleware on the router (Basic password *or* HMAC session cookie, `packages/server/src/auth.ts`), plus per-endpoint concerns (CORS flag on `serve`, pair-code endpoints, PTY tickets). [V]

**Streaming:** one SSE connection per client is the design centre. `packages/client/src/shared-events.ts` (148 lines) keeps **one** shared connection for the whole client with the same 4096 cap, treats `server.connected` as the connection marker, and uses `onActivity` keepalives. `packages/server/src/event-feed.ts` (89 lines) has `SubscriberCapacity = 4_096`, `SubscriberOverflowError`, and frames events literally as `data: ${JSON.stringify(event)}\n\n`. WebSockets exist for PTY only, plus a fixed-size ordered outbox in `packages/server/src/handlers/pty-socket.ts` (single writer, detach on every exit). [V]

**v1 vs v2 on the wire:** v1 endpoints survive as an `experimental` namespace plus a migration surface (`/api/experimental/migration/v1`), because v1 clients still exist in the wild — see the share Worker's explicit "keep V1 clients working" comment. [V]

**Codegen:** `packages/httpapi-codegen/src/index.ts` (1961 lines) — `compile(ClientApi, {groupNames, omitEndpoints})` with `emitPromise`, `emitEffectImported`, `emitEffectShape`, mapping `InputField.source ∈ {params, query, headers, payload}`. `packages/client/script/build.ts` (139 lines) writes `src/promise/generated`, `src/effect/generated`, `src/effect/api` from the compiled API, pulling type references from ~45 `@opencode/schema/*` modules. Generated code asserts the content type at the SSE boundary (`isContentType(response, "text/event-stream")` in `src/promise/generated/client.ts`), i.e. protocol violations fail loudly rather than silently hanging. [V]

---

## Q5 — SDKs, clients, languages, publishing, embedding

There is **no single "SDK"**; there are three distinct products with confusingly similar names. [V]

| Artifact | What it is | Who it is for |
| --- | --- | --- |
| `@opencode/client` | generated HTTP client; exports `.`, `./promise`, `./promise/api`, `./service`, `./solid`, `./effect`, `./effect/api`, `./effect/service`; peer `effect`, optional `solid-js` | anyone talking to a running server (TUI, web app, desktop, plugins indirectly) |
| `@opencode/sdk` | **in-process host**, exports `.`, `./effect`, `./workerd`, `./workerd/effect` | embedding opencode in another Node/worker program |
| `@opencode/plugin` | extension API baked into the server's runtime | third-party behaviour, no transport at all |

`packages/sdk/README.md` is unusually explicit about the model: it *"executes Server's assembled HTTP router in memory, opening no listener and adding no network hop"*; API is `OpenCode.create()`, `sessions.create({location:{directory}})`, `instances.key/configure` for session-selected plugins, `Symbol.asyncDispose` for teardown. The `workerd` entrypoint targets Cloudflare Durable Objects — the server can run without Node. [V]

**Languages:** TypeScript (all of it). Cross-language surface exists only as (a) the OpenAPI document, (b) the ACP server for editor clients (`opencode acp`), and (c) MCP for tool interoperability. `sdks/` in this repo contains exactly one thing — `sdks/vscode`, a **VS Code extension** (`publisher: sst-dev`) whose entire job is to open a terminal at the right cwd: commands `opencode.openTerminal` (cmd+escape), `opencode.openNewTerminal` (cmd+shift+escape), `opencode.addFilepathToTerminal` (cmd+alt+k), bundled with esbuild. [V]

**Publishing:** npm per-platform CLI packages + `@opencode/*` libraries + desktop installers (npm/brew/AUR/mise/nix, plus WinGet submission written but commented out: *"paused until our packages are accepted into microsoft/winget-pkgs"*, `script/publish.ts`). `@opencode/sdk` has `verify:package` in turbo, and CI runs "Verify packed workerd SDK" — packaging is treated as a tested product, not an afterthought. [V]

---

## Q6 — Web app, shared UI, and what the browser build is for

**`packages/app`** is a SolidJS SPA: Vite 8.2.2, Tailwind 4.3.3, `@solidjs/router`, `@tanstack/solid-query`, `@kobalte/core`, `@corvu/drawer`, `@dnd-kit/*`, `@pierre/trees`, `@sentry/solid`, and a git-pinned `ghostty-web` for the terminal. `src/entry.tsx` initialises Sentry, `render()`, `createWebPlatform`, PWA route persistence, `KeyboardInsets`, locales en/zh, and `authFromToken`. `src/app.tsx` (118 lines) stacks DialogProvider → FileComponentProvider → Font → ThemeProvider → MetaProvider → Router → QueryClientProvider → CommandProvider → DesktopCommands → GlobalProvider → LanguageProvider → ServersProvider → SettingsProvider → TabsProvider → ErrorPage → AppRoutes → ExtensionRoot. [V]

**Three hosts, one bundle:**
1. served by the CLI's `serve` at `/` (`packages/cli/src/services/web-ui.ts`: assets map + brotli, `/api`, `/api/*`, `/auth/*`, `/openapi.json` stay on the API router, missing `_assets/`/`icons/` → 404, `max-age=31536000, immutable` for assets, no-cache for html/sw.js, per-document CSP with a hashed inline theme script);
2. the Electron renderer (`packages/app/src/desktop.ts` re-exports AppBaseProviders/AppInterface, `useCommand`, layout routes, i18n, Platform, ServerConnection/useServers, `useGlobal`, `useTabs`, draft store, namespace storage, `flushPersisted`, `useExtensionServers`);
3. an installable PWA — so "the web UI" is both a local control surface and a remote one when you connect to a paired/remote server.

**Sharing is gone.** `packages/function/src/share.ts` (171 lines) serves a fixed HTML page titled *"Session sharing has been discontinued"* with `.all("*", c => c.html(page, 410))`, and stubs `POST /api/share`, `POST /api/share/:id/sync`, `DELETE /api/share/:id` to no-op success so V1 clients and the V1 GitHub Action keep passing. The source comment is explicit: *"Accept those requests without storing anything so existing clients keep working."* `packages/console/app/src/routes/s/[id].ts` still proxies `/s/<id>` to the docs site. [V]

**Shared UI:** `@opencode/ui` is a real published component library (~40 subpath exports: button, dialog, tabs, toast, select, text-input, theme tokens/styles/tailwind, icon sprites, fonts, audio, storybook scaffold). `@opencode/session-ui` carries the conversation-rendering stack — `@pierre/diffs` for diffs, `@shikijs/stream` + shiki, mermaid 11.17.2, marked, dompurify, motion, remend. Both the browser app and desktop consume these; the TUI consumes `session-ui` for content rendering but its own `packages/tui/src/ui` for chrome. The `ExtensionRoot`/`guiextensions` path (with an ast-grep rule `no-module-state` in `script/ast-grep/gui-extensions/`) is the browser-side analogue of the plugin system. [V]

---

## Q7 — Plugins

**Two surfaces, one identity.** `packages/plugin` exports `.` (Promise), `./effect`, `./host`, `./runtime`, `./tui`, `./*`; peers `@opencode/theme`, `@opentui/core|solid`, `solid-js` — all optional. `packages/plugin/src/README.md` (176 lines) documents the Promise form: `Plugin.define({id, setup})`, `ctx.options`, an optional cleanup return, `Registration.dispose`. Effect form is `Plugin.define({id, effect})` with 24 domain modules mirroring the Promise API 1:1. [V]

**Context surface** (`packages/plugin/src/promise/plugin.ts`): app, location, options, agent, aisdk, command, credential (`activate`), event, `experimental.terminal` (`persistentPty.read`), integration, mcp, model, generate, permission, plugin (`list`), provider, reference, rpc, session, shell, skill, storage, tool, vcs, websearch, worktree. [V]

**Hook list** (the actual extension points):

| Domain | Hooks |
| --- | --- |
| transforms | `agent`, `command`, `integration`, `mcp`, `model`, `provider`, `reference`, `skill`, `tool`, `vcs`, `websearch` — each `.transform` |
| AI SDK | `ctx.aisdk.hook("sdk" \| "language")` |
| session | `prompt`, `context`, `compaction`, `generate`, `title`, `model.request`, `http.request`, `http.response`, `experimental.ws.handshake\|send\|receive`, `retry` |
| tools | `execute.before`, `execute.after`; tool shape `{name, options:{codemode}, description, input, output, execute}` |
| permission | `evaluate` (+ `assert()`) |
| shell | `create.before` |
| terminal | `experimental.terminal.persistentPty.read` |
| RPC | `ctx.rpc` |

Request kinds are classified (`SessionRequestKind = "primary"|"compaction"|"title"|"generate"`), which is what makes `model.request`/`http.request` interception meaningful rather than a blanket proxy. [V]

**Published plugins in-tree:** exactly one — `packages/plugins/figma` (`@opencode/plugin-figma`), three runtime dependencies (`gray-matter` only), peer `@opencode/plugin ^2.0.27`, shipping `skills/` built from `skills-source.json` via `script/sync-skills.ts`. The plugin *registry* is server-side (`SdkPlugins`, `PluginUpdate` service nodes in `routes.ts`; CLI `plugin add|check|update|remove`). [V]

---

## Q8 — Console, cloud, identity, billing, containers, deployment

**SST v3 config** (`sst.config.ts`, 48 lines): app `opencode`, `home: "cloudflare"`, removal retain on production, providers aws 7.30.0 (us-east-1) + stripe 0.0.28 + planetscale 0.4.1. Stage `beta` deploys only `infra/webapp.ts` (`beta.opencode.ai`); other stages get `infra/stage.js` + `infra/app.js` + `infra/console.js` + optional `infra/stats.js` + `infra/share.js`. [V]

| Infra piece | What it deploys |
| --- | --- |
| `infra/stage.ts` | domains: production `opencode.ai`, dev `dev.opencode.ai`, else `<stage>.dev.opencode.ai`; zone `430ba34c…`; `shortDomain` `opncd.ai` |
| `infra/app.ts` | Secrets (GitHub App id/key, EmailOctopus, ADMIN_SECRET, Discord, Feishu, enterprise, hCaptcha, Salesforce); `Bucket("Bucket")`; Worker `Api` at `api.<domain>` from `packages/function/src/api.ts` with a `SYNC_SERVER` Durable Object + migrations; Astro docs `Web` at `docs.<domain>` from `packages/web` |
| `infra/console.ts` | archived Zen buckets `ZenData`/`ZenDataNew` (*"writer removed May 2026"*); `SolidStart("Console")` at the apex from `packages/console/app`, placed `aws:us-east-2`, compat flag `global_fetch_strictly_public` |
| `infra/share.ts` | `EnterpriseStorage` bucket; `Share` Worker at `shortDomain` (`packages/function/src/share.ts`) — now the 410 page |

**Console (`packages/console/app`)** is SolidStart + Cloudflare + nitro, with `aws4fetch`, `number-flow`, zod. Routes: `api/enterprise`, `brand`, `changelog`, `data`, `docs`, `download/[channel]/[platform]`, `enterprise`, `go`, `legal`, `oauth/opencode/client.json`, `s/[id]`, `stats`, `t/[...path].tsx`, `zen`, plus generated `openapi.json`, `llms.txt`, `changelog.json`. `src/middleware.ts` (44 lines) does locale redirects, `/black` → `/go` (*"Black subscriptions were migrated to Go"*), and 302s `/auth|/workspace` → `https://opencode.ai/console/login`. [V]

**Billing-adjacent reality:** the dashboard, login, and Zen billing live *outside this repo* — the console here redirects to them and only keeps the marketing/legal/download/docs surfaces plus archived Zen buckets. Don't infer a billing implementation from names. [V]/[U]

**Workers in `packages/function`** (hono, the only hono in the repo): `api.ts` (GitHub App, SES, hCaptcha-gated enterprise form, Jev spam scoring, Discord/Feishu notifications), `share.ts`, `github.ts`. [V]

**`services/`:** `update` (Cloudflare Worker + D1 `opencode-update`, wrangler routes `update.opencode.ai` and `opencode.ai/update[/ *]`, Analytics Engine `EVENTS`; serves `/api/latest`, `/api/latest/cli`, `/api/latest/cli/npm`, and desktop feeds `/api/<channel>/desktop/<dist>/latest{,-mac,-linux,-linux-arm64}.yml`; channel rollout by hashed `CF-Connecting-IP`), `files`, `www` (Astro 7 + pagefind docs; its `generate` step runs the protocol OpenAPI build *and* `packages/cli/script/schema.ts`), `changelog`. Each has its own `deploy-*.yml`. [V]

**Identity:** `packages/identity` contains **brand assets only** (`mark.svg`, `mark-96/192/512*.png`) — no package, no auth code. The word "identity" here means logos. [V]

**Containers:** `packages/containers` builds GHCR CI images — `base` (ubuntu 24.04 + build tools), `bun-node` (+Bun, Node 24), `rust`, `tauri-linux`, `publish` (+Docker CLI, AUR tooling) — via `REGISTRY=ghcr.io/anomalyco TAG=24.04 bun ./packages/containers/script/build.ts [--push]`, multi-arch `linux/amd64,linux/arm64` through Buildx. These speed up Linux CI jobs only; macOS/Windows runners are not covered. [V]

---

## Q9 — Build & release engineering

**One publish pipeline, many artifacts** (`.github/workflows/publish.yml`, 675 lines). Trigger: push to `ci|dev|beta|fix/npm-native-binary-install|snapshot-*`, plus `workflow_dispatch` with `bump`, `version`, `release_notes`.

| Job | Runner | What it does |
| --- | --- | --- |
| `version` | `blacksmith-4vcpu-ubuntu-2404` | `./script/version.ts` — derives version/channel |
| `build-cli` | linux | preview build from `packages/cli/script/build.ts`, `BUN_COMPILE_RELEASE: bun-v1.4.2`; legacy `packages/opencode/script/build.ts` when the ref is not dev/beta |
| `sign-cli-macos` | `macos-26` | `apple-actions/import-codesign-certs`, `codesign --options runtime --entitlements packages/cli/script/entitlements.plist`, verify `--deep --strict` |
| `build-node-app-archive` / `build-node-cli` | matrix | Node 26.4.0 targets linux-arm64/x64, darwin-arm64, windows-arm64/x64 |
| `sign-cli-windows` | windows | Azure Trusted Signing + Authenticode verification |
| `build-electron` | macos | desktop installers (`.dmg` mac arm64/x64, `.exe` win x64, `.deb/.rpm/.AppImage` linux) |
| `publish` | linux | ghcr.io docker login, QEMU+buildx, npm registry, artifact download (incl. `latest-yml-*`), AUR SSH key, `./script/publish.ts` with `AUR_KEY`, `TAURI_SIGNING_PRIVATE_KEY`, `OPENCODE_DESKTOP_DIST`, `WINGET_GITHUB_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` |
| `notify-discord-v2` | linux | release announcement |

**Signing is real and dual-platform:** Apple Developer ID + hardened runtime with an explicit entitlements plist, and Windows Azure Trusted Signing with Authenticode verification — both verified in-workflow, not assumed. `V2` artifacts are additionally covered by the updater's Tauri signing key for desktop. [V]

**Release bookkeeping** (`script/publish.ts`, 133 lines): prepends `## v<version> — <date>` to `CHANGELOG.md`, `prepareReleaseFiles()`, a desktop publish dry-run, commit `release: v<tag>`. `CHANGELOG.md` shows cadence: v2.0.26…v2.0.32 across 2026-10-08…10-16, v2.1.0 on 2026-10-20 — roughly **every 1–3 days**, with sections Highlights / Breaking changes / Core / TUI / Desktop / SDK / Extensions. No Changesets: versions are computed by `script/version.ts` and channels are encoded in the version string (`0.0.0-<channel>-<n>`), which also selects the service port and `service.json` filename. [V]

**Reproducibility/verification:** `packages/cli/script/verify-artifact.ts` and `verifySimulationGraph` run inside the build; `service-smoke.ts` exercises the compiled binary's service lifecycle in CI; `nix/{opencode.nix,desktop.nix,electron.nix,node_modules.nix,hashes.json}` plus `nix-eval.yml`/`nix-hashes.yml` keep the Nix flake honest across aarch64-linux, x86_64-linux, aarch64-darwin. [V]

---

## Q10 — Quality engineering

- **Lint stack** (root `package.json`): `oxlint` → `ast-grep scan -c script/ast-grep/gui-extensions/sgconfig.yml` → `bun script/sdk-docs.ts`. `lint:effect-patterns`, `lint:effect-simplifications`, `test:lint-rules` cover the custom rules. [V]
- **ast-grep rules** (`script/ast-grep/sgconfig.yml` + `rules/`): `no-drizzle-column-name`, `no-effect-die-string`, `no-import-alias`, `no-json-parse-cast`, `no-nested-effect-service-yield`, `no-star-import`; `effect-simplifications/` adds 10 rule+snapshot pairs (e.g. `no-effect-and-then-succeed-undefined`, `no-effect-catch-fail`, `no-effect-flat-map-suspend`, `no-identity-pipe`, each with a `.tsx` variant); `gui-extensions/` adds `no-module-state`. The `.tsx` variants matter because the TUI/app are TSX and the Effect rules would otherwise miss them. [V]
- **Typecheck:** `bun turbo typecheck --concurrency=3`; `check` aggregates. [V]
- **Tests are per-package, never from root** — `AGENTS.md` names the rule `do-not-run-tests-from-root`; root `test` is a script that refuses. `.github/workflows/test.yml` (335 lines) gates via a turbo `affected` job and runs: `unit` on linux+windows (`GITHUB_ACTIONS=false bun turbo test --affected --concurrency=3`), ast-grep rule tests, effect-simplification lint, "Verify published codemode package", "Verify packed workerd SDK", "Check generated client", "Check generated documentation", `build` (compiled service lifecycle, workspace library build, Node build verify), `e2e-suite` (Playwright against a production build, service-worker precaching, session-ui + app component tests, artifact upload), and an aggregate `e2e` gate. [V]
- **Benchmarks/simulation:** `packages/simulation` (exports `./backend`, `./frontend`, `./protocol`, `./recording`) is a record/replay harness for provider traffic — the reason tests can be deterministic without network. `perf/test-suite.md` (145 lines) documents `bun run bench:test`, `BENCH_WARMUPS`/`BENCH_RUNS`, `profile:test` with `TEST_PROFILE_GLOB`/`TEST_PROFILE_LIMIT`, and a single primary metric `METRIC test_suite_seconds=<median>`. The web app has `bench:tabs`, `bench:entry`, `bench:devex`, `test:stability` plus Storybook Playwright component tests. [V]
- **Turbo graph:** `build.dependsOn: ["transit"]`, and `transit` folds workspace dependency state into the test hash so a dependency change invalidates downstream test caches. `@opencode/desktop#test` is `cache:false`; `@opencode/sdk#verify:package` is its own task. [V]

---

## Q11 — Honest assessment: what v2 actually is

**Genuinely new in v2 (structural, not cosmetic):**
1. **Effect v4 end to end.** `effect 4.0.1` with `effect/http`, `effect/http-api`, `effect/socket`, Effect CLI (`Argument`/`Flag`/`GlobalFlag`), `LayerNode`, `Context.Service`, Schema. The API contract, the server's composable service graph, and the CLI are all the same programming model. This is the single biggest difference from v1 and it is not a wrapper. [V]
2. **The contract is data.** `packages/protocol` defines operations; OpenAPI, the Promise client, the Effect client and the CLI `api` verb are all generated from it. Drift is a CI failure, not a review comment. [V]
3. **In-process embedding.** `@opencode/sdk` runs the assembled router in memory — no listener, no hop — and has a `workerd` entrypoint. That is an architectural claim v1 could not make. [V]
4. **Transport discipline.** One SSE feed with a documented volatility contract, WS only for PTY, ticket auth for the socket. Fewer moving parts than v1's plural event paths. [V]
5. **TUI parity without a second language.** OpenTUI + Solid means the TUI shares the client, schema and content renderers with the web app.

**Where it is still carrying weight:**
1. **`packages/cli` is a hub.** It depends on `@opencode/tui`, `@opencode/server`, `@opencode/plugin`, `@opencode/schema`, plus `@agentclientprotocol/sdk`, `@opentunnel/client`, `@parcel/watcher`. Service election, config I/O, updater, web-UI serving, ssh-askpass, telemetry, and every command live in one package. The dependency law protects `client`/`schema`; nothing protects `cli` from becoming the new monolith. [V]/[I]
2. **A compatibility shell exists and is load-bearing.** `packages/core/src/v1/config/{agent,command,error,mcp,migrate,permission,plugin,provider-options,provider}.ts`, `V1Migration` in `packages/server/src/routes.ts`, `/api/experimental/migration/v1`, the TUI's `MigrationOverlay`, `@opencode/tui/config/v1`, `auth import` legacy shapes, `opencode2` shims in `install`, and a legacy `packages/opencode` build in `publish.yml`. v2 is not a clean break; it is a migration with a long tail. [V]
3. **Two UI renderers, permanently.** The TUI and the web/desktop app share data and content rendering but not chrome, dialogs, or interaction model. `@opencode/ui` (~40 exports) versus `packages/tui/src/ui` is a standing duplication tax. [V]
4. **Two plugin APIs, deliberately.** Promise + Effect doubles the public surface of `@opencode/plugin` and all its documentation; the 24 file pairs in `packages/plugin/src/{promise,effect}` must stay behaviourally identical, which is enforced socially, not mechanically. [V]/[I]
5. **The API is still `0.0.1` and large.** `openapi.json` declares version `0.0.1` at product `2.1.1`; ~144 operations, a quarter of them under `/api/experimental`, and `V2_HTTP_API_AUDIT.md` (279 lines, 10 reviewed groups, dispositions Keep/Change/Remove/Experimental-only) is effectively an admission that the v2 surface was too wide and is being trimmed. The audit already removed `POST /api/plugin/await-activation`, merged health+server into `GET /api/info`, and reduced response locations to `{directory}`. [V]
6. **Cloud story is thin by design.** Sharing discontinued (410), Zen dump writers removed, console login/billing out-of-repo, and the console's own marketing flags read from build-time env (`consoleMarketingEnabled`). The hosted-product ambitions have visibly contracted; the local-first core has not. [V]

**One-line verdict:** v2 is a real platform rewrite — Effect-typed contract, generated clients, in-process SDK, one background service owning state — with a deliberate compatibility tail and one architectural weak point (`packages/cli` as the dependency sink) that v3 should treat as its first target.
