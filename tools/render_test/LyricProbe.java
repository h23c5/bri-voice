import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

/** what does the phonemizer make of the candidate lyrics? */
public class LyricProbe {
    public static void main(String[] args) throws Exception {
        VoiceModel vm = null;
        for (File f : new File(args[0]).listFiles()) {
            if (f.getName().startsWith("luotianyi")) {
                VoicePack.Result r = VoicePack.load(f);
                if (r.ok()) vm = r.model;
            }
        }
        String[] cand = {"这", "里", "是", "Bri", "Voice", "brai", "vois", "bri", "欢", "迎", "回", "来",
                "zhe", "li", "shi", "huan", "ying", "hui", "lai", "BriVoice"};
        for (String ly : cand) {
            Note n = new Note();
            n.lyric = ly;
            System.out.printf("%-10s -> %s%n", ly, Renderer.resolvePhonemes(n, vm));
        }
        String retro = "z" + (char) 0x60;          // the CHN retroflex approximant symbol
        Note n2 = new Note();
        n2.lyric = "Bri";
        n2.phoneme = "b " + retro + " ai";
        System.out.println("Bri + phoneme override -> " + Renderer.resolvePhonemes(n2, vm));
        Note n3 = new Note();
        n3.lyric = "Voice";
        n3.phoneme = "w o i s";
        System.out.println("Voice + phoneme override -> " + Renderer.resolvePhonemes(n3, vm));
    }
}