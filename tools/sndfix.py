# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\snd_survey.py'
s = io.open(p, encoding='utf-8').read()
s = s.replace("offs = vp.scan_snd_offsets(ddb, size, 0)", "offs, _read = vp.scan_snd_offsets(ddb, size, 0)")
s = s.replace("        c = vp.snd_curve_and_features(x) if hasattr(vp, 'snd_curve_and_features') else (None, None)\n", "")
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('fixed')