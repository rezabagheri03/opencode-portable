import re

p = r"E:\Projects\opencode portable\docs\04-decisions.md"
raw = open(p, "rb").read()
nl = b"\r\n" if b"\r\n" in raw else b"\n"
lines = raw.decode("utf-8").split(nl.decode("utf-8"))

out, n = [], 0
for line in lines:
    m = re.match(r"^## ADR-(\d{3})", line)
    if m:
        n += 1
        out.append('<a id="adr-%s"></a>' % m.group(1))
    out.append(line)

if n:
    open(p, "wb").write(nl.join(x.encode("utf-8") for x in out))
print("anchors inserted =", n)
