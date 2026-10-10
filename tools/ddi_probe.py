# -*- coding: utf-8 -*-
"""[probe] What is inside the .ddi, and is there a per-frame label table?"""
import struct, sys, collections

path = sys.argv[1]
data = open(path, 'rb').read()
print('ddi size', len(data))

# walk the block list: [4B tag][u32 size][payload]
pos = 0
blocks = []
while pos + 8 <= len(data):
    tag = data[pos:pos+4]
    if not all(32 <= c < 127 for c in tag):
        break
    ln = struct.unpack('<I', data[pos+4:pos+8])[0]
    if ln > len(data) - pos - 8 or ln < 0:
        break
    blocks.append((tag.decode('latin-1'), pos + 8, ln))
    pos += 8 + ln

print('blocks: %d, consumed %d of %d bytes' % (len(blocks), pos, len(data)))
for tag, off, ln in blocks[:40]:
    print('   %-6s off=%-10d len=%d' % (tag, off, ln))
if len(blocks) > 40:
    print('   ... %d more' % (len(blocks) - 40))
print('tail after blocks: %d bytes' % (len(data) - pos))

# does any block look like a constant-stride table of 64-bit offsets?
for tag, off, ln in blocks:
    if ln % 8 == 0 and ln >= 8 * 1000:
        vals = struct.unpack_from('<%dQ' % min(ln // 8, 4000), data, off)
        mono = sum(1 for a, b in zip(vals, vals[1:]) if b >= a)
        print('   [%s] %d entries of 8B, monotone %.2f, first %s' % (tag, ln // 8, mono / max(1, len(vals) - 1), vals[:3]))
    if ln >= 1000 and ln % 1 == 0:
        # small-value table (bytes)?  look for a plausible label distribution
        seg = data[off:off + min(ln, 200000)]
        c = collections.Counter(seg)
        if len(c) < 90 and sum(c.values()) > 0:
            top = c.most_common(5)
            print('   [%s] len=%d distinct-bytes=%d top=%s' % (tag, ln, len(c), top))
