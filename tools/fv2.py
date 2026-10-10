# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

rep('    static final float NS_GAIN = 1.15f;', "    static final float NS_GAIN = 1.15f;\n    /**\n     * Frame-sequence synthesis (real measured frames concatenated by overlap-add) instead of the\n     * single-period table of {@link HarmonicVoice}. Off falls back to the older path, so the two can\n     * be compared on the device.\n     */\n    public static boolean frameSequence = true;\n    /** level of the frame path, matched to the harmonic table path it replaces */\n    static final double FRAME_GAIN = 0.55;", 'switch')
rep('        NoiseShape ns = null;', '        NoiseShape ns = null;' + chr(10) + '        FrameVoice fv = null;', 'field')
rep('            ns = new NoiseShape();', "            ns = new NoiseShape();\n            fv = new FrameVoice();\n            fv.setSampleRate(fs);", 'init')
rep('                        seqGain = Math.pow(10.0, lvlDb / 20.0);', "                        seqGain = Math.pow(10.0, lvlDb / 20.0);\n                        if (fv != null) {\n                            fv.setFrame(f0, vm.harmonicFreqs, envMix, voicedCur, seqGain * FRAME_GAIN);\n                        }", 'frame feed')
rep("            float y;\n            if (measuredNoise && ns != null && ns.isActive()) {", "            float y;\n            if (frameSequence && fv != null && fv.isActive()) {\n                // one real measured frame per hop, concatenated by overlap-add\n                y = fv.sample();\n                if (hv != null) hv.sample();   // keep the phase running for the fallback path\n            } else if (measuredNoise && ns != null && ns.isActive()) {", 'frame mix')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('renderer: frame-sequence path wired')