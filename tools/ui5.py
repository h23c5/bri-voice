# -*- coding: utf-8 -*-
"""[ui] one uniform slider widget (name + value on top, slider below, same size as a button)."""
import io, re
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"
m = root + r"\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(m, encoding="utf-8").read()

# ---- replace the old sliderGroup helper with the uniform widget ---------------
old_helper_start = s.find("    /** a labelled slider used for snap and tempo; the label doubles as the numeric editor */")
old_helper_end = s.find("    /** square icon-ish button for the zoom trio */")
assert old_helper_start > 0 and old_helper_end > old_helper_start, "old helper not found"
new_helper = '''    /**
     * One slider control. It is exactly the same size as a button (see {@link #uniformButtonWidth()})
     * and carries its own readout: the name sits on the left of the top line, the current value on
     * the right, and the slider fills the second line. Tapping the name still opens an exact editor.
     */
    private final class SliderBox extends LinearLayout {
        final TextView name;
        final TextView value;
        final SeekBar bar;

        SliderBox(String label, String initial, int max, int progress,
                  SeekBar.OnSeekBarChangeListener l, View.OnClickListener onName) {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setBackground(Theme.button(MainActivity.this, false));
            setPadding(dp(7), dp(2), dp(7), dp(2));
            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            name = new TextView(MainActivity.this);
            name.setText(label);
            name.setTextColor(Theme.TEXT_DIM);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (onName != null) {
                name.setOnClickListener(onName);
                name.setPaintFlags(name.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            }
            value = new TextView(MainActivity.this);
            value.setText(initial);
            value.setTextColor(Theme.ACCENT);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            value.setSingleLine(true);
            value.setGravity(Gravity.END);
            top.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(value, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            bar = new SeekBar(MainActivity.this);
            bar.setMax(max);
            bar.setProgress(progress);
            bar.setMinimumHeight(0);
            bar.setPadding(0, 0, 0, 0);
            try {
                bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Theme.ACCENT));
                bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.ACCENT));
                bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2A3244));
            } catch (Throwable ignored) { }
            bar.setOnSeekBarChangeListener(l);
            addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    uniformButtonWidth(), dp(38));
            lp.setMargins(dp(3), dp(2), dp(3), dp(2));
            setLayoutParams(lp);
        }

        void setValue(String v) {
            value.setText(v);
        }
    }

'''
s = s[:old_helper_start] + new_helper + s[old_helper_end:]

# ---- transport: snap, tempo and zoom all use the new widget -------------------
old_snap_start = s.find('        // snap and tempo are sliders now')
old_snap_end = s.find('        transport.addView(smallButton(L.t("transport.lyric")')
assert old_snap_start > 0 and old_snap_end > old_snap_start, "slider block not found"
new_block = '''        // snap / tempo / zoom are sliders; each one shows its own value and is the size of a button
        snapBox = new SliderBox(L.t("transport.snap.short"), snapText(), SNAP_STEPS - 1,
                snapIndex(snapTicks), new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                        if (!fromUser) return;
                        snapTicks = snapTicksAt(pr);
                        roll.setSnapTicks(snapTicks);
                        snapBox.setValue(snapText());
                        status(snapTicks == 0 ? L.t("status.snap.off") : L.t("status.snap", 1920 / snapTicks));
                    }
                    public void onStartTrackingTouch(SeekBar sb) { }
                    public void onStopTrackingTouch(SeekBar sb) { }
                }, new View.OnClickListener() {
                    public void onClick(View v) {
                        cycleSnap(null);
                        snapBox.bar.setProgress(snapIndex(snapTicks));
                    }
                });
        transport.addView(snapBox);

        tempoBox = new SliderBox(L.t("transport.tempo"), tempoText(), TEMPO_MAX - TEMPO_MIN,
                (int) Math.round(song.tempoAt(0)) - TEMPO_MIN, new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                        if (!fromUser) return;
                        setTempo(TEMPO_MIN + pr);
                        tempoBox.setValue(tempoText());
                    }
                    public void onStartTrackingTouch(SeekBar sb) { }
                    public void onStopTrackingTouch(SeekBar sb) { }
                }, new View.OnClickListener() {
                    public void onClick(View v) { editTempo(); }        // exact value by keyboard
                });
        transport.addView(tempoBox);

        zoomBox = new SliderBox(L.t("transport.zoom.short"), zoomText(),
                ZOOM_LEVELS.length - 1, zoomIndex(), new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                        if (!fromUser) return;
                        applyZoomLevel(pr);
                    }
                    public void onStartTrackingTouch(SeekBar sb) { }
                    public void onStopTrackingTouch(SeekBar sb) { }
                }, new View.OnClickListener() {
                    public void onClick(View v) { fitZoom(); zoomBox.bar.setProgress(zoomIndex()); }
                });
        transport.addView(zoomBox);

'''
s = s[:old_snap_start] + new_block + s[old_snap_end:]

