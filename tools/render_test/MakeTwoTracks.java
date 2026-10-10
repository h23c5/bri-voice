import com.dsh.v4tune.io.ProjectIO;
import com.dsh.v4tune.model.*;
import java.io.File;

/** two tracks that should sound together */
public class MakeTwoTracks {
    public static void main(String[] args) throws Exception {
        Song s = Song.empty();
        s.tracks.clear();
        for (int ti = 0; ti < 2; ti++) {
            Track t = new Track();
            t.no = ti + 1;
            t.name = "Track " + (ti + 1);
            Part p = new Part();
            long[] pos = {0, 960, 1920, 2880};
            String[] ly = {"luo", "tian", "yi", "la"};
            for (int i = 0; i < 4; i++) {
                Note n = new Note();
                n.pos = pos[i];
                n.dur = 720;
                n.key = 60 + ti * 4 + (i % 3);
                n.lyric = ly[i];
                p.notes.add(n);
            }
            p.dur = 3840;
            t.parts.add(p);
            s.tracks.add(t);
        }
        File f = new File(args[0]);
        ProjectIO.savePath(f, s);
        System.out.println("wrote " + f + " tracks=" + s.tracks.size()
                + " notes=" + (s.tracks.get(0).parts.get(0).notes.size() + s.tracks.get(1).parts.get(0).notes.size())
                + " voices=" + s.voices.size() + " maxTick=" + s.maxTick());
        for (Voice v : s.voices) System.out.println("  voice bs=" + v.bs + " pc=" + v.pc + " name=" + v.name + " compID=" + v.compID);
    }
}
