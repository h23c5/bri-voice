import com.dsh.v4tune.voicebank.*;
import java.io.File;

public class JavaBuild {
    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        VoicebankBuilder.Options o = new VoicebankBuilder.Options();
        o.maxFrames = 9000; o.clusters = 24;
        o.noiseSamples = 20000;   // every SND record: the fricatives are ~3 % and vanish in a small sample
        o.noiseBudget = 0;   // whole file, like the PC build   // 0 = whole file, like the PC build
        VoicebankWriter w = VoicebankBuilder.build(new File(a[0]), a[1], a[2], o);
        w.writeZip(new File(a[3]), null);
        System.out.println("built in " + (System.currentTimeMillis() - t0) / 1000 + "s  bands=" + w.phonemes.size()
                + " noise=" + w.noise.size() + " formants=" + w.formants.size());
        System.out.println("stats: " + w.stats);
    }
}