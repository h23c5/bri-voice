# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\FrameVoice.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

# a shared sine table: exact amplitude, no drift, and the amplitude multiplies in exactly once
rep('    private double[] c1 = new double[0];         // 2*cos(omega) of the recursive oscillator', "    private double[] dphi = new double[0];       // phase advance per sample, in turns\n    private double[] phase = new double[0];      // running phase per harmonic, in turns\n    private static final int TABLE = 4096;\n    private static final double[] SIN = new double[TABLE + 1];\n    static {\n        for (int i = 0; i <= TABLE; i++) SIN[i] = Math.sin(2.0 * Math.PI * i / TABLE);\n    }", 'sine table')
rep('            double[] c1 = new double[maxHarm + 2];',
    '            double[] dphi = new double[maxHarm + 2];' + chr(10) + '            double[] phase = new double[maxHarm + 2];', 'arrays')
rep('            c1 = new double[maxHarm + 2];', '            dphi = new double[maxHarm + 2];' + chr(10) + '            phase = new double[maxHarm + 2];', 'grow')
rep('        java.util.Arrays.fill(y1, 0.0);' + chr(10) + '        java.util.Arrays.fill(y2, 0.0);',
    '        java.util.Arrays.fill(phase, 0.0);', 'reset')
rep("                double w = 2.0 * Math.PI * k * f0 / sampleRate;\n                c1[k] = 2.0 * Math.cos(w);\n                double ph = voiced ? 0.0 : rnd.nextDouble() * 2.0 * Math.PI;\n                double a = norm * raw[k] * level;\n                y1[k] = a * Math.cos(ph);\n                y2[k] = a * Math.cos(ph - w);\n                amp[k] = a;\n                ampFrom[k] = a;", "                dphi[k] = k * f0 / sampleRate;                 // turns per sample\n                phase[k] = voiced ? 0.0 : rnd.nextDouble();\n                double a = norm * raw[k] * level;\n                amp[k] = a;\n                ampFrom[k] = a;", 'init')
rep("        for (int k = 1; k <= harmonics; k++) {\n            double y = c1[k] * y1[k] - y2[k];\n            y2[k] = y1[k];\n            y1[k] = y;\n            double a = ampFrom[k] + (amp[k] - ampFrom[k]) * t;\n            v += a * y;\n        }", "        for (int k = 1; k <= harmonics; k++) {\n            double ph = phase[k] + dphi[k];\n            if (ph >= 1.0) ph -= 1.0;\n            phase[k] = ph;\n            double x = ph * TABLE;\n            int i0 = (int) x;\n            if (i0 >= TABLE) i0 = TABLE - 1;\n            double fr = x - i0;\n            double y = SIN[i0] + (SIN[i0 + 1] - SIN[i0]) * fr;\n            double a = ampFrom[k] + (amp[k] - ampFrom[k]) * t;\n            v += a * y;\n        }", 'sample loop')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('wavetable oscillators in place')