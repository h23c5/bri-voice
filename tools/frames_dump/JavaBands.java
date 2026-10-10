import com.dsh.v4tune.voicebank.*;
import java.io.*;
import java.util.*;

public class JavaBands {
    public static void main(String[] a) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(a[0]));
        String[] h = r.readLine().trim().split("\\s+");
        int n = Integer.parseInt(h[0]), m = Integer.parseInt(h[1]);
        double[][] curves = new double[n][m];
        for (int i = 0; i < n; i++) {
            String[] t = r.readLine().trim().split("\\s+");
            for (int j = 0; j < m; j++) curves[i][j] = Double.parseDouble(t[j]);
        }
        String[] ft = r.readLine().trim().split("\\s+");
        double[] f0s = new double[n];
        for (int i = 0; i < n; i++) f0s[i] = Double.parseDouble(ft[i]);
        r.close();
        PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(a[1])));
        List<VoicebankPack.Band> pb = VoicebankPack.pitchBands(curves, f0s);
        w.println("pitch_bands -> " + pb.size() + " bands");
        for (VoicebankPack.Band b : pb) w.println("  " + shape(b));
        int[] part = new int[n];
        for (int i = 0; i < n; i++) part[i] = i;
        w.println("band_curve " + shape(VoicebankPack.bandCurve(curves, f0s, Double.valueOf(0.5))));
        w.println("frame_sequence " + shape(VoicebankPack.frameSequence(curves, f0s, part, VoicebankPack.HARM_SEQ_FRAMES)));
        for (VoicebankPack.Band b : VoicebankPack.dynVariants(curves, f0s, part)) w.println("dyn_variants " + shape(b));
        w.close();
        System.out.println("java bands done");
    }
    /** %g like the Python side: 0.0 -> "0", 0.5 -> "0.5", 1.0 -> "1" */
    static String trim(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    static String shape(VoicebankPack.Band b) {
        if (b == null) return "null";
        StringBuilder lv = new StringBuilder();
        if (b.lvl != null) for (int i = 0; i < b.lvl.length; i++) { if (i > 0) lv.append(','); lv.append(String.format(Locale.US, "%.1f", b.lvl[i])); }
        return String.format(Locale.US, "f0=%s dyn=%s dbn=%d seqN=%d lvl=%s seq=%s",
                b.hasF0 ? String.format(Locale.US, "%.1f", b.f0) : "-",
                b.dyn == null ? "-" : trim(b.dyn.doubleValue()),
                b.db == null ? 0 : b.db.length, b.seqN, lv.toString(), b.seqB64 == null ? "-" : b.seqB64);
    }
}