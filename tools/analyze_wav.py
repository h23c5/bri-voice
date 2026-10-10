# -*- coding: utf-8 -*-
import sys, wave, struct, math
import numpy as np

path = sys.argv[1]
w = wave.open(path, 'rb')
n, sr, ch, sw = w.getnframes(), w.getframerate(), w.getnchannels(), w.getsampwidth()
print('channels=%d rate=%d width=%d frames=%d duration=%.2fs' % (ch, sr, sw, n, n / sr))
raw = w.readframes(n)
a = np.frombuffer(raw, dtype='<i2').astype(np.float64)
if ch == 2:
    a = a.reshape(-1, 2).mean(axis=1)
a /= 32768.0
print('peak=%.3f rms=%.4f' % (np.max(np.abs(a)), math.sqrt(float(np.mean(a ** 2)))))
assert np.all(np.isfinite(a))
# find sounding regions
win = int(0.02 * sr)
env = np.array([np.max(np.abs(a[i:i + win])) for i in range(0, len(a) - win, win)])
sounding = np.where(env > 0.02)[0]
print('sounding windows: %d / %d  (first at %.2fs, last at %.2fs)' %
      (len(sounding), len(env), sounding[0] * 0.02 if len(sounding) else -1, sounding[-1] * 0.02 if len(sounding) else -1))
# F0 by autocorrelation in the middle of each detected note
def f0(seg, sr):
    seg = seg - seg.mean()
    if np.max(np.abs(seg)) < 1e-3: return 0
    ac = np.correlate(seg, seg, 'full')[len(seg)-1:]
    lo, hi = int(sr / 900), int(sr / 100)
    ac = ac / (ac[0] + 1e-12)
    peak = lo + int(np.argmax(ac[lo:hi]))
    return sr / peak
# group contiguous sounding windows into notes
groups, cur = [], [sounding[0]] if len(sounding) else []
for i in range(1, len(sounding)):
    if sounding[i] == sounding[i-1] + 1: cur.append(sounding[i])
    else: groups.append(cur); cur = [sounding[i]]
if cur: groups.append(cur)
print('detected %d sounding groups' % len(groups))
for g in groups[:12]:
    s = int((g[0] * 0.02 + 0.05) * sr); e = int((g[-1] * 0.02) * sr)
    if e - s < 4096: continue
    mid = (s + e) // 2
    seg = a[mid - 2048: mid + 2048]
    print('   group %5.2f-%5.2f s  f0=%.1f Hz  rms=%.4f' % (s / sr, e / sr, f0(seg, sr), math.sqrt(float(np.mean(seg ** 2)))))
# spectrum sanity: energy above 4 kHz should exist (fricatives) but be below the low band
sp = np.abs(np.fft.rfft(a[:min(len(a), sr * 4)] * np.hanning(min(len(a), sr * 4))))
fr = np.fft.rfftfreq(min(len(a), sr * 4), 1.0 / sr)
lo = sp[(fr > 100) & (fr < 1000)].sum(); hi = sp[(fr > 4000) & (fr < 12000)].sum()
print('band energy 100-1000Hz=%.0f  4-12kHz=%.0f  ratio=%.3f' % (lo, hi, hi / max(lo, 1)))
print('WAV OK')
