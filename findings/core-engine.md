# opencode v2.1.1 — `packages/core` engine subsystem

All paths are relative to the repo root (`opencode-src/`). HEAD `0718864d`. Claim types: **[V]** verified in source at the cited line, **[I]** inferred (reasoning given).

## Executive summary

1. `packages/core` is a *source-only* package (`"exports": { "./*": "./src/*.ts" }`, `packages/core/package.json:30`) built entirely on **Effect 4.0.1** (`package.json:82`) with a hand-rolled dependency-injection DSL (`LayerNode`) instead of Effect's `Layer` composition.
2. Two DI tag groups split the graph: **global** (process-wide: database, bus, project registry, session store/execution) and **location** (per-working-directory: providers, agents, models, config, tools). `LayerNode.compile(graph, { shared: Node.tags.values.global })` (`packages/core/src/instance.ts:161`) acquires globals once and rebuilds location services per directory.
3. State changes are **events**, never direct writes: every session mutation calls `bus.publish(SessionEvent.X, …)` (e.g. `packages/core/src/session/session.ts:61-80`); projectors do the SQL inside the publish transaction (`packages/core/src/bus.ts:312-330`).
4. Durability is **hybrid and deliberate**: step/tool/message boundaries are durable event-sourced rows; token deltas (`session.text.delta`, `session.reasoning.delta`, `session.tool.input.delta`, `session.tool.progress`, `session.compaction.delta`) are ephemeral PubSub-only (`packages/schema/src/session-event.ts:418,457,501,534,608`). Transcripts are *reconstructed*, not log-replayed.
5. A "part" is not a table. An assistant message row carries a `content[]` array of `text | reasoning | tool` parts (`packages/schema/src/session-message.ts:197-200`), mutated in place with `immer` (`packages/core/src/session/message-updater.ts`).
6. The turn loop is a write-ahead-claim state machine: claim on start (`time_suspended`, `resume_attempts`), release on terminal event, *preserve* the claim on shutdown so a crash is recoverable (`packages/core/src/session/store.ts:66-85,208-227`).
7. AI SDK 6 is wrapped one level lower than usual — at `LanguageModelV3`/`doStream`, normalized into an internal `LLMEvent` stream (`packages/core/src/aisdk.ts:722-747`), with transport guards (header timeout, SSE inter-chunk deadline) implemented locally (`:64-100`, `:226`).
8. Context accounting is conservative and cache-friendly: it prefers resending the full conversation unchanged, only trusts the *current provider's* measured counts (`packages/core/src/session/compaction.ts:866-871`), and supports provider-native compaction windows instead of summaries.
9. The HTTP/UI surface is derived from the event definitions themselves: the SSE endpoint's wire schema is generated as a `Schema.Union` of ~all event definitions (`packages/protocol/src/groups/event.ts:23-36`), documented as *"Volatile by contract"* — no replay, no resume (`:51`).
10. Migrations abandon Drizzle's journal for an owned `migration` table, bootstrap-or-apply (`packages/core/src/database/migration.ts:20-52`), and reserve the `_`-prefixed table namespace for embedders (`:26-28`).

---

## 1. Effect-TS usage: DI graph, not plain Layers

**Stack facts [V]:** `effect` 4.0.1 (`package.json:82`), `drizzle-orm` `1.0.0-rc.5-169397b` (`package.json:81`), `@ai-sdk/provider` 3.0.8 (`packages/core/package.json:101`), package version 2.1.1 (`:3`). Runtime-specific modules are resolved by package `imports` conditions — `#sqlite`, `#pty`, `#fff`, `#photon-wasm`, `#shell-parser-wasm`, `#v1-migration` each have `workerd`/`bun`/`node`/`default` variants (`packages/core/package.json:32-76`). Core therefore ships to Node, Bun **and** Cloudflare workerd from one source tree.

**Services are `Context.Service` classes**, e.g. `class Service extends Context.Service<Service, Interface>()("@opencode/SessionStore") {}` (`packages/core/src/session/store.ts:88`). There is no `Effect.Service` / `@effect/DI` annotation style; every service is an explicit class plus an explicit layer.

**The graph DSL** (`packages/util/src/effect/layer-node.ts`) is the distinguishing feature. A node is one of `group | unbound | layer`; each carries a tag group and a dependency list:

- `tags = LayerNode.tags({ location: ["global"], global: [] })` (`packages/util/src/effect/app-node.ts:18`) — i.e. `makeLocationNode` nodes may depend on `global` nodes; `makeGlobalNode` nodes may depend only on globals.
- `compile()` resolves the graph, detects cycles (`Cycle detected in layer graph: …`, `layer-node.ts:259-264`), rejects the same layer being wired to two different dependency sets (`Layer ${name} is wired to different dependencies; use a distinct implementation Layer`, `:284-287`), then builds via `Layer.fromBuild` with per-node `Layer.suspend` (`:298-318`).
- `buildContext` preserves the exact output context type of the compiled graph (`:321-330`) — services stay statically typed at the composition root.
- `Node.replace()` type-checks tag equality and error variance before substitution (`:81-92`), which is what lets tests and alternative hosts swap implementations.

**Composition root** (`packages/core/src/instance.ts`): a single flat array of ~60 `*.node` values (lines 60–120) plus `LayerNode.compile(graph, { replacements, shared: Node.tags.values.global })` at `:161`. Boot failures are handled asymmetrically — `FileSystem.DirectoryNotFoundError` is surfaced as a typed failure (a bad `--directory` is user error), every other cause **dies** (`:162-173`). After boot: `Layer.tap(… Effect.addFinalizer(() => Context.get(context, LocationLifecycle.Service).shutdown))` (`:174`) and a `"location services booted"` log with `durationMs` (`:175-181`).

