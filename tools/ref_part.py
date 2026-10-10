# -*- coding: utf-8 -*-
import io, re
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\fmt_in\萱草花.vsqx'
s = io.open(p, encoding='utf-8', errors='ignore').read()
i = s.find('<vsPart')
j = s.find('<note>', i)
print('--- vsPart head (reference) ---')
print(s[i:j][:900])
k = s.find('</note>', j)
seg = s[k:k + 800]
print('--- after the last note (reference) ---')
print(seg[:700])
print('--- plane occurrences ---')
for m in re.finditer('plane', s):
    print(' ', s[max(0, m.start() - 60):m.start() + 40].replace(chr(10), ' '))
    break