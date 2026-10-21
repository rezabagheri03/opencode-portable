# opencode v2.1.1 — `packages/core` Capability Layer

**Repo:** `E:\Projects\opencode portable\opencode-src` · **HEAD:** `0718864d49a5241e7df5b302b35caed2518171a0` (2026-10-20, bun monorepo)
**Scope:** the *capability* half of `packages/core/src` — tools, shell/PTY, MCP, permissions, plugins, skills/instructions, VCS/snapshots, search.
**Evidence convention:** every non-obvious claim carries `path:line`. Claims marked **[inferred]** are deduced from surrounding structure, not read verbatim. Everything else was read in the file.

---

## Executive summary

1. Tools are **Effect `Layer`-scoped registrations**, not a static registry. `Tool.Service.snapshot(permissions)` materializes an immutable `{definitions, codeModeCatalog?, execute}` per turn (`tool.ts:227`); the registry itself is a mutated draft handed to `tool.transform(...)` by every plugin.
2. **Tool filtering is catalog visibility, not authorization.** `tool/AGENTS.md` states it explicitly; authorization happens at each tool leaf via `Permission.assert`. An agent that cannot *see* `write` can still be scripted into it through MCP or shell.
3. Almost everything the model calls goes through **CodeMode** (`execute`), a hand-written JS interpreter. Direct tools are only those registered `options:{codemode:false}` — `read`, `write`, `edit`, `patch`, `glob`, `grep`, `shell`, `todowrite`, `question`, `skill`, `subagent`, `webfetch`, `websearch`.
4. The CodeMode catalog is **token-budgeted** (`INLINE_BUDGET = 2_000` chars, `codemode/catalog.ts:50`): namespaces are always shown, full listings are selected round-robin by cost until the budget dies. The model may be **unaware** of most tools and must use the in-interpreter `search` builtin to discover them.
5. Permissions are a **`findLast` wildcard match over concatenated rulesets with a default of `ask`** (`permission.ts:87`). Deny short-circuits before saved "always" allows (`:175`), so an agent deny beats a persisted grant — but a persisted allow **does** override a configured `ask`.
6. Shell approval persists **prefix wildcards derived from a tree-sitter parse** (`git commit *`, `npm run *`) — `shell/parse.ts:196-199`. Anything the parser cannot understand degrades to a 100 KB hand-written scanner (`shell/scan.ts`), and opaque results mean "ask for the whole command text".
7. External-directory approval persists an allow for the **enclosing project root**, not the approved directory: `save: <projectRoot>/*` (`file-access.ts:120`). Approving one file outside the cwd can grant a whole tree.
8. Plugins run **in-process with full ambient authority** — `permission.hook("evaluate")` can rewrite any decision to `allow` (`permission.ts:179-188`), and only two plugin IDs are removal-guarded (`plugin/internal.ts:276`).
9. Snapshots are a **shadow git repo** (`global.data/snapshot/<projectID>/<hash(worktree)>`) whose tree OIDs are the snapshot IDs; per-turn attribution joins the first `snapshot.start` to the last `snapshot.end` (`session/diff.ts:106-136`).
10. `file-mutation.ts` carries an explicit TODO list confirming **no undo, no watcher events, no multi-file transactions** (`:126-134`) — revert is snapshot-based, mutation tracking is not.

---

## 1. Tool definition, registration, inventory, selection

### Registration is a mutable draft, not a table

Every capability plugin receives a `ToolEditor` and mutates a draft:

```ts
// tool/plugin/write.ts:59
options: { codemode: false, permission: "edit" },
```

`Options` is exactly `{namespace?, permission?}` plus a `codemode`/`pinned` discriminant (`packages/schema/src/tool.ts:27-42`). `permission` is a *string* — a permission **action** name the leaf will assert; it is not an enforcement mechanism by itself.

From `packages/core/src/tool/AGENTS.md` (authoritative design notes):
- last valid registration wins; disposing a scope re-reveals the shadowed tool
- "Tool filtering is catalog visibility, not execution authorization"
- `edit`, `write`, and `patch` all resolve to the single `edit` permission action

### Snapshot + dispatch

`tool.ts:227` builds a per-turn snapshot; `tool.ts:264` replays the `tool.execute.before` hook chain before dispatching; `tool.ts:293` has `whollyDisabled(action, rules)` = a rule with `resource === "*" && effect === "deny"` (used to fully remove an action from the inventory). `"execute"` is a reserved name — registering it produces `registrationError` (`tool.ts:314-315`).

`tool/runtime.ts` is the untrusted boundary:
- `:21` `definition()` → `{type, name, description, inputSchema, outputSchema}`
- `:29` `execute()` enforces the declared `Tool.Error` contract (`:32-45` carries the comment explaining why: a tool that throws a non-declared error must not corrupt the turn)
- `:276` `normalizedName` strips everything outside `[a-zA-Z0-9_-]`; `:278` `effectiveName` = `namespace.replaceAll(".","_") + "_" + name`

So an MCP server named `my server!` exposing tool `do.thing` surfaces as `my_server__do_thing` — **collisions are possible after sanitization** [inferred from `tool/mcp.ts:16-17` + `runtime.ts:276-278`, no de-duplication seen].

### Selection: two different paths

| Path | Condition | Where |
|---|---|---|
| Direct tool in the model's tool list | `options.codemode === false` | `tool.ts:278` |
| CodeMode catalog entry | default (`codemode` undefined/true) | `tool/mcp.ts:45`, `codemode/catalog.ts` |
| Removed entirely | `whollyDisabled(tool.options?.permission ?? name, rules)` | `tool.ts:233,293` |

`tool.ts:239` is the subtle one: `const codeModeEnabled = !whollyDisabled("execute", rules)` — denying the `execute` action removes CodeMode as a **whole**, which is also the only supported way to force an all-direct-tools agent.

### The catalog is budgeted

