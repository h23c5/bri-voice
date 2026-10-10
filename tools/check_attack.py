# -*- coding: utf-8 -*-
import sys, wave
import numpy as np
w = wave.open(sys.argv[1], 'rb'); a = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(float) / 32768.0
if w.getnchannels() == 2: a = a.reshape(-1, 2).mean(axis=1)
sr = w.getframerate()
print('first 0.60 s, RMS per 20 ms:')
for i in range(0, int(0.6 * sr), int(0.02 * sr)):
    seg = a[i:i + int(0.02 * sr)]
    print('  t=%5.2f  rms=%.4f  peak=%.3f' % (i / sr, np.sqrt(np.mean(seg ** 2)), np.max(np.abs(seg))))
