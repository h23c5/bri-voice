# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\audio\Demo.java'
s = io.open(p, encoding='utf-8').read()
old = '        int[] keys = {"60", "62", "64", "65", "67", "69", "67", "65", "64"};'
new = '        int[] keys = {60, 62, 64, 65, 67, 69, 67, 65, 64};'
if old not in s: raise SystemExit('keys line not found')
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('keys fixed')