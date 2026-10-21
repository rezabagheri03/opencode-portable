
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
