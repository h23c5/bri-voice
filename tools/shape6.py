# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
p = root + r'\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

# 1) default OFF: v5.1 lost the per-phoneme loudness balance, and the user heard it as worse
rep('    public static boolean measuredNoise = true;',
    '    public static boolean measuredNoise = false;', 'default off')

# 2) level-match the shaped noise to the path it replaces, so the measured spectrum never changes
#    how loud a consonant is (that balance is a real cue: s/z`/ts` differ by up to 4 dB)
rep("            float y;\n            if (measuredNoise && ns != null && ns.isActive()) {\n                // measured fricative: full-band noise through its own measured envelope\n                y = ns.process(src.noiseOnly(0.55f)) * NS_GAIN;\n                if (hv != null) hv.sample();\n            } else if (hv != null && hw > 0) {\n                float yf = ff.filter(exc, noise);", "            float y;\n            if (measuredNoise && ns != null && ns.isActive()) {\n                // Measured fricative spectrum. The level follows the formant path it replaces:\n                // the measured curve is normalised to its own peak, and using it directly made\n                // every fricative equally loud (s, x, f, z`, ts` all at -4.2 dB), which threw away\n                // a real cue and was heard as a loss of clarity.\n                float ref = ff.filter(exc, noise);\n                float shaped = ns.process(src.noiseOnly(0.55f)) * NS_GAIN;\n                nsRmsRef += (ref * ref - nsRmsRef) * nsCoef;\n                nsRmsNew += (shaped * shaped - nsRmsNew) * nsCoef;\n                double match = Math.sqrt(Math.max(1e-9, nsRmsRef) / Math.max(1e-9, nsRmsNew));\n                match = clamp(match, 0.05, 20.0);\n                y = (float) (shaped * match);\n                if (hv != null) hv.sample();\n            } else if (hv != null && hw > 0) {\n                float yf = ff.filter(exc, noise);", 'level match')
rep('        NoiseShape ns = null;', "        NoiseShape ns = null;\n        double nsRmsRef = 0.0, nsRmsNew = 0.0;                       // running levels for the match\n        final double nsCoef = 1.0 - Math.exp(-1.0 / (0.030 * fs));   // 30 ms", 'rms state')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('renderer: level matched, feature off by default')