import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;

import java.io.File;

/** Does the DYN parameter now change the TIMBRE (loudness variants measured from the voicebank)? */
public class DynTimbreTest {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "build/voicepacks");
        int fails = 0, tested = 0;
        for (File f : dir.listFiles()) {
            if (!f.getName().endsWith(".v4voice")) continue;
            VoicePack.Result r = VoicePack.load(f);
            if (!r.ok()) { System.out.println("load fail " + f.getName() + ": " + r.error); fails++; continue; }
            VoiceModel vm = r.model;
            if (vm.harmonics.size() == 0) { System.out.println(f.getName() + ": no harmonics"); continue; }
            tested++;

            // how many loudness variants does one phoneme carry?
            VoiceModel.HarmonicBand[] bs = vm.harmonics.get("a");
            int variants = bs == null ? 0 : bs.length;
            System.out.printf("== %s: %d curves for /a/%n", vm.name, variants);

            float[] soft = render(vm, 20);
            float[] mid = render(vm, 64);
            float[] loud = render(vm, 127);
            double dS = diff(soft, mid), dL = diff(mid, loud), dSL = diff(soft, loud);
            double hfS = hfBalance(soft), hfL = hfBalance(loud);
            System.out.printf("   rms soft/mid/loud = %.4f / %.4f / %.4f%n", rms(soft), rms(mid), rms(loud));
            System.out.printf("   spectral difference  soft-vs-mid=%.4f  mid-vs-loud=%.4f  soft-vs-loud=%.4f%n", dS, dL, dSL);
            System.out.printf("   HF balance (2-8k vs 200-800 Hz): soft %+.2f dB, loud %+.2f dB%n", hfS, hfL);
            boolean ok = dSL > 0.05;
            System.out.println(ok ? "   PASS  DYN changes the timbre" : "   FAIL  DYN does not change the timbre");
            if (!ok) fails++;
            boolean bri = hfL > hfS;
            System.out.println(bri ? "   PASS  louder variant is brighter (real voicebank behaviour)"
                                   : "   FAIL  louder variant is not brighter");
            if (!bri) fails++;
        }
        System.out.println(fails == 0 ? ("ALL PASS (" + tested + " packs)") : (fails + " FAILURES"));
        if (fails > 0) System.exit(1);
    }

    /** one sustained /a/ with a flat DYN curve at the given value */
    static float[] render(VoiceModel vm, int dyn) {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.dur = 1920 * 2;
        Note n = new Note();
        n.pos = 0; n.dur = 1920; n.key = 57; n.lyric = "a";
        p.notes.add(n);
        ControlCurve c = p.ctrl("DYN");
        c.def = dyn; c.min = 0; c.max = 127;
        c.putRaw(0, dyn);
        c.putRaw(1920, dyn);
        return Renderer.renderPart(s, p, vm, new Renderer.Cfg());
    }

    static double rms(float[] x) { double s = 0; for (float v : x) s += v * v; return Math.sqrt(s / Math.max(1, x.length)); }

    static double diff(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double d = 0, e = 0;
        for (int i = 0; i < n; i++) { d += (a[i] - b[i]) * (a[i] - b[i]); e += a[i] * a[i]; }
        return Math.sqrt(d / Math.max(1e-12, e));
    }

    /** coarse HF/LF energy ratio of the sustained part */
    static double hfBalance(float[] x) {
        int sr = 44100, N = 16384, a = (int) (0.7 * sr);
        double lo = 0, hi = 0;
        for (int k = 1; k < N / 2; k++) {
            double re = 0, im = 0;
            for (int i = 0; i < N; i++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
                double ph = 2 * Math.PI * k * i / N;
                re += x[a + i] * w * Math.cos(ph);
                im -= x[a + i] * w * Math.sin(ph);
            }
            double m = re * re + im * im;
            double f = k * (double) sr / N;
            if (f >= 200 && f < 800) lo += m;
            else if (f >= 2000 && f < 8000) hi += m;
        }
        return 10 * Math.log10((hi + 1e-12) / (lo + 1e-12));
    }
}
