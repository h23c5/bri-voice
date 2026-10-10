import com.dsh.v4tune.ai.AiConfig;
import com.dsh.v4tune.ai.AiTuner;
import java.util.Map;

/** the real HTTP round trip against a stub endpoint (same code the app runs). */
public class AiClientTest {
    static int checks = 0, fails = 0;

    public static void main(String[] args) throws Exception {
        AiConfig cfg = new AiConfig();
        cfg.aiEnabled = true;
        cfg.baseUrl = args[0];
        cfg.model = "stub-model";
        cfg.apiKey = "sk-test";
        check("config is ready", cfg.ready());
        check("chat url built", cfg.chatUrl().equals(args[0] + "/chat/completions"));

        String reply = AiTuner.chat(cfg, "{\"notes\":[]}", 15000);
        System.out.println("reply: " + reply.replace('\n', ' '));
        check("reply arrived", reply != null && reply.length() > 0);

        Map<String, double[][]> curves = AiTuner.parseCurves(reply, 5760);
        System.out.println("parameters: " + curves.keySet());
        check("four parameters accepted", curves.size() == 4);
        check("DYN points", curves.containsKey("DYN") && curves.get("DYN").length == 5);
        check("VIB accepted", curves.containsKey("VIB"));
        check("BRE accepted", curves.containsKey("BRE"));
        check("PIT accepted", curves.containsKey("PIT"));

        AiConfig bad = new AiConfig();
        bad.baseUrl = "http://127.0.0.1:1/v1";
        boolean threw = false;
        try { AiTuner.chat(bad, "x", 3000); } catch (Throwable t) { threw = true; System.out.println("bad endpoint -> " + t.getMessage()); }
        check("a dead endpoint fails with a message", threw);

        System.out.println(fails == 0 ? ("ALL PASS (" + checks + " checks)") : (fails + " FAILURES"));
        if (fails > 0) System.exit(1);
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("  FAIL " + what); }
    }
}