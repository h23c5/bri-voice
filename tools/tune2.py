# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

rep('        int s0, s1;', "        int s0, s1;\n        /** this segment starts right after the previous note: no fresh attack */\n        boolean legatoIn;\n        /** the next note starts right after this one: no long release */\n        boolean legatoOut;", 'seg fields')

rep("        int maxLead = (int) (total * 0.45);\n        if (lead > maxLead) lead = maxLead;\n        if (total < 0.12 * fs) lead = Math.min(lead, (int) (total * 0.3));", "        // The vowel is what makes a syllable recognisable. Lead consonant and coda used to be\n        // budgeted separately (45% + 25%), which on a 100 ms note left a 30 ms vowel - fast\n        // passages turned to mush. They now share one budget and the vowel keeps the rest.\n        boolean tight = total < 0.25 * fs;                       // fast notes\n        int vowelFloor = (int) (total * (tight ? 0.60 : 0.55));\n        int budget = Math.max(0, total - vowelFloor);            // what consonants may use together\n        int maxLead = Math.min((int) (total * 0.45), budget);\n        if (lead > maxLead) lead = maxLead;", 'lead budget')

rep('                coda = Math.min((int) (last.durDef * fs), (int) (total * 0.25));', "                coda = Math.min((int) (last.durDef * fs),\n                        Math.min((int) (total * 0.25), Math.max(0, budget - lead)));", 'coda budget')

rep('        int leadStart = i0;', "        // a note that follows the previous one immediately is sung legato: the envelope must\n        // not dip at the join (that dip is what made phrases sound chopped)\n        int prevSegEnd = segs.isEmpty() ? -1 : segs.get(segs.size() - 1).s1;\n        boolean legato = prevSegEnd >= 0 && i0 >= prevSegEnd && (i0 - prevSegEnd) <= (int) (0.035 * fs);\n        if (legato) segs.get(segs.size() - 1).legatoOut = true;\n        int leadStart = i0;", 'legato detect')

s = s.replace('            sg.ph = defs.get(i);' + chr(10) + '            sg.vowel = false;',
              '            sg.ph = defs.get(i);' + chr(10) + '            sg.vowel = false;' + chr(10) + '            sg.legatoIn = legato;', 1)
s = s.replace('            sg.ph = defs.get(i);' + chr(10) + '            sg.vowel = true;',
              '            sg.ph = defs.get(i);' + chr(10) + '            sg.vowel = true;' + chr(10) + '            sg.legatoIn = legato;', 1)

rep('        double atk = Math.min(0.02, d * 0.15), rel = Math.min(0.06, d * 0.25);', "        // legato joins keep their level: a long release followed by a fresh attack punched a\n        // hole in the middle of every phrase\n        double atk = Math.min(sg.legatoIn ? 0.010 : 0.02, d * 0.15);\n        double rel = Math.min(sg.legatoOut ? 0.018 : 0.06, d * 0.25);", 'legato envelope')

rep("        double depthCents = vibDepth > 0.5 ? Math.pow(vibDepth / 127.0, 1.5) * 120.0 : 0.0;\n        if (vm.vibratoDepthCents > depthCents) depthCents = vm.vibratoDepthCents;\n        depthCents *= clamp(vibScale, 0, 2.0);", "        double depthCents = vibDepth > 0.5 ? Math.pow(vibDepth / 127.0, 1.5) * 120.0 : 0.0;\n        if (vm.vibratoDepthCents > depthCents) depthCents = vm.vibratoDepthCents;\n        // A perfectly steady, perfectly in-tune tone is exactly what makes a synthesizer sound\n        // electronic. Real singing always carries a little vibrato, so a note with no vibrato data\n        // gets a natural default: about 22 cents at 5.3 Hz, after a 0.22 s delay and a 0.3 s fade,\n        // so short notes stay steady and long ones bloom. VIB=0 still switches it off completely.\n        boolean explicitVib = depthCents > 0.5;\n        if (!explicitVib) {\n            depthCents = 22.0;\n            if (vibRate < 0) vibRate = 41;        // 4 + (41/127)*4 = 5.3 Hz\n        }\n        depthCents *= clamp(vibScale, 0, 2.0);", 'default vibrato')

s = s.replace('            double delay = delayCents < 0 ? 0.18 : delayCents;',
              '            double delay = delayCents < 0 ? (explicitVib ? 0.18 : 0.22) : delayCents;', 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('all voicing fixes applied')