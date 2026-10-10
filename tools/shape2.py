# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

rep('        HarmonicVoice hv = null;', "        HarmonicVoice hv = null;\n        // measured spectra for unvoiced consonants: the pack ships the real band levels of every\n        // fricative it measured, and they used to be thrown away (the harmonic path only runs for\n        // voiced sounds), so /s/ and /x/ both came out as the same generic noise\n        NoiseShape ns = null;", 'shaper field')
rep('            hv = new HarmonicVoice();', "            hv = new HarmonicVoice();\n            ns = new NoiseShape();\n            ns.setSampleRate(fs);", 'shaper init')
rep('                        seqGain = Math.pow(10.0, lvlDb / 20.0);', "                        seqGain = Math.pow(10.0, lvlDb / 20.0);\n                        // an unvoiced consonant the bank measured: shape the noise with that spectrum\n                        if (ns != null) {\n                            if (!voicedCur && b1 != null) ns.set(vm.harmonicFreqs, envMix, 30.0);\n                            else ns.reset();\n                        }", 'shaper update')
rep("            float y;\n            if (hv != null && hw > 0) {\n                float yf = ff.filter(exc, noise);", "            float y;\n            if (ns != null && ns.isActive()) {\n                // measured fricative: full-band noise through its own measured envelope\n                y = ns.process(src.noiseOnly(0.55f)) * NS_GAIN;\n                if (hv != null) hv.sample();\n            } else if (hv != null && hw > 0) {\n                float yf = ff.filter(exc, noise);", 'shaper mix')
rep('    static final double HARMONIC_GAIN = 0.20;', "    static final double HARMONIC_GAIN = 0.20;\n    /** level of the measured-noise path, matched to the formant path it replaces */\n    static final float NS_GAIN = 1.15f;", 'gain constant')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('written')