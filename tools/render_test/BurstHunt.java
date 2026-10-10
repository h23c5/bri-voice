import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;

import java.io.File;

/**
 * Transient hunt: render several scenarios and locate every abrupt event.
 * A "burst" shows up as a spike in the short-time high-frequency energy (first-difference energy).
 */
public class BurstHunt {

    static VoiceModel vm;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "build/voicepacks");
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.getName().startsWith("luotianyi")) {
                VoicePack.Result r = VoicePack.load(f);
                if (r.ok()) vm = r.model;
            }
        }
        System.out.println("voice = " + (vm == null ? "NONE" : vm.name));
        String[] lyr = {"a", "a", "a", "a"};
        scan("pure vowel, adjacent notes   ", lyr, 480, 480);
        scan("pure vowel, 200 ms gaps     ", lyr, 960, 480);
        scan("stop onsets ta/pa/ka/da     ", new String[]{"ta", "pa", "ka", "da"}, 960, 480);
        scan("fricative sa/sha/fa/ha      ", new String[]{"sa", "sha", "fa", "ha"}, 960, 480);
        scan("liquid/glide la/ma/na/ya    ", new String[]{"la", "ma", "na", "ya"}, 960, 480);
        scan("affricate za/zha/ji/qi      ", new String[]{"za", "zha", "ji", "qi"}, 960, 480);
    }

    static void scan(String title, String[] lyrics, int spacing, int dur) {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.dur = (long) spacing * lyrics.length + 1920;
        for (int i = 0; i < lyrics.length; i++) {
            Note n = new Note();
            n.pos = i * (long) spacing;
            n.dur = dur;
            n.key = 57;
            n.lyric = lyrics[i];
            p.notes.add(n);
        }
        ControlCurve d = p.ctrl("DYN");
        d.putRaw(0, 64); d.putRaw(p.dur, 64);
        Renderer.Cfg cfg = new Renderer.Cfg();
        float[] x = Renderer.renderPart(s, p, vm, cfg);
        int sr = cfg.sampleRate;
        // short-time HF energy (first difference) with 5 ms hop, 3 ms window
        int win = sr / 333, hop = sr / 200;
        double best = 0; int bestAt = 0;
        double[] hf = new double[x.length / hop + 1];
        for (int i = 0, k = 0; i + win < x.length; i += hop, k++) {
            double e = 0;
            for (int j = i + 1; j < i + win; j++) { double df = x[j] - x[j - 1]; e += df * df; }
            hf[k] = Math.sqrt(e / win);
            if (hf[k] > best) { best = hf[k]; bestAt = i; }
        }
        double med = median(hf.clone());
        // list every 5 ms frame whose HF energy exceeds 4x the median (a transient)
        StringBuilder sb = new StringBuilder();
        int events = 0;
        for (int k = 1; k < hf.length - 1; k++) {
            if (hf[k] > 4.0 * med && hf[k] >= hf[k - 1] && hf[k] >= hf[k + 1]) {
                events++;
                if (events <= 6) sb.append(String.format(" t=%.3fs hf=%.4f (x%.1f)", k * hop / (double) sr, hf[k], hf[k] / Math.max(1e-9, med)));
            }
        }
        System.out.printf("%s transients=%d  peak=%.4f at %.3fs  median=%.4f%s%n",
                title, events, best, bestAt / (double) sr, med, sb);
    }

    static double median(double[] v) {
        java.util.Arrays.sort(v);
        return v[v.length / 2];
    }
}
