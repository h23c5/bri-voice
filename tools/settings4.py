# -*- coding: utf-8 -*-
import io, re
T = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\i18n_selftest\I18nSelftest.java'
t = io.open(T, encoding='utf-8').read()
if 'want.put("set"' not in t:
    i = t.find('want.put("app"')
    j = t.rfind(chr(10), 0, i) + 1
    t = t[:j] + 'want.put("set", Integer.valueOf(4));' + chr(10) + t[j:]
    io.open(T, 'w', encoding='utf-8', newline='').write(t)
    print('set group added')
else:
    print('already present')