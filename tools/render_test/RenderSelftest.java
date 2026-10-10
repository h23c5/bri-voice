import com.dsh.v4tune.audio.Demo;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.TimeUtil;
import com.dsh.v4tune.phoneme.ChineseG2P;
import com.dsh.v4tune.phoneme.PhonemeTable;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.SingerLibrary;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.WavWriter;

import java.io.File;

/** Objective render checks: levels, NaNs, note timing, pitch accuracy, phoneme resolution. */
public class RenderSelftest {

    static int fails = 0, checks = 0;

    static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) fails++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (detail == null ? "" : "   [" + detail + "]"));
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "build/renderout");
        out.mkdirs();
        if (args.length > 1) {
            try {
                ChineseG2P.init(new java.io.FileInputStream(args[1]));
                System.out.println("pinyin dict loaded: " + ChineseG2P.isReady());
            } catch (Throwable t) {
                System.out.println("pinyin dict NOT loaded: " + t);
            }
        }

        Song song = Demo.twinkle();
        Part part = song.tracks.get(0).parts.get(0);
        VoiceModel vm = SingerLibrary.luoTianyi();
        Renderer.Cfg cfg = new Renderer.Cfg();
        cfg.sampleRate = 44100;

        System.out.println("== phoneme resolution ==");
        String[] lyr = {"luo", "tian", "yi", "xiao", "xing", "shan", "jing"};
        for (String l : lyr) {
            Note n = new Note();
            n.lyric = l;
            java.util.List<String> ph = Renderer.resolvePhonemes(n, vm);
            System.out.println("   " + l + " -> " + ph);
            check("phonemes for " + l, ph.size() >= 1, ph.toString());
        }

        System.out.println("== render ==");
        long t0 = System.currentTimeMillis();
        float[] pcm = Renderer.renderPart(song, part, vm, cfg);
        long ms = System.currentTimeMillis() - t0;
        double dur = pcm.length / (double) cfg.sampleRate;
        System.out.println("   rendered " + pcm.length + " samples (" + String.format("%.2f", dur) + " s) in " + ms + " ms  (" +
                String.format("%.0f", pcm.length / 44100.0 / (ms / 1000.0)) + "x realtime)");

        boolean nan = false;
        double peak = 0, sum = 0;
        for (float v : pcm) {
            if (Float.isNaN(v) || Float.isInfinite(v)) nan = true;
            double a = Math.abs(v);
            if (a > peak) peak = a;
            sum += a * a;
        }
        double rms = Math.sqrt(sum / Math.max(1, pcm.length));
        check("no NaN/Inf", !nan, null);
        check("peak in (0.05, 1.0)", peak > 0.05 && peak <= 1.0, "peak=" + String.format("%.3f", peak));
        check("rms in (0.01, 0.5)", rms > 0.01 && rms < 0.5, "rms=" + String.format("%.4f", rms));

        // note timing: each note should be audible around its expected start
        int misses = 0;
        for (int i = 0; i < part.notes.size(); i++) {
            Note n = part.notes.get(i);
            double startSec = TimeUtil.tickToSec(song, n.pos);
            double endSec = TimeUtil.tickToSec(song, n.pos + n.dur);
            int a = (int) ((startSec + 0.05) * cfg.sampleRate);
            int b = (int) ((endSec - 0.02) * cfg.sampleRate);
            if (b <= a || b > pcm.length) continue;
            double e = 0;
            for (int k = a; k < b; k++) e += Math.abs(pcm[k]);
            e /= (b - a);
            if (e < 0.01) { misses++; System.out.println("   note " + i + " (" + n.lyric + ") silent: energy=" + e); }
        }
        check("every note produces sound", misses == 0, misses + " silent notes");

        // pitch: autocorrelation on the middle of each note
        int bad = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < part.notes.size(); i++) {
            Note n = part.notes.get(i);
            double startSec = TimeUtil.tickToSec(song, n.pos);
            double endSec = TimeUtil.tickToSec(song, n.pos + n.dur);
            double expected = 440.0 * Math.pow(2, (n.key - 69) / 12.0);
            double got = FlatPitchTest.f0ByDft(pcm, cfg.sampleRate,
                    startSec + (endSec - startSec) * 0.62, expected, 4096);
            double err = Math.abs(got - expected) / expected;
            sb.append(String.format("   %s key=%d expect=%.1fHz got=%.1fHz err=%.1f%%%n", n.lyric, n.key, expected, got, err * 100));
            if (got <= 0 || err > 0.03) bad++;
        }
        System.out.print(sb);
        check("pitch within 3% of the score", bad == 0, bad + " notes off");

        // different singers must differ audibly
        float[] a = Renderer.renderPart(song, part, SingerLibrary.luoTianyi(), cfg);
        float[] b = Renderer.renderPart(song, part, SingerLibrary.miku(), cfg);
        double diff = 0, ea = 0;
        int nn = Math.min(a.length, b.length);
        for (int i = 0; i < nn; i++) { diff += (a[i] - b[i]) * (a[i] - b[i]); ea += a[i] * a[i]; }
        double rel = Math.sqrt(diff / Math.max(1e-9, ea));
        check("singers are audibly different", rel > 0.05, "relative difference=" + String.format("%.3f", rel));

        // write WAVs so a human can listen
        WavWriter.writeMono16(new File(out, "demo_luotianyi.wav"), a, cfg.sampleRate);
        WavWriter.writeMono16(new File(out, "demo_miku.wav"), b, cfg.sampleRate);
        System.out.println("   wrote " + new File(out, "demo_luotianyi.wav").getAbsolutePath());

        System.out.println(fails == 0 ? ("ALL PASS (" + checks + " checks)") : (fails + "/" + checks + " CHECKS FAILED"));
        if (fails > 0) System.exit(1);
    }

    /** normalised autocorrelation (NSDF-style) pitch estimate: picks the first strong peak, which avoids octave-too-low errors */
    static double estimateF0(float[] x, int centre, int sr) {
        int win = 2048;
        int a = Math.max(0, centre - win / 2);
        int b = Math.min(x.length, a + win);
        if (b - a < 1024) return -1;
        int minLag = sr / 1200, maxLag = sr / 55;
        if (a + maxLag + (b - a) > x.length) return -1;
        double e0 = 0;
        for (int i = a; i < b; i++) e0 += x[i] * x[i];
        if (e0 < 1e-6) return -1;
        double best = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double s = 0, e1 = 0;
            for (int i = a; i < b; i++) { s += x[i] * x[i + lag]; e1 += x[i + lag] * x[i + lag]; }
            double r = s / Math.sqrt(e0 * e1 + 1e-12);
            if (r > best) best = r;
        }
        if (best < 0.3) return -1;
        double thresh = 0.88 * best;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double s = 0, e1 = 0;
            for (int i = a; i < b; i++) { s += x[i] * x[i + lag]; e1 += x[i + lag] * x[i + lag]; }
            double r = s / Math.sqrt(e0 * e1 + 1e-12);
            if (r >= thresh) {
                // parabolic refinement around the peak
                double rm = 0, rp = 0;
                if (lag > minLag && lag < maxLag) {
                    double s2 = 0, e2 = 0, s3 = 0, e3 = 0;
                    for (int i = a; i < b; i++) {
                        s2 += x[i] * x[i + lag - 1]; e2 += x[i + lag - 1] * x[i + lag - 1];
                        s3 += x[i] * x[i + lag + 1]; e3 += x[i + lag + 1] * x[i + lag + 1];
                    }
                    rm = s2 / Math.sqrt(e0 * e2 + 1e-12);
                    rp = s3 / Math.sqrt(e0 * e3 + 1e-12);
                }
                double denom = (rm - 2 * r + rp);
                double shift = Math.abs(denom) > 1e-9 ? 0.5 * (rm - rp) / denom : 0;
                if (shift > 1) shift = 1; if (shift < -1) shift = -1;
                return sr / (lag + shift);
            }
        }
        return -1;
    }
}
