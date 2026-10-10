# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\audio\Demo.java'
s = io.open(p, encoding='utf-8').read()
BT = chr(92) + 'u0060'
lyr = ['\u8fd9', '\u91cc', '\u662f', 'Bri', 'Voice', '\u6b22', '\u8fce', '\u56de', '\u6765']
ph  = ['ts' + BT + ' 7', 'l i', 's' + BT + ' i' + BT, 'b z' + BT + ' aI', 'w o i s', 'x ua_n', 'iN', 'x uei', 'l aI']
keys = [60, 62, 64, 65, 67, 69, 67, 65, 64]
q = lambda xs: '{' + ', '.join('"' + x + '"' for x in xs) + '}'
lines = []
lines.append('        String[] lyr = ' + q(lyr) + ';')
lines.append('        // phoneme column carries the CHN symbols of the same syllables, so normally')
lines.append('        // written text still sings: a Hanzi lyric alone phonemises to a bare [a].')
lines.append('        String[] ph = ' + q(ph) + ';')
lines.append('        int[] keys = ' + q([str(k) for k in keys]) + ';')
lines.append('        long t0 = 480;')
lines.append('        for (int i = 0; i < lyr.length; i++) {')
lines.append('            Note n = new Note();')
lines.append('            n.pos = t0 + i * 480L;')
lines.append('            n.dur = 420;')
lines.append('            n.key = keys[i];')
lines.append('            n.vel = 64;')
lines.append('            n.lyric = lyr[i];')
lines.append('            n.phoneme = ph[i];')
lines.append('            p.notes.add(n);')
lines.append('        }')
new = '\n'.join(lines) + '\n'
i0 = s.find('        String[] lyr = ')
i1 = s.find('        // a little dynamics')
if i0 < 0 or i1 < 0: raise SystemExit('note block not found')
s = s[:i0] + new + s[i1:]
s = s.replace('p.dur = 1920 * 5;', 'p.dur = 1920 * 3;', 1)
s = s.replace('s.masterName = "\u5185\u7f6e\u793a\u4f8b \u00b7 \u6d1b\u5929\u4f9d";', 's.masterName = "\u8fd9\u91cc\u662f BriVoice \u6b22\u8fce\u56de\u6765";', 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('demo rewritten')
print(new[:400])