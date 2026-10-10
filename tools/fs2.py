# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\FrameVoice.java'
s = io.open(p, encoding='utf-8').read()
old = '        rampLeft = hop;' + chr(10) + '        rampTotal = hop;' + chr(10)
if old not in s: raise SystemExit('leftover anchor missing')
s = s.replace(old, '', 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('leftovers removed')