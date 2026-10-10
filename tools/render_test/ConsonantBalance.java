import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import com.dsh.v4tune.phoneme.PhonemeDef;
import com.dsh.v4tune.phoneme.PhonemeTable;

import java.io.File;

/** Loudness of the consonant vs the vowel, per syllable. */
public class ConsonantBalance {

    public static void main(String[] args) throws Exception {
        VoiceModel vm = null;
        File[] fs = new File(args.length > 0 ? args[0] : "build/voicepacks").listFiles();
        if (fs != null) for (File f : fs) {
            if (f.getName().startsWith("luotianyi")) {
                VoicePack.Result r = VoicePack.load(f);
                if (r.ok()) vm = r.model;
            }
        }
        String[] lyr = {"a", "sa", "sha", "fa", "ha", "ta", "pa", "ka", "za", "zha", "ji", "qi", "ma", "la", "ya"};
        System.out.printf("%-5s %-8s %8s %8s %8s %8s%n", "lyric", "phonemes", "consRMS", "vowRMS", "ratio_dB", "peak");
        for (String L : lyr) {
            Song s = Song.empty();
            Part p = s.tracks.get(0).parts.get(0);
            p.dur = 1920 * 2;
            Note n = new Note();
            n.pos = 480; n.dur = 1920; n.key = 57; n.lyric = L;
            p.notes.add(n);
            Renderer.Cfg cfg = new Renderer.Cfg();
            float[] x = Renderer.renderPart(s, p, vm, cfg);
            int sr = cfg.sampleRate;
            java.util.List<String> ph = Renderer.resolvePhonemes(n, vm);
            // consonant window: [note start - 60 ms, note start + 20 ms]; vowel window: middle of the note
            int nStart = (int) (0.5 * sr) + (int) (480.0 / 480 * 0.5 * 0);
            int noteStart = (int) (com.dsh.v4tune.model.TimeUtil.tickToSec(s, 480) * sr);
            int consA = Math.max(0, noteStart - (int) (0.060 * sr));
            int consB = Math.min(x.length, noteStart + (int) (0.020 * sr));
            int vowA = noteStart + (int) (0.25 * sr);
            int vowB = Math.min(x.length, vowA + (int) (0.30 * sr));
            double cr = rms(x, consA, consB), vr = rms(x, vowA, vowB);
            double pk = 0;
            for (int i = consA; i < consB; i++) pk = Math.max(pk, Math.abs(x[i]));
            System.out.printf("%-5s %-8s %8.4f %8.4f %+8.1f %8.4f%n", L, ph.toString().replace(" ", ""),
                    cr, vr, 20 * Math.log10((cr + 1e-9) / (vr + 1e-9)), pk);
        }
    }

    static double rms(float[] x, int a, int b) {
        if (b <= a) return 0;
        double s = 0;
        for (int i = a; i < b; i++) s += x[i] * x[i];
        return Math.sqrt(s / (b - a));
    }
}
