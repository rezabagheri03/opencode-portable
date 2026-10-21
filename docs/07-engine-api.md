# 07 — Engine API contract

What the phone bridge and the watch are allowed to assume about the engine's HTTP surface.

Everything here is **[V]** — read from `opencode-src` at the pinned version, with `path:line`. This
document exists because [02-architecture.md](02-architecture.md#event--screen-mapping) listed the real
event names as `[I]` and deferred them to P3-1. They are now known, so the watch bridge can be built
against a contract instead of a guess.

**Scope:** the three things the watch needs — *what is happening*, *wait for it*, *answer it* — plus
authentication and the event stream those ride on.

---

## 1. Authentication

| Mechanism | Accepted | Notes |
|---|---|---|
| `?auth_token=<base64 "opencode:PASSWORD">` | yes | query param, decoded at `packages/server/src/middleware/authorization.ts:32-39` |
| `Authorization: Basic <base64 "opencode:PASSWORD">` | yes | same decoder; **username must be exactly `opencode`** (`packages/server/src/auth.ts:33`) |
| **Bearer token** | **no** | not implemented — do not design around it |
| Session cookie | yes | HMAC token, 30-day TTL (`auth.ts:17,40-55`); only honoured same-origin (`middleware/authorization.ts:58-71`) |

**Answer to open question #4: yes, a non-browser client works cleanly.** CORS returns `true` when
there is no `Origin` header (`packages/server/src/cors.ts:11-12`), so a native client is never
blocked. Send Basic auth and nothing else.

**Password generation** (`packages/cli/src/server-process.ts:84-94`): `serve` reads
`OPENCODE_PASSWORD` (legacy `OPENCODE_SERVER_PASSWORD` also honoured, `packages/cli/src/env.ts:8-11`);
if neither is set it **generates a random 32-byte password**. Our launcher sets it explicitly, so the
password is stable across restarts and survives in app-private storage.

**[V]** This confirms [ADR-012](../docs/04-decisions.md#adr-012). Two additions worth noting:

- Because the password is ours and stable, `issueSession` remains valid across restarts, so a session
  cookie or pairing token is an *alternative* to re-supplying Basic auth — not required.
- Upstream already implements a pairing flow: `POST /api/pair` → `{code, expires_in}` (5-minute,
  single-use), redeemed at `GET /auth/connect/:code` → `{token}` for JSON clients
  (`packages/protocol/src/groups/server.ts:33,54`; `packages/server/src/pairing.ts`). This is not
  needed for the watch, which talks to the Kotlin bridge rather than the engine, but it is the
  cleanest way to hand a desktop-sized client access to the phone's engine later.

---

## 2. How the engine is addressed

The engine binds `127.0.0.1:4096` inside the proot rootfs and is reached over loopback. Two shapes:

- **Location-scoped** endpoints take the working directory as a query param
  `?location[directory]=<abs path>` or the header `x-opencode-directory`
  (`packages/server/src/location.ts:71-79`). Default is the process CWD.
- **Session-scoped** endpoints resolve the session's own stored location and **ignore** that param
  (`packages/server/src/middleware/session-location.ts:25`).

The watch bridge only ever uses session-scoped endpoints, so it never has to know the workspace path.

---

## 3. The three watch operations

### Wait / observe

```
GET  /api/session/:id/message        messages, ?limit&order&cursor
GET  /api/session/active             {data: {sessionID: {type:"running"}}}   ← resync after reconnect
POST /api/experimental/session/:id/wait      long-poll until idle
POST /api/session/:id/interrupt              stop execution
```

`session.active` is the reconnect primitive: the web GUI itself calls it on every `server.connected`
event to rehydrate busy state (`packages/client/src/solid/data.ts:638-645`). **The bridge does the
same** — after any event-stream gap, ask `session.active` rather than trusting accumulated state.

### Answer

```
POST /api/session/:sessionID/permission/:requestID/reply
body: {decision: "once" | "always" | "reject", message?: string}   → 204
```

`Permission.Reply` is exactly `once | always | reject` — there is no separate "deny" spelling
(`packages/schema/src/permission.ts:42`). Note the asymmetry with `Permission.Effect`, which is
`allow | deny | ask` (`:56`): the *reply* says `reject`, the *rule* says `deny`.

### Send

```
POST /api/session/:sessionID/prompt
body: {text, files?, agents?, skills?, metadata?, delivery?: "steer"|"queue", resume?}
→ {data: SessionInbox.User}
```

(`packages/protocol/src/groups/session.ts:335-345`.) The response is an inbox receipt, **not** agent
output. Everything the agent says arrives on the event stream.

---

## 4. Event stream

```
GET /api/event   →  SSE, frames are `data: <json>\n\n`
```

`packages/protocol/src/groups/event.ts:44`, `packages/server/src/event-feed.ts:27`.

**Volatile by contract.** No `Last-Event-ID`, no replay; a slow consumer overflows at 4096 queued
frames and the stream *fails* (`event-feed.ts:14,66-71`). A 15 s `:` heartbeat comment is sent
(`packages/server/src/handlers/event.ts`). Treat every reconnect as "state unknown until resynced".

Envelope: `{id, type, created, data, location?: {directory, workspaceID}, metadata?}`
(`packages/schema/src/event.ts:67`).

### Events that drive the watch

| Watch state | Event | Payload that matters |
|---|---|---|
| started working | `session.execution.started` | `sessionID` (`session-event.ts:254`) |
| finished | `session.execution.succeeded` | |
| failed | `session.execution.failed` | `error` |
| stopped | `session.execution.interrupted` | `reason: user \| shutdown \| superseded \| inactivity` |
| **what it is doing** | `session.tool.called` | `name`, `input`, `executed` (`:522`) |
| tool still running | `session.tool.progress` | live `metadata` (ephemeral) |
| **needs me** | `permission.asked` | full `Permission.Request` (`permission.ts:45`) |
| answered | `permission.replied` | `sessionID, requestID, reply` |

**Deriving "editing `auth.ts`"**: switch on `session.tool.called`'s `name` — `edit`/`write` →
`input.path`, `shell` → `input.command` and `input.workdir`, `grep` → `input.pattern`.

**Caution:** `session.status` and `session.idle` are declared in the schema but the inventory found
**no publisher in core**. Build the phase machine from `session.execution.*` and
`session/active`, which are confirmed writers.

---

## 5. What this changes in the design

1. **[02 §Event → screen mapping]** stops being a guess. The mapping above is the contract.
2. **The bridge's derived state** is now specified, not sketched:
   `{phase: idle|running|waiting, activity: "editing auth.ts", pendingDecision, elapsed}`.
3. **Reconnect is now a defined sequence**, not an aspiration: connect SSE → on `server.connected`
   → `GET /api/session/active` → repopulate. This is why US-12 ("watch reconnects on its own") has a
   concrete implementation.
4. **No custom engine endpoint is needed.** Assumption A-4 in [05-risks.md](05-risks.md) — "the Kotlin
   bridge can derive watch-worthy state from the existing SSE stream" — is **confirmed**, which keeps
   [ADR-009](04-decisions.md#adr-009)'s "never fork the core" intact.

## 6. Still open

- **Does the event stream include events for sessions in *other* locations?** `GET /api/event`
  subscribes "across all server locations", and the GUI filters client-side by
  `event.location.directory`. The bridge must apply the same filter or it will narrate another
  workspace's work. Behaviour confirmed in the client, not yet observed on the server side.
- **Long-poll vs SSE for the watch.** `POST .../wait` blocks until idle, which is ideal for the
  watch's "tell me when it's done" — but it returns nothing if a permission prompt appears, which is
  the case the watch must wake for. The bridge should use SSE and treat `wait` as a fallback.
- **Event volume under a real task** is unmeasured. 4096-frame overflow is the failure mode to test
  in P2-4.
