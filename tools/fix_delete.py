# -*- coding: utf-8 -*-
"""[v3.5] a delete must never be followed by an immediate re-create."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\ui\PianoRollView.java"
s = io.open(p, encoding="utf-8").read()

# state: when the last note was removed, and when the current gesture started
s = s.replace("private boolean moved, multi, dbl, changed; private long lastTapTime;",
              "private boolean moved, multi, dbl, changed; private long lastTapTime; private long downTime, lastDeleteAt;", 1)

# remember the gesture start
s = s.replace("downTop = topKey; moved = false; multi = false; changed = false; dbl = isDoubleTap(x, y);",
              "downTop = topKey; moved = false; multi = false; changed = false; dbl = isDoubleTap(x, y);\n"
              "                downTime = SystemClock.uptimeMillis();", 1)

# every delete path stamps the time
s = s.replace("            mode = MODE_INERT; changed = false; if (listener != null) listener.onNoteEdited(n, WHAT_DELETE); invalidate();",
              "            mode = MODE_INERT; changed = false; lastDeleteAt = SystemClock.uptimeMillis();\n"
              "            if (listener != null) listener.onNoteEdited(n, WHAT_DELETE); invalidate();", 1)
s = s.replace("        if (op != null) op.notes.remove(n); if (justCreated == n) justCreated = null;",
              "        if (op != null) op.notes.remove(n); if (justCreated == n) justCreated = null;\n"
              "        lastDeleteAt = SystemClock.uptimeMillis();", 1)
s = s.replace("        if (p != null) p.notes.remove(n); selected = null; if (justCreated == n) justCreated = null;",
              "        if (p != null) p.notes.remove(n); selected = null; if (justCreated == n) justCreated = null;\n"
              "        lastDeleteAt = SystemClock.uptimeMillis();", 1)

# the tap-to-create branch: only a genuine SHORT tap on empty space creates, and never right
# after a deletion (that was the "deleted note comes straight back" bug)
old = "                    else if (mode == MODE_SCROLL && !moved && y > rulerH && x > keyW) { createNoteAt(x, y);"
new = """                    else if (mode == MODE_SCROLL && !moved && y > rulerH && x > keyW
                            && SystemClock.uptimeMillis() - downTime < ViewConfiguration.getLongPressTimeout()
                            && SystemClock.uptimeMillis() - lastDeleteAt > 400L) { createNoteAt(x, y);"""
assert old in s, "create branch"
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8", newline="").write(s)
print("create guards added")
