"""Apply the December-2026 verification corrections to 03-platform.md and TODO.md."""
import pathlib
import sys

ROOT = pathlib.Path(r"E:\Projects\opencode portable")

WEBVIEW_SECTION = """### Three WebView hooks a browser does not need

The web build is designed to run in a plain browser and is careful about what it asks for. Three of
those asks land on the Android host, and each is a single override.

| The page asks for | Without the hook | Hook |
|---|---|---|
| A file to attach | Attachments do nothing. The web platform has no `openAttachmentPickerDialog` at all, so this is the *only* route files get in | `WebChromeClient.onShowFileChooser` |
| Camera or microphone | `getUserMedia` rejects and no prompt appears | `WebChromeClient.onPermissionRequest`, then `request.grant(request.getResources())` |
| To play a sound | Silently dropped — 26 `.aac` UI sounds ship inside the engine, and media playback is gesture-gated by default | `settings.mediaPlaybackRequiresUserGesture = false` |

The QR pairing screen states the first two in one line: it is gated on
`platform.platform === "web" && window.isSecureContext && navigator.mediaDevices.getUserMedia`
(`packages/app/src/servers/connect/camera.ts:6`). Loopback satisfies `isSecureContext`, so the gate
passes; the permission grant is the only missing piece.

**Do not patch `index.html` to add a bridge.** The served CSP hashes the one inline script that page
carries (`oc-theme-preload-script`, see `packages/cli/src/services/web-ui.ts`), so editing that file
invalidates the hash and the UI fails to boot under its own policy. The native bridge goes in with
`addJavascriptInterface`, which exposes `window.*` to every page without touching a byte of the
served HTML.

"""

TODO_ROWS = [
    (
        "| P1-5 | FGS + engine launch | `specialUse` FGS spawns proot\u2192`opencode serve`; health poll reports ready; child stderr is visible in the UI on failure | P1-2, P1-4 | 3 d |",
        "| P1-5 | FGS + engine launch | `specialUse` FGS spawns proot\u2192`opencode serve` **on the fixed port 4096 with a generated `OPENCODE_PASSWORD`** ([ADR-012](docs/04-decisions.md#adr-012)); health poll reports ready; child stderr is visible in the UI on failure | P1-2, P1-4 | 3 d |",
        "P1-5",
    ),
    (
        "| P1-6 | WebView host | GUI loads on device; dark background before load (no white flash); no cleartext error; Android back navigates the UI rather than closing the app | P1-5 | 2 d |",
        "| P1-6 | WebView host | GUI loads on device at `http://127.0.0.1:4096/?auth_token=\u2026`, so settings and drafts survive relaunch; dark background before load (no white flash); no cleartext error; Android back navigates the UI; the [three hooks](docs/03-platform.md#three-webview-hooks-a-browser-does-not-need) are wired (file chooser, permission grant, media gesture) | P1-5 | 3 d |",
        "P1-6",
    ),
    (
        "| P2-2 | Voice bridge + mic button | A patched composer mic button starts native STT; partial words stream in; release settles the text; **nothing auto-sends** | P1-9 | 3 d |",
        "| P2-2 | Voice bridge + mic button | A composer mic button starts native STT; partial words stream in; release settles the text; **nothing auto-sends**. Only the capture half is new \u2014 upstream already renders voice-originated prompts and already exposes a server-brokered realtime voice route ([ADR-005](docs/04-decisions.md#adr-005)) | P1-9 | 3 d |",
        "P2-2",
    ),
]


def patch(path, edits, insertions=()):
    p = ROOT / path
    text = p.read_text(encoding="utf-8", newline="")
    for anchor, new_block, label in insertions:
        n = text.count(anchor)
        if n != 1:
            sys.exit(f"FAIL {path} insert {label}: {n} occurrences")
        text = text.replace(anchor, new_block + anchor, 1)
        print(f"ok  {path} insert {label}")
    for old, new, label in edits:
        n = text.count(old)
        if n != 1:
            sys.exit(f"FAIL {path} {label}: {n} occurrences")
        text = text.replace(old, new, 1)
        print(f"ok  {path} {label}")
    p.write_text(text, encoding="utf-8", newline="")
    print(f"    {path} -> {len(text.encode('utf-8'))} bytes")


patch(
    "docs/03-platform.md",
    (),
    (("\n---\n\n## Permissions\n", WEBVIEW_SECTION, "webview hooks"),),
)

patch("TODO.md", TODO_ROWS)
