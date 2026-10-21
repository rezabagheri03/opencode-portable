"""Tighten ADR-012's line citations so every one names a line that actually exists."""
import pathlib
import sys

P = pathlib.Path(r"E:\Projects\opencode portable\docs\04-decisions.md")
text = P.read_text(encoding="utf-8", newline="")

FIXES = [
    (
        "(`runtime/platform/web.ts:11-16`)",
        "(`runtime/platform/web.ts`, `getCurrentServerUrl`)",
        "web.ts cite",
    ),
    ("`pwa.ts:6`", "`pwa.ts:4`", "pwa.ts line"),
    ("(`packages/app/src/entry.tsx:45-50`)", "(`packages/app/src/entry.tsx:44-50`)", "entry.tsx lines"),
    (
        "(`packages/server/src/middleware/authorization.ts:12,44-52`)",
        "(`packages/server/src/middleware/authorization.ts:12`, `decodeCredential`)",
        "authorization.ts cite",
    ),
]

for old, new, label in FIXES:
    n = text.count(old)
    if n != 1:
        sys.exit(f"FAIL {label}: {n} occurrences")
    text = text.replace(old, new, 1)
    print(f"ok  {label}")

P.write_text(text, encoding="utf-8", newline="")
print("04-decisions.md ->", len(text.encode("utf-8")), "bytes")
