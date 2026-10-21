## 7. Extensions

**What a GUI extension is:** a ZIP-distributed bundle with a `manifest.json` (schema `opencode.gui-extension/1`), an optional `main` (CommonJS, main-process), a `renderer` (renderer-process), an optional `style`, and an `imports` allow-list (`{renderer: string[], main?: string[]}`). Manifest schema in `main/extension/manifest.ts:11`; `archivePath()` rejects `\`, `:`, control characters, leading `/`, and `.`/`..` segments.

**Loading.** `main/extension/index.ts:Extensions.Service` lazily loads `./host` and `desktopCli.resolve` in one `Promise.all`, then `createHost({db, state, cli, subscriptions, servers, restart, log, write})`. Assets are served through `setExtensionAssets(extensionAsset(storage.db, request, url))` at `oc://extensions/<id>/<path>`, with the protocol handler returning **503 until storage opens**.

**Capability/test model.** `main/extension/module.ts:29-41`:
- A `shared` module map exposes `effect`, `@opencode/gui-extensions/sdk/main`, `@opencode/schema/rpc`, `@opencode/client`, `@opencode/client/effect`.
- `mainImportAllowed(name)` = `"electron"` || node builtin || shared.
- `evaluateMain` runs `new Function("require", "module", "exports", source)`.
- The comment states the model plainly: **"Installed extensions are trusted code the user chose to install."**

So the `imports` allow-list is a *hygiene/consistency* mechanism, not a security boundary. An extension's main code runs in the Electron main process with full Node privileges. There is no signature verification, no capability grant, no sandbox. The default export must be a function; an inline `i18n` catalog (`InlineCatalog`, seeded with `{en:{}}`) may be supplied.

**Lifecycle** (`main/extension/lifecycle.ts`, 388 lines) is the most carefully engineered piece in the package:
- One **serialized queue per extension** (`queues: Map<string, Promise<void>>`), so operations never interleave; a failed step never stalls the steps behind it (the queue waits for settlement, the caller sees the outcome).
- One **generation** `AbortController` per extension; `stop` aborts it so in-flight activations wind down.
- `Instance.contribute(withdraw)` â€” the host's ledger of what an instance registered; the host **withdraws synchronously the moment disposal starts**, before any finalizer runs, so a disposed extension is unreachable even if a finalizer hangs.
- `CLEANUP_TIMEOUT_MS = 3000`, `SETUP_STALL_MS = 10000` (logs once, does not abort).
- A failed reload returns to the last revision whose setup succeeded (`last: Map<string, Revision>`).
- `kept: Set<Scope>` preserves scopes across a **restart handoff**, so a failed handoff leaves the extensions working and a successful one keeps their state until quit.

**Built-ins** ship inside the app: `@opencode/gui-extensions/src/main.ts:3` composes `Extension.compose(context, btw, debug, terminal, file, review, details, browser, pairing, updater, ssh, wsl, microsoftOffice)`, with `main` lazily imported for browser/pairing/updater/ssh/wsl. Contribution registries include `menubar-item`, Ipc providers, embeds, and server endpoints (`ServerEndpoint {id, url, headers, local, username?, password?}`), which is how an extension can talk to the sidecar with credentials it is given explicitly.

**Auto-update lives here** (`@opencode/gui-extensions/src/updater/platform.ts`): it imports both `{app, autoUpdater}` from `electron` and `pkg from "electron-updater"`, handles Windows ARM64 installer arch selection, the macOS-arm64-under-Rosetta case, `update-downloaded`, and `before-quit-for-update`. This is the reason `electron-updater@6.8.9` appears in both `packages/desktop/package.json` and `packages/gui-extensions/package.json` (as a devDependency there) â€” the updater is an *extension*, and `packages/desktop/src` contains no update code at all. **[V]**

---

## 8. Persistence

`drizzle.config.ts` is 7 lines: `dialect: "sqlite"`, schema `./src/main/storage/schema.ts`, out `./src/main/storage/migration`.

**Schema** (`main/storage/schema.ts`) â€” five tables:

| Table | Columns | Purpose |
|---|---|---|
| `document` | `key` PK, `value` | Renderer draft store, keyed `${storage}:${key}` exactly as the renderer writes it |
| `blob` | `id` PK, `data` blob, `touched_at` | Binary attachments/cache |
| `state` | `name`, `key`, `value`, `updated_at`; PK `(name,key)` | Namespaced key/value replacing `electron-store` `.dat` files |
| `extension` | `id` PK, `enabled` bool, `manifest`, `revision` | Installed extension registry |
| `extension_file` | `extension_id`, `path`, `data` blob; PK | Extension archive members |

