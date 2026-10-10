# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\render_test\PackVowelAudit.java'
s = io.open(p, encoding='utf-8').read()
if 'pairwise' not in s:
    s = s.replace('    static Object[] ref(String sym) {', "    /** how far apart are the vowels of this bank, after the source tilt is removed? */\n    static void pairwise(VoiceModel v, java.util.List<String> syms, java.util.List<double[]> curves) {\n        System.out.println(\"  vowel separation (RMS dB after tilt removal; < 1.5 dB = hard to tell apart):\");\n        for (int i = 0; i < syms.size(); i++) {\n            for (int j = i + 1; j < syms.size(); j++) {\n                double[] a = curves.get(i), b = curves.get(j);\n                int n = Math.min(a.length, b.length);\n                double sum = 0;\n                for (int k = 0; k < n; k++) { double d = a[k] - b[k]; sum += d * d; }\n                double rms = Math.sqrt(sum / n);\n                if (rms < 3.0) System.out.printf(\"    %-4s vs %-4s : %.2f dB%s%n\", syms.get(i), syms.get(j), rms,\n                        rms < 1.5 ? \"   <-- too close\" : \"\");\n            }\n        }\n    }\n\n    /** the curve with its one-octave baseline removed */\n    static double[] detilted(double[] freq, double[] db) {\n        int n = Math.min(freq.length, db.length);\n        double[] res = new double[n];\n        for (int i = 0; i < n; i++) {\n            double sum = 0; int cnt = 0;\n            for (int j = 0; j < n; j++) {\n                double oct = Math.abs(Math.log(freq[j] / freq[i]) / Math.log(2));\n                if (oct <= 0.5) { sum += db[j]; cnt++; }\n            }\n            res[i] = db[i] - sum / Math.max(1, cnt);\n        }\n        return res;\n    }\n"    static Object[] ref(String sym) {', 1)
    s = s.replace('            System.out.println("  -> " + have + " vowels with data, " + bad + " with misplaced formants");',
                  '            System.out.println("  -> " + have + " vowels with data, " + bad + " outside the spoken reference ranges");' + chr(10) +
                  '            pairwise(v, withData, curveList);', 1)
    s = s.replace('            int bad = 0, have = 0;',
                  '            int bad = 0, have = 0;' + chr(10) +
                  '            java.util.List<String> withData = new java.util.ArrayList<String>();' + chr(10) +
                  '            java.util.List<double[]> curveList = new java.util.ArrayList<double[]>();', 1)
    s = s.replace('                VoiceModel.HarmonicBand b = arr[arr.length - 1];',
                  '                VoiceModel.HarmonicBand b = arr[arr.length - 1];' + chr(10) +
                  '                withData.add(sym);' + chr(10) +
                  '                curveList.add(detilted(v.harmonicFreqs, b.db));', 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('pairwise check added')