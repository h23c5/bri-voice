import com.dsh.v4tune.voicebank.*;
import java.io.File;
import java.util.ArrayList;

public class JavaPack {
    public static void main(String[] a) throws Exception {
        VoicebankWriter w = new VoicebankWriter();
        w.id = "test_bank"; w.name = "Test"; w.nameEn = "Test"; w.nameJa = "Test";
        w.compID = "TESTID0000000000"; w.source = "synthetic"; w.notes = "self test";
        w.voice.put("formantScale", 1.037); w.voice.put("f1Mul", 1.0); w.voice.put("tiltBias", -0.06);
        for (int i = 0; i < 7; i++) w.shelf.add(new double[]{60.0 * Math.pow(2, i), -5.2 + i * 0.3});
        w.formants.put("a", new int[]{800, 1200, 2800}); w.formantCounts.put("a_n", 120);
        w.stats.put("frames", Integer.valueOf(39707));
        w.stats.put("f0Median", Double.valueOf(329.0));
        w.stats.put("clusters", Integer.valueOf(48));
        w.harmFreqs = VoicebankPack.harmonicGrid(VoicebankPack.HARM_POINTS);
        int NF = 900;   // big enough that a band holds >= 80 frames, i.e. a real sequence
        double[][] curves = new double[NF][VoicebankPack.HARM_POINTS];
        double[] f0s = new double[NF];
        for (int i = 0; i < NF; i++) {
            f0s[i] = 200 + 40 * Math.sin(i * 0.2);
            for (int j = 0; j < 64; j++) curves[i][j] = -20 - 0.5 * j + 3 * Math.sin(j * 0.3 + i * 0.1);
        }
        ArrayList<VoicebankPack.Band> bands = VoicebankPack.pitchBands(curves, f0s);
        w.phonemes.put("a", bands);
        w.noise.put("f", new double[][]{w.harmFreqs, new double[64]});
        w.writeZip(new File(a[0]), null);
        System.out.println("bands=" + bands.size());
    }
}