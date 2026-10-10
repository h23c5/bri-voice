# -*- coding: utf-8 -*-
"""[cleanup] drop the superseded synchronous single-file import method."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()
start = s.find("    private void importPackFromOld(Uri uri) {")
if start < 0:
    print("already gone")
else:
    end = s.find("\n    private int countNotes()", start)
    if end < 0:
        raise SystemExit("end marker not found")
    removed = s[start:end]
    s = s[:start] + s[end + 1:]
    io.open(p, "w", encoding="utf-8", newline="").write(s)
    print("removed %d chars (%d lines)" % (len(removed), removed.count("\n")))
