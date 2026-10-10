import com.dsh.v4tune.audio.Demo;
import com.dsh.v4tune.model.*;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/** the demo phrase must show the real text AND sing the right phonemes */
public class DemoPhraseTest {
    static int checks = 0, fails = 0;

    public static void main(String[] args) throws Exception {
        VoiceModel vm = null;
        for (File f : new File(args[0]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) {
                VoicePack.Result r = VoicePack.load(f);
                if (r.ok()) vm = r.model;
            }
        }
        Song s = Demo.build();
        Part p = s.tracks.get(0).parts.get(0);
        StringBuilder shown = new StringBuilder();
        for (Note n : p.notes) shown.append(n.lyric);
        System.out.println("phrase: " + shown);
        check("phrase is the requested text", shown.toString().equals("这里是BriVoice欢迎回来"));
        check("nine syllables", p.notes.size() == 9);
        int silent = 0;
        for (Note n : p.notes) {
            java.util.List<String> ph = Renderer.resolvePhonemes(n, vm);
            String one = ph.toString();
            if (one.equals("[a]")) silent++;
            System.out.printf("  %-6s %-12s -> %s%n", n.lyric, n.phoneme, one);
        }
        check("no note fell back to a bare [a]", silent == 0);
        float[] out = Renderer.renderPart(s, p, vm, new Renderer.Cfg());
        double e = 0;
        for (float v : out) e += v * v;
        double rms = Math.sqrt(e / out.length);
        System.out.printf("rendered %d frames, rms=%.4f%n", out.length, rms);
        check("audible", rms > 1e-3);
        System.out.println(fails == 0 ? ("ALL PASS (" + checks + " checks)") : (fails + " FAILURES"));
        if (fails > 0) System.exit(1);
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("  FAIL " + what); }
    }
}