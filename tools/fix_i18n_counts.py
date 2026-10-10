# -*- coding: utf-8 -*-
"""[i18n] refresh the expected catalogue counts after adding the new-feature keys."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\i18n_selftest\I18nSelftest.java"
s = io.open(p, encoding="utf-8").read()
pairs = [("int EXPECTED_KEYS = 188;", "int EXPECTED_KEYS = 219;"),
         ('want.put("toolbar", Integer.valueOf(9));', 'want.put("toolbar", Integer.valueOf(10));'),
         ('want.put("status", Integer.valueOf(65));', 'want.put("status", Integer.valueOf(76));'),
         ('want.put("dlg", Integer.valueOf(36));', 'want.put("dlg", Integer.valueOf(51));'),
         ('want.put("menu", Integer.valueOf(15));', 'want.put("menu", Integer.valueOf(18));'),
         ('want.put("singer", Integer.valueOf(1));', 'want.put("singer", Integer.valueOf(2));')]
n = 0
for a, b in pairs:
    if a in s:
        s = s.replace(a, b)
        n += 1
io.open(p, "w", encoding="utf-8", newline="").write(s)
print("updated %d counts" % n)
