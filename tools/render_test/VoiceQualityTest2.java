import com.dsh.v4tune.model.*;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/** measures the puff in front of a note, the hiss on a vowel and how distinct syllables are. */
public class VoiceQualityTest2 {
    static final int SR = 44100;
    static VoiceModel vm;

    public static void main(String[] a) throws Exception {
        for (File f : new File(a[0]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) { VoicePack.Result r = VoicePack.load(f); if (r.ok()) vm = r.model; }
        }
        // 1) how loud is the air in front of a note?
        Song s1 = phrase(new String[]{"zhe"}, new long[]{960});
        float[] x = render(s1);
        int onset = (int) (TimeUtil.tickToSec(s1, 480) * SR);
        double before = rms(x, onset - (int) (0.12 * SR), onset - (int) (0.02 * SR));
        double steady = rms(x, onset + (int) (0.20 * SR), onset + (int) (0.40 * SR));
        System.out.printf("puff before the note: %.1f dB below the vowel%n", 20 * Math.log10(Math.max(1e-9, before) / Math.max(1e-9, steady)));

        // 2) hiss on the steady vowel: 2-6 kHz against 0.2-0.8 kHz
        Song s2 = phrase(new String[]{"a"}, new long[]{960});
        float[] y = render(s2);
        int o2 = (int) (TimeUtil.tickToSec(s2, 480) * SR);
        double hf = band(y, o2 + 8820, 2.0 * SR / 44100 * 0, 0);
        double hi = bandEnergy(y, o2 + 8820, 4000, 8000);
        double lo = bandEnergy(y, o2 + 8820, 200, 800);
        System.out.printf("vowel hiss: 4-8 kHz / 200-800 Hz = %.4f (lower is cleaner)%n", hi / Math.max(1e-9, lo));

        // 3) articulation: how far apart are different syllables?
        String[] sy = {"zhe", "shi", "hui", "lai", "ba"};
        double[][] spec = new double[sy.length][];
        for (int i = 0; i < sy.length; i++) {
            Song s = phrase(new String[]{sy[i]}, new long[]{960});
            float[] z = render(s);
            int o = (int) (TimeUtil.tickToSec(s, 480) * SR);
            spec[i] = spectrum(z, o + 2000, 8192);
        }
        double sum = 0; int n = 0;
        for (int i = 0; i < sy.length; i++) {
            for (int j = i + 1; j < sy.length; j++) { sum += distance(spec[i], spec[j]); n++; }
        }
        System.out.printf("syllable distinctness: mean spectral distance = %.3f (higher = clearer)%n", sum / n);
    }

    static Song phrase(String[] ly, long[] durs) {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        long at = 480;
        for (int i = 0; i < ly.length; i++) {
            Note n = new Note();
            n.pos = at; n.dur = durs[i]; n.key = 60; n.vel = 64; n.lyric = ly[i];
            p.notes.add(n);
            at += durs[i] + 240;
        }
        p.dur = at + 960;
        return s;
    }

    static float[] render(Song s) {
        return Renderer.renderPart(s, s.tracks.get(0).parts.get(0), vm, new Renderer.Cfg());
    }

    static double rms(float[] x, int a, int b) {
        if (a < 0) a = 0; if (b > x.length) b = x.length;
        double e = 0;
        for (int i = a; i < b; i++) e += x[i] * x[i];
        return Math.sqrt(e / Math.max(1, b - a));
    }

    /** Goertzel over a range, summed */
    static double bandEnergy(float[] x, int at, double f0, double f1) {
        double e = 0;
        for (double f = f0; f <= f1; f += 50) {
            double re = 0, im = 0;
            int n = Math.min(8192, x.length - at);
            for (int i = 0; i < n; i++) {
                double w = 2 * Math.PI * f * i / SR;
                re += x[at + i] * Math.cos(w);
                im -= x[at + i] * Math.sin(w);
            }
            e += Math.sqrt(re * re + im * im) / n;
        }
        return e;
    }

    static double band(float[] x, int at, double a, double b) { return bandEnergy(x, at, 200, 800); }

    /** coarse log spectrum for comparing syllables */
    static double[] spectrum(float[] x, int at, int n) {
        double[] out = new double[40];
        for (int b = 0; b < 40; b++) {
            double f0 = 100 * Math.pow(2, b / 8.0);
            double f1 = 100 * Math.pow(2, (b + 1) / 8.0);
            out[b] = Math.log(1e-6 + bandEnergy(x, at, f0, f1));
        }
        return out;
    }

    static double distance(double[] a, double[] b) {
        double d = 0;
        for (int i = 0; i < a.length; i++) d += Math.abs(a[i] - b[i]);
        return d / a.length;
    }
}