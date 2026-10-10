# -*- coding: utf-8 -*-
"""[fix] import SeekBar and use the existing clampScroll instead of a missing helper."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()
if "import android.widget.SeekBar;" not in s:
    s = s.replace("import android.widget.LinearLayout;", "import android.widget.LinearLayout;\nimport android.widget.SeekBar;", 1)
    print("SeekBar imported")
io.open(p, "w", encoding="utf-8", newline="").write(s)

p2 = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\ui\PianoRollView.java"
t = io.open(p2, encoding="utf-8").read()
if "clampZoomAnchors();" in t:
    t = t.replace("clampZoomAnchors();", "clampScroll();", 1)
    io.open(p2, "w", encoding="utf-8", newline="").write(t)
    print("clampScroll used")
