# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
# 1) the engine takes a compensation offset
e = root + r'\app\src\com\dsh\v4tune\audio\AudioEngine.java'
s = io.open(e, encoding='utf-8').read()
if 'cursorOffsetMs' not in s:
    s = s.replace('    private volatile boolean cancelRender;',
                  '    private volatile boolean cancelRender;' + chr(10) +
                  '    /** how far the on-screen cursor is shifted back to match what the user hears */' + chr(10) +
                  '    public volatile int cursorOffsetMs;', 1)
    s = s.replace('                            if (played < start) played = start;',
                  '                            played -= (long) (cursorOffsetMs * r.sampleRate / 1000.0);' + chr(10) +
                  '                            if (played < start) played = start;', 1)
    io.open(e, 'w', encoding='utf-8', newline='').write(s)
    print('engine offset added')
# 2) settings: load and expose it
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
t = io.open(m, encoding='utf-8').read()
if 'cursorOffsetMs' not in t:
    t = t.replace('    private boolean phonemeLock;',
                  '    private boolean phonemeLock;' + chr(10) +
                  '    /** ms the cursor is pulled back so it matches the sound the user hears */' + chr(10) +
                  '    private int cursorOffsetMs;', 1)
    t = t.replace('        phonemeLock = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("phonemeLock", false);',
                  '        phonemeLock = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("phonemeLock", false);' + chr(10) +
                  '        cursorOffsetMs = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("cursorOffsetMs", 0);' + chr(10) +
                  '        engine.cursorOffsetMs = cursorOffsetMs;', 1)
    add = ('        final TextView offLabel = label(L.t("set.offset", Integer.valueOf(cursorOffsetMs)));' + chr(10) +
           '        box.addView(offLabel);' + chr(10) +
           '        final SeekBar off = new SeekBar(this);' + chr(10) +
           '        off.setMax(1000);' + chr(10) +
           '        off.setProgress(cursorOffsetMs);' + chr(10) +
           '        off.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {' + chr(10) +
           '            public void onProgressChanged(SeekBar sb, int pr, boolean u) {' + chr(10) +
           '                offLabel.setText(L.t("set.offset", Integer.valueOf(pr)));' + chr(10) +
           '            }' + chr(10) +
           '            public void onStartTrackingTouch(SeekBar sb) { }' + chr(10) +
           '            public void onStopTrackingTouch(SeekBar sb) { }' + chr(10) +
           '        });' + chr(10) +
           '        box.addView(off);' + chr(10) +
           '        TextView offDesc = new TextView(this);' + chr(10) +
           '        offDesc.setText(L.t("set.offset.desc"));' + chr(10) +
           '        offDesc.setTextColor(Theme.TEXT_DIM);' + chr(10) +
           '        offDesc.setTextSize(12f);' + chr(10) +
           '        offDesc.setPadding(dp(4), 0, 0, dp(6));' + chr(10) +
           '        box.addView(offDesc);' + chr(10) +
           '        box.addView(label(L.t("set.phoneme.lock")));' + chr(10))
    t = t.replace('        box.addView(label(L.t("set.phoneme.lock")));', add, 1)
    t = t.replace('                        phonemeLock = lock.isChecked();',
                  '                        cursorOffsetMs = off.getProgress();' + chr(10) +
                  '                        engine.cursorOffsetMs = cursorOffsetMs;' + chr(10) +
                  '                        getSharedPreferences("brivoice", MODE_PRIVATE).edit()' + chr(10) +
                  '                                .putInt("cursorOffsetMs", cursorOffsetMs).apply();' + chr(10) +
                  '                        phonemeLock = lock.isChecked();', 1)
    io.open(m, 'w', encoding='utf-8', newline='').write(t)
    print('settings offset added')
# 3) keys
L = root + r'\app\src\com\dsh\v4tune\i18n\L.java'
u = io.open(L, encoding='utf-8').read()
rows = [('set.offset', '指针延迟补偿：%d ms', 'Cursor delay compensation: %d ms', 'カーソル遅延補正：%d ms'),
        ('set.offset.desc', '听到的声音比画面慢时，把指针往后拉。模拟器和蓝牙耳机延迟较大，真机通常 0–100 ms。', 'Pull the cursor back when the sound lags the picture. Emulators and Bluetooth headsets need more; a phone usually 0-100 ms.', '音が画面より遅れる場合にカーソルを後ろにずらします。エミュレータや Bluetooth では大きめに。')]
add = []
for r in rows:
    if ('"' + r[0] + '"') in u: continue
    add.append('        {"' + r[0] + '", ' + ', '.join('"' + v + '"' for v in r[1:]) + '},')
if add:
    i = u.find('{"set.phoneme.lock"')
    j = u.rfind(chr(10), 0, i) + 1
    u = u[:j] + chr(10).join(add) + chr(10) + u[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(u)
print('keys added:', len(add))