**Error channel.** Each service declares narrow typed errors (`MessageDecodeError`, `ModelUnavailableError`, `Snapshot.Error`, `InvalidDurableEventError`…) and `Effect.orDie` is reserved for true invariants, e.g. every Drizzle call in `SessionStore` (`packages/core/src/session/store.ts:97,139,218`). The turn loop's union is explicit: `RunError = AIError | SessionRunnerModel.Error | MessageDecodeError | AgentNotFoundError | StepFailedError | Instructions.InitializationBlocked | FileSystem.DirectoryNotFoundError` (`packages/core/src/session/runner/index.ts:12-19`).

**Why Effect, concretely [I, from usage]:** (a) the graph must be rebuilt per directory while globals are memoized — needs first-class layer values, not module singletons; (b) streaming, retry schedules, semaphores and structured shutdown are all `Effect` primitives (`Schedule`, `Semaphore`, `KeyedMutex`, `Scope`, `FiberMap`, `PubSub`); (c) the same code must run on workerd, where a shared semaphore is *unsafe*: `database.ts:28-32` documents that releasing a semaphore shared across Durable Objects resumes the waiter inside the wrong object's I/O context. Effect's explicit resource model is what makes that observable and fixable.

---

## 2. Instance / location: the process model

A **Location** is one working directory plus its project identity:

```ts
// packages/core/src/location.ts:11-13
Interface extends Info { vcs?: Project.Vcs }
```

`layer(ref)` resolves `Project.Service.resolve(ref.directory)` → `{ directory, workspaceID, project: { id, directory, canonical }, vcs }` (`:19-32`); `boundNode(ref)` mints a per-directory node (`:34-39`). So a "location" is a *value* (`Ref`) and the corresponding service bundle is compiled from it, not a process.

**`Instance.Service`** is the swappable bundle:

```ts
// packages/core/src/instance/service.ts:11-15
provide(session: Session.Info) => <A,E,R>(effect) => Effect<A, E|Error, Exclude<R, Services>>
```

Any code that needs session-scoped services wraps its effect in `instances.provide(session)` — e.g. `packages/core/src/session/revert.ts:57`.

**Isolation model.** Concurrent sessions are fibers, not processes; isolation is enforced by (a) location-scoped services being distinct per directory, (b) per-session serialization in the inbox (`inboxLocks = KeyedMutex.makeUnsafe<SessionSchema.ID>()` + `serialized(sessionID, effect)`, `packages/core/src/session/inbox.ts:59-63`), (c) per-aggregate durable locks in the bus (`durableLocks = KeyedMutex`, `packages/core/src/bus.ts` ~209).

**Shutdown** is ordered: close permission/forms/rpc, then publish `LocationEvent.Shutdown` (`packages/core/src/location-lifecycle.ts:43-56`). Conversely, activity *reactivates* a location: the lifecycle service subscribes to the bus, filters `Schema.is(SessionEvent.Durable)` whose `LocationServiceMap.canonical(event.location)` matches, and forks `project.activate(…)` off the publish path (`:33-40`) — publishing must never block on project bookkeeping.

**Multiple clones of one repo** are one project but different directories: `Resolved { previous?, id, directory, canonical, vcs? }` with the comment *"This checkout's main directory; the stored project canonical may be another clone"* (`packages/core/src/project.ts:39-46`). Project ID is derived from the git remote, falling back to a hash: `Hash.fast("git-remote:" + normalized)` (`:282-288`), with scp-style URL parsing (`:290-300`). Activation is throttled (`ACTIVATE_INTERVAL = 60_000`, `SWEEP_INTERVAL = Duration.hours(1)`, `:60-61`). Note `root()` is **filesystem-only** (`.git`, `.hg`) because *"permission checks must not run VCS"* (`:48-58`) — a security-motivated constraint, not an optimization.

**Remote/cloud execution** is a separate axis: `Workspace.Info = { id, provider, binding, createdAt, lastUsedAt }` with `create` (logical record only) vs `provision` (start-or-join a shared attempt) vs `connect` (`packages/core/src/workspace.ts:20-26,36-53`).

---

## 3. Session lifecycle: messages, parts, streaming, cancel

**Session construction** is documented as host-scoped:

```ts
// packages/core/src/session/session.ts:38-41
// Build once in the host Scope: `const sessions = yield* Session.make()`.
// Use `sessions.forSession(id)` for handles that share host services and reload current state.
```

`make` yields `Bus, Database, SessionStore, Instance, SessionExecution, SessionInbox, FSUtil, Scope` (`:42-50`). Everything user-facing is a **handle** bound to one `Session.Info`; the returned object's keys (`:410-435`) are `get, message, view, rename, archive, unarchive, setMetadata, setPermissions, switchAgent, switchModel, inbox, prompt, synthetic, shell, skill, compact, wait, resume, interrupt, cancelInbox, steerInbox, queueInbox, revert`.

**Message model** (`packages/schema/src/session-message.ts`). Messages are a tagged union of *state transitions*, not chat turns: `AgentSelected, ModelSelected, LocationSwitched, User, Synthetic, System, Skill, Shell, Assistant, Compaction, Idle` (`:47-310`). Two of these matter architecturally:

- `Idle` is the turn delimiter: *"every step since the previous marker belongs to one turn, including prompts steered in while it was busy. A shutdown does not record one"* (`:285-296`). Turn boundaries are thus a *fact in the transcript*, not a counter.
- `Compaction.completed` may carry a provider **replacement window** in `native` that is replayed instead of the `summary` text (`:250-283`).

