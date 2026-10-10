import com.dsh.v4tune.format.MidiReader;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;

import java.io.File;

/** Count what MidiReader actually produced for a real file. */
public class MidiProbe {
    public static void main(String[] args) throws Exception {
        for (String p : args) {
            Song s = MidiReader.read(new java.io.FileInputStream(new File(p)));
            int notes = 0, parts = 0, withNotes = 0;
            for (Track t : s.tracks) {
                int tn = 0;
                for (Part pt : t.parts) { parts++; tn += pt.notes.size(); }
                if (tn > 0) withNotes++;
                notes += tn;
                System.out.printf("  track %d '%s': parts=%d notes=%d%n", t.no, t.name, t.parts.size(), tn);
            }
            System.out.printf("%s -> tracks=%d parts=%d tracksWithNotes=%d notes=%d tempos=%d%n",
                    new File(p).getName(), s.tracks.size(), parts, withNotes, notes, s.tempos.size());
        }
    }
}
