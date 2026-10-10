# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\FrameVoice.java'
s = io.open(p, encoding='utf-8').read()
old = '        double norm = 2.0 / window;'
if old not in s: raise SystemExit('norm anchor missing')
new = ("        // The conjugate pair already encodes the amplitude (output amplitude = the amplitude we set),\n        // and a Hann window at hop = window/4 sums to 2, so the overlap-add needs exactly 1/2. The\n        // first version divided by 2/window as well, which put the whole path 54 dB down.\n        double norm = 0.5;")
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('norm fixed')