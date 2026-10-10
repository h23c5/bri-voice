import com.dsh.v4tune.ai.AiTuner;
import com.dsh.v4tune.ai.Tuning;

/** the full tuning schema: curves + notes + tempo + track, always validated. */
public class AiTuningTest {
    static int checks = 0, fails = 0;

    public static void main(String[] args) {
        String reply = "ok!\n```json\n{\"curves\":{\"DYN\":[[0,70],[2000,130]]},"
                + "\"notes\":[{\"pos\":0,\"key\":64,\"vel\":90,\"dur\":480,\"lyric\":\"la\"},"
                + "{\"pos\":480,\"key\":999}],"
                + "\"tempo\":128.5,\"trackVolume\":100,\"trackPan\":200}\n```";
        Tuning t = AiTuner.parseTuning(reply, 1920);
        check("a curve came back", t.curves.containsKey("DYN") && t.curves.get("DYN").length == 2);
        check("DYN clipped to 127", t.curves.get("DYN")[1][1] == 127.0);
        check("two note edits", t.notes.size() == 2);
        check("note fields read", t.notes.get(0).key == 64 && t.notes.get(0).vel == 90
                && t.notes.get(0).dur == 480 && "la".equals(t.notes.get(0).lyric));
        check("an out-of-range key is clamped", t.notes.get(1).key == 127);
        check("tempo read", t.tempo != null && Math.abs(t.tempo - 128.5) < 1e-6);
        check("track volume read", t.trackVolume == 100);
        check("pan clamped to 127", t.trackPan == 127);
        check("summary mentions everything", t.summary().contains("params") && t.summary().contains("notes")
                && t.summary().contains("tempo") && t.summary().contains("track"));

        Tuning only = AiTuner.parseTuning("{\"tempo\": 90}", 1000);
        check("tempo-only answer accepted", only.tempo == 90.0 && only.curves.isEmpty());

        check("a silly tempo is ignored", AiTuner.parseTuning("{\"tempo\":9000,\"trackVolume\":10}", 100).tempo == null);
        check("empty answer rejected", rejects("{\"curves\":{}}"));
        check("prose rejected", rejects("sorry, I cannot"));
        check("notes without pos rejected", rejects("{\"notes\":[{\"key\":60}]}"));

        System.out.println(fails == 0 ? ("ALL PASS (" + checks + " checks)") : (fails + " FAILURES"));
        if (fails > 0) System.exit(1);
    }

    static boolean rejects(String reply) {
        try { AiTuner.parseTuning(reply, 1000); return false; } catch (IllegalArgumentException e) { return true; }
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("  FAIL " + what); }
    }
}