"""Append ADR-012 (fixed loopback port + one-time password) to docs/04-decisions.md."""
import pathlib
import sys

P = pathlib.Path(r"E:\Projects\opencode portable\docs\04-decisions.md")

ADR = """
---

<a id="adr-012"></a>
## ADR-012 — One fixed loopback port, and a password generated once

**Status:** Accepted

**Context.** Two properties of the upstream app collide with a mobile process model.

1. **All client state is origin-scoped.** The web build persists drafts, settings and the last
   route in `localStorage` (`platform.tsx`: *"Storage mechanism, defaults to localStorage"*;
   `pwa.ts:6`), and in production it talks to `location.origin`
   (`runtime/platform/web.ts:11-16`). The origin is `http://127.0.0.1:<port>`.
2. **A loopback port is not a private channel on Android.** Any app on the device can open a
   socket to `127.0.0.1`. By default `ServerAuth.required` is false when no password is configured
   (`packages/server/src/auth.ts:28-29`), so the engine would answer anyone who found the port.

On the desktop neither problem exists: the renderer is served through a registered custom scheme and
routes in memory (`packages/desktop/src/main/windows/`, `window/router.tsx`), so its origin is stable
and port-independent. On the phone, the page origin *is* the port.

A careless implementation picks a free port per launch. That silently resets every setting on every
launch, because a new port is a new origin with empty `localStorage` and a fresh service-worker
scope, and it looks like a data-loss bug rather than a porting mistake.

**Decision.**

1. **One fixed port**, chosen once and never varied. `4096` is the natural choice, matching the
   default already hard-coded in `runtime/platform/web.ts`.
2. **Bind `127.0.0.1` only.** Never `0.0.0.0`; the phone is not a server.
3. **Generate one 32-byte password on first launch**, store it in app-private storage, and pass it
   to the engine as `OPENCODE_PASSWORD` — the variable the CLI already reads
   (`packages/cli/src/env.ts:10`) and already generates in service mode
   (`packages/cli/src/services/standalone.ts:37`).
4. **Load the WebView at `http://127.0.0.1:4096/?auth_token=<base64("opencode:PASSWORD")>`** on
   every launch. The server accepts that query parameter
   (`packages/server/src/middleware/authorization.ts:12,44-52`), and the app consumes it and strips
   it from the URL before rendering (`packages/app/src/entry.tsx:45-50`).
5. If the port cannot be bound, retry once, then show an error screen naming the port. Do not
   silently migrate to another one.

**Consequence.** Settings, drafts, the last session and the service worker survive every launch, and
the loopback engine is no longer reachable by other apps. Both costs are one-time and small: a fixed
port can collide with another app, and the token has to be re-supplied on every launch because the
WebView may lose its stored credential. Neither is a design risk — they are the price of a stable
origin, and the alternative is a class of bug that only shows up on real devices.

**Note on the `auth_token` ordering.** `clearAuthToken()` runs before `restorePwaRoute()`, so the
route restore does not see the parameter and is not suppressed by the guard at `pwa.ts:22`. That
ordering is load-bearing; if the shell ever injects the token later, route restoration stops working.
"""

text = P.read_text(encoding="utf-8", newline="")
if "adr-012" in text:
    sys.exit("FAIL: adr-012 already present")
if not text.endswith("\n"):
    text += "\n"
text += ADR
P.write_text(text, encoding="utf-8", newline="")
print("appended ADR-012;", len(text.encode("utf-8")), "bytes")
