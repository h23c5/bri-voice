# -*- coding: utf-8 -*-
"""[ui] uniform buttons, snap + tempo sliders, visible zoom controls, disclaimer in About."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()

# ---- 1) uniform button width -------------------------------------------------
old = '''        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        b.setLayoutParams(lp);'''
new = '''        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setGravity(Gravity.CENTER);
        // every button gets the same width so the bars read as a grid; the widest label of the app
        // ("\u5bfc\u51faWAV", "\u53e6\u5b58\u4e3a") still fits comfortably
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(uniformButtonWidth(), dp(38));
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        b.setLayoutParams(lp);'''
assert old in s, "button params"
s = s.replace(old, new, 1)

# helper: one shared width for every button (kept in one place so it is easy to tune)
old2 = '''    private Button smallButton(String text, View.OnClickListener l) {'''
new2 = '''    /** width shared by every toolbar / transport button */
    private int uniformButtonWidth() {
        return dp(96);
    }

    private Button smallButton(String text, View.OnClickListener l) {'''
assert old2 in s
s = s.replace(old2, new2, 1)

# ---- 2) snap + tempo become sliders -----------------------------------------
old_snap = '''        transport.addView(smallButton(L.t("transport.snap", 8), new View.OnClickListener() {
            public void onClick(View v) { cycleSnap((Button) v); }
        }));
        transport.addView(smallButton(L.t("transport.tempo"), new View.OnClickListener() {
            public void onClick(View v) { editTempo(); }
        }));'''
new_snap = '''        // snap and tempo are sliders now: the value label is still tappable and opens the numeric
        // dialog, so typing an exact value keeps working
        snapBar = new android.widget.SeekBar(this);
        snapBar.setMax(SNAP_STEPS - 1);
        snapBar.setProgress(snapIndex(snapTicks));
        snapBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int pr, boolean fromUser) {
                if (!fromUser) return;
                snapTicks = snapTicksAt(pr);
                roll.setSnapTicks(snapTicks);
                snapLabel.setText(snapText());
                status(snapTicks == 0 ? L.t("status.snap.off") : L.t("status.snap", 1920 / snapTicks));
            }
            public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        transport.addView(sliderGroup(L.t("transport.snap.short"), snapBar,
                new View.OnClickListener() {
                    public void onClick(View v) { cycleSnap(null); snapBar.setProgress(snapIndex(snapTicks)); }
                }));

        tempoBar = new android.widget.SeekBar(this);
        tempoBar.setMax(TEMPO_MAX - TEMPO_MIN);
        tempoBar.setProgress((int) Math.round(song.tempoAt(0)) - TEMPO_MIN);
        tempoBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int pr, boolean fromUser) {
                if (!fromUser) return;
                setTempo(TEMPO_MIN + pr);
            }
            public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        transport.addView(sliderGroup(L.t("transport.tempo"), tempoBar,
                new View.OnClickListener() {
                    public void onClick(View v) { editTempo(); }
                }));'''
assert old_snap in s, "snap/tempo buttons"
s = s.replace(old_snap, new_snap, 1)

# ---- 3) visible zoom controls ------------------------------------------------
old_preview = '''        transport.addView(smallButton(L.t("transport.preview.part"), new View.OnClickListener() {
            public void onClick(View v) { previewPart(); }
        }));'''
new_preview = '''        transport.addView(smallButton(L.t("transport.preview.part"), new View.OnClickListener() {
            public void onClick(View v) { previewPart(); }
        }));
        transport.addView(compactButton("\u2212", new View.OnClickListener() {      // zoom out
            public void onClick(View v) { zoomBy(1f / 1.35f); }
        }));
        transport.addView(compactButton("\uff0b", new View.OnClickListener() {      // zoom in
            public void onClick(View v) { zoomBy(1.35f); }
        }));
        transport.addView(compactButton("\u2922", new View.OnClickListener() {      // fit
            public void onClick(View v) { fitZoom(); }
        }));'''
assert old_preview in s, "preview button"
s = s.replace(old_preview, new_preview, 1)

io.open(p, "w", encoding="utf-8", newline="").write(s)
print("transport restyled")
