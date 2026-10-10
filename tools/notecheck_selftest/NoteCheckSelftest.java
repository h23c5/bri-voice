import com.dsh.v4tune.io.LyricFill;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.NoteCheck;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;

import java.util.List;

/** Overlap detection/normalisation and the pasted-lyrics splitter. */
public class NoteCheckSelftest {

    static int checks = 0, fails = 0;

    public static void main(String[] args) {
        lyricSplit();
        overlapBasics();
        duplicates();
        normalizeSong();
        System.out.println(fails == 0 ? ("ALL PASS (" + checks + " checks)")
                : (fails + " FAILURES of " + checks));
        if (fails > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- lyrics
    static void lyricSplit() {
        LyricFill.Hanzi hanzi = new LyricFill.Hanzi() {
            public String convert(char c) {
                if (c == '\u6d1b') return "luo";       // 洛
                if (c == '\u5929') return "tian";       // 天
                if (c == '\u4f9d') return "yi";         // 依
                return null;
            }
        };
        List<String> a = LyricFill.split("luo tian yi la la", hanzi);
        eq("latin words", 5, a.size());
        eq("first word", "luo", a.get(0));
        eq("last word", "la", a.get(4));

        List<String> b = LyricFill.split("\u6d1b\u5929\u4f9d", hanzi);
        eq("hanzi per char", 3, b.size());
        eq("hanzi[0]", "luo", b.get(0));
        eq("hanzi[2]", "yi", b.get(2));

        List<String> c = LyricFill.split("\u6d1b\u5929, yi. la/la", hanzi);
        eq("mixed separators", 5, c.size());
        eq("mixed[1]", "tian", c.get(1));

        List<String> d = LyricFill.split("  \n\t ", hanzi);
        eq("blank -> empty", 0, d.size());

        List<String> e = LyricFill.split("Luo  TIAN", hanzi);
        eq("lower cased", "luo", e.get(0));
        eq("lower cased 2", "tian", e.get(1));

        List<String> f = LyricFill.split("\u3042\u3044", hanzi);   // kana stay as syllables
        eq("kana", 2, f.size());
        eq("kana[0]", "\u3042", f.get(0));
    }

    // ---------------------------------------------------------------- overlap
    static Part part(long[][] notes) {
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        for (long[] n : notes) {
            Note x = new Note();
            x.pos = n[0];
            x.dur = n[1];
            x.lyric = "a";
            p.notes.add(x);
        }
        return p;
    }

    static void overlapBasics() {
        Part p = part(new long[][]{{0, 960}, {960, 960}});
        eq("touching notes are fine", 0, NoteCheck.issues(p).size());

        p = part(new long[][]{{0, 1200}, {960, 480}});
        List<NoteCheck.Issue> is = NoteCheck.issues(p);
        eq("overlap found", 1, is.size());
        eq("overlap not duplicate", false, is.get(0).duplicate);
        eq("reported at the later tick", 960L, is.get(0).at);

        p = part(new long[][]{{0, 480}, {480, 480}, {960, 480}});
        eq("three in a row ok", 0, NoteCheck.issues(p).size());
    }

    static void duplicates() {
        Part p = part(new long[][]{{480, 480}, {480, 960}});
        List<NoteCheck.Issue> is = NoteCheck.issues(p);
        eq("same tick detected", 1, is.size());
        eq("same tick is a duplicate", true, is.get(0).duplicate);
        eq("duplicate reported at that tick", 480L, is.get(0).at);
    }

    static void normalizeSong() {
        // {0..1200} overlaps {960..1440}: trim the first to 960.  {1200..1680} starts inside the
        // second note, so that one is trimmed to 240 as well -> two fixes.
        Part p = part(new long[][]{{0, 1200}, {960, 480}, {1200, 480}});
        p.notes.get(2).pos = 1200;
        int changed = NoteCheck.normalize(null, p);
        eq("two fixes (trim + trim)", 2, changed);
        eq("no issues left", 0, NoteCheck.issues(p).size());
        eq("first note trimmed to the next start", 960L, p.notes.get(0).dur);
        eq("second note trimmed too", 240L, p.notes.get(1).dur);

        Part d = part(new long[][]{{480, 480}, {480, 960}, {2000, 200}});
        int ch = NoteCheck.normalize(null, d);
        eq("duplicate removed", 1, ch);
        eq("longer note kept", 2, d.notes.size());
        eq("no issues left after dedup", 0, NoteCheck.issues(d).size());

        Song s = Song.empty();
        s.tracks.get(0).parts.get(0).notes.clear();
        Note a = new Note(); a.pos = 0; a.dur = 480; a.lyric = "a";
        Note b = new Note(); b.pos = 100; b.dur = 480; b.lyric = "i";
        s.tracks.get(0).parts.get(0).notes.add(a);
        s.tracks.get(0).parts.get(0).notes.add(b);
        eq("song-level count", 1, NoteCheck.countIssues(s));
        eq("song-level normalize", 1, NoteCheck.normalize(s, null));
        eq("song-level clean", 0, NoteCheck.countIssues(s));
    }

    static void eq(String what, Object want, Object got) {
        checks++;
        boolean ok = (want == null) ? got == null : want.equals(got);
        if (!ok) {
            fails++;
            System.out.println("  FAIL " + what + ": want " + want + " got " + got);
        }
    }
}
