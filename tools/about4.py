# -*- coding: utf-8 -*-
import io
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()
print('has dlg.ok:', '"dlg.ok"' in s)
if '"dlg.ok"' not in s:
    i = s.find('{"dlg.about.title"')
    j = s.find('\n', i) + 1
    s = s[:j] + '        {"dlg.ok", "好", "OK", "OK"},\n' + s[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(s)
    print('dlg.ok added')