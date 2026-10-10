# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\render_test\PackVowelAudit.java'
s = io.open(p, encoding='utf-8').read()
old = '''    /** {F1, F2}: the first two prominent peaks of the curve */'''
new = '''    /**
     * {F1, F2}: the first two formant peaks.
     *
     * The raw measured curve falls steadily from the fundamental (that is the glottal source), so
     * looking for maxima directly only ever finds the lowest bands - the first version of this audit
     * reported F1=66 Hz for every vowel. Formants are the ripple ON TOP of that slope, so the slope
     * is removed first with a one-octave moving average.
     */'''
if old not in s: raise SystemExit('anchor missing')
s = s.replace(old, new, 1)
old2 = '''    static double[] peaks(double[] freq, double[] db) {
        int n = Math.min(freq.length, db.length);
        double out1 = 0, out2 = 0;
        for (int i = 1; i < n - 1 && freq[i] < 4000; i++) {
            if (db[i] >= db[i - 1] && db[i] >= db[i + 1] && db[i] > -25) {
                if (out1 == 0) out1 = freq[i];
                else if (out2 == 0 && freq[i] > out1 * 1.3) out2 = freq[i];
            }
        }
        return new double[]{out1, out2};
    }'''
new2 = '''    static double[] peaks(double[] freq, double[] db) {
        int n = Math.min(freq.length, db.length);
        double[] res = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int cnt = 0;
            for (int j = 0; j < n; j++) {
                double oct = Math.abs(Math.log(freq[j] / freq[i]) / Math.log(2));
                if (oct <= 0.5) { sum += db[j]; cnt++; }
            }
            res[i] = db[i] - sum / Math.max(1, cnt);
        }
        double out1 = 0, out2 = 0;
        for (int i = 1; i < n - 1 && freq[i] < 4000; i++) {
            if (freq[i] < 150) continue;
            if (res[i] >= res[i - 1] && res[i] >= res[i + 1] && res[i] > 1.0) {
                if (out1 == 0) out1 = freq[i];
                else if (out2 == 0 && freq[i] > out1 * 1.3) out2 = freq[i];
            }
        }
        return new double[]{out1, out2};
    }'''
if old2 not in s: raise SystemExit('peaks anchor missing')
s = s.replace(old2, new2, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('audit now removes the source tilt')