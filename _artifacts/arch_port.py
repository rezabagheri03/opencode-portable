"""Correct 02-architecture.md: a loopback port is not private on Android (ADR-012)."""
import pathlib
import sys

P = pathlib.Path(r"E:\Projects\opencode portable\docs\02-architecture.md")
text = P.read_text(encoding="utf-8", newline="")

STEP6_OLD = "6. **proot launches** `opencode serve --port PORT --hostname 127.0.0.1` inside the rootfs, with the workspace bind-mounted."
STEP6_NEW = "6. **proot launches** `opencode serve --port 4096 --hostname 127.0.0.1` inside the rootfs, with the workspace bind-mounted and `OPENCODE_PASSWORD` set from app-private storage."

STEP8_OLD = "8. **WebView loads** `http://127.0.0.1:PORT`. Its background colour is set to the app's dark background first, so there is no white flash."
STEP8_NEW = "8. **WebView loads** `http://127.0.0.1:4096/?auth_token=<base64(user:password)>`. The app consumes the parameter, strips it from the URL and keeps it as the connection credential ([ADR-012](04-decisions.md#adr-012)). Its background colour is set to the app's dark background first, so there is no white flash."

SEC_OLD = "Note what is **not** in that diagram: an open port. `opencode serve` binds `127.0.0.1` and nothing else. The Kotlin app is the only HTTP client, and the watch reaches the phone through Google's Data Layer, not through the network. There is no LAN listener to secure, no token to leak, and no router configuration to depend on."
SEC_NEW = """Note what is **not** in that diagram: a listener the network can reach. `opencode serve` binds `127.0.0.1` and nothing else, and the watch reaches the phone through Google's Data Layer rather than over the network — so there is no LAN listener, no router configuration, and no Wi-Fi pairing step to get wrong.

**Loopback is not private on Android, though.** Any app on the device can connect to `127.0.0.1`, and the server answers unauthenticated unless a password is configured (`packages/server/src/auth.ts:28-29`). The shell therefore generates a password once, keeps it in app-private storage, exports it as `OPENCODE_PASSWORD`, and loads the WebView with it in the URL. That is also why the port is **fixed** rather than chosen per launch: the page's origin is its port, and settings, drafts and the service worker are all origin-scoped. See [ADR-012](04-decisions.md#adr-012)."""

for old, new, label in (
    (STEP6_OLD, STEP6_NEW, "startup step 6"),
    (STEP8_OLD, STEP8_NEW, "startup step 8"),
    (SEC_OLD, SEC_NEW, "loopback security note"),
):
    n = text.count(old)
    if n != 1:
        sys.exit(f"FAIL {label}: {n} occurrences")
    text = text.replace(old, new, 1)
    print(f"ok  {label}")

before = text.count("127.0.0.1:PORT")
text = text.replace("127.0.0.1:PORT", "127.0.0.1:4096")
print(f"ok  port placeholder replaced x{before}")

P.write_text(text, encoding="utf-8", newline="")
print("02-architecture.md ->", len(text.encode("utf-8")), "bytes")
