"""Two stale port/security claims left over from before ADR-012."""
import pathlib
import sys

ROOT = pathlib.Path(r"E:\Projects\opencode portable")

EDITS = [
    (
        ROOT / "docs/03-platform.md",
        "The WebView loading `http://127.0.0.1:PORT` fails silently",
        "The WebView loading `http://127.0.0.1:4096` fails silently",
        "03 platform port",
    ),
    (
        ROOT / "docs/04-decisions.md",
        "- **A better security posture fell out of it.** The engine's HTTP port now stays on `127.0.0.1` and is never exposed. There is no LAN listener, no token over the wire, and the Kotlin layer is the single audited boundary.",
        "- **A better security posture fell out of it.** The engine's HTTP port stays on `127.0.0.1` and never reaches the network: no LAN listener, no router dependency, nothing to forward. The one credential lives in app-private storage and in the loopback URL, and never crosses a wire to the internet ([ADR-012](#adr-012)).",
        "04 security consequence",
    ),
]

for path, old, new, label in EDITS:
    text = path.read_text(encoding="utf-8", newline="")
    n = text.count(old)
    if n != 1:
        sys.exit(f"FAIL {label}: {n} occurrences")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="")
    print(f"ok  {label}")