# ---- drop the old +/-/fit buttons (zoom is a slider now) ----------------------
old_zoom_buttons = '''        transport.addView(compactButton("\u2212", new View.OnClickListener() {      // zoom out
            public void onClick(View v) { zoomBy(1f / 1.35f); }
        }));
        transport.addView(compactButton("\uff0b", new View.OnClickListener() {      // zoom in
            public void onClick(View v) { zoomBy(1.35f); }
        }));
        transport.addView(compactButton("\u2922", new View.OnClickListener() {      // fit
            public void onClick(View v) { fitZoom(); }
        }));
'''
if old_zoom_buttons in s:
    s = s.replace(old_zoom_buttons, "", 1)
    print("zoom buttons removed")

# ---- fields + helpers ---------------------------------------------------------
s = s.replace('''    private android.widget.SeekBar snapBar, tempoBar;
    private TextView snapLabel;''',
'''    private SliderBox snapBox, tempoBox, zoomBox;
    /** zoom steps as a percentage of the 0.25 px/tick reference */
    private static final int[] ZOOM_LEVELS = {25, 50, 75, 100, 150, 200, 300, 400};
    private static final float ZOOM_REF_PX_PER_TICK = 0.25f;''', 1)

s = s.replace('''    private void zoomBy(float factor) {
        roll.zoomBy(factor);
        status(L.t("status.zoom", Math.round(roll.getPxPerTick() / 0.25f * 100) + "%"));
    }''',
'''    private String tempoText() {
        return String.valueOf(Math.round(song.tempoAt(0)));
    }

    private String zoomText() {
        return Math.round(roll.getPxPerTick() / ZOOM_REF_PX_PER_TICK * 100) + "%";
    }

    private int zoomIndex() {
        int pct = Math.round(roll.getPxPerTick() / ZOOM_REF_PX_PER_TICK * 100);
        int best = 3;
        int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < ZOOM_LEVELS.length; i++) {
            int d = Math.abs(ZOOM_LEVELS[i] - pct);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void applyZoomLevel(int index) {
        int pct = ZOOM_LEVELS[Math.max(0, Math.min(ZOOM_LEVELS.length - 1, index))];
        roll.setZoomX(ZOOM_REF_PX_PER_TICK * pct / 100f);
        if (zoomBox != null) zoomBox.setValue(zoomText());
        status(L.t("status.zoom", zoomText()));
    }''', 1)

io.open(m, "w", encoding="utf-8", newline="").write(s)
print("slider widget rewritten")

# ---- roll: setZoomX -----------------------------------------------------------
p2 = root + r"\app\src\com\dsh\v4tune\ui\PianoRollView.java"
t = io.open(p2, encoding="utf-8").read()
if "public void setZoomX(" not in t:
    t = t.replace("    /** zoom in/out around the centre of the visible area (used by the +/- buttons) */",
'''    /** horizontal zoom only (used by the zoom slider) */
    public void setZoomX(float x) {
        float cx = keyW + (getWidth() - keyW) * 0.5f;
        float tAt = tickAtX(cx);
        pxPerTick = clampTickPx(x);
        scrollTick = tAt - (cx - keyW) / pxPerTick;
        clampZoomAnchors();
        zoomInited = true;
        invalidate();
    }

    /** zoom in/out around the centre of the visible area */''', 1)
    io.open(p2, "w", encoding="utf-8", newline="").write(t)
    print("setZoomX added")

# new i18n key for the zoom slider name
l = root + r"\app\src\com\dsh\v4tune\i18n\L.java"
u = io.open(l, encoding="utf-8").read()
if '"transport.zoom.short"' not in u:
    marker = "\n    };\n"
    i = u.find("private static final String[][] TABLE = {")
    j = u.find(marker, i)
    row = '        {"transport.zoom.short", "\u7f29\u653e", "Zoom", "\u30ba\u30fc\u30e0"},\n'
    u = u[:j] + "\n" + row.rstrip("\n") + u[j:]
    io.open(l, "w", encoding="utf-8", newline="").write(u)
    print("zoom key added")
