## Executive summary

1. `packages/core` is a *source-only* package (`"exports": { "./*": "./src/*.ts" }`, `packages/core/package.json:30`) built entirely on **Effect 4.0.1** (`package.json:82`) with a hand-rolled dependency-injection DSL (`LayerNode`) instead of Effect's `Layer` composition.
2. Two DI tag groups split the graph: **global** (process-wide: database, bus, project registry, session store/execution) and **location** (per-working-directory: providers, agents, models, config, tools). `LayerNode.compile(graph, { shared: Node.tags.values.global })` (`packages/core/src/instance.ts:161`) acquires globals once and rebuilds location services per directory.
3. State changes are **events**, never direct writes: every session mutation calls `bus.publish(SessionEvent.X, â€¦)` (e.g. `packages/core/src/session/session.ts:61-80`); projectors do the SQL inside the publish transaction (`packages/core/src/bus.ts:312-330`).
4. Durability is **hybrid and deliberate**: step/tool/message boundaries are durable event-sourced rows; token deltas (`session.text.delta`, `session.reasoning.delta`, `session.tool.input.delta`, `session.tool.progress`, `session.compaction.delta`) are ephemeral PubSub-only (`packages/schema/src/session-event.ts:418,457,501,534,608`). Transcripts are *reconstructed*, not log-replayed.
5. A "part" is not a table. An assistant message row carries a `content[]` array of `text | reasoning | tool` parts (`packages/schema/src/session-message.ts:197-200`), mutated in place with `immer` (`packages/core/src/session/message-updater.ts`).
6. The turn loop is a write-ahead-claim state machine: claim on start (`time_suspended`, `resume_attempts`), release on terminal event, *preserve* the claim on shutdown so a crash is recoverable (`packages/core/src/session/store.ts:66-85,208-227`).
7. AI SDK 6 is wrapped one level lower than usual â€” at `LanguageModelV3`/`doStream`, normalized into an internal `LLMEvent` stream (`packages/core/src/aisdk.ts:722-747`), with transport guards (header timeout, SSE inter-chunk deadline) implemented locally (`:64-100`, `:226`).
8. Context accounting is conservative and cache-friendly: it prefers resending the full conversation unchanged, only trusts the *current provider's* measured counts (`packages/core/src/session/compaction.ts:866-871`), and supports provider-native compaction windows instead of summaries.
9. The HTTP/UI surface is derived from the event definitions themselves: the SSE endpoint's wire schema is generated as a `Schema.Union` of ~all event definitions (`packages/protocol/src/groups/event.ts:23-36`), documented as *"Volatile by contract"* â€” no replay, no resume (`:51`).
10. Migrations abandon Drizzle's journal for an owned `migration` table, bootstrap-or-apply (`packages/core/src/database/migration.ts:20-52`), and reserve the `_`-prefixed table namespace for embedders (`:26-28`).

---

## 1. Effect-TS usage: DI graph, not plain Layers
