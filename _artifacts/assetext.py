import re, collections
data = open(r"_artifacts\musl\package\bin\opencode", "rb").read()
keys = set(m.group(0) for m in re.finditer(rb"_assets/[A-Za-z0-9_.\-]{1,80}", data))
ext = collections.Counter()
for k in keys:
    s = k.decode("latin1")
    base = s.rsplit("/", 1)[-1]
    e = base.rsplit(".", 1)[-1] if "." in base else "</none>"
    ext[e] += 1
print("total unique keys:", len(keys))
for e, n in ext.most_common():
    print(f"  .{e:<10s} {n}")
print("\nwasm keys:")
for k in sorted(keys):
    s = k.decode("latin1")
    if s.endswith(".wasm"):
        print("  ", s)
