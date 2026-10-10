import com.dsh.v4tune.voicebank.*;
import java.io.*;
import java.util.*;

public class JavaPick {
    public static void main(String[] a) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(a[0]));
        PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(a[1])));
        String line, name = null; double[] c = null, s = null; double f0 = 0;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("CASE ")) { name = line.substring(5).trim(); }
            else if (line.startsWith("CENTROID ")) { c = parse(line); }
            else if (line.startsWith("SHELF ")) { s = parse(line); }
            else if (line.startsWith("F0MED ")) {
                f0 = Double.parseDouble(line.substring(6).trim());
                double[] out = VoicebankCluster.pickFormants(c, s, f0);
                StringBuilder sb = new StringBuilder(name).append(" -> ");
                if (out == null) sb.append("NULL");
                else for (int i = 0; i < 3; i++) { if (i > 0) sb.append(' '); sb.append(String.format(Locale.US, "%.9e", out[i])); }
                w.println(sb.toString());
            }
        }
        w.close(); r.close();
        // determinism: cluster the same matrix twice and compare
        double[][] X = new double[120][VoicebankCluster.ENV_POINTS];
        for (int i = 0; i < X.length; i++)
            for (int j = 0; j < X[i].length; j++)
                X[i][j] = Math.sin(i * 0.11 + j * 0.07) * 12 + Math.cos(i * 0.31) * 4 - 40;
        VoicebankCluster.Clusters c1 = VoicebankCluster.kmeans(X, 12, 40);
        VoicebankCluster.Clusters c2 = VoicebankCluster.kmeans(X, 12, 40);
        boolean same = true;
        for (int i = 0; i < 12 && same; i++)
            for (int j = 0; j < X[0].length; j++)
                if (c1.centroids[i][j] != c2.centroids[i][j]) { same = false; break; }
        System.out.println("determinism: centroids identical = " + same);
        System.out.println("labels identical = " + Arrays.equals(c1.labels, c2.labels));
    }
    static double[] parse(String line) {
        String[] t = line.substring(line.indexOf(' ') + 1).trim().split("\\s+");
        double[] v = new double[t.length];
        for (int i = 0; i < t.length; i++) v[i] = Double.parseDouble(t[i]);
        return v;
    }
}