**IDs** are lexicographically ascending (`msg_` + `ascending()`), and message IDs can be *derived from the event that created them* (`ID.fromEvent(eventID)` rewrites `evt_`→`msg_`, `:22-30`). Forks renumber deterministically: `SessionMessage.ID.make(`${SessionMessage.ID.fromEvent(event.id)}_${seq}`)` in `packages/core/src/session/projector.ts:192`, with `ForkBatchSize = 500` (`:49`).

**A "part"** is a member of the assistant `content` array (`session-message.ts:197-200`):

| part | payload | notes |
|---|---|---|
| `AssistantText` | `text`, `native?` | `native` is opaque provider replay data (`Native = Schema.Record(String, Unknown)`, `:42-45`) |
| `AssistantReasoning` | `text`, `native?`, `time?` | same replay channel |
| `AssistantTool` | `id`, `name`, `executed?`, `native?`, `state`, `time` | `state` is a nested tagged union `streaming{input:string} → running{input:object,metadata} → completed{input,content: NonEmptyArray<Content>,metadata?} \| error{input, error, content?, metadata?}` (`:129-162`) |

The assistant message also carries `snapshot {start,end,files}`, `finish`, `rawFinish`, `native`, `cost: Money.USD`, `tokens: TokenUsage.Info`, `error`, `retry {attempt, at, error}`, `time {created, streamed, completed}` (`:214-240`).

**Streaming** is a three-tier pipeline: provider SSE → normalized `LLMEvent` → durable *boundaries* + ephemeral *deltas*. `streamLanguage` prepends `LLMEvent.stepStart({index})` then maps `Stream.fromReadableStream(language.doStream(options))` through `streamPartEvents` (`packages/core/src/aisdk.ts:722-747`), which tracks `{step, toolNames, open: {text|reasoning}}` fragments (`:751-755`) so that a consolidated `stepFinish`/`finish` event with `{normalized, raw}` reason closes each open fragment (`:852-861`). Persistence follows the event definitions: boundaries durable, deltas ephemeral (see §7) — so a reconnect can rebuild the exact transcript but *never* replays the token animation, by design [I].

**Cancel / interrupt.** `SessionExecution.Interface = { active, isActive, resume, wake, interrupt, awaitIdle }` (`packages/core/src/session/execution.ts:17-40`); terminal classification maps success→`succeeded`, `Cause.hasInterrupts`→`interrupted`, else `failed` (`:47-51`). The write-ahead claim is the load-bearing part:

```ts
// packages/core/src/session/execution.ts:73-75
// Write-ahead claim: starting records the durable intent that a turn is in flight, in the
// same transaction as the started event. Terminals release it — except shutdown
// interruption, which preserves the claim
```

Concretely `claim` is a guarded single-row update — the `isNull(time_suspended)` predicate makes a re-claim a zero-row no-op — and it deliberately pins `time_updated` to itself so claim bookkeeping never reorders a session list (`packages/core/src/session/store.ts:208-219`). `release` clears `time_suspended` and `resume_attempts` (`:220-227`); `listSuspended()` returns only top-level sessions still holding a claim (`:197-207`), because background children are resumed through their durable `Job` records instead; `countResume` increments and returns the attempt count (`:242-254`).

**Delivery/steering.** Inbox items are `user | synthetic | compaction | move`, each with `delivery: "steer" | "queue"` (`packages/schema/src/session-inbox.ts:11,46-49`). `compact` is admitted with `delivery: "steer"` (`session/session.ts:256-274`), as is `skill` (which publishes `SessionEvent.Skill.Activated` then `execution.resume`, `:233-255`). Promotability is a real gate used by the loop: `SessionInbox.nextPromotable(db, sessionID, "input")` (`packages/core/src/session/runner/llm.ts:56-71`).

**Revert** is snapshot-based, not log-based: `stage` captures (or reuses) a content-addressed tree, restores per-file trees, diffs, then publishes `SessionEvent.RevertEvent.Staged` (`packages/core/src/session/revert.ts:22-58`); `clear` restores the original trees (`:60-70`). `Snapshot.capture` is *"content-addressed tree"* and returns `undefined` when snapshots are disabled/unsupported — the feature degrades, it does not fail (`packages/core/src/snapshot.ts:43-51`, implemented via `git.tree.capture`, `:132`).

---

## 4. The agent loop

**Entry point:** `SessionRunner.drain({ sessionID, force, continuation?, promotable? })` (`packages/core/src/session/runner/index.ts:31-40`), returning `DrainResult = Complete | Moved{continuation?} | Reloaded{force, continuation?}` (`:23-28`). `Continuation = { step: number }` (`:21`) — the loop is *resumable at a step index*, not restartable-only.

**Four nested loops:**

1. `drain` — session-level: resolve model/context, `plugins.awaitActivation`, `settleStaleCompactions`, `settleStaleToolCalls`, `InstructionState.prepare` (`runner/llm.ts:56-71,195-200`).
2. `runStep` — `while(true)` turn loop (`:203-266`): try `compaction.compact({ reason: "auto", context: loaded })`; on `"completed"` mint a new `assistantMessageID` and continue; enforce the agent's step budget (`loaded.agent.info.steps`); build the request via `SessionModelRequest.baseTranscript` + `context.request.primary({ toolChoice: stepLimitReached ? "none" : undefined, webSocket: "session", inputTokens: SessionCompaction.estimatePrompt(loaded) })`; when the limit is reached, append a synthetic assistant message containing `MAX_STEPS_PROMPT`.
3. `SessionStep` attempt — one provider stream plus tool execution (`runner/step.ts`).
4. `model-retry` — provider-level retry policy (`packages/core/src/model-retry.ts`).

**Continuation conditions** are the `Step.Outcome` tagged enum (`runner/step.ts:32-43`):

