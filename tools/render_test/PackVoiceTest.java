import com.dsh.v4tune.audio.Demo;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.SingerLibrary;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;

import java.io.File;

/** Does an imported .v4voice pack actually change the rendered voice? */
public class PackVoiceTest {

    public static void main(String[] args) throws Exception {
        Song song = Demo.twinkle();
        Part part = song.tracks.get(0).parts.get(0);
        Renderer.Cfg cfg = new Renderer.Cfg();

        System.out.println("== built-in singers ==");
        float[] base = null;
        for (VoiceModel vm : SingerLibrary.builtin()) {
            float[] pcm = Renderer.renderPart(song, part, vm, cfg);
            System.out.printf("  %-24s rms=%.4f  overrides=%d  shelf=%d%n",
                    vm.name, rms(pcm), vm.overrides.size(), vm.shelfFreqs.length);
            if (base == null) base = pcm;
        }

        File dir = new File(args.length > 0 ? args[0] : "build/voicepacks");
        File[] packs = dir.listFiles();
        if (packs == null) { System.out.println("no pack dir " + dir); return; }
        int loaded = 0;
        for (File f : packs) {
            if (!f.getName().endsWith(".v4voice")) continue;
            VoicePack.Result r = VoicePack.load(f);
            if (!r.ok()) { System.out.println("  LOAD FAIL " + f.getName() + ": " + r.error); continue; }
            loaded++;
            VoiceModel vm = r.model;
            float[] pcm = Renderer.renderPart(song, part, vm, cfg);
            double d = relDiff(base, pcm);
            System.out.printf("  %-40s rms=%.4f  overrides=%d  shelf=%d  diff-vs-builtin=%.3f  %s%n",
                    vm.name, rms(pcm), vm.overrides.size(), vm.shelfFreqs.length, d,
                    d > 0.05 ? "OK (audibly different)" : "*** NO EFFECT ***");
            System.out.print("      shelf:");
            for (int i = 0; i < vm.shelfFreqs.length; i++)
                System.out.printf(" %.0fHz%+.1fdB", vm.shelfFreqs[i], vm.shelfGains[i]);
            System.out.println();
            String[] sample = {"a", "i", "uo", "iE_n", "AN"};
            for (String sym : sample) {
                double[] ov = vm.override(sym);
                if (ov != null) System.out.printf("      override %-6s -> F1=%.0f F2=%.0f F3=%.0f%n", sym, ov[0], ov[1], ov[2]);
            }
        }
        System.out.println(loaded == 0 ? "FAIL: no packs loaded" : ("PASS: " + loaded + " pack(s) loaded and rendered"));
        if (loaded == 0) System.exit(1);
    }

    static double rms(float[] x) {
        double s = 0;
        for (float v : x) s += v * v;
        return Math.sqrt(s / Math.max(1, x.length));
    }

    static double relDiff(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double d = 0, e = 0;
        for (int i = 0; i < n; i++) { d += (a[i] - b[i]) * (a[i] - b[i]); e += a[i] * a[i]; }
        return Math.sqrt(d / Math.max(1e-12, e));
    }
}
