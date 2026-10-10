import com.dsh.v4tune.model.*;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/** level, spectral accuracy and - the point of the whole change - real frame evolution. */
public class FrameSequenceTest {
    static final int SR = 44100;
    static VoiceModel vm;

    public static void main(String[] a) throws Exception {
        for (File f : new File(a[0]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) { VoicePack.Result r = VoicePack.load(f); if (r.ok()) vm = r.model; }
        }
        Song s = note("a", 60, 2400);
        Part p = s.tracks.get(0).parts.get(0);

        Renderer.frameSequence = true;
        float[] frame = Renderer.renderPart(s, p, vm, new Renderer.Cfg());
        Renderer.frameSequence = false;
        float[] table = Renderer.renderPart(s, p, vm, new Renderer.Cfg());

        int at = (int) (TimeUtil.tickToSec(s, 480) * SR);
        double rmsFrame = rms(frame, at + 4410, at + 44100);
        double rmsTable = rms(table, at + 4410, at + 44100);
        System.out.printf("level (vowel RMS): frame path %.4f, table path %.4f  -> %+.1f dB%n",
                rmsFrame, rmsTable, 20 * Math.log10(rmsFrame / rmsTable));
        System.out.printf("spectral accuracy vs the measured envelope: frame %.3f, table %.3f%n",
                accuracy(frame, at), accuracy(table, at));

        VoiceModel.HarmonicBand band = vm.bandFor("a", 261.6);
        double stored = band == null ? 0 : storedDelta(band);
        System.out.printf("inter-harmonic energy (lower = cleaner): frame %.1f dB, table %.1f dB%n",
                inharmonic(frame, at + 8820, 261.6), inharmonic(table, at + 8820, 261.6));
        System.out.printf("frame-to-frame spectral change (dB per hop): stored frames %.3f, rendered frame path %.3f, rendered table path %.3f%n",
                stored, renderedDelta(frame, at), renderedDelta(table, at));
    }

    static Song note(String ly, int key, long dur) {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        Note n = new Note();
        n.pos = 480; n.dur = dur; n.key = key; n.vel = 64; n.lyric = ly;
        p.notes.add(n);
        p.dur = dur + 960;
        return s;
    }

    /** correlation between the rendered harmonic levels and the bank's measured curve */
    static double accuracy(float[] x, int at) {
        VoiceModel.HarmonicBand band = vm.bandFor("a", 261.6);
        if (band == null) return 0;
        double f0 = 261.6;
        double[] rendered = new double[24], measured = new double[24];
        for (int k = 1; k <= 24; k++) {
            double hz = k * f0;
            if (hz > 8000) { rendered[k - 1] = -90; measured[k - 1] = -90; continue; }
            rendered[k - 1] = 20 * Math.log10(Math.max(1e-7, goertzel(x, at + 2000, hz)));
            measured[k - 1] = interp(vm.harmonicFreqs, band.db, hz);
        }
        return correlate(rendered, measured);
    }

    /** mean absolute change between consecutive frames, in dB, on 12 log bands */
    static double renderedDelta(float[] x, int at) {
        double sum = 0; int n = 0;
        int hop = 256;                                  // the synthesis hop
        double[] prev = null;
        for (int i = at + 4410; i + 4096 < at + 35000; i += hop) {
            double[] cur = bands(x, i);
            if (prev != null) {
                for (int b = 0; b < cur.length; b++) sum += Math.abs(cur[b] - prev[b]);
                n += cur.length;
            }
            prev = cur;
        }
        return n == 0 ? 0 : sum / n;
    }

    static double storedDelta(VoiceModel.HarmonicBand band) {
        if (band.seq == null || band.seq.length < 2) return 0;
        double sum = 0; int n = 0;
        for (int i = 1; i < band.seq.length; i++) {
            double[] a = band.seq[i - 1], b = band.seq[i];
            int len = Math.min(a.length, b.length);
            for (int k = 0; k < len; k += 5) { sum += Math.abs(b[k] - a[k]); n++; }
        }
        return n == 0 ? 0 : sum / n;
    }


    /** energy between the harmonics vs at them: phase splatter shows up here */
    static double inharmonic(float[] x, int at, double f0) {
        double on = 0, off = 0;
        for (int k = 1; k <= 30; k++) {
            if (k * f0 > 7000) break;
            on += goertzel(x, at, k * f0) + goertzel(x, at, k * f0 + 8);
            off += goertzel(x, at, (k + 0.5) * f0);
        }
        return 20 * Math.log10(Math.max(1e-12, off) / Math.max(1e-12, on));
    }

    static double[] bands(float[] x, int at) {
        double[] out = new double[12];
        for (int b = 0; b < 12; b++) {
            double f0 = 200 * Math.pow(2, b / 1.6);
            double f1 = 200 * Math.pow(2, (b + 1) / 1.6);
            double e = 0;
            for (double f = f0; f <= f1; f *= 1.08) e += goertzel(x, at, f);
            out[b] = 20 * Math.log10(Math.max(1e-7, e));
        }
        return out;
    }

    static double goertzel(float[] x, int at, double f) {
        double re = 0, im = 0;
        int n = Math.min(4096, x.length - at);
        if (n <= 0) return 0;
        for (int i = 0; i < n; i++) {
            double w = 2 * Math.PI * f * i / SR;
            re += x[at + i] * Math.cos(w);
            im -= x[at + i] * Math.sin(w);
        }
        return Math.sqrt(re * re + im * im) / n;
    }

    static double interp(double[] f, double[] v, double x) {
        int n = Math.min(f.length, v.length);
        if (x <= f[0]) return v[0];
        if (x >= f[n - 1]) return v[n - 1];
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) { int m = (lo + hi) >>> 1; if (f[m] <= x) lo = m; else hi = m; }
        double t = Math.log(x / f[lo]) / Math.log(f[hi] / f[lo]);
        return v[lo] + (v[hi] - v[lo]) * t;
    }

    static double correlate(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < a.length; i++) { ma += a[i]; mb += b[i]; }
        ma /= a.length; mb /= b.length;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < a.length; i++) { double x = a[i] - ma, y = b[i] - mb; num += x * y; da += x * x; db += y * y; }
        return num / Math.sqrt(Math.max(1e-12, da * db));
    }

    static double rms(float[] x, int a, int b) {
        if (b > x.length) b = x.length;
        double e = 0;
        for (int i = a; i < b; i++) e += x[i] * x[i];
        return Math.sqrt(e / Math.max(1, b - a));
    }
}