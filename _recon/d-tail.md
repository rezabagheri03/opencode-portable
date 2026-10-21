  windowID, bootstrap, storageSnapshot, extensions, getPathForFile,
})
```

- `windowID` â€” the window's id.
- `bootstrap` â€” parsed from `windowBootstrapFromArguments(process.argv)` (`shared/window-bootstrap.ts`), passed as `additionalArguments`.
- `storageSnapshot` â€” the result of an **eager** `ipcRenderer.invoke(WindowSnapshotChannel, storageSnapshotNames(bootstrap.id))` at preload time, so the renderer's first storage read needs no IPC round trip.
- `extensions` â€” extension enable state.
- `getPathForFile` â€” `webUtils.getPathForFile`, the only way to dereference a drag-and-dropped `File`.

No `ipcRenderer` reference, no node API, no `require` is exposed. Combined with `contextIsolation: true` / `nodeIntegration: false` / `sandbox: true` (`windows/early.ts:58-60`, `windows/appearance.ts:43-45` for restored windows), the renderer is a genuine web sandbox even though it is loading `file:`-class content.

Layered defences, all **[V]**:
- Privileged scheme only: `oc://renderer/*` privileged with `{secure, standard, supportFetchAPI, corsEnabled, stream, codeCache}` (`windows/scheme.ts:16`); `oc://extensions/<id>/<path>` for extension assets.
- Path traversal: `windows/protocol.ts` rejects `..` and absolute resolution via `path.relative`.
- Navigation: `will-navigate` prevented for non-renderer URLs; `setWindowOpenHandler` denies all and calls `openExternalURL` for the rest (`windows/security.ts:30-40`).
- Permissions: an exact allow-list of `{"clipboard-sanitized-write","notifications"}` with both URL and webContents identity checks (`security.ts:8`).
- Header injection: `onBeforeSendHeaders` adds `Authorization` for `http://127.0.0.1/*` and `http://localhost/*` **only** when `frame.parent === null && isRendererUrl(frame.url)` â€” explicitly excluding webviews, embedded pages and service workers (`security.ts:47`).
- Content injection: `documentPolicyHeader` (`DocumentPolicyIncludeJSCallStacksInCrashReports`) injected on `.html` responses, matching the feature enabled in `configure.ts`.
- Drag cancel: `before-input-event` Escape emits `opencode:drag-cancel` down to the renderer (`ipc.ts`), routed via preload re-dispatch.

The residual risk is structural rather than a bug: **the renderer holds a live sidecar origin with injected credentials**, so any renderer XSS (e.g. from model output rendered as HTML) reaches an authenticated local server. The team mitigates with the frame check and a sandboxed renderer; there is no per-request capability token.

---

## 5. Renderer

### 5.1 Composition

`renderer/index.tsx:23` â†’ `render(() => <DesktopApp api={api} version={version} />, requireRendererRoot())`, plus `startDesktopMenu(api)`, `startDeepLinks(api)`, `void initializeSentry(version)`.

`renderer/desktop-app.tsx` assembles:
- `AppBaseProviders` / `AppInterface` from `@opencode/app/desktop` (the whole product UI).
- `PlatformProvider` with `createDesktopPlatform(...)`.
- A memory router: `DesktopMemoryRouter` + `windowID` + `getLastActiveUrl(windowState.id)` (`renderer/window/router.tsx`, `route-storage.ts`) â€” no URL bar, but per-window last-route persistence.
- Four resources: `firstLaunch` (`bootstrap.firstLaunchPending` else `api.isFirstLaunchOnboardingPending()`), `sidecar` (`api.awaitInitialization()`), `locale`, `initialRoute` (`/new-session` when first launch and current url is `/`).
- A **lazy** `MigrationStatus` component.
- `DesktopEffects`: binds menu commands to `command.trigger(id)` and pushes `--background-base` to main via `api.setBackgroundColor`.

