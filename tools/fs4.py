# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\FrameVoice.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

rep('    private double[,] dphi', 'x', 'noop') if False else None
rep('    private double[] dphi = new double[0];       // phase advance per sample, in turns', "    private double[] dphi = new double[0];       // target phase advance per sample, in turns\n    private double[] dphiCur = new double[0];    // smoothed advance: the control rate would otherwise\n                                                 // staircase the vibrato into FM sidebands\n    private double freqCoef = 1.0;\n    private static final double FREQ_SEC = 0.004;", 'freq state')
rep('            dphi = new double[maxHarm + 2];', 
    '            dphi = new double[maxHarm + 2];' + chr(10) + '            dphiCur = new double[maxHarm + 2];', 'grow')
rep('            dphi[k] = k * f0 / sampleRate;                   // turns per sample',
    '            dphi[k] = k * f0 / sampleRate;                   // turns per sample' + chr(10) +
    '            if (restart) dphiCur[k] = dphi[k];', 'seed')
rep('            double ph = phase[k] + dphi[k];',
    '            dphiCur[k] += (dphi[k] - dphiCur[k]) * freqCoef;' + chr(10) +
    '            double ph = phase[k] + dphiCur[k];', 'smoothed advance')
rep('        smoothCoef = 1.0 - Math.exp(-1.0 / (SMOOTH_SEC * sampleRate));',
    '        smoothCoef = 1.0 - Math.exp(-1.0 / (SMOOTH_SEC * sampleRate));' + chr(10) +
    '        freqCoef = 1.0 - Math.exp(-1.0 / (FREQ_SEC * sampleRate));', 'coef')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('written')