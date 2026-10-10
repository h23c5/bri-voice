# -*- coding: utf-8 -*-
"""How many SND samples of this bank are noise-like? The converter sampled 800 and found 790 tonal.

A consonant needs a noise-like spectrum (high spectral flatness). This scans far more records spread over
the whole file and prints the flatness/centroid distribution, so we know whether the consonant data is
there at all or whether the earlier sample was simply biased."""
import io, os, sys, glob
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voicebank_pack as vp

bank = sys.argv[1]
ddb = glob.glob(os.path.join(bank, '*.ddb'))[0]
size = os.path.getsize(ddb)
want = int(sys.argv[2]) if len(sys.argv) > 2 else 4000
offs, _read = vp.scan_snd_offsets(ddb, size, 0)
print('SND records found: %d' % len(offs))
if len(offs) == 0:
    sys.exit(0)
pick = np.unique(np.linspace(0, len(offs) - 1, min(want, len(offs))).astype(np.int64))
rows = []
with io.open(ddb, 'rb') as f:
    for j in pick:
        f.seek(int(offs[j]))
        buf = f.read(18)
        if len(buf) < 18 or not buf.startswith(b'SND '):
            continue
        n = int(np.frombuffer(buf[4:8], dtype='<u4')[0])
        if n < 1000 or n > (1 << 24):
            continue
        pcm = f.read(n)
        if len(pcm) < n:
            continue
        x = np.frombuffer(pcm[:n - (n % 2)], dtype='<i2').astype(np.float64) / 32768.0
        if x.size < 4096:
            continue
        if np.abs(x).max() < 1e-4:
            continue
        rows.append((j, x))
print('records read: %d' % len(rows))
flat, cent = [], []
for j, x in rows:
    w = np.hanning(4096)
    X = np.abs(np.fft.rfft(x[:4096] * w))
    fr = np.fft.rfftfreq(4096, 1.0 / 44100.0)
    band = (fr > 200) & (fr < 12000)
    P = X[band] ** 2 + 1e-12
    flat.append(float(np.exp(np.mean(np.log(P))) / np.mean(P)))
    cent.append(float((fr[band] * P).sum() / P.sum()))
flat = np.asarray(flat); cent = np.asarray(cent)
print('flatness: median %.4f  90th %.4f  max %.4f' % (np.median(flat), np.percentile(flat, 90), flat.max()))
print('centroid: median %.0f Hz  90th %.0f Hz' % (np.median(cent), np.percentile(cent, 90)))
noisy = (flat > 0.01) & (cent > 1500)
print('noise-like records (flatness>0.01 and centroid>1.5kHz): %d of %d (%.1f%%)'
      % (int(noisy.sum()), flat.size, 100.0 * noisy.sum() / max(1, flat.size)))
if noisy.sum() > 0:
    print('  their centroids: %s' % ' '.join('%.0f' % v for v in cent[noisy][:20]))
    print('  their flatness : %s' % ' '.join('%.3f' % v for v in flat[noisy][:20]))