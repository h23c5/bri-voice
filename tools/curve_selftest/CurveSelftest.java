import com.dsh.v4tune.model.ControlCurve;

/** simplify() must flatten drag jitter and keep real corners. */
public class CurveSelftest {
    static int checks = 0, fails = 0;

    public static void main(String[] args) {
        ControlCurve c = new ControlCurve(64, 0, 127);
        c.putNear(1000, 64, 10);
        c.putNear(1004, 80, 10);
        eq("one point after two near writes", 1, c.pts.size());
        eq("value updated", 80.0, c.pts.get(0).v);

        ControlCurve z = new ControlCurve(64, 0, 127);
        for (int i = 0; i < 200; i++) z.putNear(500 + (i % 12), 30 + (i % 7) * 10, 10);
        check("zigzag stays bounded", z.pts.size() <= 3);

        // a straight-ish drag with 1 unit of jitter collapses to its two ends
        ControlCurve j = new ControlCurve(64, 0, 127);
        for (int i = 0; i <= 20; i++) j.add(i * 50L, 60 + (i % 2 == 0 ? 1 : -1));
        int before = j.pts.size();
        int removed = j.simplify(2.0);
        System.out.println("jitter: " + before + " -> " + j.pts.size() + " points");
        check("jitter flattened", j.pts.size() <= 3);
        check("removed counted", removed >= 18);

        // a real peak must survive
        ControlCurve k = new ControlCurve(64, 0, 127);
        for (int i = 0; i <= 20; i++) k.add(i * 50L, 40 + (i == 10 ? 40 : 0));
        k.simplify(2.0);
        // a flat line with one spike is not a straight segment: RDP keeps a few points to trace it,
        // but the spike value must survive exactly
        check("spike approximated", k.pts.size() >= 3 && k.pts.size() <= 8);
        double peak = 0;
        for (int i = 0; i < k.pts.size(); i++) peak = Math.max(peak, k.pts.get(i).v);
        eq("peak value kept", 80.0, peak);

        // two corners survive as well
        ControlCurve m = new ControlCurve(64, 0, 127);
        for (int i = 0; i <= 30; i++) m.add(i * 40L, i < 15 ? 40 + i : 70 - (i - 15));
        m.simplify(2.0);
        check("triangle kept", m.pts.size() >= 3 && m.pts.size() <= 5);

        System.out.println(fails == 0 ? ("ALL PASS (" + checks + " checks)") : (fails + " FAILURES"));
        if (fails > 0) System.exit(1);
    }

    static void eq(String what, Object want, Object got) {
        checks++;
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) { fails++; System.out.println("  FAIL " + what + ": want " + want + " got " + got); }
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("  FAIL " + what); }
    }
}
