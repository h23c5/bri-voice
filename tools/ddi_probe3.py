# -*- coding: utf-8 -*-
"""[probe] hunt for (a) a frame-offset directory and (b) a per-frame phoneme label array."""
import struct, sys, collections
import numpy as np

p = sys.argv[1]
d = np.fromfile(p, dtype=np.uint8)
print('ddi size', d.size)

# (a) u64 directory: values that grow and stay below the .ddb size
ddb_size = int(sys.argv[2]) if len(sys.argv) > 2 else 4165429908
u64 = d[:d.size - (d.size % 8)].view(np.uint64)
best = None
run_start = 0
runs = []
for i in range(u64.size):
    v = int(u64[i])
    if v >= ddb_size:
        if i - run_start > 5000:
            runs.append((run_start, i))
        run_start = i + 1
if u64.size - run_start > 5000:
    runs.append((run_start, u64.size))
print('(a) runs of plausible u64 offsets (>=5000 entries):')
for a, b in runs[:8]:
    seg = u64[a:b]
    mono = float((np.diff(seg.astype(np.int64)) > 0).mean())
    print('    bytes %d..%d  entries=%d  byte-offset=%d  monotone=%.3f  first=%d last=%d' %
          (a * 8, b * 8, b - a, a * 8, mono, int(seg[0]), int(seg[-1])))

# (b) byte arrays whose values all stay below 62 (phoneme labels)
print('(b) long runs of bytes < 62 (candidate per-frame labels):')
i = 0
found = 0
n = d.size
while i < n and found < 8:
    if d[i] < 62:
        j = i
        while j < n and d[j] < 62:
            j += 1
        if j - i >= 20000:
            seg = d[i:j]
            c = collections.Counter(seg.tolist())
            print('    bytes %d..%d  len=%d  distinct=%d  top=%s' %
                  (i, j, j - i, len(c), [(int(k), int(v)) for k, v in c.most_common(6)]))
            found += 1
        i = j
    else:
        i += 1
if found == 0:
    print('    none found')
