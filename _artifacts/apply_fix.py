import io

P = r"E:\Projects\opencode portable\docs\04-decisions.md"

PAIRS = [
    ("+15 MB compressed rootfs", "+~16 MB compressed rootfs"),
    (
        "working `git`, `bash`, and `ripgrep` — every one of which the agent needs",
        "working `git`, `bash`, `ripgrep` and the `libstdc++.so.6` that the engine "
        "itself links against — every one of which the agent needs",
    ),
    ("Cost: a 270 MB APK", "Cost: a ~125 MiB APK"),
]

raw = open(P, "rb").read()
nl = b"\r\n" if b"\r\n" in raw else b"\n"
s = raw.decode("utf-8")

for old, new in PAIRS:
    n = s.count(old)
    if n != 1:
        raise SystemExit("EXPECTED 1 OCCURRENCE, GOT %d FOR: %r" % (n, old[:60]))
    s = s.replace(old, new)

open(P, "wb").write(s.encode("utf-8"))
print("ok: %d replacements applied, %d bytes" % (len(PAIRS), len(s.encode("utf-8"))))
