import com.dsh.v4tune.model.*;
import com.dsh.v4tune.format.VsqxWriter;
import com.dsh.v4tune.phoneme.ChineseG2P;
import java.io.*;

/** exports a small project with the current writer so the XML can be compared with a real V4 file */
public class ExportSample {
    public static void main(String[] a) throws Exception {
        ChineseG2P.init(new java.util.zip.GZIPInputStream(new FileInputStream(a[1])));
        Song s = Song.empty();
        Track t = s.tracks.get(0);
        t.name = "Track 1";
        Part p = t.parts.get(0);
        p.notes.clear();
        p.name = "Sample";
        String[] ly = {"luo", "yi", "a"};
        int[] keys = {60, 62, 64};
        for (int i = 0; i < ly.length; i++) {
            Note n = new Note();
            n.pos = 480 + i * 480L;
            n.dur = 440;
            n.key = keys[i];
            n.vel = 64;
            n.lyric = ly[i];
            p.notes.add(n);
        }
        p.dur = 480 + ly.length * 480L;
        VsqxWriter.writeFile(s, new File(a[0]));
        System.out.println("wrote " + a[0] + " (" + new File(a[0]).length() + " bytes)");
    }
}