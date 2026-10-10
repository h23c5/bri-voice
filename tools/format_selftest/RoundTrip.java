import com.dsh.v4tune.model.*;
import com.dsh.v4tune.io.ProjectIO;
import java.io.File;

/** loads a project with the app's reader and writes it back with the app's writer */
public class RoundTrip {
    public static void main(String[] a) throws Exception {
        Song s = ProjectIO.openPath(new File(a[0]));
        System.out.println("tracks=" + s.tracks.size() + " voices=" + s.voices.size()
                + " notes=" + countNotes(s) + " ticks=" + s.tracks.get(0).parts.get(0).dur);
        ProjectIO.savePath(new File(a[1]), s);
        System.out.println("wrote " + a[1] + "  " + new File(a[1]).length() + " bytes");
    }

    static int countNotes(Song s) {
        int n = 0;
        for (Track t : s.tracks) for (Part p : t.parts) n += p.notes.size();
        return n;
    }
}