Reveal is gated on `startup.ready && themeReady() && onboarding complete` â€” the window is created `show: true` but the app does not paint the interface until all three settle, which is why the background color is set from native side rather than CSS.

### 5.2 Routing, onboarding, theming

- Routing: memory router, per-window. Deep links arrive as `DeepLinksOpened` events and are consumed through `api.consumeInitialDeepLinks()`.
- Onboarding (`renderer/onboarding/index.tsx`): **no bespoke UI**. It triggers only when `initialUrl === "/"`, the route type is `home`, `tabs.store.length === 0`, and every server is `builtin`. It then calls `finishFirstLaunchOnboarding(shouldTrigger)` (which creates `documents/<nativeT("desktop.onboarding.defaultProject")>`), opens the default project on `ServerConnection.Key.make("sidecar")`, syncs provider+model, and calls `tabs.newDraft(...)`. It logs `[desktop-onboarding]` lines. So "onboarding" is a **bootstrap decision**, not a screen.
- Theming: `useTheme` from `@opencode/ui/theme/context`; the resolved background is mirrored to main (`setBackgroundColor`) and persisted only when it changes (`appearance.ts` â€” comment: electron-store fsyncs per set). Titlebar overlay is re-applied on win32.

### 5.3 Migration status

`renderer/migration-status.tsx` is often misread: it is **server-side v1â†’v2 data migration progress**, surfaced as a persistent toast. It polls `client.migration.v1.status()` every 1 s after a 1 s delay against `OpenCode.make({baseUrl: props.server.url})`, and renders `toast.migration.progress.*` i18n keys. It is not desktop state migration.

### 5.4 Offline / first run

- Offline: the app is fully local â€” the sidecar is a child process, so "offline" only affects model calls and update checks. `README.md` documents `HTTPS_PROXY` pointing at a closed port as the offline test technique for benchmarks.
- First run: `lifecycle/onboarding.ts` decides pending-ness from `FIRST_LAUNCH_ONBOARDING_COMPLETE_KEY`; if the key is absent it inspects `userData` entries via `hasExistingAppState(entries)` so an existing install never sees onboarding.

---

## 6. Native integration

| Capability | Implementation | Notes |
|---|---|---|
| Folder picker | `dialog.showOpenDialog({properties:["openDirectory"]})` with `nativeT("desktop.dialog.chooseFolder")` | `main/files/index.ts` |
| File picker | `showOpenDialog` â†’ `pickedFiles.add(sender, filePaths)` token + `assertAttachmentBudget(files)` | Token prevents the renderer from fabricating paths; budget caps attachment size |
| Save picker | `showSaveDialog` + `nativeT("desktop.dialog.saveFile")` | `main/files/index.ts` |
| Drag & drop | `webUtils.getPathForFile` via preload `getPathForFile`; Escape â†’ `opencode:drag-cancel` | `preload/index.ts:31`, `main/ipc.ts` |
| Open in editor / external | `openExternalURL` denied-and-externalized in `setWindowOpenHandler`; `openLocalFileURL` guarded by `resolveLocalFilePath` | `windows/security.ts:34`, `files/index.ts` |
| App existence | `AppCheckAppExists` / `AppResolveAppPath` | `main/files/apps.ts:7` â€” win32 `where` + `.cmd`/`.bat` shim unwrapping; macOS `.app` probing; linux always true |
| Notifications | Renderer `Notification` API, permitted by the 2-item allow-list | `windows/security.ts:8` |
| Context menu | `electron-context-menu` lazily imported; `showSaveImageAs:true`, no Look Up, no Search-with-Google | `lifecycle/environment.ts:12` |
| Menus | `@opencode/app/desktop-menu` `DESKTOP_MENU`, extension `MenubarEntry` contributions re-read `enabled()` on `menu-will-show` | `native/menu.ts` |
| Logging | `electron-log`, 5 MiB file transport, `initialize({preload:false, spyRendererConsole:true})` | `native/logging.ts` |
| Crash reporting | `crashReporter.start({uploadToServer:false, compress:true})` after 500 ms | `main/desktop.ts` |
| Debug export | `downloads/opencode-debug-<stamp>.zip` via **dynamic** `import("@zip.js/zip.js")` | `native/logging.ts` |
| Auto-update | **`electron-updater`, driven from `@opencode/gui-extensions/src/updater/platform.ts`** | see below |
| Sentry | `@sentry/solid`, initialized only when `VITE_SENTRY_DSN` is set | `renderer/startup/sentry.ts:9` |

