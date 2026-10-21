import struct, sys, os

p = sys.argv[1]
d = open(p, 'rb').read()
print("file bytes      =", len(d))
print("sha256          =", __import__('hashlib').sha256(d).hexdigest())

assert d[:4] == b'\x7fELF', "not ELF"
cls, enc = d[4], d[5]
assert cls == 2 and enc == 1, "expect ELF64 LE"

(e_type, e_machine, e_version, e_entry, e_phoff, e_shoff, e_flags,
 e_ehsize, e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx) = \
    struct.unpack_from('<HHIQQQIHHHHHH', d, 16)

MACH = {183: 'AArch64', 62: 'x86-64', 40: 'ARM', 3: 'x86'}
TYPE = {2: 'EXEC', 3: 'DYN (PIE)', 1: 'REL'}
print("e_machine       =", MACH.get(e_machine, e_machine))
print("e_type          =", TYPE.get(e_type, e_type))
print("e_entry         = 0x%x" % e_entry)
print("e_flags         = 0x%x" % e_flags)
print("e_phnum         =", e_phnum)
print("e_shnum         =", e_shnum)

PT = {1: 'LOAD', 2: 'DYNAMIC', 3: 'INTERP', 4: 'NOTE', 6: 'PHDR',
      7: 'TLS', 0x6474e551: 'GNU_STACK', 0x6474e553: 'GNU_PROPERTY',
      0x6474e552: 'GNU_RELRO', 0x6474e550: 'GNU_EH_FRAME'}

phdrs = []
for i in range(e_phnum):
    o = e_phoff + i * e_phentsize
    f = struct.unpack_from('<IIQQQQQQ', d, o)
    phdrs.append(f)

print("\n--- PROGRAM HEADERS ---")
print("%-14s %8s %10s %10s %12s %12s %12s %10s" %
      ("type", "flags", "offset", "vaddr", "filesz", "memsz", "align", "align=B"))
for (pt, pf, poff, pva, ppa, pfsz, pmsz, pal) in phdrs:
    print("%-14s %8s %10d 0x%08x %12d %12d 0x%08x %10d" %
          (PT.get(pt, hex(pt)), pf, poff, pva, pfsz, pmsz, pal, pal))

loads = [p for p in phdrs if p[0] == 1]
aligns = sorted({p[7] for p in loads})
print("\nLOAD p_align distinct =", [hex(a) for a in aligns])
print("16KB-page ready       =", all(t[7] >= 0x4000 for t in loads),
      "(needs p_align >= 0x4000)")
print("LOAD vaddr range      = 0x%x .. 0x%x" %
      (min(t[3] for t in loads), max(t[3] + t[6] for t in loads)))

# INTERP
interp = None
for (pt, pf, poff, pva, ppa, pfsz, pmsz, pal) in phdrs:
    if pt == 3:
        interp = d[poff:poff + pfsz].rstrip(b'\x00').decode()
print("PT_INTERP             =", interp if interp else "(none -> static)")

# dynamic
def read_dyn():
    dyn = None
    for (pt, pf, poff, pva, ppa, pfsz, pmsz, pal) in phdrs:
        if pt == 2:
            dyn = (poff, pfsz)
    return dyn

dyn = read_dyn()
needed, soname, rpath, runpath, strtab_vaddr, strsz = [], None, [], [], None, None
if dyn:
    ents = {}
    for o in range(dyn[0], dyn[0] + dyn[1], 16):
        tag, val = struct.unpack_from('<QQ', d, o)
        if tag == 0:
            break
        ents.setdefault(tag, []).append(val)
    strtab_vaddr = (ents.get(5) or [None])[0]
    strsz = (ents.get(10) or [None])[0]
    # map vaddr -> offset
    def v2o(v):
        for (pt, pf, poff, pva, ppa, pfsz, pmsz, pal) in phdrs:
            if pt == 1 and pva <= v < pva + pfsz:
                return poff + (v - pva)
        return None
    so = v2o(strtab_vaddr) if strtab_vaddr is not None else None
    def gstr(idx):
        if so is None:
            return "?"
        b = d[so + idx:]
        return b[:b.index(b'\x00')].decode('utf-8', 'replace')
    for v in ents.get(1, []):
        needed.append(gstr(v))
    soname = gstr(ents[14][0]) if 14 in ents else None
    rpath = [gstr(v) for v in ents.get(15, [])]
    runpath = [gstr(v) for v in ents.get(29, [])]

print("\n--- DYNAMIC ---")
print("DT_NEEDED             =", needed if needed else "(none -> static)")
print("DT_SONAME             =", soname)
print("DT_RPATH              =", rpath)
print("DT_RUNPATH            =", runpath)

# sections
print("\n--- SECTIONS (top 20 by size) ---")
stroff = e_shoff + e_shstrndx * e_shentsize
sh = struct.unpack_from('<IIQQQQIIQQ', d, stroff)
shstr_off = sh[4]
secs = []
for i in range(e_shnum):
    f = struct.unpack_from('<IIQQQQIIQQ', d, e_shoff + i * e_shentsize)
    secs.append(f)
def sname(n):
    b = d[shstr_off + n:]
    return b[:b.index(b'\x00')].decode('utf-8', 'replace')
named = [(sname(s[0]), s[3], s[4], s[5], s[2]) for s in secs]
total_sec = sum(x[3] for x in named)
print("sum(section size)     =", total_sec, "(file:", len(d), ")")
for (n, addr, off, size, flags) in sorted(named, key=lambda x: -x[3])[:20]:
    print("  %-22s addr=0x%09x off=%-10d size=%-11d %s" %
          (n, addr, off, size, 'ALLOC' if flags & 2 else ''))

print("\n--- SECTION NAME LIST ---")
print(", ".join(n for (n, a, o, s, f) in named))

# targeted byte hunts
print("\n--- MARKER SEARCH ---")
marks = [b'opencode.ai', b'/api/', b'brotli', b'Bun\x00', b'bun.js', b'skia',
         b'Skia', b'opentui', b'ripgrep', b'workerd', b'sqlite', b'libc',
         b'console', b'zed.dev', b'node_modules', b'assets/index', b'.br',
         b'anthropic', b'openai', b'deepseek', b'big-pickle']
for m in marks:
    c = d.count(m)
    first = d.find(m)
    print("  %-16s count=%-6d first_off=%s" %
          (m.decode('latin1').strip('\x00'), c, first))
