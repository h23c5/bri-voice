import com.dsh.v4tune.synth.VoicePack;
import java.io.File;

public class LoadTime {
    public static void main(String[] args) {
        File[] fs = new File(args[0]).listFiles();
        for (File f : fs) {
            if (!f.getName().endsWith(".v4voice")) continue;
            long t0 = System.nanoTime();
            VoicePack.Result r = VoicePack.load(f);
            long ms = (System.nanoTime() - t0) / 1000000;
            System.out.printf("%-40s %8d bytes  load=%d ms  ok=%s%n", f.getName(), f.length(), ms, r.ok());
        }
    }
}
