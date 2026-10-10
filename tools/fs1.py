# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\FrameVoice.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

rep('    private int rampLeft, rampTotal = 1;', "    private double[] smoothed = new double[0];   // one-pole amplitude trajectory\n    private double smoothCoef = 1.0;             // set from SMOOTH_SEC\n    private static final double SMOOTH_SEC = 0.020;", 'state')
rep('            dphi = new double[maxHarm + 2];', 
    '            dphi = new double[maxHarm + 2];' + chr(10) + '            smoothed = new double[maxHarm + 2];', 'grow')
rep('        java.util.Arrays.fill(phase, 0.0);' + chr(10) + '        rampLeft = 0;',
    '        java.util.Arrays.fill(phase, 0.0);' + chr(10) + '        java.util.Arrays.fill(smoothed, 0.0);' + chr(10) +
    '        smoothCoef = 1.0 - Math.exp(-1.0 / (SMOOTH_SEC * sampleRate));', 'reset')
rep('            ampFrom[k] = amp[k];' + chr(10) + '            amp[k] = norm * raw[k] * level;',
    '            amp[k] = norm * raw[k] * level;' + chr(10) +
    '            if (restart) smoothed[k] = 0.0;', 'amp assign')
rep('        if (restart) java.util.Arrays.fill(smoothed, 1, maxHarm + 1, 0.0);',
    '        if (restart) java.util.Arrays.fill(smoothed, 1, maxHarm + 1, 0.0);', 'restart zero') if False else None
rep("        double t = rampLeft > 0 ? 1.0 - rampLeft / (double) rampTotal : 1.0;\n        double v = 0.0;", "        double v = 0.0;", 'drop ramp')
rep("            v += (ampFrom[k] + (amp[k] - ampFrom[k]) * t) * y;\n        }\n        if (rampLeft > 0) rampLeft--;", "            // One-pole trajectory instead of a linear ramp per hop. Ramping the amplitudes at the\n            // 172 Hz hop rate modulated every harmonic and put sidebands between them: the\n            // inter-harmonic energy measured +5.4 dB against -39.7 dB for the old engine. A 20 ms\n            // one-pole keeps the frame evolution (a few Hz) and removes the hop-rate sidebands.\n            smoothed[k] += (amp[k] - smoothed[k]) * smoothCoef;\n            v += smoothed[k] * y;\n        }", 'one-pole')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('written')