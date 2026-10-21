import struct, sys
p = sys.argv[1]
d = open(p, 'rb').read()

# .bun section bounds from the ELF section table
e_shoff, = struct.unpack_from('<Q', d, 40)
e_shentsize, e_shnum, e_shstrndx = struct.unpack_from('<HHH', d, 58)
sh = struct.unpack_from('<IIQQQQIIQQ', d, e_shoff + e_shstrndx * e_shentsize)
shstr = sh[4]
secs = {}
for i in range(e_shnum):
    f = struct.unpack_from('<IIQQQQIIQQ', d, e_shoff + i * e_shentsize)
    nb = d[shstr + f[0]:]
    nm = nb[:nb.index(b'\x00')].decode()
    secs[nm] = (f[4], f[4] + f[5])   # offset, end

bun_off, bun_end = secs['.bun']
bun = d[bun_off:bun_end]
print("file           =", len(d))
print(".bun offset    =", bun_off, "end =", bun_end, "size =", len(bun))
print()

MAGICS = {
    'woff2 (wOF2)':   b'wOF2',
    'woff  (wOFF)':   b'wOFF',
    'ttf   (OTTO)':   b'OTTO',
    'ttf   (0x00010000)': b'\x00\x01\x00\x00',
    'png':            b'\x89PNG\r\n\x1a\n',
    'jpeg':           b'\xff\xd8\xff\xe0',
    'jpeg-c2':        b'\xff\xd8\xff\xe1',
    'gif':            b'GIF89a',
    'wasm  (\\0asm)': b'\x00asm',
    'gzip':           b'\x1f\x8b\x08',
    'zstd':           b'\x28\xb5\x2f\xfd',
    'xz':             b'\xfd7zXZ',
    'sqlite':         b'SQLite format 3\x00',
    'sourceMappingURL': b'sourceMappingURL',
    'assets/':        b'assets/',
    'import.meta':    b'import.meta',
    'createRequire':  b'createRequire',
    'opentui':        b'opentui',
}
print("%-20s %10s %10s" % ("marker", "count(.bun)", "count(file)"))
for k, m in MAGICS.items():
    print("%-20s %10d %10d" % (k, bun.count(m), d.count(m)))

# repeated-string histogram inside .bun to guess payload composition
print()
print("--- top 25 repeated 24-grams in .bun (cheap content sniff) ---")
from collections import Counter
c = Counter()
step = 1
for i in range(0, len(bun) - 24, 4096):
    c[bun[i:i+24]] += 1
for s, n in c.most_common(12):
    print("  %6d  %r" % (n, s))

# how much of .bun is printable ASCII (7-bit) vs binary
import statistics
sample = bun[:2000000]
printable = sum(1 for b in sample if 32 <= b < 127 or b in (9, 10, 13))
print()
print("ascii-printable ratio of first 2 MB of .bun = %.3f" % (printable / len(sample)))
whole = sum(1 for b in bun[:20000000] if 32 <= b < 127 or b in (9, 10, 13))
print("ascii-printable ratio of first 20 MB of .bun = %.3f" % (whole / 20000000))
