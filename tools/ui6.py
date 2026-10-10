# -*- coding: utf-8 -*-
"""[ui] the snap widget shows only the value; the full phrase stays in the status line."""
import io
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"

l = root + r"\app\src\com\dsh\v4tune\i18n\L.java"
u = io.open(l, encoding="utf-8").read()
if '"transport.snap.off.short"' not in u:
    marker = "\n    };\n"
    i = u.find("private static final String[][] TABLE = {")
    j = u.find(marker, i)
    row = '        {"transport.snap.off.short", "\u5173", "Off", "\u30aa\u30d5"},\n'
    u = u[:j] + "\n" + row.rstrip("\n") + u[j:]
    io.open(l, "w", encoding="utf-8", newline="").write(u)
    print("short off key added")

m = root + r"\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(m, encoding="utf-8").read()
old = '''    private String snapText() {
        return snapTicks == 0 ? L.t("transport.snap.off") : L.t("transport.snap", 1920 / snapTicks);
    }'''
new = '''    /** the full phrase, used in the status line */
    private String snapText() {
        return snapTicks == 0 ? L.t("transport.snap.off") : L.t("transport.snap", 1920 / snapTicks);
    }

    /** just the value, used inside the slider widget where the name is already on the left */
    private String snapValueText() {
        return snapTicks == 0 ? L.t("transport.snap.off.short") : ("1/" + (1920 / snapTicks));
    }'''
assert old in s
s = s.replace(old, new, 1)
s = s.replace('snapBox = new SliderBox(L.t("transport.snap.short"), snapText(), SNAP_STEPS - 1,',
              'snapBox = new SliderBox(L.t("transport.snap.short"), snapValueText(), SNAP_STEPS - 1,', 1)
s = s.replace('                        snapBox.setValue(snapText());', '                        snapBox.setValue(snapValueText());', 1)
io.open(m, "w", encoding="utf-8", newline="").write(s)
print("snap value fixed")

t = root + r"\tools\i18n_selftest\I18nSelftest.java"
v = io.open(t, encoding="utf-8").read()
v = v.replace("int EXPECTED_KEYS = 224;", "int EXPECTED_KEYS = 225;")
v = v.replace('want.put("transport", Integer.valueOf(10));', 'want.put("transport", Integer.valueOf(11));')
io.open(t, "w", encoding="utf-8", newline="").write(v)
print("counts updated")
