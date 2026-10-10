import com.dsh.v4tune.voicebank.*;
import java.io.*;
import java.util.*;

public class JavaClassify {
    public static void main(String[] a) throws Exception {
        double[][] r = {
            {60, 5200, 2600, 0.30, -30}, {40, 3000, 1800, 0.25, -28}, {30, 3000, 900, 0.20, -26},
            {50, 2600, 700, 0.15, -25}, {70, 2000, 1500, 0.40, -27}, {55, 1000, 900, 0.22, -24},
            {80, 900, 600, 0.35, -60}, {10, 3000, 500, 0.30, -20}, {60, 3000, 800, 0.005, -22},
        };
        List<VoicebankNoise.ClassStat> cs = new ArrayList<VoicebankNoise.ClassStat>();
        for (double[] x : r) cs.add(new VoicebankNoise.ClassStat((int) x[0], x[1], x[2], x[3], x[4]));
        double[] lvl = new double[1];
        Map<String, Integer> named = VoicebankNoise.classifyNoiseClasses(cs, lvl);
        PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(a[0])));
        w.println(String.format(Locale.US, "lvl_med=%.6f", lvl[0]));
        List<String> keys = new ArrayList<String>(named.keySet());
        Collections.sort(keys);
        for (String k : keys) w.println(k + "=" + named.get(k));
        w.close();
        System.out.println("java done");
    }
}