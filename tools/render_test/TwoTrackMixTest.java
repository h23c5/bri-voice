import com.dsh.v4tune.io.ProjectIO;
import com.dsh.v4tune.model.*;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;

import java.io.File;

/**
 * Two tracks must be audible AT THE SAME TIME. Mirrors what AudioEngine.render does (render each
 * part, accumulate into one buffer at the part's start) and then proves with an FFT that both
 * tracks' pitches are present in the mix.
 */
public class TwoTrackMixTest {

    static int checks = 0, fails = 0;

    public static void main(String[] args) throws Exception {
        Song s = ProjectIO.openPath(new File(args[0]));
        VoiceModel vm = null;
        for (File f : new File(args[1]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) {
                VoicePack.Result r = VoicePack.load(f);
                if (r.ok()) vm = r.model;
            }
        }
        System.out.println("tracks=" + s.tracks.size() + " voice=" + (vm == null ? "NONE" : vm.name));
        int sr = 44100;
        int n = (int) (TimeUtil.tickToSec(s, s.maxTick()) * sr) + sr;
        float[] mix = new float[n];
        for (Track t : s.tracks) {
            for (Part p : t.parts) {
                float[] mono = Renderer.renderPart(s, p, vm, new Renderer.Cfg());
                int at = (int) (TimeUtil.tickToSec(s, p.pos) * sr);
                for (int i = 0; i < mono.length; i++) {
                    int k = at + i;
                    if (k >= 0 && k < n) mix[k] += mono[i];
                }
                System.out.printf("  track %s part pos=%d notes=%d -> %d frames%n",
                        t.name, p.pos, p.notes.size(), mono.length);
            }
        }

        // energy in the two pitch regions of the two tracks (track 1: C4..D4, track 2: E4..F#4)
        double lo = band(mix, sr, 240, 300);
        double hi = band(mix, sr, 320, 380);
        System.out.printf("band 240-300 Hz = %.4f, band 320-380 Hz = %.4f%n", lo, hi);
        check("track 1 audible", lo > 1e-4);
        check("track 2 audible", hi > 1e-4);
        check("both at once", mixRms(mix) > 1e-3);

        // and the parts must overlap in time, otherwise "at once" is meaningless
        Part p0 = s.tracks.get(0).parts.get(0), p1 = s.tracks.get(1).parts.get(0);
        check("parts overlap in time", p0.pos < p1.end() && p1.pos < p0.end());

        System.out.println(fails == 0 ? ("ALL PASS (" + checks + " checks)") : (fails + " FAILURES"));
        if (fails > 0) System.exit(1);
    }

    static double band(float[] x, int sr, double f0, double f1) {
        double e = 0;
        for (double f = f0; f <= f1; f += 2.0) {
            double re = 0, im = 0;
            double w = 2 * Math.PI * f / sr;
            for (int i = 0; i < 16384 && i < x.length; i++) {
                double win = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / 16383);
                re += x[i] * win * Math.cos(w * i);
                im -= x[i] * win * Math.sin(w * i);
            }
            e += Math.sqrt(re * re + im * im) / 16384;
        }
        return e;
    }

    static double mixRms(float[] x) {
        double s = 0;
        for (float v : x) s += v * v;
        return Math.sqrt(s / Math.max(1, x.length));
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("  FAIL " + what); }
    }
}
