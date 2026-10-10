# -*- coding: utf-8 -*-
"""[fix] the render buffer must cover the notes, not just the part's stored duration."""
import io
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"

# 1) root cause: size from the notes as well
r = root + r"\app\src\com\dsh\v4tune\synth\Renderer.java"
s = io.open(r, encoding="utf-8").read()
old = '''        final double partStartSec = TimeUtil.tickToSec(song, part.pos);
        final double partEndSec = TimeUtil.tickToSec(song, part.end());
        int n = (int) Math.ceil((partEndSec - partStartSec + cfg.tailSec) * fs);'''
new = '''        final double partStartSec = TimeUtil.tickToSec(song, part.pos);
        // The stored duration is only a hint: a part created in the editor (or written by another
        // program) can hold notes past its own dur, and sizing the buffer from dur alone made every
        // write past the end throw ArrayIndexOutOfBoundsException. Cover the notes too.
        long endTick = part.end();
        for (Note nt : part.notes) endTick = Math.max(endTick, nt.pos + nt.dur);
        final double partEndSec = TimeUtil.tickToSec(song, endTick);
        int n = (int) Math.ceil((partEndSec - partStartSec + cfg.tailSec) * fs);'''
assert old in s, "buffer sizing"
s = s.replace(old, new, 1)

# 2) defence in depth: the breath layer must never write outside the buffer
old_breath = '''                for (int i = 0; i < len; i++) {
                    double e = Math.sin(Math.PI * i / len);
                    out[st + i] += (float) (nz.next() * e * e * 0.06 * vm.breathSoundAmount);
                }'''
new_breath = '''                for (int i = 0; i < len; i++) {
                    int k = st + i;
                    if (k < 0 || k >= out.length) continue;      // segments may reach past the buffer
                    double e = Math.sin(Math.PI * i / len);
                    out[k] += (float) (nz.next() * e * e * 0.06 * vm.breathSoundAmount);
                }'''
assert old_breath in s, "breath"
s = s.replace(old_breath, new_breath, 1)
io.open(r, "w", encoding="utf-8", newline="").write(s)
print("renderer fixed")

# 3) keep the stored duration in step while editing, so new projects stay consistent
p = root + r"\app\src\com\dsh\v4tune\ui\PianoRollView.java"
t = io.open(p, encoding="utf-8").read()
if "private void syncPartDur(" not in t:
    t = t.replace("    private void deleteSelected() {",
'''    /** the part's stored duration must cover its notes (see Renderer.renderPart) */
    private void syncPartDur(Part p) {
        if (p == null) return;
        long end = p.pos + 480L;
        for (Note n : p.notes) end = Math.max(end, n.pos + n.dur);
        if (end - p.pos > p.dur) p.dur = end - p.pos;
    }

    private void deleteSelected() {''', 1)
    # call it wherever notes are added or edited
    t = t.replace("        n.dur = Math.max(480L, snapEnabled ? snapTicks : 0L); n.vel = 64; n.lyric = inheritLyric(p, n.pos);",
                  "        n.dur = Math.max(480L, snapEnabled ? snapTicks : 0L); n.vel = 64; n.lyric = inheritLyric(p, n.pos);\n        syncPartDur(p);", 1)
    t = t.replace("                if (nd != hitNote.dur) { hitNote.dur = nd; changed = true; invalidate(); }",
                  "                if (nd != hitNote.dur) { hitNote.dur = nd; syncPartDur(hitPart); changed = true; invalidate(); }", 1)
    io.open(p, "w", encoding="utf-8", newline="").write(t)
    print("roll keeps dur in sync")
