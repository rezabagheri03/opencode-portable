import re, sys
p = r"_artifacts\musl\package\bin\opencode"
data = open(p, "rb").read()
print("size", len(data))
keys = [b"_assets/", b"index.html", b"registerSW.js", b"oc-theme-preload", b"webmanifest",
        b"sw.js", b"favicon-96x96-v3.png", b"manifest.webmanifest", b"_headers",
        b"icons/", b"assets/"]
for k in keys:
    n = data.count(k)
    first = data.find(k)
    print(f"{k.decode():24s} count={n:<5d} first={first}")
# show unique _assets/ neighbours
found = set()
for m in re.finditer(rb"_assets/[A-Za-z0-9_.\-]{1,60}", data):
    found.add(m.group(0))
print("\nunique _assets/ strings:", len(found))
for s in sorted(found)[:25]:
    print("  ", s.decode("latin1"))
