import com.dsh.v4tune.model.*;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/** the breath layer itself: silence before a pure-vowel note, and the gap between two notes. */
public class BreathLevelTest {
    static final int SR = 44100;
    static VoiceModel vm;

    public static void main(String[] a) throws Exception {
        for (File f : new File(a[0]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) { VoicePack.Result r = VoicePack.load(f); if (r.ok()) vm = r.model; }
        }
        // a pure vowel: whatever sits before it can only be the breath layer
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        Note n = new Note();
        n.pos = 1920; n.dur = 960; n.key = 60; n.vel = 64; n.lyric = "a";
        p.notes.add(n);
        p.dur = 3840;
        float[] x = Renderer.renderPart(s, p, vm, new Renderer.Cfg());
        int onset = (int) (TimeUtil.tickToSec(s, 1920) * SR);
        double airBefore = rms(x, onset - (int) (0.35 * SR), onset - (int) (0.03 * SR));
        double vowel = rms(x, onset + (int) (0.15 * SR), onset + (int) (0.45 * SR));
        System.out.printf("air before a pure vowel: %.1f dB below the vowel%n",
                20 * Math.log10(Math.max(1e-9, airBefore) / Math.max(1e-9, vowel)));

        // two notes with a real gap: the gap should be quiet
        Note n2 = new Note();
        n2.pos = 1920 + 960 + 1920; n2.dur = 960; n2.key = 62; n2.vel = 64; n2.lyric = "a";
        p.notes.add(n2);
        p.dur = 6000;
        float[] y = Renderer.renderPart(s, p, vm, new Renderer.Cfg());
        int gapA = (int) (TimeUtil.tickToSec(s, 1920 + 960 + 200) * SR);
        int gapB = (int) (TimeUtil.tickToSec(s, 1920 + 960 + 1700) * SR);
        double gap = rms(y, gapA, gapB);
        double v2 = rms(y, (int) (TimeUtil.tickToSec(s, 1920) * SR) + 8820, (int) (TimeUtil.tickToSec(s, 1920) * SR) + 22050);
        System.out.printf("breath in the gap: %.1f dB below the vowel%n",
                20 * Math.log10(Math.max(1e-9, gap) / Math.max(1e-9, v2)));
    }

    static double rms(float[] x, int a, int b) {
        if (a < 0) a = 0; if (b > x.length) b = x.length;
        double e = 0;
        for (int i = a; i < b; i++) e += x[i] * x[i];
        return Math.sqrt(e / Math.max(1, b - a));
    }
}