```ts
// codemode/catalog.ts:48-55
const DESCRIPTION_LIMIT = 120
const CHARACTERS_PER_TOKEN = 4
const INLINE_BUDGET = 2_000
const budget = options.budget ?? INLINE_BUDGET
```

`catalog.ts:52-53` states the policy: *"Keep every namespace visible, then select full listings one per namespace per round, considering shorter listings first until the inline budget is exhausted."* `:89-90` splits listings into `selectionOrder` (ranked, budgeted) and `selectedListings` (always shown). `pinned` tools are pre-seeded into `selectedListings` (`:79-84`), and only `opencode.*` tools set it (`tool/plugin/opencode.ts:136,160,174,187`).

The `execute` tool's description tells the model how to cope (`codemode/tool.ts:74`):
> "the only callable tools are those explicitly listed in the Code Mode catalog instructions or returned by the `search` function"

`search` is a real interpreter builtin backed by a `searchIndex` (`packages/codemode/src/tool-runtime.ts:379,447`), not an LLM-side convention.

**Consequence:** in CodeMode the model's tool surface is a *sampled view*. Namespace discovery is unbounded by design, with a `TODO` to bound it (`catalog.ts:96`).

### Built-in tool inventory

| Tool (model-facing) | Purpose | Notable flags |
|---|---|---|
| `read` | Read files, images, dirs | `codemode:false`; `access.authorizeRead` (`read.ts:42,55`); post-read AGENTS.md injection (`:82-107`) |
| `write` | Create/overwrite files | `codemode:false, permission:"edit"` (`write.ts:59`); asserts `edit` (`:79`) |
| `edit` | Exact-match string replace | `permission:"edit"` (`edit.ts:221`); unique-match required (`:289-293`); formatter after write (`:307-309`) |
| `patch` | Apply unified diff | `permission:"edit"` (`patch.ts:81`); asserts at `:197` |
| `glob` | Filename search | `codemode:false` (`glob.ts:58`); ripgrep `--files` |
| `grep` | Content search | `codemode:false` (`grep.ts:77`); ripgrep `--json` |
| `shell` | Run a command | `codemode:false`; **no `permission` option** — asserts per-parsed-resource inside (`shell.ts:138-145`) |
| `todowrite` | Session todo list | `codemode:false` (`todo.ts:57`) |
| `question` | Ask the user | `codemode:false`; asserts `question` (`question.ts:65`) |
| `skill` | Load a skill | `codemode:false` (`skill.ts:42`); deny-filtered in `skill.ts:33-34` |
| `subagent` | Spawn a child session | `codemode:false` (`subagent.ts:104`); Job-backed, recoverable |
| `webfetch` | HTTP(S) GET → text/markdown/html | `codemode:false` (`webfetch.ts:113`); 30 s default / 120 s max (`:14-15`) |
| `websearch` | Provider-routed web search | `codemode:false` (`websearch.ts:43`) |
| `execute` | **CodeMode entry point** | Registered per-snapshot, not via `draft.add` (`codemode/tool.ts:85`) |
| `opencode_session_rename`, `opencode_session_move`, `opencode_worktree_create`, `opencode_worktree_list`, `opencode_worktree_remove`, `opencode_models`, `opencode_list_mcp_resources`, `opencode_read_mcp_resource` | Self-management | namespace `opencode` (`opencode.ts:105-109`); `pinned:true`; `worktree_remove` reuses `permission:"edit"` (`:187,193`); MCP resource tools use actions `opencode_list_mcp_resources` / `opencode_read_mcp_resource` (`mcp-resource.ts:37,66`) |
| `<server>_<tool>` | Every MCP tool | `options:{namespace, codemode: tool.codemode !== false}` (`tool/mcp.ts:45`); per-tool permission action, `resources:["*"], save:["*"]` (`:51-63`) |

---

## 2. Permissions and the safety model

### The matcher

```ts
// permission.ts:87  (evaluate)
// findLast over rulesets; default { effect: "ask" }
```

Effects are `allow | ask | deny`. The rule set is built in `configured` (`:158`) as agent permissions merged with session permissions, and `missingAgentPermissions` is `[{action:"*", resource:"*", effect:"deny"}]` (`:19`) — i.e. an agent with no rules gets deny-all by construction, not allow-all.

`evaluateInput` (`:173-189`) is the ordering that matters:

```ts
// permission.ts:175-188 (paraphrased order of operations)
if (deny) return deny                      // :175 — short-circuit
const all = [...rules, ...savedRules()]    // :176
if (any ask) return ask
return hooks.trigger("permission", "evaluate", …)  // :179-188 — can rewrite effect
```

Read precisely, the precedence is:
1. explicit `deny` anywhere → deny, *before* saved grants are considered (:175)
2. saved "always" allows participate in the same `findLast` as configured rules (:176) → a saved allow **overrides a configured `ask`**
3. `ask` wins over `allow` among rules
4. a **plugin hook may rewrite the final effect** (:179-188)

### Errors as control flow

`permission.ts` defines three typed failures: `DeclinedError` (`:64`), `CorrectedError{feedback}` (`:66`), `BlockedError` (`:70`). `assert` (`:235-267`) maps deny → `BlockedError`, ask → blocks on a `Deferred`, and tunnels `DeclinedError` as a **defect** (`:257`) so it cannot be caught by ordinary effect error handling [inferred intent from the comment].

`reply` (`:269-329`) has two non-obvious behaviors:
- **"reject" cascades to the whole session batch** (`:286-299`) — one rejection denies every other pending request in that session
- **"always" persists `request.save` resources** (`:303-309`) and then auto-approves other already-pending matching requests (`:314-326`)

### Persistence is allow-only and project-scoped

`permission/saved.ts` stores rows `{id, project_id, action, resource}` in SQLite, written with `onConflictDoNothing` (`:72-87`). `savedRules()` maps them to `effect:"allow"` (`permission.ts:148`). **There is no persisted deny** — you cannot "always block" through the UI.

