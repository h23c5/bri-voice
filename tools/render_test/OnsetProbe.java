import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;

import java.io.File;

/** Onset analysis with real lyrics: is there an artificial burst in front of every note? */
public class OnsetProbe {

    public static void main(String[] args) throws Exception {
        VoiceModel vm = null;
        File dir = new File(args.length > 0 ? args[0] : "build/voicepacks");
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.getName().startsWith("luotianyi")) {
                VoicePack.Result r = VoicePack.load(f);
                if (r.ok()) vm = r.model;
            }
        }
        System.out.println("voice = " + (vm == null ? "NONE" : vm.name));
        String[] lyrics = {"luo", "tian", "yi", "la"};
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.dur = 1920 * 4;
        for (int i = 0; i < lyrics.length; i++) {
            Note n = new Note();
            n.pos = i * 960L;         // 1.0 s apart at 120 bpm
            n.dur = 900;              // 0.94 s notes, small gaps
            n.key = 57;
            n.lyric = lyrics[i];
            p.notes.add(n);
        }
        Renderer.Cfg cfg = new Renderer.Cfg();
        float[] pcm = Renderer.renderPart(s, p, vm, cfg);
        int sr = cfg.sampleRate;
        for (int i = 0; i < lyrics.length; i++) {
            int start = (int) ((i * 1.0) * sr);
            if (i != 1 && i != 2) continue;      // focus on "tian" (aspirated stop) and "yi" (vowel onset)
            System.out.println("--- note " + i + " \"" + lyrics[i] + "\" at " + i + ".0 s");
            StringBuilder sb = new StringBuilder();
            double maxStep = 0; int maxStepAt = 0;
            for (int w = -2; w < 30; w++) {
                int a = start + w * sr / 200;
                if (a < 0 || a + sr / 100 >= pcm.length) continue;
                double e = 0, pk = 0;
                int n = sr / 200;
                for (int k = a; k < a + n; k++) { e += pcm[k] * pcm[k]; double av = Math.abs(pcm[k]); if (av > pk) pk = av; }
                e = Math.sqrt(e / n);
                for (int k = a + 1; k < a + n; k++) {
                    double st = Math.abs(pcm[k] - pcm[k - 1]);
                    if (st > maxStep) { maxStep = st; maxStepAt = k - start; }
                }
                sb.append(String.format("%n   t%+4dms rms=%.4f peak=%.4f crest=%.1f", w * 5, e, pk, pk / Math.max(1e-9, e)));
            }
            System.out.println(sb);
            System.out.printf("   max sample step %.4f at t%+dms%n", maxStep, maxStepAt * 1000 / sr);
        }
    }
}
