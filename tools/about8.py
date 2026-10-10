# -*- coding: utf-8 -*-
import io
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()
before = s.count('"Luo Tianyi BV"')
s = s.replace('bundles "Luo Tianyi BV";', "bundles 'Luo Tianyi BV';")
s = s.replace('bundles "Luo Tianyi BV" (parametric', "bundles 'Luo Tianyi BV' (parametric")
io.open(L, 'w', encoding='utf-8', newline='').write(s)
print('quotes replaced, was', before)
left = [l for l in s.split(chr(10)) if l.count('"') % 2 == 1 and 'Luo' in l]
print('lines with odd quote count:', len(left))