# -*- coding: utf-8 -*-
import io, re
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
p = root + r'\app\src\com\dsh\v4tune\model\Song.java'
s = io.open(p, encoding='utf-8').read()
n0 = s.count('Luo Tianyi V4_Meng')
s = s.replace('Luo Tianyi V4_Meng', '洛天依 BV')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('Song.java default voice name updated:', n0)
p2 = root + r'\app\src\com\dsh\v4tune\io\ProjectIO.java'
t = io.open(p2, encoding='utf-8').read()
n1 = t.count('Luo Tianyi V4_Meng')
t = t.replace('Luo Tianyi V4_Meng', '洛天依 BV')
io.open(p2, 'w', encoding='utf-8', newline='').write(t)
print('ProjectIO default voice name updated:', n1)