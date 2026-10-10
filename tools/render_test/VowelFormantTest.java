import com.dsh.v4tune.model.*;
import com.dsh.v4tune.phoneme.PhonemeTable;
import com.dsh.v4tune.phoneme.PhonemeDef;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;
import java.util.List;

/**
 * Are the vowels the engine actually sings the vowels it intends?
 *
 * The envelope is read straight off the harmonics (they sit exactly on the measured curve the synth
 * used), so the peaks are the formants the ear gets. Each is compared with the phoneme table and with
 * the reference range for a female Mandarin voice.
 */
public class VowelFormantTest {
    static final int SR = 44100;
    static VoiceModel vm;

    /** reference F1/F2 for a female Mandarin voice, from the phonetics literature */
    static final double[][] REF = {
        // symbol, F1 low, F1 high, F2 low, F2 high
        {0, 850, 1050, 1250, 1550},   // a
        {1, 500, 700, 850, 1150},     // o
        {2, 500, 700, 1250, 1550},    // e (ɤ)
        {3, 260, 400, 2400, 2900},    // i
        {4, 300, 480, 750, 1000},     // u
        {5, 260, 400, 1800, 2300},    // ü (y)
        {6, 500, 700, 1350, 1750},    // er
    };
    static final String[] NAMES = {"a", "o", "7", "i", "u", "v", "@"};

    public static void main(String[] args) throws Exception {
        for (File f : new File(args[0]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) { VoicePack.Result r = VoicePack.load(f); if (r.ok()) vm = r.model; }
        }
        System.out.printf("%-4s %-12s %-12s %-12s %s%n", "", "measured F1/F2", "table F1/F2", "reference F1/F2", "verdict");
        for (int i = 0; i < NAMES.length; i++) {
            String sym = NAMES[i];
            PhonemeDef d = PhonemeTable.getOrDefault(sym);
            if (d.type != PhonemeDef.T_VOWEL) { System.out.println(sym + ": not in the table"); continue; }
            double[] fm = formants(sym);
            double[] ref = REF[i];
            String verdict = "ok";
            if (fm[0] < ref[1] * 0.8 || fm[0] > ref[2] * 1.2) verdict = "F1 off";
            else if (fm[1] < ref[3] * 0.8 || fm[1] > ref[4] * 1.2) verdict = "F2 off";
            System.out.printf("%-4s %4.0f / %-6.0f %4.0f / %-6.0f %4.0f-%-4.0f/%-4.0f-%-4.0f %s%n",
                    sym, fm[0], fm[1], d.f1, d.f2, ref[1], ref[2], ref[3], ref[4], verdict);
        }
    }

    /** F1 and F2 the engine renders for one vowel */
    static double[] formants(String sym) {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        Note n = new Note();
        n.pos = 480; n.dur = 1920; n.key = 60; n.vel = 64;
        n.lyric = sym;
        n.phoneme = sym;
        p.notes.add(n);
        p.dur = 3360;
        float[] x = Renderer.renderPart(s, p, vm, new Renderer.Cfg());
        int at = (int) (TimeUtil.tickToSec(s, 480 + 480) * SR);
        double f0 = 261.6;
        int K = 26;
        double[] db = new double[K + 1];
        double[] hz = new double[K + 1];
        for (int k = 1; k <= K; k++) {
            hz[k] = k * f0;
            db[k] = 20 * Math.log10(Math.max(1e-7, goertzel(x, at, hz[k])));
        }
        // light smoothing so a single weak harmonic does not create a false peak
        double[] sm = new double[K + 1];
        for (int k = 1; k <= K; k++) {
            double sum = 0; int cnt = 0;
            for (int j = Math.max(1, k - 1); j <= Math.min(K, k + 1); j++) { sum += db[j]; cnt++; }
            sm[k] = sum / cnt;
        }
        if (System.getenv("VOWEL_DEBUG") != null) {
            StringBuilder sb = new StringBuilder("DEBUG ENVELOPE " + sym + ": ");
            for (int k = 1; k <= K; k++) sb.append(String.format("%.0f:%.0f ", hz[k], db[k]));
            System.out.println(sb);
        }
        double[] peaks = new double[4];
        int found = 0;
        for (int k = 2; k < K && found < 3; k++) {
            if (sm[k] > sm[k - 1] && sm[k] >= sm[k + 1] && sm[k] > sm[1] && sm[k] > -60) {
                peaks[found++] = hz[k];
            }
        }
        // F1 = first peak below 1100 Hz, F2 = next one after it
        double f1 = 0, f2 = 0;
        for (int i = 0; i < found; i++) {
            if (f1 == 0 && peaks[i] < 1100) { f1 = peaks[i]; continue; }
            if (f1 != 0 && f2 == 0) { f2 = peaks[i]; }
        }
        if (f1 == 0 && found > 0) f1 = peaks[0];
        if (f2 == 0 && found > 1) f2 = peaks[1];
        return new double[]{f1, f2};
    }

    static double goertzel(float[] x, int at, double f) {
        double re = 0, im = 0;
        int n = Math.min(8192, x.length - at);
        if (n <= 0) return 0;
        for (int i = 0; i < n; i++) {
            double w = 2 * Math.PI * f * i / SR;
            re += x[at + i] * Math.cos(w);
            im -= x[at + i] * Math.sin(w);
        }
        return Math.sqrt(re * re + im * im) / n;
    }
}