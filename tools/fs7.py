# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\render_test\PackCurveProbe.java'
s = io.open(p, encoding='utf-8').read()
s = s.replace('                    for (int i = 0; i < b.db.length; i += 6) System.out.printf("%.0f ", b.db[i]);',
              '                    for (int i = 0; i < b.db.length; i++) {' + chr(10) +
              '                        if (v.harmonicFreqs[i] > 4200) break;' + chr(10) +
              '                        System.out.printf("%.0f:%.0f ", v.harmonicFreqs[i], b.db[i]);' + chr(10) +
              '                    }', 1)
s = s.replace('                System.out.println(sym + ": " + arr.length + " variants, f0 values:");',
              '                System.out.println(sym + ": " + arr.length + " variants; full curve to 4 kHz:");' + chr(10) +
              '                arr = new VoiceModel.HarmonicBand[]{arr[arr.length - 1]};', 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('probe updated')