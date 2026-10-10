# -*- coding: utf-8 -*-
"""Look for the per-run phoneme field: print the bytes just before each run and the PHDC list."""
import io, os, sys, glob
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voicebank_pack as vp
from ddi_runs import runs, formants

bank = sys.argv[1]
ddi = glob.glob(os.path.join(bank, '*.ddi'))[0]
ddb = glob.glob(os.path.join(bank, '*.ddb'))[0]
ddb_size = os.path.getsize(ddb)
symbols, flags, size, ver = vp.read_phdc(ddi)
print('PHDC index list:')
for i, s in enumerate(symbols):
    print('   %2d %s' % (i, s))
raw = io.open(ddi, 'rb').read()
rs = runs(ddi, ddb_size)
print()
print('runs: %d' % len(rs))
print('%-4s %-9s %-6s %-6s %-7s %-8s %-8s %s' % ('#', 'ddi_pos', 'len', 'f0', 'F1', 'F2', 'pre-u32', 'pre-hex'))
with io.open(ddb, 'rb') as f:
    for idx, r in enumerate(rs[:40]):
        step = max(1, r['len'] // 10)
        f0s, f1s, f2s = [], [], []
        for k in range(0, r['len'], step):
            off = r['first'] + k * r['stride']
            if off + vp.REC_BYTES > ddb_size:
                break
            f.seek(off)
            dec = vp.decode_frame(f.read(vp.REC_BYTES), vp.HARM_MIN_HARMONICS, vp.HARM_F0_MIN, vp.HARM_F0_MAX)
            if dec is None:
                continue
            f0, hf, mags, err = dec
            f0s.append(f0)
            a, b = formants(hf, mags)
            f1s.append(a); f2s.append(b)
        p = r['ddi_pos'] - 24
        pre = raw[p:r['ddi_pos']] if p >= 0 else b''
        u = np.frombuffer(pre, dtype='<u4') if len(pre) == 24 else []
        f0m = float(np.median(f0s)) if f0s else 0.0
        f1m = float(np.median(f1s)) if f1s else 0.0
        f2m = float(np.median(f2s)) if f2s else 0.0
        print('%-4d %-9d %-6d %-6.0f %-7.0f %-8.0f %-8s %s' % (idx, r['ddi_pos'], r['len'], f0m, f1m, f2m,
              ' '.join(str(int(x)) for x in u), pre.hex()))