| outcome | meaning | loop action |
|---|---|---|
| `Completed{needsContinuation}` | stream ended cleanly | continue if the model left tool calls / unfinished work |
| `Retry{error, decision}` | retryable error | `retry.wait`, same step |
| `Continue{error, decision}` | non-fatal error | publish `SessionEvent.Synthetic`, new assistant message ID (`llm.ts:267-300`) |
| `RecoverFull` | context overflow | compaction path |
| `Compacted` | compaction applied | new assistant message |
| `OutputLimit{needsContinuation}` | hit output cap | *count consecutive `length` finish rows*, then prompt `CONTINUE_AFTER_OUTPUT_LIMIT` |

Two literal prompts drive continuation: `CONTINUE_AFTER_INCOMPLETE_STREAM` and `CONTINUE_AFTER_OUTPUT_LIMIT` (`runner/llm.ts:34-38`).

**Step exhaustion is a hard stop, not a suggestion** [V]: `MAX_STEPS_PROMPT` in `runner/max-steps.ts` says *"CRITICAL - MAXIMUM STEPS REACHED … Tools are disabled until next user input … Any attempt to use tools is a critical violation."*, and the request is built with `toolChoice: "none"` plus only a `MAX_STEPS_PROMPT`-bearing assistant message, so the model literally has no tools to call.

**Tool dispatch.** The request object owns the tool set: `Prepared.executeTool(input) => Effect<Tool.NormalizedResult, ExecuteError>` where `ExecuteError = Tool.Error | Permission.DeclinedError | QuestionTool.CancelledError` (`packages/core/src/session/model-request.ts:58-70`). Parallel tool runs are settled together, collecting `{ interrupted, declines, failure }` (`runner/step.ts:300-320`) — a declined permission is a *first-class result*, not an exception.

**Snapshot capture races the provider request**: `attempt` forks `snapshots.capture()` with `Effect.forkScoped` while the request is in flight (`runner/step.ts:74-76`), so the pre-edit tree is captured without adding latency to the turn.

**Retry policy** (`packages/core/src/model-retry.ts`): clamped `retry-after` at 15 minutes (`:11-19`); schedule is `max(min(exponential "2 seconds", spaced "10 seconds"), recurs 10)` + jitter, total ≈84 s (`:22-36`); `Transport`/`Timeout` errors get `MAX_TIMEOUT_RETRIES = 3` (`:38-40`); `retry(effect)` loops on `Effect.result` (`:63-72`).

**Token/context accounting** is split into *measured* vs *estimated* and never mixes providers:

```ts
// packages/core/src/session/compaction.ts:866-871 (hasMeasuredPrompt)
// only trusts the current provider's measured counts
```

Compaction knobs: `Settings { auto, buffer?, keep }`, default `{ auto: true, keep: 15_000 }` (`:41-47,200`); `SHRINK_STEPS = [0.7, 0.5, 0.35]` for overflow retries (`:93`); `RESERVE_MIN = 16_000`, `RESERVE_SHARE = 0.15`, `UNKNOWN_WINDOW = 200_000`, `IMAGE_TOKEN_ESTIMATE = 1_500`, `PDF_TOKEN_ESTIMATE = 2_000` (`:95-105`). Ceiling: `window = limit.input || limit.context`, minus `max(floor(window*0.15), window >= 32_000 ? 16_000 : 0)` (`:849-856`). `due()` also refuses to fire immediately after a completed compaction and waits for a measured response when the native window is encrypted (`:245-254`). Overflow is special-cased to a *smaller* budget: `min(cap, floor(estimateContext * 0.7))` (`:230-231`). Native compaction exists as a first-class branch (`context.model.compaction?.type === "native"` → `compactNatively`, else `summarize`, `:216-236`) and is refused up front when unsupported (`model-resolver.ts:183-193`).

Request-side output budget (`packages/core/src/session/model-request.ts:87-97`):

```ts
const model = Math.min(limit.output > 0 ? limit.output : OUTPUT_TOKEN_FALLBACK, OUTPUT_TOKEN_MAX)
const requested = kind === "compaction" ? Math.min(model, SUMMARY_OUTPUT_MAX) : model
const room = limit.context - inputTokens.measured - Math.ceil(inputTokens.estimated * (1 + ESTIMATE_ERROR))
return Math.min(requested, Math.max(OUTPUT_TOKEN_MIN, room))
```

