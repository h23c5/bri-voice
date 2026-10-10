import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import com.dsh.v4tune.phoneme.PhonemeTable;
import com.dsh.v4tune.phoneme.PhonemeDef;
import java.io.File;

/** which phonemes carry measured data in the pack, and are the consonants among them? */
public class PackPhonemeProbe {
    public static void main(String[] a) throws Exception {
        for (File f : new File(a[0]).listFiles()) {
            if (!f.getName().startsWith("luotianyi")) continue;
            VoicePack.Result r = VoicePack.load(f);
            if (!r.ok()) continue;
            VoiceModel v = r.model;
            System.out.println("bands=" + v.harmonicFreqs.length + " phonemes=" + v.harmonics.size());
            int voiced = 0, noise = 0, missing = 0;
            StringBuilder noiseKeys = new StringBuilder();
            for (String k : new String[]{"a", "i", "u", "s", "sh", "x", "f", "h", "z`", "ts`", "p", "p_h", "t", "k"}) {
                VoiceModel.HarmonicBand[] b = v.harmonics.get(k);
                PhonemeDef d = PhonemeTable.getOrDefault(k);
                boolean has = b != null && b.length > 0;
                if (has) {
                    if (d.type == PhonemeDef.T_VOWEL || d.type == PhonemeDef.T_NASAL || d.type == PhonemeDef.T_LIQUID) voiced++; else noise++;
                    if (d.type == PhonemeDef.T_FRIC || d.type == PhonemeDef.T_AFFRIC || d.type == PhonemeDef.T_ASPIR) {
                        noiseKeys.append(k).append('(').append(b.length).append(") ");
                    }
                } else missing++;
                System.out.printf("  %-5s type=%d measured=%s bands=%d%n", k, d.type, has, b == null ? 0 : b.length);
            }
            System.out.println("noise-type phonemes WITH measured data: " + noiseKeys);
            // what does a stored fricative envelope look like?
            VoiceModel.HarmonicBand[] s = v.harmonics.get("s");
            if (s != null) {
                System.out.print("  s band levels (dB @ mid band): ");
                for (int i = 0; i < s.length; i += Math.max(1, s.length / 12)) System.out.printf("%.0f ", s[i].db[Math.min(s[i].db.length - 1, s[i].db.length / 2)]);
                System.out.println();
            }
        }
    }
}