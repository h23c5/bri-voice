import com.dsh.v4tune.model.*;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/** measures the three voicing changes: vibrato, legato join, vowel share at speed. */
public class VoiceQualityTest {
    static final int SR = 44100;
    static VoiceModel vm;

    public static void main(String[] args) throws Exception {
        for (File f : new File(args[0]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) { VoicePack.Result r = VoicePack.load(f); if (r.ok()) vm = r.model; }
        }
        System.out.println("voicebank: " + (vm == null ? "none" : vm.name));

        // 1) vibrato on a long note, and the same note with VIB forced to 0
        float[] vib = render(one(60, 1200, "a", -1));
        float[] flat = render(one(60, 1200, "a", 0));
        System.out.printf("vibrato depth: default=%.1f cents, VIB=0 -> %.1f cents%n", spread(vib), spread(flat));

        // 2) legato join vs a gap: the envelope must not dip when notes touch
        Song touch = two(480, 420, 480, 420, 0);
        Song gap = two(480, 420, 480, 420, 200);
        System.out.printf("join dip: touching=%.2f dB, 200-tick gap=%.2f dB (0 = no dip)%n",
                dip(touch), dip(gap));

        // 3) fast passage: how much of each note is steady (recognisable) vowel
        Song fast = fastPhrase();
        System.out.printf("fast 16ths (150bpm): steady-vowel share=%.0f%%, notes=%d%n",
                steadyShare(fast) * 100, 8);
    }

    static Song one(int key, long dur, String ly, double vibParam) {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        Note n = new Note();
        n.pos = 480; n.dur = dur; n.key = key; n.vel = 64; n.lyric = ly;
        p.notes.add(n);
        p.dur = dur + 960;
        if (vibParam >= 0) p.ctrl("VIB").putRaw(0, vibParam);
        return s;
    }

    static Song two(long p1, long d1, long p2, long d2, long gap) {
        Song s = one(60, d1, "a", -1);
        Part p = s.tracks.get(0).parts.get(0);
        Note n = new Note();
        n.pos = p1 + d1 + gap; n.dur = d2; n.key = 60; n.vel = 64; n.lyric = "a";
        p.notes.add(n);
        p.dur = n.pos + d2 + 480;
        return s;
    }

    /** eight 16th notes at 150 bpm with two-phoneme syllables */
    static Song fastPhrase() {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        String[] ly = {"zhe", "shi", "hui", "lai", "zhe", "shi", "hui", "lai"};
        for (int i = 0; i < 8; i++) {
            Note n = new Note();
            n.pos = 480 + i * 120L;      // 16th at 150 bpm is 120 ticks
            n.dur = 110;
            n.key = 62 + (i % 3);
            n.vel = 64;
            n.lyric = ly[i];
            p.notes.add(n);
        }
        p.dur = 480 + 8 * 120L + 480;
        return s;
    }

    static float[] render(Song s) {
        Part p = s.tracks.get(0).parts.get(0);
        return Renderer.renderPart(s, p, vm, new Renderer.Cfg());
    }

    /** peak-to-peak f0 deviation in cents over the steady part of the note */
    static double spread(float[] x) {
        double lo = 1e9, hi = -1e9;
        for (int at = (int) (0.55 * SR); at + 2048 < x.length && at < (int) (0.95 * SR); at += 512) {
            double f = f0(x, at);
            if (f <= 0) continue;
            if (f < lo) lo = f;
            if (f > hi) hi = f;
        }
        if (hi < lo) return 0;
        return 1200 * Math.log(hi / lo) / Math.log(2);
    }

    static double f0(float[] x, int at) {
        int w = 2048;
        // search only around the note we rendered (C4 = 261.6 Hz): an unrestricted search picks
        // octave errors and turns them into a fake 19-semitone "vibrato"
        double exp = 261.6;
        int loLag = (int) (SR / (exp * 1.25)), hiLag = (int) (SR / (exp * 0.80));
        double best = 0; int bestLag = 0;
        double e = 0;
        for (int i = 0; i < w; i++) e += x[at + i] * x[at + i];
        if (e < 1e-6) return 0;
        for (int lag = loLag; lag <= hiLag; lag++) {
            double c = 0;
            for (int i = 0; i < w - lag; i++) c += x[at + i] * x[at + i + lag];
            c /= (w - lag);
            if (c > best) { best = c; bestLag = lag; }
        }
        return bestLag == 0 ? 0 : (double) SR / bestLag;
    }

    /** the level drop in dB at the join between the two notes */
    static double dip(Song s) {
        float[] x = render(s);
        int step = SR / 100;                       // 10 ms
        int join = (int) (TimeUtil.tickToSec(s, 480 + 420) * SR);
        double here = rms(x, join - 4000, join + 4000);
        double at = rms(x, join - step, join + step);
        return 20 * Math.log10(Math.max(1e-9, at) / Math.max(1e-9, here));
    }

    static double rms(float[] x, int a, int b) {
        if (a < 0) a = 0;
        if (b > x.length) b = x.length;
        double e = 0;
        for (int i = a; i < b; i++) e += x[i] * x[i];
        return Math.sqrt(e / Math.max(1, b - a));
    }

    /** for every note: the fraction of its length that is within 3 dB of the note's peak level */
    static double steadyShare(Song s) {
        float[] x = render(s);
        Part p = s.tracks.get(0).parts.get(0);
        double sum = 0; int n = 0;
        int step = SR / 200;                       // 5 ms
        for (Note nt : p.notes) {
            int a = (int) (TimeUtil.tickToSec(s, nt.pos) * SR);
            int b = (int) (TimeUtil.tickToSec(s, nt.pos + nt.dur) * SR);
            double peak = 0;
            for (int i = a; i + step < b; i += step) peak = Math.max(peak, rms(x, i, i + step));
            int steady = 0, total = 0;
            for (int i = a; i + step < b; i += step) {
                total++;
                if (rms(x, i, i + step) >= peak * 0.707) steady++;
            }
            if (total > 0) { sum += steady / (double) total; n++; }
        }
        return n == 0 ? 0 : sum / n;
    }
}