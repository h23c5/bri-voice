import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.SingerLibrary;

/** Isolated pitch accuracy + vowel formant check on sustained notes (no consonants, no portamento). */
public class FlatPitchTest {

    public static void main(String[] args) throws Exception {
        int fails = 0, n = 0;
        int[] keys = {45, 52, 57, 60, 64, 67, 72, 76};
        String[] vowels = {"a", "i", "u", "o", "e"};
        Renderer.Cfg cfg = new Renderer.Cfg();
        for (int k : keys) {
            Song s = Song.empty();
            Part p = s.tracks.get(0).parts.get(0);
            p.dur = 1920 * 2;
            Note nt = new Note();
            nt.pos = 0; nt.dur = 1920; nt.key = k; nt.lyric = "a";
            p.notes.add(nt);
            float[] pcm = Renderer.renderPart(s, p, SingerLibrary.luoTianyi(), cfg);
            double want = 440.0 * Math.pow(2, (k - 69) / 12.0);
            double got = f0ByDft(pcm, cfg.sampleRate, 0.55, want);
            double err = Math.abs(got - want) / want;
            n++;
            if (err > 0.01) fails++;
            System.out.println(String.format("key=%2d want=%7.2f Hz got=%7.2f Hz err=%5.2f%%  %s",
                    k, want, got, err * 100, err > 0.01 ? "FAIL" : "ok"));
        }
        System.out.println("-- vowel spectral balance (should differ per vowel) --");
        for (String v : vowels) {
            Song s = Song.empty();
            Part p = s.tracks.get(0).parts.get(0);
            p.dur = 1920 * 2;
            Note nt = new Note();
            nt.pos = 0; nt.dur = 1920; nt.key = 60; nt.lyric = v;
            p.notes.add(nt);
            float[] pcm = Renderer.renderPart(s, p, SingerLibrary.luoTianyi(), cfg);
            double[] band = bands(pcm, cfg.sampleRate, 0.5);
            System.out.println(String.format("  /%s/  F1band=%6.1f  F2band=%6.1f  F3band=%6.1f  centroid=%6.0f Hz",
                    v, band[0], band[1], band[2], band[3]));
        }
        System.out.println(fails == 0 ? ("PITCH OK (" + n + " notes)") : (fails + "/" + n + " PITCH FAILURES"));
        if (fails > 0) System.exit(1);
    }

    /** precise F0: DFT magnitude peak near the expected frequency with parabolic interpolation (0.74 s window) */
    static double f0ByDft(float[] x, int sr, double atSec, double expected) {
        return f0ByDft(x, sr, atSec, expected, 32768);
    }

    static double f0ByDft(float[] x, int sr, double atSec, double expected, int N) {
        int a = (int) (atSec * sr);
        if (a + N > x.length) a = Math.max(0, x.length - N);
        int lo = (int) (expected * 0.75 * N / sr), hi = (int) (expected * 1.3 * N / sr);
        double[] mag = new double[hi + 2];
        for (int k = Math.max(1, lo - 2); k <= hi + 1 && k < N / 2; k++) {
            double re = 0, im = 0;
            for (int i = 0; i < N; i++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
                double ph = 2 * Math.PI * k * i / N;
                re += x[a + i] * w * Math.cos(ph);
                im -= x[a + i] * w * Math.sin(ph);
            }
            mag[k] = Math.sqrt(re * re + im * im);
        }
        double best = -1; int bk = lo;
        for (int k = Math.max(1, lo); k <= hi && k < N / 2; k++) if (mag[k] > best) { best = mag[k]; bk = k; }
        double m0 = mag[Math.max(1, bk - 1)], m1 = mag[bk], m2 = mag[Math.min(hi + 1, bk + 1)];
        double den = (m0 - 2 * m1 + m2);
        double shift = Math.abs(den) > 1e-9 ? 0.5 * (m0 - m2) / den : 0;
        if (shift > 1) shift = 1; if (shift < -1) shift = -1;
        return (bk + shift) * (double) sr / N;
    }

    /** crude band energy: argmax of a DFT magnitude in three formant regions + spectral centroid */
    static double[] bands(float[] x, int sr, double atSec) {
        int a = (int) (atSec * sr);
        int N = 4096;
        if (a + N > x.length) a = Math.max(0, x.length - N);
        double[] mag = new double[N / 2];
        for (int k = 1; k < N / 2; k++) {
            double re = 0, im = 0;
            for (int i = 0; i < N; i++) {
                double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (N - 1));
                double ph = 2 * Math.PI * k * i / N;
                re += x[a + i] * w * Math.cos(ph);
                im -= x[a + i] * w * Math.sin(ph);
            }
            mag[k] = Math.sqrt(re * re + im * im);
        }
        double f1 = peak(mag, sr, N, 250, 1100);
        double f2 = peak(mag, sr, N, 900, 2600);
        double f3 = peak(mag, sr, N, 2400, 3600);
        double num = 0, den = 0;
        for (int k = 1; k < N / 2; k++) { num += k * (double) sr / N * mag[k]; den += mag[k]; }
        return new double[]{f1, f2, f3, den > 0 ? num / den : 0};
    }

    static double peak(double[] mag, int sr, int N, double lo, double hi) {
        int k0 = Math.max(1, (int) (lo * N / sr)), k1 = Math.min(mag.length - 1, (int) (hi * N / sr));
        double best = -1; int bk = k0;
        for (int k = k0; k <= k1; k++) if (mag[k] > best) { best = mag[k]; bk = k; }
        return bk * (double) sr / N;
    }
}
