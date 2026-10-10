import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/** what does the bank's own curve for /a/ look like, and at which f0 was it measured? */
public class PackCurveProbe {
    public static void main(String[] a) throws Exception {
        for (File f : new File(a[0]).listFiles()) {
            if (!f.getName().startsWith("luotianyi")) continue;
            VoicePack.Result r = VoicePack.load(f);
            if (!r.ok()) continue;
            VoiceModel v = r.model;
            System.out.println("grid: " + v.harmonicFreqs[0] + " .. " + v.harmonicFreqs[v.harmonicFreqs.length - 1]
                    + "  (" + v.harmonicFreqs.length + " bands)");
            System.out.print("grid points: ");
            for (int i = 0; i < v.harmonicFreqs.length; i += 7) System.out.printf("%.0f ", v.harmonicFreqs[i]);
            System.out.println();
            for (String sym : new String[]{"a", "i", "o", "u"}) {
                VoiceModel.HarmonicBand[] arr = v.harmonics.get(sym);
                if (arr == null) { System.out.println(sym + ": no data"); continue; }
                System.out.println(sym + ": " + arr.length + " variants; full curve to 4 kHz:");
                arr = new VoiceModel.HarmonicBand[]{arr[arr.length - 1]};
                for (VoiceModel.HarmonicBand b : arr) {
                    System.out.printf("   f0=%.0f Hz dyn=%.2f  curve(dB): ", b.f0, b.dyn);
                    for (int i = 0; i < b.db.length; i++) {
                        if (v.harmonicFreqs[i] > 4200) break;
                        System.out.printf("%.0f:%.0f ", v.harmonicFreqs[i], b.db[i]);
                    }
                    System.out.println();
                }
            }
        }
    }
}