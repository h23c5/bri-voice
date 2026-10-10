import com.dsh.v4tune.model.*;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/** A/B: do rendered fricatives now carry the voicebank's own measured spectrum? */
public class FricativeSpectrumTest {
    static final int SR = 44100;
    static VoiceModel vm;

    public static void main(String[] a) throws Exception {
        for (File f : new File(a[0]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) { VoicePack.Result r = VoicePack.load(f); if (r.ok()) vm = r.model; }
        }
        String[] cons = {"s", "x", "f", "z`", "ts`"};
        for (boolean measured : new boolean[]{false, true}) {
            Renderer.measuredNoise = measured;
            System.out.println(measured ? "-- with measured consonant spectra --" : "-- generic formant noise (before) --");
            double[] corr = new double[cons.length];
            double[][] spec = new double[cons.length][];
            for (int i = 0; i < cons.length; i++) {
                float[] x = renderConsonant(cons[i]);
                int at = (int) (TimeUtil.tickToSec(songFor(cons[i]), 480) * SR) + 1200;   // inside the consonant
                spec[i] = bands(x, at);
                corr[i] = correlate(spec[i], measuredEnvelope(cons[i]));
                System.out.printf("  %-4s correlation with the bank's measured spectrum = %+.2f%n", cons[i], corr[i]);
            }
            double d = 0; int n = 0;
            for (int i = 0; i < cons.length; i++)
                for (int j = i + 1; j < cons.length; j++) { d += distance(spec[i], spec[j]); n++; }
            System.out.printf("  mean distance between different fricatives = %.3f (higher = easier to tell apart)%n", d / n);
        }
    }

    static Song songFor(String c) {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        Note n = new Note();
        n.pos = 480; n.dur = 960; n.key = 60; n.vel = 64;
        n.lyric = c;
        n.phoneme = c + " a";
        p.notes.add(n);
        p.dur = 2880;
        return s;
    }

    static float[] renderConsonant(String c) {
        Song s = songFor(c);
        return Renderer.renderPart(s, s.tracks.get(0).parts.get(0), vm, new Renderer.Cfg());
    }

    /** 12 log-spaced band levels of the rendered consonant */
    static double[] bands(float[] x, int at) {
        double[] out = new double[12];
        for (int b = 0; b < 12; b++) {
            double f0 = 200 * Math.pow(2, b / 1.6);
            double f1 = 200 * Math.pow(2, (b + 1) / 1.6);
            out[b] = Math.log(1e-7 + energy(x, at, f0, f1));
        }
        return out;
    }

    /** the bank's own envelope for a phoneme, resampled to the same 12 bands */
    static double[] measuredEnvelope(String c) {
        VoiceModel.HarmonicBand[] arr = vm.harmonics.get(c);
        VoiceModel.HarmonicBand band = (arr == null || arr.length == 0) ? null : arr[Math.min(arr.length / 2, arr.length - 1)];
        double[] out = new double[12];
        if (band == null) return out;
        double[] f = vm.harmonicFreqs, v = band.db;
        for (int b = 0; b < 12; b++) {
            double f0 = 200 * Math.pow(2, b / 1.6);
            out[b] = interp(f, v, f0);
        }
        return out;
    }

    static double interp(double[] f, double[] v, double x) {
        int n = Math.min(f.length, v.length);
        if (x <= f[0]) return v[0];
        if (x >= f[n - 1]) return v[n - 1];
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) { int m = (lo + hi) >>> 1; if (f[m] <= x) lo = m; else hi = m; }
        double t = (x - f[lo]) / (f[hi] - f[lo]);
        return v[lo] + (v[hi] - v[lo]) * t;
    }

    static double energy(float[] x, int at, double f0, double f1) {
        double e = 0;
        for (double f = f0; f <= f1; f *= 1.06) {
            double re = 0, im = 0;
            int n = Math.min(4096, x.length - at);
            for (int i = 0; i < n; i++) {
                double w = 2 * Math.PI * f * i / SR;
                re += x[at + i] * Math.cos(w);
                im -= x[at + i] * Math.sin(w);
            }
            e += Math.sqrt(re * re + im * im) / n;
        }
        return e;
    }

    static double correlate(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < a.length; i++) { ma += a[i]; mb += b[i]; }
        ma /= a.length; mb /= b.length;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i] - ma, y = b[i] - mb;
            num += x * y; da += x * x; db += y * y;
        }
        return num / Math.sqrt(Math.max(1e-12, da * db));
    }

    static double distance(double[] a, double[] b) {
        double d = 0;
        for (int i = 0; i < a.length; i++) d += Math.abs(a[i] - b[i]);
        return d / a.length;
    }
}