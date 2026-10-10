import com.dsh.v4tune.voicebank.*;
import java.io.*;

public class JavaSnd {
    public static void main(String[] a) throws Exception {
        File ddb = new File(a[0]);
        VoicebankNoise.Scan s = VoicebankNoise.scanSndOffsets(ddb, ddb.length(), 0);
        System.out.println("offsets=" + s.offsets.length + " bytesRead=" + s.bytesRead + " expected=" + a[1]);
        RandomAccessFile f = new RandomAccessFile(ddb, "r");
        short[] pcm = VoicebankNoise.readPcm(f, s.offsets[0]);
        f.close();
        long e = 0;
        for (short v : pcm) e += (long) v * v;
        System.out.println("first record samples=" + pcm.length + " rms=" + (long) Math.sqrt(e / (double) pcm.length));
    }
}