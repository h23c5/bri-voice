# -*- coding: utf-8 -*-
import struct, sys, collections
p = sys.argv[1]
d = open(p, 'rb').read()
print('size', len(d))
print('first 128 bytes:')
for i in range(0, 128, 16):
    chunk = d[i:i+16]
    print('  %06x  %s  %s' % (i, ' '.join('%02x' % c for c in chunk),
          ''.join(chr(c) if 32 <= c < 127 else '.' for c in chunk)))
for tag in (b'DBSe', b'PHDC', b'PHG2', b'PHG1', b'PHON', b'IDX ', b'IDX2'):
    idx = []
    start = 0
    while True:
        k = d.find(tag, start)
        if k < 0 or len(idx) >= 6: break
        idx.append(k); start = k + 1
    print('%-6s at %s' % (tag.decode('latin-1'), idx))