with `OUTPUT_TOKEN_FALLBACK 32_000` / `OUTPUT_TOKEN_MAX 256_000` / `SUMMARY_OUTPUT_MAX 32_000` / `OUTPUT_TOKEN_MIN 1_024` / `ESTIMATE_ERROR 0.15` (`:42-56`). Note the estimate is *inflated* by 15 %, and `MAX` (not the model's claim) caps the ask. Oversized images are dropped from the tail of the request with an explicit replacement message rather than truncated silently (`IMAGE_REMOVED`, `:44-45`, triggers at 25 MiB / targets 15 MiB, `:42-43`).

---

## 5. Model / provider layer

**AI SDK 6 wrapping is at `LanguageModelV3`.** `aisdk.ts` imports `LanguageModelV3*` types from `@ai-sdk/provider` (`:5-17`) and exposes `{ hook.sdk, hook.language, runSDK, runLanguage, language(model), model(model) }` (`:237-250`). Models and SDKs are cached per location with a `cacheKey` that substitutes *function identity* for function-valued options (`:254-271`); hook registrations are released via `Scope.addFinalizer` (`:273-288`).

**Transport is hand-built** because AI SDK's transport is not deadline-aware: `wrapSSE(res, ms, ctl)` parses SSE with `makeParser` from `effect/encoding/Sse` and aborts on `"SSE read timed out"` (`:64-100`); a separate `HEADER_TIMEOUT_MESSAGE = "Response headers timed out"` covers pre-stream stalls (`:226`). Defaults: `DEFAULT_TIMEOUT_MS = 300_000`, `DEFAULT_CHUNK_TIMEOUT_MS = 900_000` (`packages/core/src/provider.ts:201,208`).

**Provider abstraction.** `provider.ts` defines `AISDK_PREFIX = "aisdk:"` with `isAISDK`/`aisdk`/`packageName` helpers (`:24-37`) — a provider is either a real npm AI-SDK package or an external `aisdk:` reference. Overlay merging is narrow and typed: `CORE_KEYS = ["chunkTimeout","compaction","encoding","fetch","headerTimeout","timeout","transport"]` (`:187`) with `mergeOverlay`/`mergeHeaders` (`:231-265`). A shared index memoizes model lookups per `Model.Info[]` via `WeakMap` (`:325-339`). The interface notes **"raw model inventories never cross the HTTP API"** (`:315-321`) — a deliberate boundary: core will hand out a `Snapshot`/`available` projection, never the catalog objects.

**Model resolution** (`packages/core/src/model-resolver.ts`):

```ts
Resolved { model: LanguageModel, ref: Ref, capabilities, cost, limit, compaction?, transport?, chunkTimeout? }
// "Durable records and displays must use `ref`, never the API model id"  (:110-127)
```

That comment is the whole design: the *resolved* handle is ephemeral, the *ref* is what may be persisted. Errors are a closed union — `VariantUnavailableError`, `UnsupportedPackageError`, `ModelConfigurationError`, `ModelInitializationError`, `UnresolvedProviderVariablesError`, `UnsupportedCompactionError` (`:15-99`). `fromCatalogModel`/`resolveCatalogModel` branch on `Provider.isAISDK(package)`, run `prepareProviderSettings` + `nativeCredentialSettings`, then `LanguageModel.update(..., { compatibility })` and set `defaults.http = mergeHttpOptions(... Provider.timeouts(configured), encoding)` (`:168-250`).

**Fallback** is a two-stage affair: session-level model selection (`runner/model.ts` raises `ModelNotSelectedError`, `ModelUnavailableError({providerID, modelID})` when the model is absent from the catalog, `:75-91`), then provider-level retry/reroute in `model-retry.ts`. When a session has no model, the resolver runs `resolver.resolve()` (`:75-91`) — i.e. selection is lazy at drain time, not at session creation.

**Variants** are not a generic abstraction; they are ~80 per-package *protocol functions* dispatched on `model.package` (`packages/core/src/variant.ts:29-38,606-686`), with `Support = {type:"effort",values?} | {type:"toggle"} | {type:"budget_tokens",min?,max?}` (`:7-10`). Notable per-family knowledge is hardcoded: `ANTHROPIC_OUTPUT_TOKEN_MAX = 32_000`, `ALIBABA_THINKING_BUDGET_MAX = 64_000` (`:40-45`), a 16 k `manualThinking` budget (`:84-88`), and model-ID regex parsing for Claude families incl. `fable`/`mythos` (`:68-82`). In models.dev terms `default` is the *provider* default, not a variant (`:12-22`). Overlay application is `withVariant` in the resolver: it patches `settings`/`headers`/`body` and treats `"default"` as no variant (`model-resolver.ts:137-161`).

**models.dev** (`packages/core/src/models-dev.ts`): default source `https://models.opencode.ai` (`:280`); a bundled snapshot (`packages/core/src/models-dev/snapshot.txt`, refreshed by `bun run script/update-models-snapshot.ts`) is *"Decoded and normalized once per isolate"* because *"one isolate can host many runtimes (Cloudflare colocates Durable Object instances)"* (`:282-289`). Fetch writes a KV cache best-effort — *"Durable Object SQLite caps values at 2 MB and api.json passed it in Aug 2026"* (`:347-369`), with `bodyDigest` short-circuiting identical bodies so no cache write/invalidation/`Refreshed` event fires (`:305,416-418`), polled via `Schedule.spaced` (`:433`).

---

## 6. Persistence

**Engine:** Drizzle over SQLite, with a *vendored* Effect adapter (`packages/core/src/database/drizzle/`) and runtime-conditional drivers (`sqlite.bun.ts` / `sqlite.node.ts` / `sqlite.workerd.ts`).

**Bootstrap** (`packages/core/src/database/database.ts`): WAL + `synchronous = NORMAL` + `busy_timeout = 5000` + `cache_size = -64000` + passive checkpoint (`:39-45`), `foreign_keys = ON` except on workerd where the platform rejects the pragma (`:46-47`). Migrations run under a **per-file semaphore** so two instances over one file cannot race, while `:memory:` gets its own (`:55-65`); the DB file and its `-wal`/`-shm` sidecars are `chmod 0600` (`:83-95`).

**Migration mechanism** (`packages/core/src/database/migration.ts`) is a replacement for Drizzle's journal:

- If the DB is empty → run `schema.up(tx)` (from `schema.gen.ts`) and *seed* the `migration` table with all known ids in one transaction (`:34-46`).
- If `session`/`session_v2` exists → `applyOnly` (`:29-30`).
- If tables exist but neither session table → die: `"Database is not empty and has no session table"` (`:31`).
- Bootstrap ignores `_`-prefixed tables: *"OpenCode owns the unprefixed table namespace. Embedders sharing this database may own underscore-prefixed tables"* (`:26-28`).
- Legacy installs are upgraded by reading the old `__drizzle_migrations` table and mapping by timestamp prefix; an unmatched timestamp **dies** (`:62-95`).

**Tables (19)** in `packages/core/src/database/schema.gen.ts`: `account_state`, `account`, `control_account`, `credential`, `event_sequence`, `event`, `kv`, `permission`, `project_directory`, `project`, `instruction_blob`, `instruction_entry`, `instruction_state`, `session_inbox`, `session_message`, `session_pending`, `session_v2`, `workspace`, `worktree`. Key shapes:

- `event(id, aggregate_id, seq, created, type, data)` with an FK to `event_sequence(aggregate_id PK, seq, owner_id)` `ON DELETE CASCADE`, plus unique index `event_aggregate_seq_idx(aggregate_id, seq)` (`:61-70` inline DDL region).
- `session_v2` carries `cost`, five token counters (`input/output/reasoning/cache_read/cache_write`), `revert`, `permission`, `agent`, `model`, `time_idle`, `time_viewed`, `idle_outcome`, `time_compacting`, `time_archived`, **`time_suspended` ("execution claim timestamp")**, `resume_attempts` (`packages/core/src/session/sql.ts:22-77`).
- Partial unique index `session_pending_session_compaction_idx … WHERE type = 'compaction'` — compaction de-duplication is a database constraint, not application logic [V, `schema.gen.ts` session_pending block].

**What is persisted vs in memory.** Persisted: events, projections (`session_v2`, `session_message`, `session_inbox`, `session_pending`, instruction blobs/state), jobs, workspaces/worktrees, credentials. In memory only: everything in `State.create` services (`agent`, `provider` model catalog, `config` documents, `managed-policy` statements, `snapshot` editor) plus live `PubSub` channels. `Bus` keeps `sessions = new Map<SessionID, Location.Ref>()` purely as an in-process routing index (`packages/core/src/bus.ts:209`).

**Projection.** `SessionProjector` registers `bus.project(...)` handlers for `SessionEvent.Created` (`.onConflictDoNothing()`; dies `new SessionAlreadyProjected()` if the row is missing), `SessionEvent.Moved`, and `Worktree.Event.Resolved` (`packages/core/src/session/projector.ts:464-491,492-507,512+`). Writes are serialized through `Schema.encodeSync(SessionMessage.Info)` (`:35`) and mutate assistant content with `immer.produce` (`session/message-updater.ts`).

**Migrations count** ~55 files under `packages/core/src/database/migration/`, dated 2026-01 to 2026-10 — the schema is under active churn (e.g. `20260622170816_reset_v2_session_state.ts`, `20260812181746_session_inbox.ts`), and `migration.gen.ts` is generated from them.

---

## 7. Event bus taxonomy and the UI bridge

**Two-class taxonomy, enforced by the schema**, not by convention:

```ts
// packages/schema/src/event.ts:37-48
DurableDefinition  = { type, durability: "durable", durable: { version, aggregate }, … }
EphemeralDefinition = { … durability: "ephemeral" … }
```

Event IDs are `Event.ID = "evt_" + ascending()` (`:9-14`); `DurableEnvelope = { aggregateID, seq, version }` (`:34`); old payloads are upgraded on read (`decodeStoredData` → `SessionLegacy.event`, `bus.ts:75-76`) so durable rows are versioned in place.

**Durable manifest** is a single explicit list: `Event.durableMap([...SessionEvent.DurableDefinitions, Worktree.Event.Resolved])` (`packages/schema/src/durable-event-manifest.ts:8`). Session durable events include `created`, `agent.selected`, `model.selected`, `moved`, `renamed`, `metadata.updated`, `permissions`, `viewed`, `archived/unarchived`, `message.content.updated`, `usage.recorded`, `deleted`, `forked`, `inbox.delivered/enqueued/cancelled/delivery.changed`, `execution.started/succeeded/failed/interrupted`, `instructions.updated`, `synthetic`, `skill.activated`, `shell.started/ended`, `step.started/streamed/ended/failed`, `text.started/ended`, `reasoning.started/ended`, `tool.input.started/ended`, `tool.called/success/failed`, `retry.scheduled`, `compaction.started/ended/failed`, `revert.staged/cleared/committed` (`packages/schema/src/session-event.ts`, c. 48–657).

**Ephemeral** (all high-frequency deltas): `session.usage.updated` (`:181`), `session.text.delta` (`:418`), `session.reasoning.delta` (`:457`), `session.tool.input.delta` (`:501`), `session.tool.progress` (`:534`), `session.compaction.delta` (`:608`).

**Publish path** (`packages/core/src/bus.ts`): durable publish runs inside `Effect.uninterruptible` + `db.transaction`, stamps the next per-aggregate `seq` from `event_sequence`, and dies with `Aggregate mismatch: expected … got …` if the sequence was stolen (`:312-330`). Sequence allocation is `latestSequence`/`reserveSequence` (`:22-49`). `PublishOptions = { id, metadata, location, global, commit }` (`:96-103`) — the inline `commit` hook is how a publisher adds a non-event write to the *same* transaction (used by `project.persist`, `project.ts:198-215`).

**Consumers**: projectors (`bus.project`), UI/HTTP subscribers (`bus.subscribe`), location lifecycle (`location-lifecycle.ts:33-40`), and the logger, which is deliberately tiny — `event-logger.ts` logs exactly `agent.updated`, `provider.updated`, `model.updated`, `command.updated`, `config.updated` (`:1-19`), i.e. only *state-catalog* events, never session traffic.

**Module-cycle engineering**: `bus.configured()` breaks the `bus → location → project → bus` cycle with a deferred `await import("./location.js")` inside `Layer.effect` (`bus.ts:191-197`). This is the single most load-bearing hack in the engine [I].

**Bridge to server/UI** lives in `packages/protocol`: an Effect `HttpApi` of ~25 groups (`packages/protocol/src/api.ts:161-201`), with **middleware placement owned by protocol and concrete context keys injected by server** — *"Protocol owns middleware placement, while Server injects concrete keys so Core service identities stay downstream"* (`:138`). Location-scoped groups get `LocationId` middleware; session-scoped ones get a separate `SessionLocationId`; `Authorization` and `SchemaErrorMiddleware` wrap the whole API (`:200-201`).

The event stream endpoint *is generated from the event definitions*:

```ts
// packages/protocol/src/groups/event.ts:44-53
HttpApiEndpoint.get("event.subscribe", "/api/event", { success: HttpApiSchema.StreamSse({ data: EventSchema }) })
// "Subscribe to native events and plugin RPC events across all server locations.
//  Volatile by contract: a slow consumer overflows and fails the stream, and events
//  during disconnection are missed."
```

`EventSchema` is `Schema.Union([...definitions, rpcEvent, server.connected?])` (`:23-36`) built from `EventManifest.ServerDefinitions` (`event-manifest.ts:70-80`), which is a *subset* (`foundationDefinitions` + `featureDefinitions` + TUI/status/installation/vcs events) of all definitions (`:85-100`). So durability does **not** buy client-delivery guarantees: reconnecting clients must refetch state; the SSE stream is fire-and-forget. `rpc.*` events carry plugin-defined payloads as opaque `Record(String, Unknown)` (`:18-20`), published via `Rpc.emit` onto the Bus with the captured `Location.Ref` (`packages/core/src/rpc.ts:91-103`).

---

## 8. Config resolution

`Config.Interface.entries()` is documented as *"lowest→highest priority"* (`packages/core/src/config.ts:29-45`). The actual assembly order (`:281-290`):

```
managedPolicies → wellknown → global → explicit → direct → project
→ content (OPENCODE_CONFIG_CONTENT) → managedSettings
```

The comment above it (`:246-249`) explains the inversion: managed **settings** load last so they override everything, MDM preferences outrank the system directory, and managed **policies** are split off to load *first* so that file-based authority wins — because policies are checked as a separate decision list, not as merged values.

- **Discovery** (`packages/core/src/config/discovery.ts`): filenames `opencode.json` / `opencode.jsonc` (`:12`); system directory `/Library/Application Support/opencode` (darwin), `%ProgramData%\opencode` (win32), `/etc/opencode` else (`:28-32`); macOS MDM preferences at `/Library/Managed Preferences/<user>/ai.opencode.managed.plist` with the global fallback (`:35-40`), only when darwin and `system !== false` (`:110-117`). The project walk ascends from `location.directory` collecting `.claude`, `.agents`, `.opencode`, `opencode.jsonc`, `opencode.json` (`:77-94`) and filters global roots back out (`:101-108`) — so `.claude` and `.agents` are first-class config sources, not compatibility afterthoughts.
- **Parsing**: JSONC with `allowTrailingComma: true`, invalid documents logged and skipped (`config.ts:110-121`). Validation is `Schema.decodeUnknownOption(Info, { errors: "all", onExcessProperty: "ignore" })` (`:108-109`) — **unknown keys are ignored, not errors**, which is what allows forward-compatible config in a churning schema.
- **Managed settings** are read by shelling out to `plutil -convert json` and stripping MDM payload keys (`:196-209`, `discovery.ts:43-50`).
- **Managed policy** (`packages/core/src/managed-policy.ts`): `State { statements, organization? }`; `Interface.current()` is **synchronous** because it is consulted on hot permission paths; *"statements never merge across connections"* (`:17-23`). `statements(entries, organization)` orders config-file policies first (reversed) then console/org policies, each with its own denial message (`:50-64`); `decision()` uses `findLast` + `Wildcard.match` and **defaults to `"allow"`** (`:66-74`).
- **Runtime reload** is serialized: `reloadLock`/`updateLock` are two `Semaphore`s (`:94-108`), and `State.Transformable.reload` is the notification path (`state.ts:29-32`).

---

## 9. Genuinely notable / unusual architecture

1. **A bespoke DI graph with tag-grouped subgraphs.** `LayerNode` + `makeLocationNode`/`makeGlobalNode` + a `shared` memo map is not an Effect pattern you find in the wild; it exists to make "one process, many working directories" cheap, and it buys cycle detection, duplicate-wiring rejection and typed output contexts (`layer-node.ts:259-330`).
2. **Durability is per-event-class, chosen for token economy.** Only boundaries are durable; deltas are ephemeral. The transcript is a *projection*, so streaming is a view concern with zero storage cost.
3. **Write-ahead execution claims** instead of a job queue: `time_suspended` + `resume_attempts` on the session row, claim-preserving on shutdown, `listSuspended()` on boot (`store.ts:66-85`). Crash recovery is a property of the data model.
4. **Turn boundaries are transcript facts** (`Idle` messages), which is why "steered" prompts join the current turn without any extra bookkeeping (`session-message.ts:285-296`).
5. **The HTTP API shape is derived from event definitions** (`protocol/groups/event.ts:23-36`), and the SSE stream is documented as lossy by contract — an unusually honest interface contract for an agent UI.
6. **Managed policy is evaluated, not merged**, synchronously, with `allow` as the default and `findLast` ordering: config files beat console/org statements (`managed-policy.ts:50-74`).
7. **Migrations were rewritten off Drizzle** into an owned journal with bootstrap-vs-apply and legacy seeding, and the schema reserves `_`-prefixed tables for embedders (`migration.ts:20-95`).
8. **One source tree, three runtimes** via package `imports` conditions, with workerd-specific correctness fixes called out inline (no shared semaphore across Durable Objects, no `PRAGMA foreign_keys`) (`package.json:32-76`, `database.ts:28-32,46-47`).
9. **The AI SDK is wrapped below the agent abstraction** (`LanguageModelV3.doStream` → `LLMEvent`), with transport deadlines, SSE parsing and finish-reason normalization owned by core (`aisdk.ts:64-100,722-747,852-861`). Similarity to Vercel's higher-level loop is avoided on purpose.
10. **Function identity participates in cache keys** for model/SDK instances (`aisdk.ts:254-271`) — options that are closures cannot be serialized, so the cache keys on object identity instead.
11. **Compaction prefers cache-preserving resend** over aggressive summarization, and can hand the whole job to the provider (native window) (`compaction.ts:216-236`).
12. **A `noopLayer` exists for callers that only need durable recording** (`execution.ts:182-192`) — an explicit acknowledgement that some hosts want event-sourcing without live execution.

**Limitations of this report [V/I]:** claims about *why* a design was chosen are inferred from code comments and structure; no build or test was run. Files read but not cited in detail (e.g. `session/history.ts`, `session/context.ts`, `session/usage.ts`, `session/title.ts`, `session/run-coordinator.ts`, `tool.ts`, `repository*.ts`, `worktree.ts`, `location-activity.ts`) are summarised via their consumers here, so per-file responsibility rows below are partly inferred.

---

## Module → responsibility

| Module | Responsibility | Key evidence |
|---|---|---|
| `instance.ts` | composition root: all nodes, compile, finalizer, boot timing | `instance.ts:60-181` |
| `instance/service.ts` | `Instance.provide(session)` service-bundle swap | `instance/service.ts:11-15` |
| `location.ts` / `location-lifecycle.ts` | per-directory services; reactivation on durable events; ordered shutdown | `location.ts:11-39`, `location-lifecycle.ts:33-56` |
| `bus.ts` | durable sequences, publish-in-transaction, projectors, PubSub tiers, deferred-import cycle break | `bus.ts:22-49,191-197,312-330` |
| `event-logger.ts` | logs only catalog events | `event-logger.ts:1-19` |
| `session/session.ts` | session handle API; publishes events instead of writing | `session/session.ts:410-435` |
| `session/execution.ts` | write-ahead claim, interrupt/terminal classification | `execution.ts:47-51,73-75` |
| `session/store.ts` | reads + claim/release/resume accounting | `store.ts:66-85,208-254` |
| `session/inbox.ts` | `steer`/`queue` delivery, per-session serialization | `inbox.ts:44-63` |
| `session/projector.ts` | durable event → `session_v2`/`session_message` rows; fork renumbering | `projector.ts:35,49,192,464-507` |
| `session/message-updater.ts` | `immer` part mutation for assistant/shell/compaction | `message-updater.ts` (adapter) |
| `session/runner/llm.ts` | drain loop, step budget, compaction trigger, outcome match | `runner/llm.ts:56-71,203-300` |
| `session/runner/step.ts` | one attempt: stream + parallel tools + snapshot fork | `runner/step.ts:32-43,74-76,300-320` |
| `session/runner/model.ts` | session model selection/errors | `runner/model.ts:75-96` |
| `session/compaction.ts` | ceiling math, shrink steps, summary vs native | `compaction.ts:93-105,213-236,849-871` |
| `session/model-request.ts` | `baseTranscript`, `outputLimit`, media trimming, tool executor | `model-request.ts:87-119,58-70` |
| `model-resolver.ts` | catalog→`LanguageModelV3`, variants, ref-vs-id rule | `model-resolver.ts:110-250` |
| `model-retry.ts` | retry schedule + `retry-after` clamp + timeout retries | `model-retry.ts:11-72` |
| `aisdk.ts` | AI SDK 6 adapter, SSE/header deadlines, `LLMEvent` normalization | `aisdk.ts:64-100,226,722-861` |
| `provider.ts` | provider packages/overlays, timeouts, no-raw-inventory boundary | `provider.ts:24-37,187,315-321` |
| `variant.ts` | ~80 per-package reasoning protocols | `variant.ts:7-45,606-686` |
| `models-dev.ts` | catalog fetch/cache/normalize, per-isolate snapshot | `models-dev.ts:280-289,347-369` |
| `config.ts` / `config/discovery.ts` | JSONC sources, precedence, MDM | `config.ts:246-290`, `discovery.ts:12-117` |
| `managed-policy.ts` | enterprise statements, synchronous decision, default allow | `managed-policy.ts:17-74` |
| `state.ts` | replayable transform/reload state containers | `state.ts:29-32,87-111` |
| `snapshot.ts` | content-addressed git-tree capture/restore/diff | `snapshot.ts:43-56,132` |
| `session/revert.ts` | staged revert via snapshots + durable revert events | `revert.ts:22-70` |
| `project.ts` / `workspace.ts` | project identity (git remote hash), activation sweeps, workspace lifecycle | `project.ts:39-61,282-300`, `workspace.ts:20-53` |
| `job.ts` | background jobs with `shell`/`subagent` recovery | `job.ts:9-38` |
| `rpc.ts` | plugin RPC registrations → bus events | `rpc.ts:16-21,91-103` |
| `database/database.ts` / `migration.ts` | pragmas, per-file lock, owned journal | `database.ts:28-95`, `migration.ts:20-95` |
| `packages/protocol` | HttpApi groups + generated SSE event schema | `api.ts:138-201`, `groups/event.ts:23-53` |
| `packages/schema` | event/message/inbox definitions + durable manifest | `event.ts:37-48`, `session-message.ts:197-240`, `durable-event-manifest.ts:8` |