### Path rules and confinement

`file-access.ts:93-122` decides internal vs external:

```ts
const internal =
  FSUtil.contains(location.directory, absolute) ||
  (worktree !== path.parse(worktree).root && FSUtil.contains(worktree, absolute))
```

Note the second clause: if the cwd is a subdirectory of the project worktree, **the entire worktree is "internal"** and requires no `external_directory` approval. Only paths outside both fall through to:

```ts
// file-access.ts:116-121
externalDirectory: {
  action: "external_directory",
  directory,
  resource: slash(path.join(directory, "*")),
  save: slash(path.join((yield* Project.root(fs, directory)) ?? directory, "*")),
}
```

`save` is the **project root of the approved directory**, widened from the directory itself. `authorizeExternal` (`:125-142`) de-dupes by resource before one `assert`. `authorizeRead` (`:144-161`) resolves → authorizes external → asserts `read` with `save:["*"]` (`:154-159`), i.e. "always" for a read persists a global read allow, not a per-file one.

### What is enforced vs advisory

| Mechanism | Enforced? | Evidence |
|---|---|---|
| Per-leaf `permission.assert` | **Yes** — the only real gate | `file-access.ts:154`, `edit.ts:275-283`, `shell.ts:138-145`, `write.ts:79`, `patch.ts:197`, `question.ts:65` |
| Tool visibility filtering (`whollyDisabled`) | **No** — presentation only | `tool.ts:293`, `tool/AGENTS.md` |
| External-directory containment | **Yes**, at `resolve` time | `file-access.ts:93-122` |
| `ConfigPolicyPlugin` `tool.use` | **Yes** — rewrites the decision to deny | `config/plugin/policy.ts:54-69` |
| Managed/org policy | **Yes**, ordered last so org wins | `managed-policy.ts:50-75` |
| CodeMode confinement (no `require`, no timers) | **Yes**, in-interpreter | `codemode/tool.ts:71-78`; `interpreter/globals.ts:30-34` |
| Snapshot path-escape rejection | **Yes** | `snapshot.ts:196-213` |

### Policy layering above permissions

`config/plugin/policy.ts` removes providers (`:28-41`), blocks `integration.use` for `mcp:<name>` and `skill:<id>` (`:42-53`), and denies tools by mutating a permission hook (`:54-69`). `managed-policy.ts:50-64` merges authored document statements (reversed, so **user-global outranks repo**) and then Console org statements last; `decision()` is again `findLast` with a default of **allow** (`:66-75`) — note the inversion: permission evaluation defaults to `ask`, managed policy defaults to `allow`.

`config/plugin/agent.ts` pushes global document permissions into every agent (`:84-92`), appends per-agent permissions last so agent entries win (`:121-123`), drops `disabled:true` agents (`:97-100`), and `expandPermissions` expands `~` **only for path actions, never for shell text** (`:141-147`).

---

## 3. Shell, PTY, streaming, background jobs

### The synchronous shell

`shell.ts` (21 KB) — constants: `EXITED_LIMIT = 25` (`:32`), `RETENTION = 7 days` (`:33`), `DIRECTORY = "shell"` (`:34`). Output is spooled to `<global.data>/shell/<projectID>/<shellID>.out` (`:144,289`) and read back by **byte range**:

```ts
// shell.ts:208-236  output(id, { cursor, limit = 65536 })
```

`result()` (`:238-262`) tail-truncates against the `tool_output` config (`max_lines`/`max_bytes`) and appends a notice: `[showing lines X-Y of N; full output saved to <file>]`.

`create` (`:264`) fires the `shell.create.before` hook (`:284`), spawns with `detached: process.platform !== "win32"` (`:315`) and `forceKillAfter: 3s` (`:316`); the timeout kill path is deliberately **uninterruptible** (`:371-391`). Env injected at spawn: `TERM=xterm-256color`, `OPENCODE_TERMINAL=1` (`:280-281`).

The `shell` **tool** adds the agent-facing env (`tool/plugin/shell.ts:213-216`):

```ts
invocation.env.AGENT = "1"
invocation.env.OPENCODE = "1"
invocation.env.AI_AGENT ||= "opencode"
invocation.env.OPENCODE_SESSION_ID = context.sessionID
```

### Command → permission resource derivation

This is the most interesting parser in the capability layer. `shell/parse.ts` uses a WASM tree-sitter bash grammar (`:178-206`) and emits, per simple command:

```ts
// shell/parse.ts:196-199 (shape)
{ resource: <node text, or the redirected_statement text>,
  save: <prefix tokens joined> + " *" }
```

The prefix length comes from an arity table (`:23-170`: `git:2`, `docker:2`, `bun run:3`, `aws:3`, …), so `git commit -m "x"` → `resource: "git commit -m x"`, `save: "git commit *"`. **Approving once therefore approves the whole subcommand family.** `CWD` commands (`cd`, `chdir`, `popd`, `pushd`, `push-location`, `set-location`) are tracked at `:14` so later relative paths resolve correctly.

The fallback is `scanPortable` (`parse.ts:209`) → `ShellScan` in `shell/scan.ts` (100 KB, hand-written bash/zsh/posix scanner plus PowerShell at `:1729`). It returns a discriminated result:

```ts
// shell/scan.ts:27
Result = { kind: "scanned", commands } | { kind: "opaque", reason }
```

`OpaqueReason` (`scan.ts:3-12`) is the honest failure mode: when the scanner cannot prove what the command does, the tool must ask for approval of the raw command string.

Selection between the two is runtime-configurable (`tool/plugin/shell.ts:126-128`):

```ts
const portable = Config.latest(yield* config.entries(), "experimental")?.portable_shell_scanner ??
  (shellParserWasm.bash === "" || ctx.app.channel === "local" || ctx.app.channel === "dev")
```

i.e. local/dev builds **default to the hand-written scanner**, production uses tree-sitter when the WASM is bundled. Two different parsers → two different permission surfaces for the same command, by build channel.

