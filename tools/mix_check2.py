# -*- coding: utf-8 -*-
import wave, struct, math
path = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\api_mix.wav'
w = wave.open(path, 'rb')
sr = w.getframerate(); n = w.getnframes(); ch = w.getnchannels()
raw = w.readframes(n)
w.close()
v = struct.unpack('<%dh' % (len(raw) // 2), raw)
mono = [v[i] / 32768.0 for i in range(0, len(v), ch)]
print('exported %d frames (%.1f s)' % (n, n / sr))
def band(start_sec, f0, f1):
    a = int(start_sec * sr)
    wnd = min(32768, len(mono) - a - 1)
    best = 0.0
    f = f0
    while f <= f1:
        re = im = 0.0
        for i in range(wnd):
            win = 0.5 - 0.5 * math.cos(2 * math.pi * i / (wnd - 1))
            re += mono[a + i] * win * math.cos(2 * math.pi * f * i / sr)
            im -= mono[a + i] * win * math.sin(2 * math.pi * f * i / sr)
        best = max(best, math.sqrt(re * re + im * im) / wnd)
        f += 5.0
    return best
print('tone band at 0.2s  : %.5f' % band(0.2, 1495, 1505))
print('tone band at 5.0s  : %.5f   <- clip lives here' % band(5.0, 1495, 1505))
print('control 1800Hz 5.0s: %.5f' % band(5.0, 1795, 1805))
print('vocal 255-300Hz 0.5s: %.5f' % band(0.5, 255, 300))
ok = band(5.0, 1495, 1505) > 0.1 and band(0.2, 1495, 1505) < 0.01
print('PASS: the imported clip is in the export, and only where it was placed' if ok else 'CHECK')