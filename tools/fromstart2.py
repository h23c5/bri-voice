# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
s = s.replace('        engineCancel();\n', '')
s = s.replace('        roll.scrollToTick(0L);\n', '')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('trimmed to existing helpers')