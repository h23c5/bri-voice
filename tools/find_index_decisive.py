# -*- coding: utf-8 -*-
"""Decisive test for the unit -> phoneme index.

If a field really holds the PHDC index, the units it groups must carry the formants of THAT symbol: the
group's median F1/F2 should match REFERENCE_FORMANTS[symbols[v]] about as well as it matches the best
symbol in the table. A field that is not the index scores far worse.
"""
import io, os, sys, glob
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voicebank_pack as vp
from ddi_runs import runs, formants

MAX_RUNS = int(os.environ.get('MAX_RUNS', '1200'))
FRAMES = int(os.environ.get('FRAMES', '6'))
HEAD = 64

bank = sys.argv[1]
ddi_path = glob.glob(os.path.join(bank, '*.ddi'))[0]
ddb = glob.glob(os.path.join(bank, '*.ddb'))[0]
ddb_size = os.path.getsize(ddb)
raw = io.open(ddi_path, 'rb').read()
symbols, _, _, _ = vp.read_phdc(ddi_path)
rs = runs(ddi_path, ddb_size)[:MAX_RUNS]

hdr_bytes, rec_words, f1s, f2s = [], [], [], []
with io.open(ddb, 'rb') as f:
    for r in rs:
        p = r['ddi_pos'] - HEAD
        hdr_bytes.append(raw[p:r['ddi_pos']] if p >= 0 else b'')
        f.seek(r['first'])
        head = f.read(32)
        rec_words.append([int(np.frombuffer(head[o:o + 4], dtype='<u4')[0]) if len(head) >= o + 4 else -1
                          for o in (16, 20, 24, 28)])
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
            cs.append((np.asarray(hf, float), np.asarray(mags, float)))
        if len(cs) < 3:
            f1s.append(np.nan); f2s.append(np.nan); continue
        fmin = max(c[0].min() for c in cs); fmax = min(c[0].max() for c in cs)
        if fmax <= fmin * 1.2:
            f1s.append(np.nan); f2s.append(np.nan); continue
        grid = np.exp(np.linspace(np.log(max(60.0, fmin)), np.log(min(8000.0, fmax)), 96))
        curves = [np.interp(np.log(grid), np.log(h[0][h[1] > -200]), h[1][h[1] > -200]) for h in cs]
        med = np.median(np.asarray(curves), axis=0)
        a, b, _ = formants(grid, med)
        f1s.append(a if a > 0 else np.nan); f2s.append(b if b > 0 else np.nan)

f1 = np.asarray(f1s); f2 = np.asarray(f2s)
ok = np.isfinite(f1) & np.isfinite(f2)
print('units with formants: %d of %d' % (int(ok.sum()), len(rs)))
idx = np.nonzero(ok)[0]
l1 = np.log(f1[idx]); l2 = np.log(f2[idx])


def evaluate(name, values):
    v = np.asarray(values)[idx]
    if v.size < 60:
        return
    if not ((v >= 0) & (v <= 61)).mean() > 0.9:
        return
    if len(np.unique(v)) < 8:
        return
    own = oracle = 0.0
    groups = 0
    for u in np.unique(v):
        m = v == u
        if m.sum() < 3:
            continue
        g1 = float(np.median(l1[m])); g2 = float(np.median(l2[m]))
        ref = vp.REFERENCE_FORMANTS.get(symbols[int(u)]) if int(u) < len(symbols) else None
        best = min(abs(np.log(np.exp(g1) / r[0])) + abs(np.log(np.exp(g2) / r[1]))
                   for r in vp.REFERENCE_FORMANTS.values() if r[0] > 0)
        if ref and ref[0] > 0:
            d = abs(g1 - np.log(ref[0])) + abs(g2 - np.log(ref[1]))
            own += d
            oracle += best
            groups += 1
    if groups < 4:
        return
    own /= groups; oracle /= groups
    print('  %-26s groups=%-3d  own-symbol dist %.3f   best-possible %.3f   %s'
          % (name, groups, own, oracle, 'MATCHES THE INDEX' if own < oracle * 1.25 else ''))


print('candidates (own-symbol distance close to the best possible = the field is the index):')
for pos in range(0, HEAD):
    evaluate('header byte %d' % pos, [int(h[pos]) if len(h) > pos else -1 for h in hdr_bytes])
for wi, off in enumerate((16, 20, 24, 28)):
    evaluate('FRM2 header u32 @%d' % off, [w[wi] for w in rec_words])