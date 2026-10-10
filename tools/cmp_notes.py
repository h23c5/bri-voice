# -*- coding: utf-8 -*-
import glob, io, re
fs = glob.glob(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\fmt_in\*.vsqx')
print('reference files:', len(fs))
for f in fs[:6]:
    s = io.open(f, encoding='utf-8', errors='ignore').read()
    m = re.search(r'<[^>]*note[^>]*>.*?</[^>]*note>', s, re.S | re.I)
    if not m:
        m = re.search(r'<[^>]*note[^>]*/>', s, re.I)
    print('===', f.split(chr(92))[-1], '=== vsq4:', 'vsq4' in s[:400], 'len', len(s))
    if m:
        print(m.group(0)[:700].replace(chr(10), ' '))
    print()