## 9. Genuinely notable / unusual architecture

1. **A bespoke DI graph with tag-grouped subgraphs.** `LayerNode` + `makeLocationNode`/`makeGlobalNode` + a `shared` memo map is not an Effect pattern you find in the wild; it exists to make "one process, many working directories" cheap, and it buys cycle detection, duplicate-wiring rejection and typed output contexts (`layer-node.ts:259-330`).
2. **Durability is per-event-class, chosen for token economy.** Only boundaries are durable; deltas are ephemeral. The transcript is a *projection*, so streaming is a view concern with zero storage cost.
3. **Write-ahead execution claims** instead of a job queue: `time_suspended` + `resume_attempts` on the session row, claim-preserving on shutdown, `listSuspended()` on boot (`store.ts:66-85`). Crash recovery is a property of the data model.
4. **Turn boundaries are transcript facts** (`Idle` messages), which is why "steered" prompts join the current turn without any extra bookkeeping (`session-message.ts:285-296`).
5. **The HTTP API shape is derived from event definitions** (`protocol/groups/event.ts:23-36`), and the SSE stream is documented as lossy by contract â€” an unusually honest interface contract for an agent UI.
6. **Managed policy is evaluated, not merged**, synchronously, with `allow` as the default and `findLast` ordering: config files beat console/org statements (`managed-policy.ts:50-74`).
7. **Migrations were rewritten off Drizzle** into an owned journal with bootstrap-vs-apply and legacy seeding, and the schema reserves `_`-prefixed tables for embedders (`migration.ts:20-95`).
8. **One source tree, three runtimes** via package `imports` conditions, with workerd-specific correctness fixes called out inline (no shared semaphore across Durable Objects, no `PRAGMA foreign_keys`) (`package.json:32-76`, `database.ts:28-32,46-47`).
9. **The AI SDK is wrapped below the agent abstraction** (`LanguageModelV3.doStream` â†’ `LLMEvent`), with transport deadlines, SSE parsing and finish-reason normalization owned by core (`aisdk.ts:64-100,722-747,852-861`). Similarity to Vercel's higher-level loop is avoided on purpose.
10. **Function identity participates in cache keys** for model/SDK instances (`aisdk.ts:254-271`) â€” options that are closures cannot be serialized, so the cache keys on object identity instead.
11. **Compaction prefers cache-preserving resend** over aggressive summarization, and can hand the whole job to the provider (native window) (`compaction.ts:216-236`).
12. **A `noopLayer` exists for callers that only need durable recording** (`execution.ts:182-192`) â€” an explicit acknowledgement that some hosts want event-sourcing without live execution.

**Limitations of this report [V/I]:** claims about *why* a design was chosen are inferred from code comments and structure; no build or test was run. Files read but not cited in detail (e.g. `session/history.ts`, `session/context.ts`, `session/usage.ts`, `session/title.ts`, `session/run-coordinator.ts`, `tool.ts`, `repository*.ts`, `worktree.ts`, `location-activity.ts`) are summarised via their consumers here, so per-file responsibility rows below are partly inferred.

---

## Module â†’ responsibility
