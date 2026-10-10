import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * How far apart are the vowels a bank actually stores?
 *
 * The source tilt is removed first (the raw curve falls ~12 dB/oct from the fundamental, which is the
 * glottal source, not the formants). Pairs closer than 1.5 dB cannot be told apart by a listener, and no
 * synthesis tuning can separate them.
 */
public class VowelSeparationTest {

    public static void main(String[] args) throws Exception {
        for (File f : new File(args[0]).listFiles()) {
            if (!f.getName().endsWith(".v4voice")) continue;
            VoicePack.Result r = VoicePack.load(f);
            if (!r.ok()) continue;
            VoiceModel v = r.model;
            List<String> syms = new ArrayList<String>();
            List<double[]> curves = new ArrayList<double[]>();
            for (String s : new String[]{"a", "o", "7", "e", "i", "u", "v", "y", "@", "er", "i`", "i0"}) {
                VoiceModel.HarmonicBand[] arr = v.harmonics.get(s);
                if (arr == null || arr.length == 0) continue;
                syms.add(s);
                curves.add(detilt(v.harmonicFreqs, arr[arr.length - 1].db));
            }
            System.out.println("=== " + v.name + ": " + syms.size() + " vowels with measured data ===");
            double worst = 1e9; String worstPair = "";
            for (int i = 0; i < syms.size(); i++) {
                StringBuilder row = new StringBuilder(String.format("  %-4s", syms.get(i)));
                for (int j = 0; j < syms.size(); j++) {
                    if (j <= i) { row.append(String.format("%7s", "")); continue; }
                    double d = rms(curves.get(i), curves.get(j));
                    row.append(String.format("%7.1f", d));
                    if (d < worst) { worst = d; worstPair = syms.get(i) + "/" + syms.get(j); }
                }
                System.out.println(row);
            }
            System.out.printf("  closest pair: %s at %.2f dB %s%n", worstPair, worst,
                    worst < 1.5 ? "  <-- indistinguishable" : "(separable)");
        }
    }

    static double[] detilt(double[] freq, double[] db) {
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
        return res;
    }

    static double rms(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double s = 0;
        for (int i = 0; i < n; i++) { double d = a[i] - b[i]; s += d * d; }
        return Math.sqrt(s / n);
    }
}