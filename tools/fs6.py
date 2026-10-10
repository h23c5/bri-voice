# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\render_test\VowelFormantTest.java'
s = io.open(p, encoding='utf-8').read()
if 'DEBUG ENVELOPE' not in s:
    anchor = '        double[] peaks = new double[4];'
    dbg = ('        if (System.getenv("VOWEL_DEBUG") != null) {' + chr(10) +
           '            StringBuilder sb = new StringBuilder("DEBUG ENVELOPE " + sym + ": ");' + chr(10) +
           '            for (int k = 1; k <= K; k++) sb.append(String.format("%.0f:%.0f ", hz[k], db[k]));' + chr(10) +
           '            System.out.println(sb);' + chr(10) +
           '        }' + chr(10))
    if anchor not in s: raise SystemExit('anchor missing')
    s = s.replace(anchor, dbg + anchor, 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('debug added')