### The approval path

`tool/plugin/shell.ts:118-155` is the whole gate, in order:

1. `access.resolve({ path: cwd, kind: "directory" })` and rewrite `invocation.cwd` (`:123-124`)
2. `ShellParse.scan(command, shell, cwd, { portable })` (`:129`)
3. resolve every directory the parse discovered as a potential cwd, then `access.authorizeExternal([cwd, ...dirs])` (`:130-136`)
4. `permission.assert({ action: name, resources: parsed.commands.map(c => c.resource), save: parsed.commands.map(c => c.save) })` (`:138-145`)
5. **re-validate the directory immediately before spawning** — `:146` comment: *"Approval can outlive the directory, so validate immediately before spawning."*

Step 5 is a real TOCTOU mitigation for the directory; step 4's `resources` are a snapshot of the parse, so a command that changes behavior between parse and exec (e.g. reads a file the parser only saw as a redirect target) is covered only insofar as the parse text matched.

### Background jobs and restart recovery

`job.ts` (18.8 KB) is the general background-execution substrate, KV-backed and **recoverable across restarts**:

```ts
// job.ts:12-30
recovery: Schema.Union([
  { kind: "shell", sessionID, shellID, command },
  { kind: "subagent", parentSessionID, childSessionID, agent, description },
])
```

`backgroundPrefix = "job.background/"` (`:37`), `COMPLETED_LIMIT = 25` (`:38`), `persistBackground` (`:199-209`) writes the record, `settle` (`:211-245`) classifies the exit as `completed | error | cancelled` (`:220-224`) — interrupts-only counts as *cancelled* and is **not** persisted (`:236`).

The shell tool's background mode (`tool/plugin/shell.ts:239-267`): start a Job with `recovery:{kind:"shell",…}`, then either `jobs.background` + `notifyWhenDone` (`:254-258`) or `jobs.block` and, if the caller backgrounds mid-flight, drop the shell timeout to 0 and notify (`:263-267`). `notifyWhenDone` (`:157-189`) injects a **synthetic session message** on completion — this is why the tool description tells the model *"DO NOT poll"* (`:26-27`).

The block/background handoff is a race on two `Deferred`s (`job.ts:349-355`): `Effect.raceFirst(await done, await backgrounded)`.

### PTY and the persistent PTY daemon

`pty.ts` (11 KB): `BUFFER_LIMIT = 2MB` (`:12`), `EXITED_LIMIT = 25` (`:15`), cursor-based attach/replay (`:53-71`) — attach returns the buffered backlog from a cursor, so re-attaching is idempotent.

`persistent-pty/daemon.ts` (25.7 KB) is a **native daemon over a unix socket**, not an in-process pty:
- `ProtocolVersion = 7`, `MaxFrameBytes = 8MB` (`:8-9`) — a versioned wire protocol with a frame cap
- `WireTerminal` (`:17-30`), `Registration{instance_id, pid, protocol, socket, token}` (`:33-39`)
- state dir created `mode: 0o700` (`:438`)
- spawned `detached: true, windowsHide: true, stdio: "ignore"` (`:233-236`) so it outlives the app
- a **handoff ticket** (`:53`, minted `:356-416`) lets a new app instance take over an existing daemon

Per-daemon `token` + `0o700` directory is the auth model; there is no OS-level credential. Any process running as the same user that can read the state dir can speak protocol v7 to the daemon.

---

## 4. MCP

### Transports

`mcp/client.ts` (20.6 KB): remote servers use `StreamableHTTPClientTransport` (`:272`), local servers use `McpStdio.make` (`:249`). Retry and session-expiry handling at `:163-240`; a `roots/list` handler is installed at `:206`.

### Naming and collision handling

```ts
// tool/mcp.ts:16-17
namespace(server)  // sanitize server name to [a-zA-Z0-9_-]
name(server, tool) // sanitize tool name
```
Both are then combined by `runtime.ts:278` `effectiveName`. Sanitization is lossy and there is **no collision resolution** in `tool/mcp.ts`; two servers differing only in stripped characters map to the same prefix [inferred — no dedupe code path observed in `mcp.ts` or `runtime.ts`].

### Per-tool permissions

`tool/mcp.ts:51-63` registers each MCP tool with a permission whose **action is the tool's own name** and `resources:["*"], save:["*"]`. So every MCP tool is independently deniable, and "always" is a blanket grant for that tool.

### Auth

`mcp/index.ts:181-215`: any **remote** MCP server is auto-registered as an OAuth integration, keyed `mcp_<sha1(name \0 url)[0:16]>`. There is no per-server static-token path in this code — credentials flow through the generic `Credential`/`Integration` machinery (§6/§9).

### Failure behavior

- `McpEvent.ToolsChanged` triggers a **100 ms debounced** reconcile (`tool/mcp.ts:133-143`) rather than immediate re-registration.
- Output is JSON-parsed when the server declares no output schema (`tool/mcp.ts:100-104`).
- Elicitation is routed into the generic form channel with a global pseudo-session (`mcp/index.ts:231-282`) — i.e. an MCP server can pop a UI prompt attributed to no real session.

### CodeMode and MCP interact badly enough to need a workaround

`mcp/client.ts:266-290` appends `?codemode=false` to remote MCP URLs, with an allowlist retry path for Google/Zoho/localhost (`:410-417`) because those servers 404 on unknown query parameters. A transport-level hack driven by one feature flag is a code smell worth flagging: it means remote MCP server URL identity is not stable across modes.

---

## 5. Plugins

### Everything built-in is a plugin

`plugin/internal.ts` (292 lines) enumerates the internal plugin set: `services` (`:109-159`), `requirements` (`:163`), `pre` (`:217-252`), `post` (`:254-272`), and

```ts
// plugin/internal.ts:276
guarded = { OpencodePlugin.id, ConfigPolicyPlugin.Plugin.id }
```

