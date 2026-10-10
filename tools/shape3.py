# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
if 'measuredNoise' in s:
    print('already present')
else:
    s = s.replace('    static final float NS_GAIN = 1.15f;', "    static final float NS_GAIN = 1.15f;\n    /**\n     * Diagnostic switch: when false, unvoiced consonants fall back to the generic formant-filtered\n     * noise they used before the measured spectra were applied. Used by the A/B measurements in\n     * tools/render_test and handy when tuning.\n     */\n    public static boolean measuredNoise = true;", 1)
    s = s.replace('            if (ns != null && ns.isActive()) {',
                  '            if (measuredNoise && ns != null && ns.isActive()) {', 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('diagnostic switch added')