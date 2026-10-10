# -*- coding: utf-8 -*-
"""[v3.8] a "play from the beginning" button next to play."""
import io
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"

M = root + r"\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(M, encoding="utf-8").read()

# the button, right after 播放
old = '''        transport.addView(smallButton(L.t("transport.preview.part"), new View.OnClickListener() {'''
new = '''        transport.addView(smallButton(L.t("transport.fromstart"), new View.OnClickListener() {
            public void onClick(View v) { playFromStart(); }
        }));
        transport.addView(smallButton(L.t("transport.preview.part"), new View.OnClickListener() {'''
assert old in s, "transport"
s = s.replace(old, new, 1)

# the action: rewind to the first tick and start
old_stop = "    private void stopPlay() {"
new_stop = '''    /** jump the cursor to the very beginning and start playing from there */
    private void playFromStart() {
        boolean wasPlaying = engine.isPlaying();
        if (wasPlaying) engine.stop();                 // silent stop: do not keep the old cursor
        engineCancel();
        startTick = 0L;
        roll.setPlayheadTick(0L);
        if (paramView != null) paramView.setPlayheadTick(0L);
        posText.setText(formatTick(0L));
        roll.scrollToTick(0L);
        playBtn.setText(L.t("transport.play"));
        if (wasPlaying) ui.postDelayed(new Runnable() { public void run() { playSong(); } }, 150L);
        else playSong();
    }

    private void stopPlay() {'''
assert old_stop in s
s = s.replace(old_stop, new_stop, 1)
io.open(M, "w", encoding="utf-8", newline="").write(s)
print("playFromStart added")

# i18n
l = root + r"\app\src\com\dsh\v4tune\i18n\L.java"
u = io.open(l, encoding="utf-8").read()
if '"transport.fromstart"' not in u:
    marker = "\n    };\n"
    i = u.find("private static final String[][] TABLE = {")
    j = u.find(marker, i)
    row = ('        {"transport.fromstart", "\u4ece\u5934\u64ad\u653e", "From Start", "\u6700\u521d\u304b\u3089\u518d\u751f"},\n'
           '        {"status.fromstart", "\u5df2\u56de\u5230\u5f00\u5934\uff0c\u4ece 001:1 \u5f00\u59cb\u64ad\u653e", "Rewound to the start, playing from 001:1", "\u5148\u982d\u306b\u623b\u3057\u3066\u518d\u751f\u3057\u307e\u3059"},\n')
    u = u[:j] + "\n" + row.rstrip("\n") + u[j:]
    io.open(l, "w", encoding="utf-8", newline="").write(u)
    print("i18n keys added")