`guarded` is the only protection against a repo shipping a plugin that removes the policy enforcer or the core opencode tools. **Everything else — `Edit`, `Write`, `Read`, `Shell`, `Skill`, `Question`, `Todo`, `WebFetch`, `WebSearch`, `McpResourceTools`, every `Config*` plugin — is removable by configuration.**

### Load model

`packages/plugin/src/source.node.ts:10-51` is the actual loader: Node `module.registerHooks` with a cache-busting query string.

```ts
const fresh = (specifier: string) => {
  const url = new URL(specifier)
  url.searchParams.set("__opencode_reload", version)   // version = ++generation
  return url.href
}
```

Local (non-`node_modules`) files get a fresh URL so edits reload; every touched file is tracked (`track(file)`), which is what drives dependency-aware invalidation. `prepareSource` returns `{version, load, dispose}` (`:51`).

`plugin.ts` (12.4 KB) activates revisions with **prefix-diff reuse** (`:108-130`) under a `Semaphore`: unchanged leading plugins are kept, changed tails are rebuilt. Duplicate plugin IDs are fatal (`:96`); a failed revision is **not retried** (`:23-24`).

### Three plugin authoring surfaces

| Surface | Path | Shape |
|---|---|---|
| Effect-native | `packages/plugin/src/effect/*` | `Plugin<R> = {id, effect: (Context) => Effect<void, never, R>}` (`effect/plugin.ts`) |
| Promise adapter | `packages/plugin/src/promise/*` + `adapter.ts` (31 KB) | `Plugin.define({id, async setup(ctx)})` |
| TUI | `packages/plugin/src/tui/*` (20 KB context) | SolidJS components |

The `Context` handed to plugins exposes 26 ambient services (`effect/plugin.ts`): `app, location, options, agent, aisdk, command, credential, event, experimental.terminal, integration, mcp, model, generate, permission, plugin, provider, reference, rpc, session, shell, skill, storage, tool, vcs, websearch, worktree`.

A concrete shipping example is the figma plugin — 38 lines that register an MCP server and a skill pack:

```ts
// packages/plugins/figma/index.ts:7-17
export default Plugin.define({
  id: "opencode.figma",
  async setup(ctx) {
    await ctx.mcp.transform((editor) => {
      if (editor.get("figma")) return
      editor.set("figma", { type: "remote", url: "https://mcp.figma.com/mcp" })
    })
    …ctx.skill.transform(editor => …)   // :33-37
  },
})
```

### Hooks

`plugin/hooks.ts` (108 lines) keeps a callback map keyed `domain.name`, with `providerID` scoping (`:90`) and dispose-based unregistration (`:76-84`). Domains are `aisdk | session | permission | shell | tool`. **Only `tool.execute.before` may fail** (`Tool.Error`) — every other hook is infallible by contract, which is what makes hook ordering safe to reason about but also means a permission hook cannot *reject*, only *rewrite*.

`ToolHooks` = `execute.before` / `execute.after` (with `status: "completed" | "error"`); `ShellHooks.create.before` receives `{command, cwd, timeout, shell, env}` and may mutate it.

A shipped example of a hook doing real work: `plugin/tool-input-repair.ts` — an `execute.before` hook that coerces model-generated JSON to the declared schema (`:7-16` lists the eight repair classes; `:28` skips `execute` itself because the CodeMode tool is built per-snapshot and cannot be looked up).

### Security posture

`plugin/host.ts` (27.7 KB) is the ambient-authority surface given to **in-process, unsandboxed** code:

```ts
// plugin/host.ts (regions)
permission: { hook, list, get, reply, assert }   // :414-447
tool:       { transform, reload, list, hook }     // :482-487
shell.hook                                        // :479-481
vcs                                               // :488-497
mcp.transform                                     // :403
worktree                                          // :528+
session.hook                                      // :547-548
storage: storage(kv, pluginID)                    // :478
```

There is no capability token, no per-plugin scoping of `permission.assert`, and `storage` is namespaced only by the plugin's own declared ID. Isolation is by convention.

---

## 6. Skills, instructions, codemode, forms, images, websearch

### Skills

`skill.ts:33-34` filters availability by the permission engine, not by config:
```ts
permission.evaluate("skill", skill.id, permissions).effect !== "deny"
```
`toModelOutput` (`:36-53`) emits `<skill_content name=…>` plus a `<skill_files>` block with up to 10 sampled file paths — the model sees a *sample*, so "the skill has N files" is only partially true in-context.

Remote skills (`skill/discovery.ts`) are pulled from `<url>/index.json` with:
- name/path traversal guards (`:16-46`)
- same-origin restriction for file URLs (`:131`)
- a `.opencode-version` marker file (`:122`)
- cache at `global.cache/skills/<hash(base)>` (`:106`)
- **staged swap with backup** (`:157-191`) so a failed update does not destroy the previous copy

**No signature or hash verification of skill content.** Integrity rests on TLS and the origin.

### Instructions

Two layers:

`instruction-discovery.ts` (149 lines) — `File{path, content}`, ordering is **Map insertion order = render order** ("global then nearest-to-farthest project files", `:30-31`). `render` (`:123`) emits the full block; `renderUpdate` (`:127`) emits **a diff** via `createPatch` (`:138-146`), falling back to full replacement when shorter. Instruction updates are therefore incremental in the prompt — cheap, but it means the model's context contains a patch series, not a stable document.

`config/plugin/instruction.ts` (11.9 KB) — candidates are the global `<config>/AGENTS.md` plus `ancestorDirectories(start, stop).map(d => d/AGENTS.md)` (`:51-58`); `projectSource` walks up with `fs.up({targets:["AGENTS.md"]})` (`:86-94`); denied files are **logged and skipped** (`:64-76`); configured `instructions` globs / `~` / URLs at `:98-135`.

