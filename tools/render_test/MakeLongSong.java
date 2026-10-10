import com.dsh.v4tune.io.ProjectIO;
import com.dsh.v4tune.model.*;
import java.io.File;

/** a long song, to show that playback now starts after the first slice */
public class MakeLongSong {
    public static void main(String[] args) throws Exception {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        String[] ly = {"zhe", "li", "shi", "Bri", "Voice", "huan", "ying", "hui", "lai"};
        int[] keys = {60, 62, 64, 65, 67, 69, 67, 65, 64};
        int n = 200;
        for (int i = 0; i < n; i++) {
            Note nt = new Note();
            nt.pos = 480 + i * 480L;
            nt.dur = 420;
            nt.key = keys[i % keys.length];
            nt.vel = 64;
            nt.lyric = ly[i % ly.length];
            p.notes.add(nt);
        }
        p.dur = 480 + n * 480L;
        File f = new File(args[0]);
        ProjectIO.savePath(f, s);
        System.out.println("wrote " + f + " notes=" + n + " durTicks=" + p.dur
                + " (~" + Math.round(p.dur / 480.0 * 0.5) + " s at 120bpm)");
    }
}