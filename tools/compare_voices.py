# -*- coding: utf-8 -*-
"""A/B two renders: are they different, and does the imported pack apply its measured shelf?"""
import sys, wave
import numpy as np

def load(p):
    w = wave.open(p, 'rb')
    a = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(float) / 32768.0
    if w.getnchannels() == 2: a = a.reshape(-1, 2).mean(axis=1)
    return a, w.getframerate()

a, sr = load(sys.argv[1])
b, _ = load(sys.argv[2])
n = min(len(a), len(b)); a, b = a[:n], b[:n]
print('builtin rms=%.4f  pack rms=%.4f  (len %d)' % (np.sqrt(np.mean(a**2)), np.sqrt(np.mean(b**2)), n))
d = np.sqrt(np.mean((a-b)**2)) / max(1e-9, np.sqrt(np.mean(a**2)))
print('relative difference = %.3f  %s' % (d, 'DIFFERENT' if d > 0.05 else 'TOO SIMILAR'))
# band comparison (where the pack's shelf acts)
def bands(x, sr):
    N = 8192; out = {}
    seg = x[int(0.6*sr):int(0.6*sr)+N]
    if len(seg) < N: seg = np.pad(seg, (0, N-len(seg)))
    sp = np.abs(np.fft.rfft(seg*np.hanning(N))); fr = np.fft.rfftfreq(N, 1.0/sr)
    for lo, hi in ((60,200),(200,800),(800,2500),(2500,6000),(6000,12000)):
        m = (fr>=lo)&(fr<hi)
        out['%d-%dHz' % (lo,hi)] = 20*np.log10(np.sqrt(np.mean(sp[m]**2))+1e-12)
    return out
ba, bb = bands(a, sr), bands(b, sr)
print('band (dB)      builtin     pack      diff')
for k in ba:
    print('  %-12s %8.2f %8.2f %+8.2f' % (k, ba[k], bb[k], bb[k]-ba[k]))
