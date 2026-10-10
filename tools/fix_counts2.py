# -*- coding: utf-8 -*-
"""[i18n] refresh the expected counts again (5 more keys)."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\i18n_selftest\I18nSelftest.java"
s = io.open(p, encoding="utf-8").read()
for a, b in [("int EXPECTED_KEYS = 219;", "int EXPECTED_KEYS = 224;"),
             ('want.put("transport", Integer.valueOf(9));', 'want.put("transport", Integer.valueOf(10));'),
             ('want.put("status", Integer.valueOf(76));', 'want.put("status", Integer.valueOf(78));'),
             ('want.put("about.line", Integer.valueOf(13));', 'want.put("about.line", Integer.valueOf(15));')]:
    if a in s:
        s = s.replace(a, b)
        print("ok:", a[:40])
    else:
        print("MISS:", a[:40])
io.open(p, "w", encoding="utf-8", newline="").write(s)
