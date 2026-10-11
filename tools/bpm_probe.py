import sys, wave, numpy as np
w = wave.open(sys.argv[1], 'rb')
n = w.getnframes(); sr = w.getframerate(); ch = w.getnchannels(); sw = w.getsampwidth()
x = np.frombuffer(w.readframes(n), dtype={1: np.int8, 2: '<i2', 4: '<i4'}[sw]).astype(np.float64)
if ch > 1: x = x.reshape(-1, ch).mean(axis=1)
hop = 512
e = np.array([np.sqrt((x[i*hop:(i+1)*hop] ** 2).mean() + 1e-12) for i in range(len(x) // hop)])
o = np.maximum(0.0, np.diff(e)); o = o - o.mean()
ac = np.correlate(o, o, 'full')[len(o)-1:]
lo = int(sr / hop * 60 / 190); hi = int(sr / hop * 60 / 60)
seg = ac[lo:hi]; lag = int(np.argmax(seg)) + lo
print('BPM=%.1f CONF=%.3f dur=%.2fs' % (60.0 * sr / hop / lag, float(seg.max() / (ac[0] + 1e-12)), n / sr))