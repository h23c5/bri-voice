import com.dsh.v4tune.voicebank.*;
import java.io.*;

public class JavaHarm {
    public static void main(String[] a) throws Exception {
        double[] grid = VoicebankPack.harmonicGrid(VoicebankPack.HARM_POINTS);
        File ddb = new File(a[0]);
        long[] offs = VoicebankFrames.scanFrm2Offsets(ddb, ddb.length(), 8L << 20);
        RandomAccessFile f = new RandomAccessFile(ddb, "r");
        PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(a[1])));
        w.println(fmt(grid));
        int n = 0;
        for (long off : offs) {
            if (n >= 20) break;
            VoicebankFrames.Frame fr = VoicebankFrames.readFrame(f, off, VoicebankFrames.F0_MIN_HZ, VoicebankFrames.F0_MAX_HZ);
            if (fr == null) continue;
            double[] logGrid = new double[grid.length];
            for (int i = 0; i < grid.length; i++) logGrid[i] = Math.log(grid[i]);
            w.println(fmt(VoicebankPack.frameHarmCurve(fr.freqs, fr.magsDb, logGrid)));
            n++;
        }
        w.close(); f.close();
        System.out.println("java rows: grid + " + n + " curves");
    }
    static String fmt(double[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(' '); sb.append(String.format(java.util.Locale.US, "%.9e", v[i])); }
        return sb.toString();
    }
}