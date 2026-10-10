import com.dsh.v4tune.voicebank.*;
import java.io.*;
import java.util.*;

public class JavaNoise {
    public static void main(String[] a) throws Exception {
        File ddb = new File(a[0]);
        VoicebankNoise.Scan s = VoicebankNoise.scanSndOffsets(ddb, ddb.length(), 64L << 20);
        double[] grid = VoicebankPack.harmonicGrid(VoicebankPack.HARM_POINTS);
        double[] logGrid = new double[grid.length];
        for (int i = 0; i < grid.length; i++) logGrid[i] = Math.log(grid[i]);
        RandomAccessFile f = new RandomAccessFile(ddb, "r");
        short[] pcm = VoicebankNoise.readPcm(f, s.offsets[0]);
        f.close();
        VoicebankNoise.Noise n = VoicebankNoise.sndAnalysis(pcm, logGrid, VoicebankNoise.NOISE_SMOOTH);
        PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(a[1])));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.curve.length; i++) { if (i > 0) sb.append(' '); sb.append(String.format(Locale.US, "%.9e", n.curve[i])); }
        w.println(sb.toString());
        w.println(String.format(Locale.US, "centroid=%.6f bandwidth=%.6f flatness=%.9e level=%.6f samples=%d", n.centroid, n.bandwidth, n.flatness, n.level, n.samples));
        w.close();
        System.out.println("java ok, samples=" + pcm.length);
    }
}