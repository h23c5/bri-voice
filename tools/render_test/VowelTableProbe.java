import com.dsh.v4tune.phoneme.PhonemeTable;
import com.dsh.v4tune.phoneme.PhonemeDef;
import java.util.List;

/** which vowel phonemes exist, and what formants does the table give them? */
public class VowelTableProbe {
    public static void main(String[] a) {
        List<String> syms = PhonemeTable.symbols();
        System.out.println("vowels in the table:");
        for (String s : syms) {
            PhonemeDef d = PhonemeTable.getOrDefault(s);
            if (d.type != PhonemeDef.T_VOWEL) continue;
            System.out.printf("  %-5s F1=%4.0f F2=%4.0f F3=%4.0f F4=%4.0f gain=%.2f%n",
                    s, d.f1, d.f2, d.f3, d.f4, d.gain);
        }
    }
}