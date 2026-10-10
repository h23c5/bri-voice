# -*- coding: utf-8 -*-
import io
m = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
blk = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\ai_methods.txt'
s = io.open(m, encoding='utf-8').read()
body = io.open(blk, encoding='utf-8').read()
if 'private String extStatus()' in s:
    print('already inserted')
else:
    i = s.find('    private void about() {')
    if i < 0: raise SystemExit('about() anchor missing')
    s = s[:i] + body + s[i:]
    io.open(m, 'w', encoding='utf-8', newline='').write(s)
    print('methods inserted, file lines =', s.count(chr(10)))