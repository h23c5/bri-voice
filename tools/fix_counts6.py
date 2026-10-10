# -*- coding: utf-8 -*-
import io
t = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\i18n_selftest\I18nSelftest.java'
v = io.open(t, encoding='utf-8').read()
v = v.replace('int EXPECTED_KEYS = 231;', 'int EXPECTED_KEYS = 233;')
v = v.replace('want.put("transport", Integer.valueOf(12));', 'want.put("transport", Integer.valueOf(13));')
v = v.replace('want.put("status", Integer.valueOf(79));', 'want.put("status", Integer.valueOf(80));')
io.open(t, 'w', encoding='utf-8', newline='').write(v)
print('counts updated')