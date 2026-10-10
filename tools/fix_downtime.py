# -*- coding: utf-8 -*-
"""[fix] downTime was never set, so the new short-tap test blocked every create."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\ui\PianoRollView.java"
s = io.open(p, encoding="utf-8").read()
print("downTime set:", "downTime = SystemClock.uptimeMillis();" in s)
print("lastDeleteAt set:", s.count("lastDeleteAt = SystemClock.uptimeMillis();"))
if "downTime = SystemClock.uptimeMillis();" not in s:
    i = s.find("dbl = isDoubleTap(x, y);")
    if i < 0:
        raise SystemExit("anchor missing")
    j = s.find("\n", i) + 1
    s = s[:j] + "                downTime = SystemClock.uptimeMillis();\n" + s[j:]
    io.open(p, "w", encoding="utf-8", newline="").write(s)
    print("downTime added")
