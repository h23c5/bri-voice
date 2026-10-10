import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/**
 * Mean distance of the stored vowel formants from the reference table, in log units.
 *
 * Used as the accuracy side of the assignment trade-off: separating two phonemes by pushing one onto a
 * distant cluster shows up here as a larger deviation. Singing raises formants above the spoken
 * reference, so the absolute number matters less than the comparison between packs.
 */
public class VowelFormantDeviation {

    static final Object[][] REF = {
        {"a", 850, 1200}, {"o", 500, 800}, {"7", 400, 1100}, {"i", 300, 2300},
        {"u", 350, 800}, {"y", 300, 1900}, {"i`", 400, 1500}, {"@`", 500, 1400},
        {"i\\", 350, 1600}, {"z`", 400, 1400}, {"n", 250, 1700}, {"m", 250, 1100},
    };

    public static void main(String[] args) throws Exception {
        for (File f : new File(args[0]).listFiles()) {
            if (!f.getName().endsWith(".v4voice")) continue;
            VoicePack.Result r = VoicePack.load(f);
            if (!r.ok()) continue;
            VoiceModel v = r.model;
            double sum = 0; int n = 0;
            StringBuilder sb = new StringBuilder();
            for (Object[] row : REF) {
                VoiceModel.HarmonicBand[] arr = v.harmonics.get((String) row[0]);
                if (arr == null || arr.length == 0) continue;
                double[] pk = peaks(v.harmonicFreqs, arr[arr.length - 1].db);
                if (pk[0] <= 0 || pk[1] <= 0) continue;
                double d = Math.abs(Math.log(pk[0] / (Integer) row[1])) + Math.abs(Math.log(pk[1] / (Integer) row[2]));
                sum += d; n++;
                sb.append(String.format("%s %.2f  ", row[0], d));
            }
            System.out.printf("%-28s vowels=%-2d mean deviation %.3f   [%s]%n",
                    f.getName(), n, n == 0 ? 0 : sum / n, sb.toString().trim());
        }
    }

    static double[] peaks(double[] freq, double[] db) {
        int n = Math.min(freq.length, db.length);
        double[] res = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0; int cnt = 0;
            for (int j = 0; j < n; j++) {
                double oct = Math.abs(Math.log(freq[j] / freq[i]) / Math.log(2));
                if (oct <= 0.5) { sum += db[j]; cnt++; }
            }
            res[i] = db[i] - sum / Math.max(1, cnt);
        }
        double f1 = 0, f2 = 0;
        for (int i = 1; i < n - 1 && freq[i] < 4000; i++) {
            if (freq[i] < 150) continue;
            if (res[i] >= res[i - 1] && res[i] >= res[i + 1] && res[i] > 1.0) {
                if (f1 == 0) f1 = freq[i];
                else if (f2 == 0 && freq[i] > f1 * 1.3) f2 = freq[i];
            }
        }
        return new double[]{f1, f2};
    }
}