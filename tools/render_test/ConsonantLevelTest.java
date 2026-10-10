import com.dsh.v4tune.model.*;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/** the number the last change never checked: how loud is the consonant against its vowel? */
public class ConsonantLevelTest {
    static final int SR = 44100;
    static VoiceModel vm;

    public static void main(String[] a) throws Exception {
        for (File f : new File(a[0]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) { VoicePack.Result r = VoicePack.load(f); if (r.ok()) vm = r.model; }
        }
        String[] cons = {"s", "x", "f", "z`", "ts`"};
        System.out.printf("%-6s %-22s %-22s%n", "", "generic noise (v5.0)", "measured spectra (v5.1)");
        for (String c : cons) {
            double[] old = levels(c, false);
            double[] now = levels(c, true);
            System.out.printf("%-6s consonant %+5.1f dB / peak %.3f   consonant %+5.1f dB / peak %.3f%n",
                    c, old[0], old[1], now[0], now[1]);
        }
    }

    /** { consonant level in dB relative to the vowel, absolute peak } */
    static double[] levels(String c, boolean measured) {
        Renderer.measuredNoise = measured;
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        Note n = new Note();
        n.pos = 480; n.dur = 960; n.key = 60; n.vel = 64;
        n.lyric = c;
        n.phoneme = c + " a";
        p.notes.add(n);
        p.dur = 2880;
        float[] x = Renderer.renderPart(s, p, vm, new Renderer.Cfg());
        int onset = (int) (TimeUtil.tickToSec(s, 480) * SR);
        double cons = rms(x, onset - 1800, onset + 600);      // the consonant + its lead-in
        double vowel = rms(x, onset + 8820, onset + 20000);   // the steady vowel
        double peak = 0;
        for (int i = Math.max(0, onset - 4000); i < Math.min(x.length, onset + 8000); i++) peak = Math.max(peak, Math.abs(x[i]));
        return new double[]{20 * Math.log10(Math.max(1e-9, cons) / Math.max(1e-9, vowel)), peak};
    }

    static double rms(float[] x, int a, int b) {
        if (a < 0) a = 0; if (b > x.length) b = x.length;
        double e = 0;
        for (int i = a; i < b; i++) e += x[i] * x[i];
        return Math.sqrt(e / Math.max(1, b - a));
    }
}