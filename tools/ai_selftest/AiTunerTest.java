import com.dsh.v4tune.ai.AiTuner;
import java.util.Map;

/** the AI reply is never trusted: everything is parsed, clipped and validated. */
public class AiTunerTest {
    static int checks = 0, fails = 0;

    public static void main(String[] args) {
        String wrapped = "Sure! Here is the tuning:\n```json\n"
                + "{\"curves\":{\"DYN\":[[0,70],[960,55],[2000,130]],\"PIT\":[[0,0],[480,-40]],\"BOGUS\":[[0,1]]}}\n```\nEnjoy!";
        Map<String, double[][]> c = AiTuner.parseCurves(wrapped, 1920);
        check("two usable parameters", c.size() == 2);
        check("unknown parameter dropped", !c.containsKey("BOGUS"));
        check("DYN has three points", c.get("DYN").length == 3);
        check("value clipped to 127", c.get("DYN")[2][1] == 127.0);
        check("tick clipped to the part", c.get("DYN")[2][0] == 1920.0);
        check("PIT accepted", c.containsKey("PIT"));

        Map<String, double[][]> flat = AiTuner.parseCurves("{\"DYN\":[[10,20]]}", 1000);
        check("flat answer accepted", flat.containsKey("DYN") && flat.get("DYN").length == 1);

        check("prose without json rejected", rejects("I cannot help with that."));
        check("empty curves rejected", rejects("{\"curves\":{}}"));
        check("garbage rejected", rejects("{\"curves\":{\"DYN\":[]}}"));

        String deep = AiTuner.extractJson("a { b } then {\"x\":{\"y\":1}} end");
        check("extractJson finds the first complete object", deep != null && deep.equals("{\"x\":{\"y\":1}}"));

        String prompt = AiTuner.systemPrompt();
        check("prompt lists the parameters", prompt.contains("DYN") && prompt.contains("PIT"));
        check("prompt states the ranges", prompt.contains("2400") && prompt.contains("127"));

        System.out.println(fails == 0 ? ("ALL PASS (" + checks + " checks)") : (fails + " FAILURES"));
        if (fails > 0) System.exit(1);
    }

    static boolean rejects(String reply) {
        try { AiTuner.parseCurves(reply, 1000); return false; } catch (IllegalArgumentException e) { return true; }
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("  FAIL " + what); }
    }
}