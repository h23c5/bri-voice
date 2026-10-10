# -*- coding: utf-8 -*-
"""[ui] the transport row can scroll horizontally in portrait (sliders made it wide)."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()
old = '''        } else {
            root.addView(transportBar(false));
        }'''
new = '''        } else {
            // portrait: the row now carries sliders too, so let it scroll instead of clipping
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setBackgroundColor(Theme.BG_DEEP);
            hs.addView(transportBar(false));
            root.addView(hs, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }'''
if old in s:
    s = s.replace(old, new, 1)
    io.open(p, "w", encoding="utf-8", newline="").write(s)
    print("portrait transport wrapped")
else:
    print("pattern not found")
