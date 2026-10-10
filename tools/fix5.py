# -*- coding: utf-8 -*-
"""[fix] the transport is built before the piano roll exists: never touch it unguarded."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()

old = '''    private String zoomText() {
        return Math.round(roll.getPxPerTick() / ZOOM_REF_PX_PER_TICK * 100) + "%";
    }

    private int zoomIndex() {
        int pct = Math.round(roll.getPxPerTick() / ZOOM_REF_PX_PER_TICK * 100);'''
new = '''    private String zoomText() {
        if (roll == null) return "100%";        // the transport is built before the roll
        return Math.round(roll.getPxPerTick() / ZOOM_REF_PX_PER_TICK * 100) + "%";
    }

    private int zoomIndex() {
        if (roll == null) return 3;             // default step = 100%
        int pct = Math.round(roll.getPxPerTick() / ZOOM_REF_PX_PER_TICK * 100);'''
assert old in s, "zoom helpers"
s = s.replace(old, new, 1)

old2 = '''    private void applyZoomLevel(int index) {
        int pct = ZOOM_LEVELS[Math.max(0, Math.min(ZOOM_LEVELS.length - 1, index))];
        roll.setZoomX(ZOOM_REF_PX_PER_TICK * pct / 100f);'''
new2 = '''    private void applyZoomLevel(int index) {
        if (roll == null) return;
        int pct = ZOOM_LEVELS[Math.max(0, Math.min(ZOOM_LEVELS.length - 1, index))];
        roll.setZoomX(ZOOM_REF_PX_PER_TICK * pct / 100f);'''
assert old2 in s, "applyZoomLevel"
s = s.replace(old2, new2, 1)

# after the roll exists, sync the slider to the real zoom level
old3 = '''        right.addView(roll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));'''
new3 = '''        right.addView(roll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (zoomBox != null) zoomBox.bar.setProgress(zoomIndex());'''
assert old3 in s, "roll add"
s = s.replace(old3, new3, 1)

io.open(p, "w", encoding="utf-8", newline="").write(s)
print("null guards added")
