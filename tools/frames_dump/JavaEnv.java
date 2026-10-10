import com.dsh.v4tune.voicebank.*;
import java.io.*;

public class JavaEnv {
    public static void main(String[] a) throws Exception {
        File ddb = new File(a[0]);
        long[] offs = VoicebankFrames.scanFrm2Offsets(ddb, ddb.length(), 8L << 20);
        RandomAccessFile f = new RandomAccessFile(ddb, "r");
        PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(a[1])));
        int rows = 0;
        for (long off : offs) {
            if (rows >= 50) break;
            VoicebankFrames.Frame fr = VoicebankFrames.readFrame(f, off, VoicebankFrames.F0_MIN_HZ, VoicebankFrames.F0_MAX_HZ);
            if (fr == null) continue;
            double[] env = VoicebankCluster.frameEnvelope(fr.freqs, fr.magsDb);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < env.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format(java.util.Locale.US, "%.9e", env[i]));
            }
            w.println(sb.toString());
            rows++;
        }
        w.close(); f.close();
        System.out.println("java envelopes: " + rows);
    }
}