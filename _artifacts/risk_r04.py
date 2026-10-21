"""Lower R-04's likelihood now that the web build is verified browser-first."""
import pathlib
import sys

P = pathlib.Path(r"E:\Projects\opencode portable\docs\05-risks.md")
text = P.read_text(encoding="utf-8", newline="")

OLD = "| **R-04** | WebView GUI is unusable on a phone screen \u2014 layout, keyboard, diffs | Medium | High | Mobile CSS pass in P1, tested on glass before P2 starts | The GUI cannot be made usable with CSS-only patches, which forces a native client and a plan rewrite |"
NEW = "| **R-04** | WebView GUI is unusable on a phone screen \u2014 layout, keyboard, diffs | Low\u2013Medium | High | Mobile CSS pass in P1, tested on glass before P2 starts. Lowered: the web build is already a first-class browser target \u2014 keyboard insets via `visualViewport` (`packages/app/src/runtime/platform/keyboard.ts`), `safe-area-inset-bottom` handling, standalone detection and route restore (`pwa.ts`) | The GUI cannot be made usable with CSS-only patches, which forces a native client and a plan rewrite |"

n = text.count(OLD)
if n != 1:
    sys.exit(f"FAIL R-04: {n} occurrences")
P.write_text(text.replace(OLD, NEW, 1), encoding="utf-8", newline="")
print("ok  R-04 likelihood lowered")
