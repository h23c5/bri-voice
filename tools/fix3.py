# -*- coding: utf-8 -*-
"""[fix] drop the duplicated getter, pass the percent as an argument."""
import io
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"

p = root + r"\app\src\com\dsh\v4tune\ui\PianoRollView.java"
s = io.open(p, encoding="utf-8").read()
dup = '''    /** current horizontal zoom (pixels per tick) */
    public float getPxPerTick() { return pxPerTick; }

    /** zoom in/out around the centre of the visible area (used by the +/- buttons) */'''
if dup in s:
    s = s.replace(dup, "    /** zoom in/out around the centre of the visible area (used by the +/- buttons) */", 1)
    io.open(p, "w", encoding="utf-8", newline="").write(s)
    print("duplicate getter removed")
else:
    print("getter pattern not found (already ok?)")

m = root + r"\app\src\com\dsh\v4tune\MainActivity.java"
t = io.open(m, encoding="utf-8").read()
old = 'status(L.t("status.zoom", Math.round(roll.getPxPerTick() / 0.25f * 100)));'
new = 'status(L.t("status.zoom", Math.round(roll.getPxPerTick() / 0.25f * 100) + "%"));'
if old in t:
    t = t.replace(old, new, 1)
    io.open(m, "w", encoding="utf-8", newline="").write(t)
    print("percent passed as argument")
else:
    print("zoom status line not found")

l = root + r"\app\src\com\dsh\v4tune\i18n\L.java"
u = io.open(l, encoding="utf-8").read()
for a, b in [('{"status.zoom", "\u7f29\u653e %d%%", "Zoom %d%%", "\u30ba\u30fc\u30e0 %d%%"},',
             '{"status.zoom", "\u7f29\u653e %s", "Zoom %s", "\u30ba\u30fc\u30e0 %s"},')]:
    if a in u:
        u = u.replace(a, b)
        io.open(l, "w", encoding="utf-8", newline="").write(u)
        print("zoom key rephrased")
    else:
        print("zoom key pattern not found")
