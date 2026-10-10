import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;

import java.io.File;

/**
 * Does one sustained note really CHANGE timbre over time (real stored frame sequences), instead of
 * holding one frozen spectrum?
 */
public class SequenceEvolutionTest {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "build/voicepacks");
        int fails = 0, tested = 0;
        File[] fs = dir.listFiles();
        if (fs == null) { System.out.println("no packs"); System.exit(1); }
        for (File f : fs) {
            if (!f.getName().endsWith(".v4voice")) continue;
            VoicePack.Result r = VoicePack.load(f);
            if (!r.ok()) continue;
            VoiceModel vm = r.model;
            VoiceModel.HarmonicBand[] bs = vm.harmonics.get("a");
            if (bs == null || bs.length == 0) continue;
            int withSeq = 0, frames = 0;
            for (VoiceModel.HarmonicBand b : bs) if (b.seq != null) { withSeq++; frames += b.seq.length; }
            System.out.printf("== %s: %d bands for /a/, %d carry a frame sequence (%d frames total)%n",
                    vm.name, bs.length, withSeq, frames);
            if (withSeq == 0) { System.out.println("   FAIL  no frame sequences in this pack"); fails++; continue; }
            tested++;

            float[] x = render(vm);
            int sr = 44100;
            double[] early = spectrum(x, sr, (int) (0.15 * sr));
            double[] late = spectrum(x, sr, (int) (1.60 * sr));
            double maxDb = 0; int maxK = 0;
            double sum = 0; int cnt = 0;
            for (int k = 2; k < 40; k++) {                 // compare the first 40 harmonics
                double a = 20 * Math.log10(early[k] + 1e-12);
                double b = 20 * Math.log10(late[k] + 1e-12);
                double d = Math.abs(a - b);
                sum += d; cnt++;
                if (d > maxDb) { maxDb = d; maxK = k; }
            }
            System.out.printf("   spectral change across the note: mean %.2f dB, max %.2f dB (harmonic %d)%n",
                    sum / cnt, maxDb, maxK);
            boolean ok = maxDb > 0.8;
            System.out.println(ok ? "   PASS  the timbre evolves within a single note"
                                  : "   FAIL  the timbre is frozen");
            if (!ok) fails++;
        }
        System.out.println(fails == 0 ? ("ALL PASS (" + tested + " packs)") : (fails + " FAILURES"));
        if (fails > 0) System.exit(1);
    }

    static float[] render(VoiceModel vm) {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.dur = 1920 * 8;
        Note n = new Note();
        n.pos = 0; n.dur = 1920 * 6; n.key = 57; n.lyric = "a";
        p.notes.add(n);
        ControlCurve d = p.ctrl("DYN");
        d.putRaw(0, 64); d.putRaw(p.dur, 64);
        return Renderer.renderPart(s, p, vm, new Renderer.Cfg());
    }

    /** magnitude of the first 48 harmonics of the f0=220 Hz note in a window */
    static double[] spectrum(float[] x, int sr, int at) {
        int N = 8192;
        double[] out = new double[64];
        double f0 = 220.0;
        for (int k = 1; k < out.length; k++) {
            double re = 0, im = 0;
            double w0 = 2 * Math.PI * k * f0 / sr;
            for (int i = 0; i < N; i++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
                re += x[at + i] * w * Math.cos(w0 * i);
                im -= x[at + i] * w * Math.sin(w0 * i);
            }
            out[k] = Math.sqrt(re * re + im * im) / N;
        }
        return out;
    }
}