**The read tool injects instructions as a side effect.** `tool/plugin/read.ts:82-107`: after any successful read, nearby `AGENTS.md` files are lazily discovered and appended to `sessionInstructions` as durable synthetic instructions — skipped only for external directories. A file read is therefore a prompt-mutation primitive.

Assembly order is deliberate (`session/context.ts:149-157`): `combine([CodeModeInstructions, mcp, references, skills, discovery, builtins, entries])`, described in-source as "most to least shared … so the baseline stays a reusable prompt-cache prefix".

### CodeMode

`packages/codemode` — "Effect-native confined code execution over schema-described tools", dependencies `acorn 8.15.0` + `effect` only. The interpreter is hand-written (`interpreter.ts`, ~96 KB) and enforces hard limits (`interpreter/limits.ts`): `MAX_STRING_LENGTH = 1<<24`, `MAX_ARRAY_LENGTH = 10_000_000`, `MAX_PENDING_PROMISES = 10_000`, `MAX_VALUE_DEPTH = 32`. Timeout is `Effect.timeoutOrElse` with a separate warning budget (`interpreter/execute.ts:61-98,134-176`).

The `Function` constructor is explicitly rejected rather than emulated (`interpreter/globals.ts:30-34`):
```ts
typeError("The Function constructor is not supported; write the function inline.")
```

Model-facing invariants (`codemode/tool.ts:71-78`): no imports, no filesystem, no timers; `fetch` **is** available; only catalog tools callable.

### Forms (elicitation / questions)

`form.ts` (15.1 KB) is a request/answer channel over the Bus with `RETENTION = 10 minutes` (`:8`). It backs both the `question` tool and MCP elicitation — one UI path, two very different trust levels.

### Images

`image.ts` — defaults `autoResize: true`, `maxWidth/maxHeight: 2000`, `maxBase64Bytes: 5 MiB` (`:63-68`); the actual resizer is a **lazy dynamic import** of `./image/photon.js` behind `Effect.cached` (`:78-83`), so a runtime without the native module fails with `ResizerUnavailableError` instead of crashing at load.

### WebSearch

`websearch.ts` — providers are a `Map<ID, ProviderImplementation>` mutated through `State` (`:102-112`). Selection is persisted in KV under `websearch:provider` (`:31`) and may be `false` (disabled), `"random"`, or a concrete ID (`:32`). `randomProvider` (`:133-146`) maintains per-provider **cooldowns** and a per-session `affinity` so a session sticks to one provider, with `attempted` preventing retry loops. Session deletion/move clears affinity (`:94-101`).

### Formatter

`formatter.ts:56-94` — on `file(filepath)`, match by extension, then per formatter resolve `enabled` (cached in a `WeakMap`, `:32,48-54`), substitute `$FILE` (`:63`), run with `cwd: location.directory`, `extendEnv: true`, and **all three stdio ignored** (`:71-73`). Failure is logged and the next matching formatter is tried; a non-zero exit does not fail the tool.

Built-ins (`formatter/builtins.ts`): prettier `:48`, oxfmt `:89`, biome `:103`, clang-format `:144`, ruff `:156`, air `:177`, uv `:190`, ocamlformat `:206`, pint `:223`. `enabled` is an Effect that decides per-project whether the formatter is present/configured.

### References

`reference.ts` (4.3 KB) — named sources (`local` or `git`), refreshed with `cache.ensure({… refresh: "daily" })` at concurrency 4 (`:76-94`), plus an **hourly** background re-check (`:112`) independent of session activity. Git references land in `global.repos` via `Repository.cachePath` (`:72`).

---

## 7. VCS, worktrees, snapshots, revert, attribution

### VCS is pluggable

`vcs.ts` (10.4 KB): `VcsDefinition` providers keyed by id with a `State`-driven selection; the default is `location.vcs?.type`. `protect`/`review` wrappers degrade to fallbacks and raise a typed `DiffError` (`:38-120`). `vcs/patch.ts` supplies `MAX_TOTAL_PATCH_BYTES` and `PATCH_CONTEXT_LINES` so diffs are bounded before they reach the model.

### Worktrees

`worktree.ts` (15.2 KB) is a **global** node (`:341`) with a DB-backed `Interface` (`:93-104`: `list`, `create`, `remove`, `refresh`), typed failures `SourceDirectoryNotFoundError`, `DestinationExistsError`, `InvalidDirectoryError`, `StrategyUnavailableError` (`:50-70`). Strategies are themselves a pluggable `State`-transformable service (`worktree/strategies.ts`), with `directory.ts`, `git.ts`, `sql.ts` beside it — so "worktree" is a strategy slot, not a hard dependency on `git worktree`.

The model reaches it through `opencode_worktree_create|list|remove`, all `pinned:true`, and `worktree_remove` reuses the `edit` permission (`tool/plugin/opencode.ts:187,193`). Removal is treated as a *file mutation*, which is the correct classification and easy to get wrong.

### Snapshots

`snapshot.ts` (244 lines):
- shadow repo path is `<global.data>/snapshot/<projectID>/<hash(worktree)>`, cached as a fiber for the location's lifetime (`:90-106`)
- `enabled()` requires `location.vcs?.type === "git"` (`:114`) — **no snapshots outside git repos**
- `capture` (`:128`) with `maximumUntrackedFileBytes: 2*1024*1024` (`:136`) — untracked files above 2 MiB are not captured, so a large new file is invisible to revert
- `packObjects` every 10 minutes (`:118-126`) keeps the shadow repo from growing unboundedly
- `files`/`diff` filter paths the source repo ignores (`:156-194`) so `.gitignore`d output does not pollute a turn's diff
- `plan`/`restore` **reject path escapes** (`:196-213`)
- `noopLayer` (`:225`) provides a disabled implementation, so call sites never branch on availability

Snapshot IDs are **git tree OIDs**, which makes them content-addressed and cheap to compare.

### Per-turn attribution

`session/diff.ts` (138 lines) is where "what did this turn change" is defined:

