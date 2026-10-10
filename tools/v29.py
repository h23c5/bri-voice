# -*- coding: utf-8 -*-
"""[v2.9] live zoom callback from the roll, and a two-decimal tempo."""
import io
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"

# ---------- 1) the roll reports every zoom change -----------------------------
pr = root + r"\app\src\com\dsh\v4tune\ui\PianoRollView.java"
t = io.open(pr, encoding="utf-8").read()
if "interface ZoomListener" not in t:
    anchor = "    public interface Listener {"
    t = t.replace(anchor, '''    /** notified whenever the zoom level changes (pinch, buttons, slider, resize) */
    public interface ZoomListener {
        void onZoomChanged(float pxPerTick);
    }

''' + anchor, 1)

    t = t.replace("    public void setListener(Listener l) { listener = l; }",
'''    public void setListener(Listener l) { listener = l; }

    public void setZoomListener(ZoomListener l) { zoomListener = l; }

    /** tell the UI about a new zoom level, whatever changed it */
    private void notifyZoom() {
        if (zoomListener != null) {
            try {
                zoomListener.onZoomChanged(pxPerTick);
            } catch (Throwable ignored) {
            }
        }
    }
''', 1)

    t = t.replace("    private boolean vInited", "    private ZoomListener zoomListener;\n    private boolean vInited", 1)
    if "private ZoomListener zoomListener;" not in t:      # field may be declared elsewhere
        t = t.replace("    private Listener listener;", "    private Listener listener;\n    private ZoomListener zoomListener;", 1)

    # notify after every place that changes pxPerTick
    t = t.replace("        pxPerTick = clampTickPx(pxPerTick * sx); pxPerSemitone = clampKeyPx(pxPerSemitone * sy);",
                  "        pxPerTick = clampTickPx(pxPerTick * sx); pxPerSemitone = clampKeyPx(pxPerSemitone * sy); notifyZoom();", 1)
    t = t.replace("        pxPerSemitone = clampKeyPx(pxPerSemitone * sy);\n        clampScroll();\n        zoomInited = true;\n        invalidate();\n    }\n\n    public void setZoom(",
                  "        pxPerSemitone = clampKeyPx(pxPerSemitone * sy);\n        clampScroll();\n        zoomInited = true;\n        notifyZoom();\n        invalidate();\n    }\n\n    public void setZoom(", 1)
    t = t.replace("    public void setZoomX(float x) {\n        float cx = keyW + (getWidth() - keyW) * 0.5f;\n        float tAt = tickAtX(cx);\n        pxPerTick = clampTickPx(x);\n        scrollTick = tAt - (cx - keyW) / pxPerTick;\n        clampScroll();\n        zoomInited = true;\n        invalidate();\n    }",
                  "    public void setZoomX(float x) {\n        float cx = keyW + (getWidth() - keyW) * 0.5f;\n        float tAt = tickAtX(cx);\n        pxPerTick = clampTickPx(x);\n        scrollTick = tAt - (cx - keyW) / pxPerTick;\n        clampScroll();\n        zoomInited = true;\n        notifyZoom();\n        invalidate();\n    }", 1)
    # resetZoom + double-tap: notify at the end of that method
    old_reset = "    private void resetZoom(float x, float y) { float tAt = tickAtX(x), kAt = keyAtY(y);"
    if old_reset in t:
        idx = t.find(old_reset)
        end = t.find("\n    }", idx)
        seg = t[idx:end]
        seg2 = seg.replace("invalidate();", "notifyZoom(); invalidate();")
        t = t[:idx] + seg2 + t[end:]
    io.open(pr, "w", encoding="utf-8", newline="").write(t)
    print("roll zoom callback added")

# ---------- 2) tempo with two decimals ----------------------------------------
m = root + r"\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(m, encoding="utf-8").read()

old_tempo = '''    private String tempoText() {
        return String.valueOf(Math.round(song.tempoAt(0)));
    }'''
new_tempo = '''    /** tempo readout with two decimals, the same resolution the slider and the dialog use */
    private String tempoText() {
        return String.format(java.util.Locale.US, "%.2f", song.tempoAt(0));
    }'''
assert old_tempo in s
s = s.replace(old_tempo, new_tempo, 1)

old_set = '''    private void setTempo(double bpm) {
        double v = Math.max(TEMPO_MIN, Math.min(TEMPO_MAX, Math.round(bpm)));
        beginEdit();
        song.tempos.clear();
        song.tempos.add(new long[]{0, Math.round(v * 100)});
        commitEdit();
        refreshAll();
        status(L.t("status.tempo", (int) v));
    }'''
new_set = '''    /** stores the tempo in centi-BPM, so 120.37 is kept exactly (no rounding to whole BPM) */
    private void setTempo(double bpm) {
        double v = Math.max(TEMPO_MIN, Math.min(TEMPO_MAX, bpm));
        v = Math.round(v * 100) / 100.0;
        beginEdit();
        song.tempos.clear();
        song.tempos.add(new long[]{0, Math.round(v * 100)});
        commitEdit();
        refreshAll();
        status(L.t("status.tempo", tempoText()));
    }'''
assert old_set in s
s = s.replace(old_set, new_set, 1)

# slider resolution: 0.01 BPM per step
old_slider = '''        tempoBox = new SliderBox(L.t("transport.tempo"), tempoText(), TEMPO_MAX - TEMPO_MIN,
                (int) Math.round(song.tempoAt(0)) - TEMPO_MIN, new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                        if (!fromUser) return;
                        setTempo(TEMPO_MIN + pr);
                        tempoBox.setValue(tempoText());
                    }'''
new_slider = '''        tempoBox = new SliderBox(L.t("transport.tempo"), tempoText(), (TEMPO_MAX - TEMPO_MIN) * 100,
                tempoProgress(), new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                        if (!fromUser) return;
                        setTempo(TEMPO_MIN + pr / 100.0);
                        tempoBox.setValue(tempoText());
                    }'''
assert old_slider in s, "tempo slider"
s = s.replace(old_slider, new_slider, 1)

# helper + keep the slider in sync when the dialog sets a value
s = s.replace('''    private String zoomText() {''',
'''    /** slider position for the current tempo (0.01 BPM steps) */
    private int tempoProgress() {
        return (int) Math.round((song.tempoAt(0) - TEMPO_MIN) * 100);
    }

    private String zoomText() {''', 1)

# wire the roll's zoom callback into the slider
old_hook = '''        if (zoomBox != null) zoomBox.bar.setProgress(zoomIndex());'''
new_hook = '''        if (zoomBox != null) zoomBox.bar.setProgress(zoomIndex());
        roll.setZoomListener(new PianoRollView.ZoomListener() {
            public void onZoomChanged(float pxPerTick) {
                // pinch / double-tap zoom keeps the slider and its readout in step
                if (zoomBox == null) return;
                zoomBox.bar.setProgress(zoomIndex());
                zoomBox.setValue(zoomText());
            }
        });'''
assert old_hook in s
s = s.replace(old_hook, new_hook, 1)

# tempo dialog result must move the slider too
s = s.replace('''                            commitEdit();
                            refreshAll();
                            status("速度 = " + bpm + " BPM");''',
'''                            commitEdit();
                            refreshAll();
                            if (tempoBox != null) {
                                tempoBox.bar.setProgress(tempoProgress());
                                tempoBox.setValue(tempoText());
                            }
                            status(L.t("status.tempo", tempoText()));''', 1)

io.open(m, "w", encoding="utf-8", newline="").write(s)
print("tempo precision + zoom sync wired")
