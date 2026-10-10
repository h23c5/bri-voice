import com.dsh.v4tune.phoneme.PhonemeTable;
import com.dsh.v4tune.phoneme.PhonemeDef;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/**
 * Audits the vowels stored in a pack: the formants the curve actually carries.
 *
 * Reads the measured curve of every simple vowel, finds its peaks and compares them with the
 * reference F1/F2 of a female Mandarin voice. This is what the ear gets, so a curve whose peaks sit
 * in the wrong place cannot be fixed by any amount of synthesis tuning.
 */
public class PackVowelAudit {

    static final String[] SYMS = {"a", "o", "7", "e", "i", "u", "v", "y", "@", "er", "i`", "i0", "r`"};
    /** symbol -> {F1 lo, F1 hi, F2 lo, F2 hi} for a female voice */
    static final Object[][] REF = {
        {"a", 750, 1100, 1150, 1600},
        {"o", 450, 750, 800, 1200},
        {"7", 450, 750, 1150, 1600},
        {"e", 450, 750, 1800, 2400},
        {"i", 250, 450, 2200, 3000},
        {"u", 300, 500, 700, 1050},
        {"v", 250, 450, 1700, 2400},
        {"y", 250, 450, 1700, 2400},
        {"@", 450, 750, 1250, 1800},
        {"er", 450, 750, 1250, 1800},
        {"i`", 300, 550, 1200, 1900},
        {"i0", 300, 550, 1200, 1900},
        {"r`", 300, 550, 1200, 1900},
    };

    public static void main(String[] args) throws Exception {
        for (File f : new File(args[0]).listFiles()) {
            if (!f.getName().endsWith(".v4voice")) continue;
            VoicePack.Result r = VoicePack.load(f);
            if (!r.ok()) { System.out.println(f.getName() + ": load failed"); continue; }
            VoiceModel v = r.model;
            System.out.println("=== " + v.name + " (" + f.getName() + ") ===");
            System.out.printf("%-5s %-9s %-13s %-13s %s%n", "phon", "stored?", "F1 (peak)", "F2 (peak)", "verdict");
            int bad = 0, have = 0;
            for (String sym : SYMS) {
                VoiceModel.HarmonicBand[] arr = v.harmonics.get(sym);
                Object[] ref = ref(sym);
                if (arr == null || arr.length == 0) {
                    System.out.printf("%-5s %-9s %-13s %-13s %s%n", sym, "NO DATA", "-", "-", ref == null ? "" : "-");
                    continue;
                }
                have++;
                VoiceModel.HarmonicBand b = arr[arr.length - 1];
                double[] pk = peaks(v.harmonicFreqs, b.db);
                String verdict = "?";
                if (ref != null && pk[0] > 0) {
                    boolean f1ok = pk[0] >= (Integer) ref[1] * 0.8 && pk[0] <= (Integer) ref[2] * 1.2;
                    boolean f2ok = pk[1] > 0 && pk[1] >= (Integer) ref[3] * 0.8 && pk[1] <= (Integer) ref[4] * 1.2;
                    verdict = f1ok && f2ok ? "ok" : (f1ok ? "F2 wrong" : "F1 wrong");
                    if (!f1ok || !f2ok) bad++;
                }
                System.out.printf("%-5s %-9s %-13s %-13s %s%n", sym, "yes", fmt(pk[0]), fmt(pk[1]), verdict);
            }
            System.out.println("  -> " + have + " vowels with data, " + bad + " with misplaced formants");
        }
    }

    static Object[] ref(String sym) {
        for (Object[] row : REF) if (row[0].equals(sym)) return row;
        return null;
    }

    static String fmt(double hz) { return hz <= 0 ? "none" : String.format("%.0f Hz", hz); }

    /**
     * {F1, F2}: the first two formant peaks.
     *
     * The raw measured curve falls steadily from the fundamental (that is the glottal source), so
     * looking for maxima directly only ever finds the lowest bands - the first version of this audit
     * reported F1=66 Hz for every vowel. Formants are the ripple ON TOP of that slope, so the slope
     * is removed first with a one-octave moving average.
     */
    static double[] peaks(double[] freq, double[] db) {
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
    }
}