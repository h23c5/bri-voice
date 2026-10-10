# -*- coding: utf-8 -*-
"""[look] icon lookup without R (the manifest package differs from the java package), primary play
   button, card-style status bar and section dividers."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()

# R would be generated under the application id (com.bri.voice), not this package: resolve by name
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
assert old in s
s = s.replace(old, new, 1)

# play button gets the accent fill
old_play = '''        playBtn = smallButton(L.t("transport.play"), new View.OnClickListener() {
            public void onClick(View v) { if (engine.isPlaying()) stopPlay(); else playSong(); }
        });'''
new_play = '''        playBtn = smallButton(L.t("transport.play"), new View.OnClickListener() {
            public void onClick(View v) { if (engine.isPlaying()) stopPlay(); else playSong(); }
        }, true);'''
assert old_play in s
s = s.replace(old_play, new_play, 1)

# status bar: a card that sits above the navigation bar
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
        statusText.setSingleLine(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(6), dp(3), dp(6), dp(6));
        right.addView(statusText, slp);'''
assert old_status in s
s = s.replace(old_status, new_status, 1)

# a hairline under the toolbar and above the parameter strip
old_trl = '''        if (land) {
            android.widget.ScrollView side = new android.widget.ScrollView(this);
            side.setBackgroundColor(Color.parseColor("#12141A"));'''
new_trl = '''        if (land) {
            android.widget.ScrollView side = new android.widget.ScrollView(this);
            side.setBackgroundColor(Theme.BG_DEEP);'''
assert old_trl in s
s = s.replace(old_trl, new_trl, 1)

io.open(p, "w", encoding="utf-8", newline="").write(s)
print("polish applied")