Two comments carry real intent: `document.key` mirrors the renderer's own draft-store key format, and `state.name` keeps `.dat`-style namespace names **so the shared app's relocation/alias logic is unchanged** by the desktop migration.

**Location:** `path.join(app.getPath("userData"), "drafts.sqlite")` â€” the historical name is kept on purpose (`storage/index.ts:22`) so a pre-existing drafts file is not moved.

**Why SQLite over files:** `packages/desktop/AGENTS.md` makes this a rule â€” "Avoid FS operations where possibleâ€¦ performance and EPERM and many other things, especially on windows can be quite painful. Using anything other than sqlite should come with strong reasons."

**Migration story, three layers:**
1. `storage/migration.ts` â€” a hand-rolled journal (`CREATE TABLE migration (id, time_completed)`), one transaction, `BEGIN`/`COMMIT`/`ROLLBACK`, and a **legacy adoption** path: if no migration is applied *and* a `document` table already exists, the first migration's statements are skipped because `drafts.sqlite` predates the journal.
2. `storage/legacy.ts` â€” `importLegacyStores` copies `default.dat` and `opencode.*.dat` into `state`, then deletes the files. It is documented as "the only place the storage layer touches the filesystem, and it runs before the first renderer request". Draft retention: 30 days and 100 most-recent files (`DRAFT_RETENTION_MS`, `DRAFT_KEEP_RECENT`). `onConflictDoNothing()` so an interrupted import never overwrites newer state.
3. `storage/index.ts` â€” `DesktopStorage.layer`; `setStorageSnapshotProvider` returns `{storage: {name: items(name)}, extensions: readEnableState(db.$client)}` for the preload's eager snapshot; flush on `before-quit` and per-window `session-end`.

**Still-files, deliberately:** `storage/store.ts` is a *synchronous* JSON settings store replacing `electron-store` (same tab-indented format, `.tmp` + rename, corrupt file renamed `.corrupt`, throws on `set(key, undefined)`). It carries a warning not to instantiate at module load because import hoisting would run it before `app.setPath("userData", â€¦)`. So settings stay a file; bulk state does not.

**Perf mechanism:** `storage/write-behind.ts` â€” coalesces a burst of writes into one batch, latest-value-wins per key, and on a failed write **re-queues** the batch ("The renderer already saw these writes succeedâ€¦ keep them queued so the next flush retries them; anything written for the same key since then takes precedence"). A 1.5 KB file that removes an entire class of data-loss bug.

**What is local vs core:** sessions, messages, providers, projects all live in the **sidecar's** database (core). Desktop-local is: window geometry/ids, drafts, `storage.*` namespaces, extension enable state and archives, bubble/background color, first-launch flag, bundled CLI version.

---

## 9. Packaging and distribution

`electron-builder.config.ts` (264 lines) with three channels.

**Channels and identities** (`src/main/constants.ts:23`, `electron-builder.config.ts:30`):

| Channel | appId | productName | update feed |
|---|---|---|---|
| `dev` | `ai.opencode.desktop.dev` | `OpenCode Dev` | none (dev CLI downloaded) |
| `beta` | `ai.opencode.desktop.beta` | `OpenCode Beta` | `https://opencode.ai/update/api/latest/desktop/opencode/â€¦` (beta path) |
| `prod` | `ai.opencode.desktop` | `OpenCode` | `https://opencode.ai/update/api/latest/desktop/opencode/`, `channel: "latest"` |

A subtlety: the `local` channel used by `bun dev` maps to the **dev** identity, so a dev build never collides with an installed beta/prod app.

**Packaging targets and artifacts:**

| Platform | Targets | Artifact | Notes |
|---|---|---|---|
| macOS | `dmg` + `zip` | `opencode-desktop-mac-<arch>.dmg/.zip` | `entitlements.plist` (allow-jit, audio-input); `entitlements.cli.plist` (allow-jit, disable-library-validation) applied **only** to `Contents/Resources/opencode-cli` because the Bun CLI dlopens `bun-pty` |
| Windows | `nsis` | `opencode-desktop-win-<arch>.exe` (+ `.blockmap`) | `installer.nsh` grants `*S-1-15-2-2` RX on `$INSTDIR` so the Chromium sandbox AppContainer can read it (links electron#49143); signing only on `win32` + `GITHUB_ACTIONS==="true"` via `script/sign-windows.ps1` |
| Linux | `AppImage`, `deb`, `rpm` | `opencode-desktop-linux-<arch>.<ext>` | fpm metainfo from `resources/<appId>.metainfo.xml`; `.desktop` entry pins `StartupWMClass=ai.opencode.desktop` and is `NoDisplay=true` so it does not duplicate in menus |

`artifactName: "opencode-desktop-${os}-${arch}.${ext}"` â€” one predictable naming scheme across all targets, which is what `scripts/publish.ts` globs.
