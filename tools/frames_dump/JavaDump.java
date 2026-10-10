import com.dsh.v4tune.voicebank.VoicebankFrames;
import java.io.*;

public class JavaDump {
    public static void main(String[] a) throws Exception {
        File ddb = new File(a[0]);
        long[] offs = VoicebankFrames.scanFrm2Offsets(ddb, ddb.length(), 8L << 20);
        RandomAccessFile f = new RandomAccessFile(ddb, "r");
        PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(a[1])));
        int n = 0;
        for (long off : offs) {
            if (n >= 200) break;
            VoicebankFrames.Frame fr = VoicebankFrames.readFrame(f, off, VoicebankFrames.F0_MIN_HZ, VoicebankFrames.F0_MAX_HZ);
            if (fr == null) { w.println(off + " SKIP"); n++; continue; }
            double s = 0;
            for (double m : fr.magsDb) s += m;
            w.println(String.format(java.util.Locale.US, "%d %.9f %d %.9g %.6f", off, fr.f0, fr.freqs.length, fr.spacingErr, s));
            n++;
        }
        w.close(); f.close();
        System.out.println("java: offsets=" + offs.length + " dumped=" + n);
    }
}