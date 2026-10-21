import os, re, sys, json

ROOT = r"E:\Projects\opencode portable"
files = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    if ".git" in dirpath or "_artifacts" in dirpath or "opencode-src" in dirpath:
        continue
    for fn in filenames:
        if fn.endswith(".md"):
            files.append(os.path.join(dirpath, fn))

def slug(h):
    h = h.strip()
    h = re.sub(r"^#+\s*", "", h)
    h = h.lower()
    h = h.replace("`", "")
    out = []
    for ch in h:
        if ch.isalnum() or ch in " -_":
            out.append(ch)
    s = "".join(out)
    s = s.replace(" ", "-")
    return s

anchors = {}
for f in files:
    txt = open(f, encoding="utf-8").read()
    a = set()
    for line in txt.splitlines():
        if re.match(r"^#{1,6}\s", line):
            a.add(slug(line))
    for m in re.finditer(r'<a\s+id="([^"]+)"', txt):
        a.add(m.group(1))
    # dedupe suffixes github adds for repeats: -1, -2
    anchors[os.path.abspath(f)] = a

LINK = re.compile(r"\[([^\]]*)\]\(([^)\s]+)\)")
total = broken = 0
problems = []
for f in files:
    txt = open(f, encoding="utf-8").read()
    base = os.path.dirname(f)
    for m in LINK.finditer(txt):
        target = m.group(2)
        if target.startswith(("http://", "https://", "mailto:")):
            continue
        total += 1
        path, _, anchor = target.partition("#")
        if path:
            dest = os.path.abspath(os.path.join(base, path))
            if not os.path.exists(dest):
                broken += 1
                problems.append((os.path.relpath(f, ROOT), target, "FILE MISSING"))
                continue
        else:
            dest = os.path.abspath(f)
        if anchor and dest.endswith(".md"):
            if anchor not in anchors.get(dest, set()):
                broken += 1
                problems.append((os.path.relpath(f, ROOT), target, "ANCHOR MISSING"))

print("md files      =", len(files))
print("links checked =", total)
print("broken        =", broken)
for p in problems:
    print("  %-34s %-58s %s" % p)