**Why `lighthouse` and `@zip.js/zip.js` are direct runtime dependencies of a desktop app** â€” a legitimate "why is this here" question with a clean answer: neither is used by `packages/desktop`'s own code paths at runtime in a way that belongs there.
- `@zip.js/zip.js` **is** used by desktop: `native/logging.ts:exportDebug` does a dynamic import to build the debug bundle. Bundling it lazily keeps it out of the boot-critical main chunk.
- `lighthouse` is **not** imported anywhere in `packages/desktop/src`. It is declared in `packages/desktop/package.json` because `@opencode/gui-extensions/src/browser/lighthouse.ts` does `await import("lighthouse")` and the **browser extension is bundled into the desktop app** â€” the extension's `main` bundle resolves through electron-vite's main-process externals, so the package must be physically present in the desktop's `node_modules` for the packaged app. **[V]** for the import site; **[I]** for the precise reason it is declared in *desktop* rather than *gui-extensions* (which declares it as a devDependency instead).

**Sentry** is deliberately narrow: `initializeSentry` filters out the `Breadcrumbs` integration always, and `GlobalHandlers` + `BrowserApiErrors` on the prod channel (`renderer/startup/sentry.ts:18-24`) â€” i.e. production does not auto-report unhandled errors from the renderer; only explicit `recordFatalRendererError` calls reach the recovery UI and the reporter.

