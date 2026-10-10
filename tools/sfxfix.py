# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\make_sfx.py'
s = io.open(p, encoding='utf-8').read()
i = s.find('def chime(')
j = s.find('add(riser()', i)
new = '''def chime(peak=0.18):
    dur = 2.4
    out = np.zeros(int(dur * SR))
    for k, f in enumerate([523.25, 659.25, 783.99]):
        start = int(k * 0.18 * SR)
        t = np.arange(int(1.2 * SR)) / SR
        seg = peak * np.exp(-3.5 * t) * np.sin(2 * np.pi * f * t)
        out[start:start + len(seg)] += seg
    return out


'''
s = s[:i] + new + s[j:]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('chime fixed')