```ts
// session/diff.ts:76-86 (range construction)
// start = first prompt after the previous idle marker
// end   = next idle marker, or next user message when not yet idle
```
then `:106-136` compares `steps.find(start)` against `steps.findLast(end)`. A range that spans a location switch is rejected (`:121-122`); `to` uses a live `capture()` only when the final step is still running (`:129-130`) — i.e. the diff of an in-flight turn is explicitly approximate.

Per-step snapshot IDs live in message JSON as `snapshot.start` / `snapshot.end` / `snapshot.files`, written by `session/runner/step.ts`: the start snapshot is **forked before the provider request** so it overlaps network latency (`:70-76`), and the end snapshot plus `snapshots.files({from,to})` is recorded at `:237-243`.

Revert restores from the recorded **start** snapshot (`session/revert.ts:106-111`). Because every step carries both endpoints, one message can be reverted without touching neighbours — this is the mechanism behind per-message undo.

---

## 8. ripgrep, ignore rules, retention, mutation tracking

### Invocations

`ripgrep.ts` (277 lines):

```ts
// :198-207  find
--no-config --files [--hidden] [--follow] --glob=… --glob=!**/.git/** .
// :222-234  grep
--no-config --json --hidden --no-messages [--fixed-strings] [--ignore-case]
  --glob=!**/.git/** -- <pattern> .
```

`--no-config` means a developer's `RIPGREP_CONFIG_PATH` cannot change tool behavior — a deliberate reproducibility choice. There is **no `--no-ignore`**, so `.gitignore`/`.ignore` are honoured; only `.git/**` is force-excluded. The tool can therefore *not* see ignored files, which matters when a model is asked to find generated artifacts.

Bounds: `MAX_SUBMATCHES = 100` and text truncated at 2000 chars (`:261`).

### Binary acquisition

`ripgrep/binary.ts` pins `VERSION = "15.1.0"` and, at runtime, downloads the per-platform release artifact from GitHub, extracts it with `tar` / System32 `tar.exe`, and `chmod 0o755` (`:14-100`). No signature or checksum verification. This is remote code fetch + execute, gated only by the tag.

### Filesystem protection lists

`filesystem/protected.ts` (57 lines) is a home-directory denylist used to skip watch/stat/scan. macOS: `Music/Pictures/Movies/Downloads/Desktop/Documents/Public/Applications/Library` plus Library subpaths including `Application Support/com.apple.TCC`. Windows: `AppData/Downloads/Desktop/Documents/Pictures/Music/Videos/OneDrive`.

This is a **performance guard, not an access control** — it prevents the watcher from walking huge trees. `read` can still read those paths subject to permissions.

`filesystem.ts` sets `DEFAULT_SEARCH_LIMIT = 100` (`:69`) and `DEFAULT_SEARCH_TIMEOUT_MS = 30_000` (`:70`), with typed `NotFoundError` / `DirectoryNotFoundError` / `DirectoryAccessDeniedError`.

### Retention

Three independent GC loops, all mtime-based, all 7 days:
- shell output — `shell.ts:32-33`
- tool output — `tool-output.ts:15`, cleaned hourly (`:129-138`)
- generic `file-retention.ts` (23 lines, concurrency 8)

`tool-output.ts` is the truncation service: `MAX_LINES = 2_000`, `MAX_BYTES = 50 KiB` (`:13-14`). Overflow writes the full text to `<global.data>/tool-output/tool_<id>` and replaces the tail with `[showing lines 1-N of M; full output saved to <file>]` (`:86-90`), recording `metadata:{truncated:true, outputPath}` (`:116`). Note it spills **before** the bytes are dropped and reports the path to the model, so nothing is silently lost.

### Mutation tracking

`file-mutation.ts` (134 lines): a global `KeyedMutex` (`transactionLocks`, `:60-74`) serializes writes per path, `write` at `:87`, BOM-preserving variant at `:103`. And then, verbatim in-source:

```ts
// file-mutation.ts:126-134  (TODO list)
// - no watcher events
// - no snapshots / undo
// - no LSP diagnostics
// - no multi-file transactions / rollback
```

Multi-file atomicity is explicitly absent: a `patch` that touches five files can half-apply, and only the snapshot layer would let you recover.

---

## 9. Notable and unusual findings

