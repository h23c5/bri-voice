# -*- coding: utf-8 -*-
import io
t = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\i18n_selftest\I18nSelftest.java'
v = io.open(t, encoding='utf-8').read()
v = v.replace('int EXPECTED_KEYS = 226;', 'int EXPECTED_KEYS = 229;')
v = v.replace('want.put("param", Integer.valueOf(12));', 'want.put("param", Integer.valueOf(15));')
io.open(t, 'w', encoding='utf-8', newline='').write(v)
print('counts updated')