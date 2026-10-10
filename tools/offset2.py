# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
widgets = "        box.addView(lock);\n\n        // cursor delay compensation: the sound a device emits lags the picture by its output buffer\n        final TextView offLabel = label(L.t(\"set.offset\", Integer.valueOf(cursorOffsetMs)));\n        box.addView(offLabel);\n        final SeekBar off = new SeekBar(this);\n        off.setMax(1000);\n        off.setProgress(cursorOffsetMs);\n        off.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {\n            public void onProgressChanged(SeekBar sb, int pr, boolean u) {\n                offLabel.setText(L.t(\"set.offset\", Integer.valueOf(pr)));\n            }\n            public void onStartTrackingTouch(SeekBar sb) { }\n            public void onStopTrackingTouch(SeekBar sb) { }\n        });\n        box.addView(off);\n        TextView offDesc = new TextView(this);\n        offDesc.setText(L.t(\"set.offset.desc\"));\n        offDesc.setTextColor(Theme.TEXT_DIM);\n        offDesc.setTextSize(12f);\n        offDesc.setPadding(dp(4), 0, 0, dp(6));\n        box.addView(offDesc);"
if 'final SeekBar off' in s:
    print('already there')
else:
    i = s.find('private void settingsDialog() {')
    if i < 0: raise SystemExit('settingsDialog missing')
    anchor = '        box.addView(lock);'
    j = s.find(anchor, i)
    if j < 0: raise SystemExit('lock anchor missing')
    s = s[:j] + widgets + s[j + len(anchor):]
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('offset widgets inserted')