## 4. The agent loop

**Entry point:** `SessionRunner.drain({ sessionID, force, continuation?, promotable? })` (`packages/core/src/session/runner/index.ts:31-40`), returning `DrainResult = Complete | Moved{continuation?} | Reloaded{force, continuation?}` (`:23-28`). `Continuation = { step: number }` (`:21`) â€” the loop is *resumable at a step index*, not restartable-only.

**Four nested loops:**

1. `drain` â€” session-level: resolve model/context, `plugins.awaitActivation`, `settleStaleCompactions`, `settleStaleToolCalls`, `InstructionState.prepare` (`runner/llm.ts:56-71,195-200`).
2. `runStep` â€” `while(true)` turn loop (`:203-266`): try `compaction.compact({ reason: "auto", context: loaded })`; on `"completed"` mint a new `assistantMessageID` and continue; enforce the agent's step budget (`loaded.agent.info.steps`); build the request via `SessionModelRequest.baseTranscript` + `context.request.primary({ toolChoice: stepLimitReached ? "none" : undefined, webSocket: "session", inputTokens: SessionCompaction.estimatePrompt(loaded) })`; when the limit is reached, append a synthetic assistant message containing `MAX_STEPS_PROMPT`.
3. `SessionStep` attempt â€” one provider stream plus tool execution (`runner/step.ts`).
4. `model-retry` â€” provider-level retry policy (`packages/core/src/model-retry.ts`).

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

**Step exhaustion is a hard stop, not a suggestion** [V]: `MAX_STEPS_PROMPT` in `runner/max-steps.ts` says *"CRITICAL - MAXIMUM STEPS REACHED â€¦ Tools are disabled until next user input â€¦ Any attempt to use tools is a critical violation."*, and the request is built with `toolChoice: "none"` plus only a `MAX_STEPS_PROMPT`-bearing assistant message, so the model literally has no tools to call.

**Tool dispatch.** The request object owns the tool set: `Prepared.executeTool(input) => Effect<Tool.NormalizedResult, ExecuteError>` where `ExecuteError = Tool.Error | Permission.DeclinedError | QuestionTool.CancelledError` (`packages/core/src/session/model-request.ts:58-70`). Parallel tool runs are settled together, collecting `{ interrupted, declines, failure }` (`runner/step.ts:300-320`) â€” a declined permission is a *first-class result*, not an exception.

**Snapshot capture races the provider request**: `attempt` forks `snapshots.capture()` with `Effect.forkScoped` while the request is in flight (`runner/step.ts:74-76`), so the pre-edit tree is captured without adding latency to the turn.

**Retry policy** (`packages/core/src/model-retry.ts`): clamped `retry-after` at 15 minutes (`:11-19`); schedule is `max(min(exponential "2 seconds", spaced "10 seconds"), recurs 10)` + jitter, total â‰ˆ84 s (`:22-36`); `Transport`/`Timeout` errors get `MAX_TIMEOUT_RETRIES = 3` (`:38-40`); `retry(effect)` loops on `Effect.result` (`:63-72`).

**Token/context accounting** is split into *measured* vs *estimated* and never mixes providers:

```ts
// packages/core/src/session/compaction.ts:866-871 (hasMeasuredPrompt)
// only trusts the current provider's measured counts
```

Compaction knobs: `Settings { auto, buffer?, keep }`, default `{ auto: true, keep: 15_000 }` (`:41-47,200`); `SHRINK_STEPS = [0.7, 0.5, 0.35]` for overflow retries (`:93`); `RESERVE_MIN = 16_000`, `RESERVE_SHARE = 0.15`, `UNKNOWN_WINDOW = 200_000`, `IMAGE_TOKEN_ESTIMATE = 1_500`, `PDF_TOKEN_ESTIMATE = 2_000` (`:95-105`). Ceiling: `window = limit.input || limit.context`, minus `max(floor(window*0.15), window >= 32_000 ? 16_000 : 0)` (`:849-856`). `due()` also refuses to fire immediately after a completed compaction and waits for a measured response when the native window is encrypted (`:245-254`). Overflow is special-cased to a *smaller* budget: `min(cap, floor(estimateContext * 0.7))` (`:230-231`). Native compaction exists as a first-class branch (`context.model.compaction?.type === "native"` â†’ `compactNatively`, else `summarize`, `:216-236`) and is refused up front when unsupported (`model-resolver.ts:183-193`).

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
