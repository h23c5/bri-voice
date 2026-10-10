import com.dsh.v4tune.model.*;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/** Clicks and buzz: the largest sample-to-sample jump and the 6-14 kHz band around a fricative. */
public class ClickTest {
    public static void main(String[] a) throws Exception {
        VoiceModel vm = null;
        for (File f : new File(a[0]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) { VoicePack.Result r = VoicePack.load(f); if (r.ok()) vm = r.model; }
        }
        for (boolean on : new boolean[]{false, true}) {
            Renderer.measuredNoise = on;
            for (String syl : new String[]{"s a", "f a", "h a"}) {
                Song s = Song.empty();
                Part p = s.tracks.get(0).parts.get(0);
                p.notes.clear();
                Note n = new Note();
                n.pos = 480; n.dur = 960; n.key = 60; n.vel = 64;
                n.lyric = syl.substring(0, 1);
                n.phoneme = syl;
                p.notes.add(n);
                p.dur = 2880;
                float[] x = Renderer.renderPart(s, p, vm, new Renderer.Cfg());
                int onset = (int) (TimeUtil.tickToSec(s, 480) * 44100);
                double maxJump = 0; int big = 0;
                for (int i = Math.max(1, onset - 8820); i < Math.min(x.length, onset + 8820); i++) {
                    double d = Math.abs(x[i] - x[i - 1]);
                    if (d > maxJump) maxJump = d;
                    if (d > 0.05) big++;
                }
                double hf = bandRms(x, onset - 8820, onset + 4410, 6000, 14000);
                double lf = bandRms(x, onset - 8820, onset + 4410, 200, 2000);
                System.out.printf("measuredNoise=%-5s  %-4s maxJump=%.3f  jumps>0.05=%-5d  HF/LF=%.4f%n",
                        on, syl.replace(" ", ""), maxJump, big, hf / Math.max(1e-9, lf));
            }
        }
    }

    static double bandRms(float[] x, int a, int b, double f0, double f1) {
        if (a < 0) a = 0;
        if (b > x.length) b = x.length;
        double sum = 0; int n = 0;
        for (double f = f0; f <= f1; f *= 1.15) {
            double re = 0, im = 0;
            for (int i = a; i < b; i++) {
                double w = 2 * Math.PI * f * i / 44100.0;
                re += x[i] * Math.cos(w);
                im -= x[i] * Math.sin(w);
            }
            sum += Math.sqrt(re * re + im * im) / Math.max(1, b - a);
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }
}