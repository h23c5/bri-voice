# -*- coding: utf-8 -*-
"""[look] restyle the chrome: rounded buttons, cards, dividers, accent title."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()

# 1) button styling + a primary variant
old_btn = '''    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(Color.parseColor("#DCE8F8"));
        b.setBackgroundColor(Color.parseColor("#1D2432"));
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }'''
new_btn = '''    private Button smallButton(String text, View.OnClickListener l) {
        return smallButton(text, l, false);
    }

    /** @param primary true for the one main action of a bar (filled with the accent colour) */
    private Button smallButton(String text, View.OnClickListener l, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        b.setTextColor(primary ? Theme.ON_PRIMARY : Theme.TEXT);
        if (primary) b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setBackground(Theme.button(this, primary));
        b.setPadding(dp(12), dp(5), dp(12), dp(5));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);          // no drop shadow: flat night look
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }'''
assert old_btn in s, "button helper not found"
s = s.replace(old_btn, new_btn, 1)

# 2) the play button is the primary action
s = s.replace('playBtn = smallButton(L.t("transport.play"), new View.OnClickListener() {',
              'playBtn = smallButton(L.t("transport.play"), new View.OnClickListener() {', 1)

# 3) window + bars background
s = s.replace('root.setBackgroundColor(Color.parseColor("#0E1015"));',
              'root.setBackgroundColor(Theme.BG_DEEP);', 1)
s = s.replace('transport.setBackgroundColor(Color.parseColor("#161A24"));',
              'transport.setBackgroundColor(Theme.BG_DEEP);', 1)
s = s.replace('hs.setBackgroundColor(Color.parseColor("#12141A"));',
              'hs.setBackgroundColor(Theme.BG_DEEP);', 1)
s = s.replace('bar.setBackgroundColor(Color.parseColor("#12141A"));',
              'bar.setBackgroundColor(Theme.BG_DEEP);', 1)

# 4) title with the launcher icon, accent coloured
old_title = '''        TextView title = new TextView(this);
        title.setText("BriVoice");
        title.setTextColor(Color.parseColor("#E8F1FF"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setPadding(dp(4), 0, dp(10), 0);
        bar.addView(title);'''
new_title = '''        TextView title = new TextView(this);
        title.setText("BriVoice");
        title.setTextColor(Theme.ACCENT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        title.setPadding(dp(4), 0, dp(12), 0);
        try {                                   // the app icon doubles as a logo mark
            android.graphics.drawable.Drawable ic = getResources().getDrawable(R.mipmap.ic_launcher);
            int sz = dp(24);
            ic.setBounds(0, 0, sz, sz);
            title.setCompoundDrawables(ic, null, null, null);
            title.setCompoundDrawablePadding(dp(6));
        } catch (Throwable ignored) {
        }
        bar.addView(title);'''
assert old_title in s, "title block not found"
s = s.replace(old_title, new_title, 1)

io.open(p, "w", encoding="utf-8", newline="").write(s)
print("chrome restyled")
