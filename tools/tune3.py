# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\render_test\VoiceQualityTest.java'
s = io.open(p, encoding='utf-8').read()
old = '        int loLag = (int) (SR / 500.0), hiLag = (int) (SR / 80.0);'
new = ('        // search only around the note we rendered (C4 = 261.6 Hz): an unrestricted search picks' + chr(10) +
       '        // octave errors and turns them into a fake 19-semitone "vibrato"' + chr(10) +
       '        double exp = 261.6;' + chr(10) +
       '        int loLag = (int) (SR / (exp * 1.25)), hiLag = (int) (SR / (exp * 0.80));')
if old not in s: raise SystemExit('f0 anchor missing')
s = s.replace(old, new, 1)
s = s.replace('            System.out.printf', '            System.out.printf', 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('f0 search restricted')