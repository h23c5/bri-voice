# -*- coding: utf-8 -*-
"""Find the unit -> phoneme field in the .ddi header of each unit table.

A real phoneme index must (a) sit in 0..61 and (b) group the units so that the formants inside a group
agree. Units of the same phoneme share formants, so the right field collapses the within-group variance
of log F1/F2 while the wrong one leaves it at the population level.
"""
import io, os, sys, glob
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voicebank_pack as vp
from ddi_runs import runs, formants

MAX_RUNS = int(os.environ.get('MAX_RUNS', '1500'))
FRAMES = int(os.environ.get('FRAMES', '8'))
HEAD = 64

bank = sys.argv[1]
ddi_path = glob.glob(os.path.join(bank, '*.ddi'))[0]
ddb = glob.glob(os.path.join(bank, '*.ddb'))[0]
ddb_size = os.path.getsize(ddb)
raw = io.open(ddi_path, 'rb').read()
rs = runs(ddi_path, ddb_size)[:MAX_RUNS]
print('units sampled: %d' % len(rs))

headers = []
f1s, f2s = [], []
with io.open(ddb, 'rb') as f:
    for r in rs:
        p = r['ddi_pos'] - HEAD
        if p < 0:
            headers.append(None); f1s.append(np.nan); f2s.append(np.nan); continue
        headers.append(raw[p:r['ddi_pos']])
        step = max(1, r['len'] // FRAMES)
        cs = []
        for k in range(0, r['len'], step):
            off = r['first'] + k * r['stride']
            if off + vp.REC_BYTES > ddb_size:
                break
            f.seek(off)
            dec = vp.decode_frame(f.read(vp.REC_BYTES), vp.HARM_MIN_HARMONICS, vp.HARM_F0_MIN, vp.HARM_F0_MAX)
            if dec is None:
                continue
            f0, hf, mags, err = dec
            cs.append((np.asarray(hf, dtype=float), np.asarray(mags, dtype=float)))
        if len(cs) < 3:
            f1s.append(np.nan); f2s.append(np.nan); continue
        # formants of the unit's median magnitude curve, on the decoded frequency axis
        fmin = max(c[0].min() for c in cs)
        fmax = min(c[0].max() for c in cs)
        if fmax <= fmin * 1.2:
            f1s.append(np.nan); f2s.append(np.nan); continue
        grid = np.exp(np.linspace(np.log(max(60.0, fmin)), np.log(min(8000.0, fmax)), 96))
        curves = []
        for hf, mg in cs:
            ok = np.isfinite(mg) & (mg > -200) & (hf > 0)
            if ok.sum() < 6:
                continue
            curves.append(np.interp(np.log(grid), np.log(hf[ok]), mg[ok]))
        if len(curves) < 3:
            f1s.append(np.nan); f2s.append(np.nan); continue
        med = np.median(np.asarray(curves), axis=0)
        a, b, _ = formants(grid, med)
        f1s.append(a if a > 0 else np.nan)
        f2s.append(b if b > 0 else np.nan)

f1 = np.asarray(f1s); f2 = np.asarray(f2s)
ok = np.isfinite(f1) & np.isfinite(f2)
print('units with measured F1/F2: %d' % int(ok.sum()))
if ok.sum() < 50:
    print('too few measurements to score fields')
    sys.exit(0)
l1 = np.log(f1[ok]); l2 = np.log(f2[ok])
base1 = l1.var(); base2 = l2.var()
print('population variance: F1 %.3f  F2 %.3f' % (base1, base2))

rows = []
for w, dt in ((1, 'u1'), (2, '<u2'), (4, '<u4')):
    for pos in range(0, HEAD - w + 1):
        vals = []
        for h in headers:
            if h is None or len(h) < HEAD:
                vals.append(-1); continue
            vals.append(int(np.frombuffer(h[pos:pos + w], dtype=dt)[0]))
        v = np.asarray(vals)
        sel = np.zeros(len(v), dtype=bool)
        sel[np.nonzero(ok)[0]] = True
        vv = v[sel]
        if vv.size < 50:
            continue
        if not ((vv >= 0) & (vv <= 61)).mean() > 0.9:
            continue
        if len(np.unique(vv)) < 8:
            continue
        g1 = np.zeros(0); g2 = np.zeros(0)
        for u in np.unique(vv):
            m = vv == u
            if m.sum() < 3:
                continue
            g1 = np.concatenate([g1, l1[m] - l1[m].mean()])
            g2 = np.concatenate([g2, l2[m] - l2[m].mean()])
        if g1.size < 30:
            continue
        ratio = ((g1.var() / base1) + (g2.var() / base2)) / 2.0
        rows.append((ratio, w, pos, len(np.unique(vv)), int(ok.sum())))
rows.sort()
print('best candidate fields (lower ratio = the field groups units by formant):')
for ratio, w, pos, nuniq, n in rows[:8]:
    print('  width %d at header byte %-3d : variance ratio %.3f, %d distinct values, %d units'
          % (w, pos, ratio, nuniq, n))