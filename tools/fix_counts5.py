# -*- coding: utf-8 -*-
import io
t = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\i18n_selftest\I18nSelftest.java'
v = io.open(t, encoding='utf-8').read()
v = v.replace('int EXPECTED_KEYS = 230;', 'int EXPECTED_KEYS = 231;')
v = v.replace('want.put("singer", Integer.valueOf(2));', 'want.put("singer", Integer.valueOf(3));')
io.open(t, 'w', encoding='utf-8', newline='').write(v)
print('counts updated')