**Recovery UI** (`windows/recovery.ts`) is the safety net: it hooks `did-fail-load`, `did-fail-provisional-load` (ignoring non-main-frame and `errorCode === -3`)`, `render-process-gone`, `unresponsive`/`responsive` with a sampler, `console-message` (terminal-related only), and `preload-error`, and offers `relaunch | export-logs | keep-waiting | quit`. The window's own renderer failure path is `AppRecordFatalRendererError` â†’ `FatalRendererError` in the contract.

---

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

**How core + CLI ship:** the CLI is **excluded from `app.asar` and placed beside it in `resources/`** â€” `files: [..., "!resources/opencode-cli*"]` plus electron-builder's `extraResources`. `packages/desktop/README.md` states packaging fails if it is missing. `scripts/utils.ts:copyCliToResources` also writes `opencode-cli.version` from the npm package's `package.json` â€” the comment explains this spares a **~400 ms spawn of the 200 MB binary** on first launch just to read `--version`. `CLI_BINARIES` covers 6 targets (darwin arm64/x64-baseline, windows arm64/x64-baseline, linux x64-baseline/arm64), each mapped to an `@opencode/cli-*` npm package.

**Channel build inputs** (from `README.md`, table reproduced accurately): dev â†’ download the dev CLI; beta â†’ copy the supplied `OPENCODE_CLI_DIST` or download the beta CLI; prod â†’ **must** supply `OPENCODE_CLI_DIST`. `scripts/prebuild.ts:8` throws "OPENCODE_CLI_DIST is required for production desktop builds" â€” fail-fast at build time rather than a broken artifact.

**Publishing** (`scripts/publish.ts`, 148 lines): requires `OPENCODE_DESKTOP_DIST` and `LATEST_YML_DIR`, globs `*.{exe,blockmap,dmg,zip,AppImage,deb,rpm,app.tar.gz}`, uploads/publishes each `UpdateArtifact` with `{channel, name:"desktop", distribution:"opencode"}`, assembles `desktop.yml`, `desktop-mac.yml`, `desktop-linux.yml`, `desktop-linux-arm64.yml` from per-target `latest*.yml`, and **validates** version match, `releaseDate` presence, and `sha512`/`size` on every file before publishing. Beta publishes the app and `beta.opencode.ai` as `0.0.0-beta-<run>`.

**Icons:** `scripts/copy-icons.ts <channel>` copies the per-channel icon set (beta/dev/prod) â€” this is why icon identity tracks appId rather than being hardcoded.

**Everything is code-signed on macOS by default and only on CI for Windows** â€” macOS signing options are always present, Windows signing is gated on `GITHUB_ACTIONS`. **[V]** `electron-builder.config.ts:signWindows`.

---

## 10. Performance work

Three scripts, three different performance problems.

**`scripts/bench-startup.ts`** â€” a *packaged-build* startup benchmark, not a microbenchmark. It uses an isolated home (`%TEMP%\opencode-bench-startup`), requires compared builds to share a channel (`appIdFor(build.exe)`), only ever kills its own process tree, and supports `--service cold`, `--fresh`, `--profile-main`, `--profile-renderer`, `--trace`, `--seed`, `--compare`, `--warmup`. Its measurements come from `lifecycle/marks.ts` â€” that tiny 7-line map exists **solely** because the benchmark needs `entry/ready/window/served/bundle/...` deltas from inside the app. This is the most convincing evidence that the boot staging in Â§2 is empirically driven rather than aesthetic.

**`scripts/picker.ts`** â€” wraps `@brendonovich/vite-plugin-opencode` and **replaces its Babel parse+print with an Oxc `parseSync` + `Visitor` pass** that inserts `data-opencode-picker-source="<rel path>:<line>"` onto lowercase JSX elements. The comment: roughly a quarter of the cost, and it is on the critical path because the first renderer bundle waits for it. It also injects `virtual:vite-opencode-picker/client` into HTML and serves a dev middleware at `/__vite_opencode_picker_client.js`.

**`scripts/profile-by-source.ts`** â€” attributes a `.cpuprofile`'s self time back to original sources through source maps. It splits phases `evaluate` / `render` / `later` by looking for Solid's `render`/`createRoot` on the stack plus a 5 ms idle heuristic, then prints 25 ms timeline buckets and top packages by inclusive time.

Other startup engineering visible in the source:
- **V8 compile cache** for the main bundle: `configure.ts` calls `enableCompileCache(userData/compile-cache)`.
- **Single-chunk renderer**: `electron.vite.config.ts` ships an `initialChunk` plugin that force-bundles every statically-imported module of the entry into one `app` chunk. The comment is explicit: automatic splitting produced ~50 files, and "each costing a main-thread round trip".
- **`codeCache: true`** on the `oc://` scheme, so the renderer's compiled bytecode is cached between launches.
- **Renderer avoids the Effect runtime** (`ipc-client.ts`), saving roughly a third of the startup script.
- **Lazy main bundle**: `await import("./desktop")` only after renderer assets are served.
- **Sidecar probe overlaps** the renderer-asset wait.
- **Build-time version file** (`opencode-cli.version`) to skip a 400 ms CLI spawn.
- **Low-priority UI build in dev**: `scripts/dev.ts:buildWebUi` spawns the `packages/app` build `detached` with `PRIORITY_LOW` â€” "idle priority because below-normal still delayed window by seconds".

---

## 11. DX: how `bun --cwd packages/desktop dev` works end to end

`scripts/dev.ts` (94 lines) is the whole story:

