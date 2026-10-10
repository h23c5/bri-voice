# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\render_test\FricativeSpectrumTest.java'
s = io.open(p, encoding='utf-8').read()
s = s.replace('                System.out.printf("  %-4s correlation with the bank\'s measured spectrum = %+.2f%n", cons[i], corr[i]);',
              '                System.out.printf("  %-4s centroid = %5.0f Hz   correlation = %+.2f%n", cons[i], centroid(spec[i]), corr[i]);')
if 'static double centroid' not in s:
    s = s.replace('    static double correlate(double[] a, double[] b) {', "    /** log-spectral centroid of the 12 band levels, in Hz */\n    static double centroid(double[] bands) {\n        double num = 0, den = 0;\n        for (int b = 0; b < bands.length; b++) {\n            double w = Math.pow(10, bands[b] / 20.0);\n            num += w * (200 * Math.pow(2, (b + 0.5) / 1.6));\n            den += w;\n        }\n        return den <= 0 ? 0 : num / den;\n    }\n"    static double correlate(double[] a, double[] b) {')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('centroid added')