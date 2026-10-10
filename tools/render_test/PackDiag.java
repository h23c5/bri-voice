import com.dsh.v4tune.synth.VoicePack;
import com.dsh.v4tune.synth.VoiceModel;
import java.io.File;

public class PackDiag {
    public static void main(String[] args) throws Exception {
        File[] fs = new File(args[0]).listFiles();
        for (File f : fs) {
            if (!f.getName().endsWith(".v4voice")) continue;
            VoicePack.Result r = VoicePack.load(f);
            System.out.println(f.getName() + " ok=" + r.ok() + " err=" + r.error);
            if (!r.ok()) continue;
            VoiceModel vm = r.model;
            System.out.println("  harmonics symbols=" + vm.harmonics.size() + " grid=" + vm.harmonicFreqs.length);
            VoiceModel.HarmonicBand[] bs = vm.harmonics.get("a");
            if (bs == null) { System.out.println("  no bands for 'a'"); continue; }
            System.out.println("  bands for a = " + bs.length);
            for (int i = 0; i < Math.min(2, bs.length); i++) {
                VoiceModel.HarmonicBand b = bs[i];
                System.out.println("    [" + i + "] f0=" + b.f0 + " db=" + (b.db == null ? "null" : b.db.length)
                        + " seq=" + (b.seq == null ? "null" : b.seq.length + "x" + b.seq[0].length)
                        + " dyn=" + b.dyn);
            }
        }
    }
}
