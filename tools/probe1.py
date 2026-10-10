# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\render_test\PackPhonemeProbe.java'
s = io.open(p, encoding='utf-8').read()
s = s.replace('s[i].gainDb', 's[i].db[Math.min(s[i].db.length - 1, s[i].db.length / 2)]')
s = s.replace('System.out.print("  s band gains (dB, low->high): ");', 'System.out.print("  s band levels (dB @ mid band): ");')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('probe fixed')