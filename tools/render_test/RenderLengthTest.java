import com.dsh.v4tune.model.*;
import com.dsh.v4tune.synth.*;

/**
 * Regression: a part whose notes are part-relative and extend past its own dur must be rendered to
 * its absolute end. Two bugs made it short by exactly part.pos, which silenced the tail of every
 * part that starts late (heard as the last notes going missing, in playback and in WAV export alike).
 */
public class RenderLengthTest {
    public static void main(String[] a) throws Exception {
        int fails = 0;
        fails += one(0L, 7680L, 144000L, 136560L, "part starts at 0");
        fails += one(0L, 7680L, 144000L, 144240L, "part starts late, notes past dur");
        fails += one(0L, 0L, 1920L, 4800L, "part at 0 with long notes");
        System.out.println(fails == 0 ? "RESULT: PASS" : ("RESULT: FAIL " + fails));
        if (fails != 0) System.exit(1);
    }

    static int one(long pos, long dur, long noteStart, long noteDur, String what) {
        Song s = new Song();
        Track t = new Track();
        s.tracks.add(t);
        Part p = new Part();
        p.pos = pos;
        p.dur = dur;
        t.parts.add(p);
        Note n = new Note();
        n.pos = noteStart;      // part-relative, like the vsqx format
        n.dur = noteDur;
        n.key = 60;
        n.lyric = "a";
        p.notes.add(n);
        Renderer.Cfg cfg = new Renderer.Cfg();
        cfg.sampleRate = 44100;
        long endTick = Math.max(p.end(), p.pos + n.pos + n.dur);
        double endSec = TimeUtil.tickToSec(s, endTick);
        float[] pcm = Renderer.renderPart(s, p, SingerLibrary.luoTianyi(), cfg);
        double gotSec = pcm.length / 44100.0;
        boolean ok = gotSec + 1e-6 >= endSec;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what
                + String.format("   [need >= %.2fs, got %.2fs]", endSec, gotSec));
        return ok ? 0 : 1;
    }
}