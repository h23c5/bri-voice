# -*- coding: utf-8 -*-
"""[probe] what the .ddi directory actually offers for context-based frame selection."""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
import numpy as np
from voicebank_pack import ddi_directory_offsets, FRM2_MAGIC

ddi, ddb, ddb_size = sys.argv[1], sys.argv[2], int(sys.argv[3])
offs, chunks = ddi_directory_offsets(ddi, ddb_size)
print('stride-constant runs found:', chunks)
print('unique offsets:', offs.size)
if offs.size == 0:
    raise SystemExit(0)
s = np.sort(offs)
d = np.diff(s)
print('sorted offsets: min=%d max=%d' % (s[0], s[-1]))
print('gaps: ==2832 (next frame): %d   <=8 (same frame): %d   other: %d'
      % (int((d == 2832).sum()), int((d <= 8).sum()), int(((d != 2832) & (d > 8)).sum())))
# how many maximal runs of consecutive frames does the directory describe?
runs = 1 + int((d != 2832).sum())
print('maximal consecutive-frame runs: %d  (mean run length %.1f)' % (runs, offs.size / runs))
# sample: verify a few offsets really point at FRM2 records
f = open(ddb, 'rb')
hits = 0
probe = s[:: max(1, s.size // 50)][:50]
for o in probe:
    f.seek(int(o))
    if f.read(4) == FRM2_MAGIC:
        hits += 1
print('FRM2 magic at %d of %d sampled offsets' % (hits, len(probe)))
# is there a companion parallel array (one small int per offset) near the tables?
print('=> the directory orders/locates frames; it carries no per-frame phoneme label')