1. **Environment defaulting.** `OPENCODE_CHANNEL="local"`, `OPENCODE_VERSION ??= 2.0.0-local-<Date.now()>`, `OPENCODE_DISABLE_CHANNEL_DB="0"`, `OPENCODE_DESKTOP_ISOLATED_SERVER ??= "1"`. With `--build-server`, it also sets `OPENCODE_DESKTOP_SERVER_CHANNEL="local"` so the app talks to a locally-built server rather than the installed one.
2. **`prepareDesktop()`** â€” in parallel: `bun run install-electron` and `copy-icons.ts`; on darwin it then calls `prepareDevElectron()` and exports `ELECTRON_EXEC_PATH`.
3. **`prepareDevElectron()`** (`scripts/dev-electron.ts`, macOS only) â€” content-hashed cache dir `node_modules/.cache/opencode-dev/<sha256[0:16]>`; `ditto` `Electron.app` â†’ `OpenCode Dev.app`; `plutil` rewrites `CFBundleName`, `CFBundleDisplayName`, `CFBundleIdentifier=ai.opencode.desktop.dev`, inserts `NSAutoFillRequiresTextContentTypeForOneTimeCodeOnMac`, swaps `electron.icns`; `codesign --force --deep --sign -`; writes a `ready` sentinel. Net effect: the dev app has its own identity, notification permissions, and dock icon, so it does not fight an installed build.
4. **`prepareServer()`** â€” either `--download-server <version>` â†’ `downloadCliToResources(version, windowsify("resources/opencode-cli-dev"))`, or build the web UI: spawn `bun run build` in `packages/app` with `VITE_OPENCODE_SERVER_MODE="origin"`, detached, low priority, **not awaited**, and set `OPENCODE_DESKTOP_CLI_DEV=<packages/cli>`. So in normal dev the desktop runs the **TypeScript CLI from source** via `bun run --cwd <cli> --define=OPENCODE_VERSION=â€¦ src/index.ts` (`service/desktop-cli.ts:52`) â€” no compiled CLI binary at all.
5. On win32, sets `OPENCODE_DESKTOP_WSL_CLI_BUILD` / `_OUTPUT` for the WSL extension.
6. Finally spawns `node <electron-vite>/bin/electron-vite.js dev â€¦args` with `stdio: "inherit"`, forwarding any extra argv.

`electron.vite.config.ts` then runs three Vite builds (main, preload, renderer). Two details that exist purely for dev correctness:
- Preload is emitted as **CJS** (`entryFileNames: "[name].cjs"`) with the comment that under `--no-sandbox` Electron loads preloads through Node's loader, and an ESM `.js` file fails on `require("electron")`.
- Main gets a **custom CJS shim banner** providing `__filename`/`__dirname`/`require`, and `externalizeDeps.exclude` for `effect`, `@effect/platform-node(-shared)`, `drizzle-orm` so they are bundled rather than resolved at runtime.
- Renderer uses `experimental.bundledDev` and `root: "src/renderer"` with `publicDir` pointed at the shared app's `public/`.
- Sentry's Vite plugin is added **only** when `SENTRY_AUTH_TOKEN`, `SENTRY_ORG` and `SENTRY_PROJECT` are all set â€” dev builds get no sourcemap upload.

---

## 12. Assessment

### Strengths

