import com.dsh.v4tune.model.*;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/** A part whose stored duration is too small must still render (regression for the AIOOBE). */
public class PartDurTest {
    static int checks = 0, fails = 0;

    public static void main(String[] args) throws Exception {
        VoiceModel vm = null;
        for (File f : new File(args[0]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) {
                VoicePack.Result r = VoicePack.load(f);
                if (r.ok()) vm = r.model;
            }
        }
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        p.pos = 0;
        p.dur = 480;                       // deliberately far too small
        for (int i = 0; i < 6; i++) {
            Note n = new Note();
            n.pos = i * 3840L;
            n.dur = 1920;
            n.key = 60 + i;
            n.lyric = "a";
            p.notes.add(n);
        }
        float[] out = null;
        try {
            out = Renderer.renderPart(s, p, vm, new Renderer.Cfg());
        } catch (Throwable t) {
            fails++;
            System.out.println("  FAIL threw " + t);
        }
        check("rendered without throwing", out != null);
        if (out != null) {
            long need = (long) (TimeUtil.tickToSec(s, 5 * 3840L + 1920) * 44100);
            System.out.println("buffer=" + out.length + " needed~" + need);
            check("buffer covers the last note", out.length >= need);
            double e = 0;
            for (float v : out) e += v * v;
            check("not silent", Math.sqrt(e / out.length) > 1e-4);
        }
        System.out.println(fails == 0 ? ("ALL PASS (" + checks + " checks)") : (fails + " FAILURES"));
        if (fails > 0) System.exit(1);
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("  FAIL " + what); }
    }
}
