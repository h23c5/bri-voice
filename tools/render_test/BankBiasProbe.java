import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

public class BankBiasProbe {
    public static void main(String[] a) {
        for (File f : new File(a[0]).listFiles()) {
            if (!f.getName().endsWith(".v4voice")) continue;
            VoicePack.Result r = VoicePack.load(f);
            if (!r.ok()) continue;
            VoiceModel v = r.model;
            System.out.printf("%s%n  breathBias=%.3f consonantGain=%.3f vibratoDepthCents=%.1f%n"
                    + "  tiltBias=%.3f oqBias=%.3f breathSoundAmount=%.3f%n",
                    v.name, v.breathBias, v.consonantGain, v.vibratoDepthCents,
                    v.tiltBias, v.oqBias, v.breathSoundAmount);
        }
    }
}