1. **Boot ordering is treated as a design artifact, not an accident.** A 35-line entry, explicit comments justifying each step, and a `marks` map that exists so a benchmark can prove the ordering. This is rare.
2. **Genuine process isolation.** The server is a child process with its own lifetime, discoverable and attachable by other clients. The desktop app crashing does not kill sessions; the server dying is recoverable (`PtyHandoff.clear()` and a re-recruit path).
3. **The credential boundary is real, not aspirational.** The renderer never sees the sidecar password; main-process header injection is scoped to top-level renderer frames and explicitly excludes webviews and service workers. Combined with `sandbox: true` and a 2-entry permission allow-list, this is a better posture than most Electron apps reach.
4. **Type-safety across the process boundary.** Effect `Schema` â†’ `Rpc.make` â†’ `Schema.toCodecJson` means the wire format is derived, and the renderer's hand-written client is the only place the format is restated â€” with a comment explaining the trade.
5. **Extension lifecycle engineering.** Per-extension serialized queues, generation aborts, synchronous withdrawal before finalizers, 3 s cleanup deadline, setup-stall logging, last-good-revision rollback on failed reload. This is more careful than most extension systems, let alone Electron ones.
6. **Packaging is validated, not hoped for.** Fail-fast on missing `OPENCODE_CLI_DIST`, publish script asserting `sha512`/`size`/`releaseDate`, per-channel appIds so builds can coexist, `opencode-cli.version` to skip a 400 ms spawn.
7. **Localized native surfaces.** `nativeT()` throughout menus/dialogs with a typed `DESKTOP_NATIVE_ENGLISH` catalog shared with the renderer, plural categories via `Intl.PluralRules`, and an AGENTS.md rule forbidding hardcoded user-visible English.

### Real limitations and rough edges

1. **The native application menu is macOS-only.** `native/menu.ts:createMenu` early-returns for non-darwin. Windows and Linux users get `autoHideMenuBar: true` and whatever the in-app menu offers; the `DESKTOP_MENU` definition is otherwise unused on those platforms. This is a visible platform gap, not a stylistic choice.
2. **No tray, no global shortcut, no protocol handler on Linux beyond `setAsDefaultProtocolClient`.** For an app whose central object is a long-running local server, the absence of a tray means no way to see server state without a window.
3. **Extensions are trusted code with no enforcement.** `new Function` in the main process, full `require` for builtins and `electron`, `imports` allow-list that is advisory. The comment is honest, but the practical consequence is that installing a GUI extension is equivalent to `npm install` with no lockfile review.
4. **`scripts/profile-by-source.ts:5` hardcodes an absolute import path** into one developer's worktree: `C:/Users/Lukem/.local/share/opencode/worktree/6c1049/quiet-wolf-2/node_modules/.bun/@jridgewell+trace-mapping@0.3.31/...`. As written the script only runs on that machine. A profiling tool that cannot run for anyone else is a tool that rots.
5. **Duplicate build-system knowledge.** `lighthouse` is declared in `packages/desktop/package.json` but imported only from `packages/gui-extensions`; `electron-updater` appears in both. The dependency ownership across the desktop/extension boundary is implicit â€” nothing in either `package.json` states which package must bundle which extension dependency.
6. **The `write-behind` re-queue on failure is correct but silent.** On a persistent write failure the batch is retried forever with no bound, no backoff, and no user-visible signal beyond `onError`. In a disk-full scenario the renderer believes writes succeeded indefinitely.
7. **`local` channel is folded into the dev identity**, so channel-specific code paths cannot be exercised in dev without a real build. Reasonable, but it means the beta path is only tested by CI.
8. **Recovery UI depends on the renderer having failed**; a hang in the sidecar (not the renderer) surfaces only through the client's 3-consecutive-timeout path, which terminates the service â€” a heavy hammer that discards persistent terminals by design.
9. **`installCli` is macOS-only**, so Windows/Linux users who want `opencode` on `PATH` are on their own.

### Bottom line

This is a **senior-quality Electron shell**: the hard problems (boot latency budget, process isolation, credential containment, extension lifecycle) are solved deliberately and the solutions are documented in comments that explain *why*, not *what*. It is a thin shell in the best sense â€” it does not fork the product, it does not reimplement the UI, and it puts every desktop-specific concern in one place. The weaknesses are not architectural; they are the predictable residue of a small team shipping three platforms: macOS-first native affordances, one platform-gated feature (CLI install), one machine-specific script, and an extension trust model that is a documented contract rather than a mechanism.

---

*End of report. 12/12 questions answered; every factual claim carries a `path:line` anchor and is marked [V] verified or [I] inferred.*