1. **The catalog is a sampled view of the toolset.** A 2000-character budget (`codemode/catalog.ts:50`) decides what the model sees, with `pinned` for self-management tools and an in-interpreter `search` builtin for everything else. Two agents on the same codebase can have materially different tool awareness depending on namespace ordering.
2. **Two different shell parsers by build channel.** `tool/plugin/shell.ts:126-128` defaults local/dev to the hand-written scanner and production to tree-sitter. Identical commands therefore produce different permission resources depending on where you run them.
3. **Shell "always" persists a subcommand wildcard**, not the command (`shell/parse.ts:196-199`). `git commit *` from one approval is a much wider grant than the prompt implied.
4. **External-directory "always" persists the enclosing project root** (`file-access.ts:120`). Approving `/tmp/x/file.txt` can persist an allow for `/tmp/x/*`.
5. **Reading a file can rewrite the system prompt.** `read.ts:82-107` injects discovered `AGENTS.md` as durable instructions. Any repo you read can inject instructions mid-session.
6. **Instruction updates are patches, not replacements** (`instruction-discovery.ts:127-146`). The model's context accumulates a diff series over a session.
7. **Two permission defaults, inverted.** The permission engine defaults to `ask` (`permission.ts:87`); managed policy defaults to `allow` (`managed-policy.ts:66-75`). Policy is therefore permissive where permissions are conservative.
8. **A plugin can rewrite any permission decision** to `allow` via `permission.hook("evaluate")` (`permission.ts:179-188`). Only `OpencodePlugin` and `ConfigPolicyPlugin` cannot be removed (`internal.ts:276`) — and the rewrite capability itself is not guarded.
9. **MCP elicitation is attributed to a global pseudo-session** (`mcp/index.ts:231-282`), so a third-party server's prompt is indistinguishable in the UI from a first-party question.
10. **Remote MCP transport is mode-dependent.** `?codemode=false` is appended to remote URLs (`mcp/client.ts:266-290`) with a per-vendor retry allowlist (`:410-417`).
11. **Well-known config is remote-controlled and cached across failures.** `wellknown.ts:66` fetches `/.well-known/opencode`; `:89-92` substitutes `{env:NAME}` **from `process.env`** into the remote-config URL and headers; `:119-137` deliberately keeps the last cached manifest when the origin is unreachable, so a stale remote config continues to apply after restart. A remote origin can therefore direct further config fetches, with headers built from local environment variables.
12. **Credentials are plaintext JSON in SQLite.** `credential/sql.ts:5-11` stores `value` as a JSON text column; there is no encryption anywhere in `packages/core/src` (grep for `safeStorage|keytar|aes|createCipheriv|crypto.` returns only `crypto.randomUUID`). Confidentiality rests on file mode: `database/database.ts:85-98` `chmod 0o600` on the DB plus `-wal`/`-shm`, and on Windows the comment at `:83-84` states it relies on inherited profile ACLs instead.
13. **OAuth tokens transit the URL fragment.** `oauth/page.ts:126` reads `access_token` from `location.hash` and relays it to a loopback listener via a bootstrap page. Fragment-only delivery avoids `Referer` leakage but puts the token in browser history [inferred re history; the fragment read is verified].
14. **Persistent PTY is a detached native daemon** with protocol v7, 8 MB frame cap, `0o700` state dir, and a takeover ticket (`persistent-pty/daemon.ts:8-9,53,233-236,438`). Auth is a per-daemon token file readable by the same user.
15. **`webfetch` has no SSRF guard.** `webfetch.ts:70-72` validates only that the URL parses and uses `http:`/`https:`. No private-IP, link-local, or loopback blocking exists in the file, and no equivalent guard was found elsewhere in `core/src`. Response size *is* bounded (`tool/http-body.ts:4-29`, streaming with a maximum). Cloudflare challenges trigger a one-shot retry with a different user agent (`webfetch.ts:135-137`).
16. **Subagent and shell share one job substrate** with restart recovery (`job.ts:12-30`), and completion arrives as a synthetic message rather than a poll — the tool description literally instructs the model not to poll (`tool/plugin/shell.ts:26-27`).

---

## Security observations

**Verified in source (read directly):**

- Permission filtering ≠ authorization; enforcement is per-leaf `permission.assert`. A denial on `write` does not prevent `shell` from writing; only the leaf that owns the action checks it. (`tool/AGENTS.md`; `file-access.ts:154`; `shell.ts:138-145`)
- `findLast` with default `ask`; deny short-circuits before persisted allows; persisted allow overrides configured `ask`. (`permission.ts:87,175-188`)
- Saved rules are allow-only and project-scoped; there is no UI path to persist a deny. (`permission/saved.ts:72-87`; `permission.ts:148`)
- One "reject" cascades to every pending request in the session. (`permission.ts:286-299`)
- External-directory "always" widens to the enclosing project root. (`file-access.ts:116-121`)
- Shell "always" widens to a subcommand prefix wildcard. (`shell/parse.ts:196-199`)
- Instruction injection on read; skill-file lists are sampled (max 10). (`read.ts:82-107`; `skill.ts:36-53`)
- Remote skills and the ripgrep binary are fetched over HTTPS with **no signature/hash verification**. (`skill/discovery.ts`; `ripgrep/binary.ts:14-100`)
- `webfetch` protocol check only — no private/loopback address filtering. (`webfetch.ts:70-72`)
- Credentials unencrypted at rest; 0600 on POSIX, inherited ACL on Windows. (`credential/sql.ts:5-11`; `database/database.ts:83-98`)
- Plugins are in-process with full ambient authority; permission-rewrite hooking is unguarded. (`plugin/host.ts:403-548`; `permission.ts:179-188`)
- Well-known remote config can substitute `process.env` values into outbound URLs and headers, and its cached manifest survives origin failure. (`wellknown.ts:87-92,119-137`)
- `file-mutation.ts` has no multi-file transaction and a documented absence of watcher events and undo. (`:126-134`)

**Inferred (structure implies it; not stated in-source):**

- MCP tool names can collide after sanitization, with no dedupe path. (`tool/mcp.ts:16-17` + `tool/runtime.ts:276-278`)
- Untracked files >2 MiB are outside revert, because `capture` caps untracked bytes at 2 MiB. (`snapshot.ts:136`)
- A repo that overrides the `edit`/`write`/`shell` built-ins via config narrows the permission surface without narrowing `shell`, which would then be the only mutation path and still gated per-parsed-resource.
- CodeMode's catalog budget means a tool can be *callable but not advertised*; the model must discover it via `search`, and an unawareness failure looks identical to a capability gap.

**Mitigations worth noting as deliberate:**

- `--no-config` on every ripgrep call (reproducibility over user convenience) — `ripgrep.ts:198,222`.
- Interruptible-free timeout kill for shell (`shell.ts:371-391`) so a hung child cannot be cancelled into a zombie.
- Directory re-validation immediately before spawn (`tool/plugin/shell.ts:146-153`).
- `forceKillAfter: 3s` on shell children (`shell.ts:316`).
- Snapshot `plan`/`restore` path-escape rejection (`snapshot.ts:196-213`).
- `Function` constructor disabled in the interpreter rather than shimmed (`interpreter/globals.ts:30-34`).
- Staged swap with backup for remote skill updates (`skill/discovery.ts:157-191`).
- `guarded` plugin set protects only the policy enforcer and core tools (`internal.ts:276`).

---

*Report generated from source only — no build, test, or install was run, and no repository file was modified.*
