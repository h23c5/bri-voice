# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\render_test\FrameSequenceTest.java'
s = io.open(p, encoding='utf-8').read()
add = '\n' + '\n'.join([
    '    /** energy between the harmonics vs at them: phase splatter shows up here */',
    '    static double inharmonic(float[] x, int at, double f0) {',
    '        double on = 0, off = 0;',
    '        for (int k = 1; k <= 30; k++) {',
    '            if (k * f0 > 7000) break;',
    '            on += goertzel(x, at, k * f0) + goertzel(x, at, k * f0 + 8);',
    '            off += goertzel(x, at, (k + 0.5) * f0);',
    '        }',
    '        return 20 * Math.log10(Math.max(1e-12, off) / Math.max(1e-12, on));',
    '    }',
])
if 'inharmonic(' not in s:
    s = s.replace('    static double[] bands(float[] x, int at) {', add + chr(10) + chr(10) + '    static double[] bands(float[] x, int at) {', 1)
    s = s.replace('        System.out.printf("frame-to-frame spectral change',
                  '        System.out.printf("inter-harmonic energy (lower = cleaner): frame %.1f dB, table %.1f dB%n",' + chr(10) +
                  '                inharmonic(frame, at + 8820, 261.6), inharmonic(table, at + 8820, 261.6));' + chr(10) +
                  '        System.out.printf("frame-to-frame spectral change', 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('inharmonic metric added')
else:
    print('already there')