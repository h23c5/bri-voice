# -*- coding: utf-8 -*-
import wave, struct, math
path = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\api_mix.wav'
w = wave.open(path, 'rb')
sr = w.getframerate(); n = w.getnframes(); ch = w.getnchannels()
raw = w.readframes(min(n, sr * 6))
w.close()
samples = struct.unpack('<%dh' % (len(raw) // 2), raw)
mono = [samples[i] / 32768.0 for i in range(0, len(samples), ch)]
print('exported: %d frames, %d Hz, %d ch, %.1f s' % (n, sr, ch, n / sr))
def band(f0, f1):
    e = 0.0
    f = f0
    while f <= f1:
        re = im = 0.0
        wnd = min(len(mono), 32768)
        for i in range(wnd):
            win = 0.5 - 0.5 * math.cos(2 * math.pi * i / (wnd - 1))
            re += mono[i] * win * math.cos(2 * math.pi * f * i / sr)
            im -= mono[i] * win * math.sin(2 * math.pi * f * i / sr)
        e += math.sqrt(re * re + im * im) / wnd
        f += 5.0
    return e
tone = band(1495, 1505)
control = band(1795, 1805)
voice = band(255, 300)
print('1500 Hz band = %.5f   control 1800 Hz = %.5f   vocal 255-300 Hz = %.5f' % (tone, control, voice))
print('PASS: imported tone is in the export' if tone > 0.05 and tone > control * 3 else 'FAIL: tone missing or weak')