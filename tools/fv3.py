# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\FrameVoice.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

rep('    private double[] envDb = new double[0];', "    private double[] envDb = new double[0];\n    /**\n     * The measured curves are relative dB with their own peak at 0, so their absolute values carry\n     * no loudness - without this the frame path came out 32 dB below the table path. Each frame is\n     * scaled so the harmonics it is built from have this RMS.\n     */\n    private static final double TARGET_RMS = 0.24;", 'target')
rep("        int maxHarm = (int) Math.min(half - 2, sampleRate * 0.45 / f0);\n        double phaseStep = 2.0 * Math.PI * hop / sampleRate;\n        for (int k = 1; k <= maxHarm; k++) {", "        int maxHarm = (int) Math.min(half - 2, sampleRate * 0.45 / f0);\n        double phaseStep = 2.0 * Math.PI * hop / sampleRate;\n        // power of the frame as measured, so it can be brought to a known level\n        double power = 0.0;\n        for (int k = 1; k <= maxHarm; k++) {\n            double hz = k * f0;\n            if (hz >= sampleRate * 0.45) break;\n            double amp = Math.pow(10.0, interp(bandsHz, envDb, hz) / 20.0);\n            power += amp * amp * 0.5;\n        }\n        double norm2 = power > 1e-12 ? TARGET_RMS / Math.sqrt(power) : 0.0;\n        for (int k = 1; k <= maxHarm; k++) {", 'frame power')
rep('            double amp = Math.pow(10.0, db / 20.0);',
    '            double amp = Math.pow(10.0, db / 20.0) * norm2;', 'apply norm')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('normalisation added')