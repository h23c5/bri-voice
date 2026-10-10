import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.SingerLibrary;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;

import java.io.File;

/** End-to-end: does audio rendered with an imported pack carry the voicebank's measured spectrum,
 *  and is its level sane next to the built-in singers? */
public class HarmonicRenderCheck {

    static int fails = 0, checks = 0;

    static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) fails++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (detail == null ? "" : "   [" + detail + "]"));
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "build/voicepacks");
        Song base = Song.empty();
        Part bp = base.tracks.get(0).parts.get(0);
        bp.dur = 1920;
        Note bn = new Note(); bn.pos = 0; bn.dur = 960; bn.key = 60; bn.lyric = "a"; bp.notes.add(bn);
        float[] built = Renderer.renderPart(base, bp, SingerLibrary.luoTianyi(), new Renderer.Cfg());
        double builtRms = rms(built);
        System.out.printf("built-in rms=%.4f%n", builtRms);

        File[] packs = dir.listFiles();
        if (packs == null) { System.out.println("no pack dir " + dir); System.exit(1); }
        int n = 0;
        for (File f : packs) {
            if (!f.getName().endsWith(".v4voice")) continue;
            VoicePack.Result r = VoicePack.load(f);
            if (!r.ok()) { System.out.println("  load fail " + f.getName() + ": " + r.error); continue; }
            n++;
            VoiceModel vm = r.model;
            System.out.println("== " + vm.name + "  (phonemes=" + vm.harmonics.size() + ", grid=" + vm.harmonicFreqs.length + ")");
            if (vm.harmonicFreqs.length < 8 || vm.harmonics.size() == 0) { check("pack carries harmonics", false, null); continue; }

            Song s = Song.empty();
            Part p = s.tracks.get(0).parts.get(0);
            p.dur = 1920 * 2;
            Note nt = new Note();
            nt.pos = 0; nt.dur = 1920; nt.key = 57; nt.lyric = "a"; p.notes.add(nt);
            Renderer.Cfg cfg = new Renderer.Cfg();
            float[] pcm = Renderer.renderPart(s, p, vm, cfg);

            VoiceModel.HarmonicBand band = vm.bandFor("a", 220.0);
            check("measured band for /a/", band != null, band == null ? null : ("f0=" + band.f0));
            if (band == null) continue;

            int sr = cfg.sampleRate, N = 32768;
            int a = (int) (0.55 * sr);
            int used = 0;
            double[] out = new double[16], want = new double[16];
            double[] mag = new double[N / 2];
            for (int k = 1; k < N / 2; k++) {
                double re = 0, im = 0;
                for (int i = 0; i < N; i++) {
                    double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
                    double ph = 2 * Math.PI * k * i / N;
                    re += pcm[a + i] * w * Math.cos(ph);
                    im -= pcm[a + i] * w * Math.sin(ph);
                }
                mag[k] = Math.sqrt(re * re + im * im);
            }
            for (int k = 1; k <= 14; k++) {
                double fh = k * 220.0;
                int bin = (int) Math.round(fh * N / (double) sr);
                if (bin < 2 || bin >= N / 2 - 1) break;
                out[used] = 20 * Math.log10(Math.max(mag[bin - 1], Math.max(mag[bin], mag[bin + 1])) + 1e-12);
                want[used] = interp(vm.harmonicFreqs, band.db, fh);
                used++;
            }
            check("rendered harmonics follow the measured pack spectrum", corr(out, want, used) > 0.6,
                    String.format("correlation=%.3f over %d harmonics", corr(out, want, used), used));

            double rr = rms(pcm);
            double relDb = 20 * Math.log10(rr / Math.max(1e-9, builtRms));
            check("level within 6 dB of the built-in singer", Math.abs(relDb) < 6.0,
                    String.format("rms=%.4f (%+.1f dB vs built-in)", rr, relDb));
        }
        check("at least one pack tested", n > 0, n + " pack(s)");
        System.out.println(fails == 0 ? ("ALL PASS (" + checks + " checks, " + n + " packs)") : (fails + "/" + checks + " FAILED"));
        if (fails > 0) System.exit(1);
    }

    static double corr(double[] a, double[] b, int n) {
        double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; }
        ma /= n; mb /= n;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < n; i++) { num += (a[i] - ma) * (b[i] - mb); da += (a[i] - ma) * (a[i] - ma); db += (b[i] - mb) * (b[i] - mb); }
        return num / Math.sqrt(Math.max(1e-12, da * db));
    }

    static double rms(float[] x) { double s = 0; for (float v : x) s += v * v; return Math.sqrt(s / Math.max(1, x.length)); }

    static double interp(double[] f, double[] db, double x) {
        int n = Math.min(f.length, db.length);
        if (x <= f[0]) return db[0];
        if (x >= f[n - 1]) return db[n - 1];
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) { int m = (lo + hi) >>> 1; if (f[m] <= x) lo = m; else hi = m; }
        double t = Math.log(x / f[lo]) / Math.log(f[hi] / f[lo]);
        return db[lo] + (db[hi] - db[lo]) * t;
    }
}
