# -*- coding: utf-8 -*-
import io, re
p = r'C:\Users\H23_C5\Documents\leidian14\Pictures\魔术之心.vsqx'
s = io.open(p, encoding='utf-8', errors='ignore').read()
print('len', len(s))
for tag in ['schema/vsq4', 'schema/vsq3', 'pStyle', 'plane', 'aux', 'nStyle', 'noteStyle', 'phnms']:
    print('%-14s %s' % (tag, 'YES' if tag in s else 'no'))
m = re.search(r'<vender>(.*?)</vender>', s, re.S)
print('vender:', m.group(1).strip() if m else '?')
print('notes:', s.count('<note>'))
m2 = re.search(r'<vsPart[^>]*>(.{0,700})', s, re.S)
print('--- vsPart head ---')
print(m2.group(1)[:600] if m2 else 'no vsPart')
m3 = re.search(r'<note>(.{0,400})', s, re.S)
print('--- first note ---')
print(' '.join(m3.group(1).split())[:320] if m3 else 'none')