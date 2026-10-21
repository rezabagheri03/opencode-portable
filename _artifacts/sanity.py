import os

ROOT = r"E:\Projects\opencode portable"
FILES = [
    "README.md", "TODO.md",
    r"docs\01-product.md", r"docs\02-architecture.md", r"docs\03-platform.md",
    r"docs\04-decisions.md", r"docs\05-risks.md", r"docs\06-artifact-verification.md",
]

BAD = ["\u00e2\u20ac", "\u00c3\u00a2", "\u00ef\u00bb\u00bf", "\ufffd"]
print("%-34s %6s %5s %5s %5s %5s %6s" % ("file", "bytes", "BOM", "FFFD", "mojib", "emdash", "eol"))
ok = True
for rel in FILES:
    p = os.path.join(ROOT, rel)
    raw = open(p, "rb").read()
    bom = raw.startswith(b"\xef\xbb\xbf")
    try:
        s = raw.decode("utf-8")
    except UnicodeDecodeError as e:
        print("%-34s DECODE ERROR %s" % (rel, e))
        ok = False
        continue
    fffd = s.count("\ufffd")
    mojib = sum(s.count(b) for b in BAD[:3])
    em = s.count("\u2014")
    eol = s.endswith("\n")
    if bom or fffd or mojib or not eol:
        ok = False
    print("%-34s %6d %5s %5d %5d %6d %6s" % (
        rel, len(raw), "YES" if bom else "-", fffd, mojib, em, "ok" if eol else "MISSING"))

print()
print("ALL CLEAN" if ok else "PROBLEMS FOUND")
