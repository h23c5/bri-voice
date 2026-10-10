import com.dsh.v4tune.io.ProjectIO;
import com.dsh.v4tune.model.*;
import java.io.File;

/** writes a project whose notes overlap, to exercise the pre-play check */
public class MakeOverlap {
    public static void main(String[] args) throws Exception {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        long[][] n = {{0, 960, 60}, {480, 960, 62}, {1920, 480, 64}, {1920, 480, 65}, {2880, 960, 67}};
        String[] ly = {"luo", "tian", "yi", "la", "la"};
        for (int i = 0; i < n.length; i++) {
            Note x = new Note();
            x.pos = n[i][0]; x.dur = n[i][1]; x.key = (int) n[i][2]; x.lyric = ly[i];
            p.notes.add(x);
        }
        p.dur = 4800;
        File f = new File(args[0]);
        ProjectIO.savePath(f, s);
        System.out.println("wrote " + f + " (" + f.length() + " bytes), issues=" + NoteCheck.countIssues(s));
    }
}
