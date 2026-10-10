import com.dsh.v4tune.model.*;
import com.dsh.v4tune.format.VsqxWriter;
import com.dsh.v4tune.phoneme.ChineseG2P;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/**
 * VOCALOID4 Editor refused files exported from the phone: "Verify error ... CVSMusicalNote".
 * Two reasons are checked here: a note must carry a non-empty phoneme and the full style block.
 */
public class VsqxNoteFixTest {
    static int checks = 0, fails = 0;

    public static void main(String[] a) throws Exception {
        String dict = a[0];
        ChineseG2P.init(new java.util.zip.GZIPInputStream(new FileInputStream(dict)));
        System.out.println("G2P ready: " + ChineseG2P.isReady());
        Song s = Song.empty();
        Part p = s.tracks.get(0).parts.get(0);
        p.notes.clear();
        String[] ly = {"luo", "yi", "a", "xiao", "xing", "xing"};
        int[] keys = {60, 62, 64, 65, 67, 69};
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
        File out = File.createTempFile("bri", ".vsqx");
        VsqxWriter.writeFile(s, out);
        String xml = new String(readAll(out), StandardCharsets.UTF_8);
        boolean v4 = xml.contains("schema/vsq4");
        System.out.println("exported as " + (v4 ? "vsq4" : "vsq3") + ", " + xml.length() + " chars");
        Matcher m = Pattern.compile("<note>.*?</note>", Pattern.DOTALL).matcher(xml);
        int notes = 0;
        String first = null;
        while (m.find()) {
            notes++;
            String note = m.group();
            if (first == null) first = note.replaceAll("\\s+", " ").trim();
            String phTag = v4 ? "<p" : "<phnms";
            Matcher pm = Pattern.compile(phTag + "[^>]*>(.*?)</" + (v4 ? "p" : "phnms") + ">", Pattern.DOTALL).matcher(note);
            boolean hasPh = pm.find();
            String phText = hasPh ? pm.group(1).replaceAll("<!\\[CDATA\\[|\\]\\]>", "").trim() : "";
            check((v4 ? "<p>" : "<phnms>") + " present", hasPh);
            check("phoneme not empty", !phText.isEmpty());
            String styleTag = v4 ? "nStyle" : "noteStyle";
            check(styleTag + " block present", note.contains("<" + styleTag + ">"));
            int attrs = 0;
            Matcher am = Pattern.compile(v4 ? "<v id=" : "<attr id=").matcher(note);
            while (am.find()) attrs++;
            check(styleTag + " has the 9 standard attributes", attrs >= 9);
            if (fails > 0) { System.out.println("  offending note: " + note.replaceAll("\\s+", " ").trim()); break; }
        }
        System.out.println("notes checked: " + notes);
        System.out.println("first note: " + first);
        System.out.println(fails == 0 ? ("ALL PASS (" + checks + " checks)") : ("FAILED " + fails + " of " + checks));
        out.delete();
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("  FAIL " + what); }
    }

    static byte[] readAll(File f) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        InputStream in = new FileInputStream(f);
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }
}