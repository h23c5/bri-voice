# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)

# ---- 1) Seg carries legato flags -------------------------------------------------
rep('        int s0, s1;', '        int s0, s1;' + chr(10) +
    '        /** this segment starts right after the previous note: no fresh attack */' + chr(10) +
    '        boolean legatoIn;' + chr(10) +
    '        /** the next note starts right after this one: no long release */' + chr(10) +
    '        boolean legatoOut;', 'seg fields')

# ---- 2) vowel budget + legato detection in buildNoteSegments ---------------------
old = '''        int maxLead = (int) (total * 0.45);
        if (lead > maxLead) lead = maxLead;
        if (total < 0.12 * fs) lead = Math.min(lead, (int) (total * 0.3));'''
new = '''        // The vowel is what makes a syllable recognisable. Lead consonant and coda used to be
        // budgeted separately (45% + 25%), which on a 100 ms note left a 30 ms vowel - fast
        // passages turned to mush. They now share one budget and the vowel keeps the rest.
        boolean tight = total < 0.25 * fs;                       // fast notes
        int vowelFloor = (int) (total * (tight ? 0.60 : 0.55));
        int budget = Math.max(0, total - vowelFloor);            // what consonants may use together
        int maxLead = Math.min((int) (total * 0.45), budget);
        if (lead > maxLead) lead = maxLead;'''
rep(old, new, 'lead budget')
old2 = '                coda = Math.min((int) (last.durDef * fs), (int) (total * 0.25));'
new2 = '                coda = Math.min((int) (last.durDef * fs),' + chr(10) +
       '                        Math.min((int) (total * 0.25), Math.max(0, budget - lead)));'
rep(old2, new2, 'coda budget')

# legato detection right after the lead/coda are known
old3 = '        int leadStart = i0;'
new3 = ('        // a note that follows the previous one immediately is sung legato: the envelope must' + chr(10) +
        '        // not dip at the join (that dip is what made phrases sound chopped)' + chr(10) +
        '        int prevSegEnd = segs.isEmpty() ? -1 : segs.get(segs.size() - 1).s1;' + chr(10) +
        '        boolean legato = prevSegEnd >= 0 && i0 >= prevSegEnd && (i0 - prevSegEnd) <= (int) (0.035 * fs);' + chr(10) +
        '        if (legato) segs.get(segs.size() - 1).legatoOut = true;' + chr(10) +
        '        int leadStart = i0;')
rep(old3, new3, 'legato detect')
s = s.replace('            sg.ph = defs.get(i);' + chr(10) + '            sg.vowel = false;',
              '            sg.ph = defs.get(i);' + chr(10) + '            sg.vowel = false;' + chr(10) + '            sg.legatoIn = legato;', 1)
s = s.replace('            sg.ph = defs.get(i);' + chr(10) + '            sg.vowel = true;',
              '            sg.ph = defs.get(i);' + chr(10) + '            sg.vowel = true;' + chr(10) + '            sg.legatoIn = legato;', 1)

# ---- 3) envelope: short attack/release when legato -------------------------------
old4 = '        double atk = Math.min(0.02, d * 0.15), rel = Math.min(0.06, d * 0.25);'
new4 = ('        // legato joins keep their level: a long release followed by a fresh attack punched a' + chr(10) +
        '        // hole in the middle of every phrase' + chr(10) +
        '        double atk = Math.min(sg.legatoIn ? 0.010 : 0.02, d * 0.15);' + chr(10) +
        '        double rel = Math.min(sg.legatoOut ? 0.018 : 0.06, d * 0.25);')
rep(old4, new4, 'legato envelope')

# ---- 4) natural default vibrato --------------------------------------------------
old5 = '''        double depthCents = vibDepth > 0.5 ? Math.pow(vibDepth / 127.0, 1.5) * 120.0 : 0.0;
        if (vm.vibratoDepthCents > depthCents) depthCents = vm.vibratoDepthCents;
        depthCents *= clamp(vibScale, 0, 2.0);'''
new5 = '''        double depthCents = vibDepth > 0.5 ? Math.pow(vibDepth / 127.0, 1.5) * 120.0 : 0.0;
        if (vm.vibratoDepthCents > depthCents) depthCents = vm.vibratoDepthCents;
        // A perfectly steady, perfectly in-tune tone is exactly what makes a synthesizer sound
        // electronic. Real singing always carries a little vibrato, so a note with no vibrato data
        // gets a natural default: about 22 cents at 5.3 Hz, starting after 0.22 s and fading in over
        // 0.3 s, so short notes stay steady and long ones bloom. VIB=0 still switches it off.
        boolean explicitVib = depthCents > 0.5;
        if (!explicitVib) {
            depthCents = 22.0;
            if (vibRate < 0) vibRate = 41;        // 4 + (41/127)*4 = 5.3 Hz
        }
        depthCents *= clamp(vibScale, 0, 2.0);'''
rep(old5, new5, 'default vibrato')
s = s.replace('            double delay = delayCents < 0 ? 0.18 : delayCents;',
              '            double delay = delayCents < 0 ? (explicitVib ? 0.18 : 0.22) : delayCents;', 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('renderer: vowel budget + legato + default vibrato')