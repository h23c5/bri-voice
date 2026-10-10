# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
def rep_in(path, old, new, what):
    s = io.open(path, encoding='utf-8').read()
    if old not in s: raise SystemExit('anchor missing: ' + what)
    io.open(path, 'w', encoding='utf-8', newline='').write(s.replace(old, new, 1))
    print('ok:', what)

# ---- 1) FrameVoice: phases must survive vibrato --------------------------------------
f = root + r'\app\src\com\dsh\v4tune\synth\FrameVoice.java'
rep_in(f,
    '        boolean pitchMoved = Math.abs(f0 - this.f0) > 0.05 || harmonics == 0;',
    ('        // Only a real jump (a new note, not vibrato) may restart the phases. The first version' + chr(10) +
     '        // tested a 0.05 Hz change, which the default vibrato crosses on every control step, so all' + chr(10) +
     '        // harmonic phases were reset every 5 ms - heard as heavy electronic noise.' + chr(10) +
     '        boolean restart = harmonics == 0 || Math.abs(f0 - this.f0) > 0.25 * this.f0;'),
    'restart condition')
rep_in(f, '            if (pitchMoved) {', '            if (restart) {', 'restart use')
rep_in(f, '        if (pitchMoved) java.util.Arrays.fill(ampFrom, 1, maxHarm + 1, 0.0);   // fade the new pitch in',
    ('        // no amplitude reset: zeroing ampFrom on every pitch change gated the output at the' + chr(10) +
     '        // control rate (another source of buzz)'),
    'no amplitude gating')
rep_in(f, '        boolean pitchMoved = Math.abs', '        boolean restart = Math.abs', 'old name cleanup') if False else None

# ---- 2) the frame engine stays opt-in, as the user prefers the old one ----------------
r = root + r'\app\src\com\dsh\v4tune\synth\Renderer.java'
rep_in(r, '    public static boolean frameSequence = true;', '    public static boolean frameSequence = false;', 'default off')

# ---- 3) cursor delay: -2500 .. +2500 ms ----------------------------------------------
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
rep_in(m, '        off.setMax(1000);' + chr(10) + '        off.setProgress(cursorOffsetMs);',
    ('        off.setMax(5000);                       // -2500 .. +2500 ms, 2500 is zero' + chr(10) +
     '        off.setProgress(cursorOffsetMs + 2500);'), 'slider range')
rep_in(m, '                offLabel.setText(L.t("set.offset", Integer.valueOf(pr)));',
    '                offLabel.setText(L.t("set.offset", Integer.valueOf(pr - 2500)));', 'label')
rep_in(m, '                        cursorOffsetMs = off.getProgress();',
    '                        cursorOffsetMs = off.getProgress() - 2500;', 'save')
rep_in(m, '        Renderer.frameSequence = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("frameseq", true);',
    '        Renderer.frameSequence = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("frameseq", false);', 'pref default')
print('all done')