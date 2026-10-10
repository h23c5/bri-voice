# -*- coding: utf-8 -*-
"""[look] icon lookup without R, primary play button, card status bar."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()
n = 0

old = '''        try {                                   // the app icon doubles as a logo mark
            android.graphics.drawable.Drawable ic = getResources().getDrawable(R.mipmap.ic_launcher);
            int sz = dp(24);
            ic.setBounds(0, 0, sz, sz);
            title.setCompoundDrawables(ic, null, null, null);
            title.setCompoundDrawablePadding(dp(6));
        } catch (Throwable ignored) {
        }'''
new = '''        try {                                   // the app icon doubles as a logo mark
            int id = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
            if (id != 0) {
                android.graphics.drawable.Drawable ic = getResources().getDrawable(id);
                int sz = dp(26);
                ic.setBounds(0, 0, sz, sz);
                title.setCompoundDrawables(ic, null, null, null);
                title.setCompoundDrawablePadding(dp(7));
            }
        } catch (Throwable ignored) {
        }'''
if old in s: s = s.replace(old, new, 1); n += 1
else: print("MISS icon block")

old_play = '''        playBtn = smallButton("\u25b6 \u64ad\u653e", new View.OnClickListener() {
            public void onClick(View v) { if (engine.isPlaying()) stopPlay(); else playSong(); }
        });'''
new_play = '''        playBtn = smallButton(L.t("transport.play"), new View.OnClickListener() {
            public void onClick(View v) { if (engine.isPlaying()) stopPlay(); else playSong(); }
        }, true);'''
if old_play in s: s = s.replace(old_play, new_play, 1); n += 1
else: print("MISS play button")

old_status = '''        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#8FA3BF"));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusText.setPadding(dp(8), dp(3), dp(8), dp(3));
        statusText.setBackgroundColor(Color.parseColor("#12141A"));
        right.addView(statusText);'''
new_status = '''        statusText = new TextView(this);
        statusText.setTextColor(Theme.TEXT_DIM);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusText.setPadding(dp(12), dp(7), dp(12), dp(7));
        statusText.setBackground(Theme.card(this, Theme.PANEL));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(6), dp(3), dp(6), dp(6));
        right.addView(statusText, slp);'''
if old_status in s: s = s.replace(old_status, new_status, 1); n += 1
else: print("MISS status bar")

if "import com.dsh.v4tune.ui.Theme;" not in s:
    s = s.replace("import com.dsh.v4tune.ui.PianoRollView;",
                  "import com.dsh.v4tune.ui.PianoRollView;\nimport com.dsh.v4tune.ui.Theme;", 1)
    n += 1

io.open(p, "w", encoding="utf-8", newline="").write(s)
print("applied %d edits" % n)
