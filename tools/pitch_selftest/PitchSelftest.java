import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.ui.PitchMath;

/** Desktop checks for the v12.3.1 pitch-line maths and the two-axis point drag. */
public class PitchSelftest {

    private static int pass = 0, fail = 0;

    private static void eq(String what, Object got, Object want) {
        boolean ok = (got == null) ? want == null : got.equals(want);
        if (ok) { pass++; }
        else { fail++; System.out.println("FAIL " + what + ": got " + got + ", want " + want); }
    }

    public static void main(String[] a) {
        // ---- snap
        eq("snap off keeps 149", PitchMath.snap(149.0, false), 149.0);
        eq("snap on floors to 100", PitchMath.snap(149.0, true), 100.0);
        eq("snap on rounds up to 200", PitchMath.snap(151.0, true), 200.0);
        eq("snap on exact stays", PitchMath.snap(-300.0, true), -300.0);
        eq("snap on zero", PitchMath.snap(0.0, true), 0.0);

        // ---- formatting (semitones, trimmed)
        eq("0", PitchMath.cents(0.0), "0");
        eq("+1 semitone", PitchMath.cents(100.0), "+1");
        eq("+1.5 semitones", PitchMath.cents(150.0), "+1.5");
        eq("-20.5 semitones", PitchMath.cents(-2050.0), "-20.5");
        eq("+24 semitones", PitchMath.cents(2400.0), "+24");
        eq("half a cent still zero", PitchMath.cents(0.4), "0");
        eq("5 cents keeps precision", PitchMath.cents(5.0), "+0.05");
        eq("negative keeps sign", PitchMath.cents(-1.0), "-0.01");
        eq("badge label", PitchMath.label(150.0), "+1.5 st");
        eq("on semitone true", PitchMath.onSemitone(-1200.0), true);
        eq("on semitone false", PitchMath.onSemitone(1205.0), false);

        // ---- two-axis drag: moving a point past its neighbour must re-sort and keep the value
        ControlCurve c = new ControlCurve(0.0, -2400.0, 2400.0);
        c.add(0, 0);
        c.add(480, 0);
        c.add(960, 0);
        ControlCurve.P dragged = c.pts.get(0);   // the one at tick 0
        eq("drag start order", c.pts.get(0).t, 0L);

        // emulate the drag: move it to tick 1200 (past both neighbours), then sort like ACTION_UP
        dragged.t = 1200;
        dragged.v = PitchMath.snap(149.0, true);
        c.sort();
        eq("sorted after drag", c.pts.get(c.pts.size() - 1).t, 1200L);
        eq("object survived sort", c.pts.get(c.pts.size() - 1) == dragged, true);
        eq("snapped value kept", dragged.v, 100.0);
        eq("interpolation at 1020", c.valueAt(1020), 25.0);   // 1/4 of the way from 960:0 to 1200:100
        eq("flat stretch at 600", c.valueAt(600), 0.0);        // between 480:0 and 960:0
        eq("count unchanged", c.pts.size(), 3);

        // dragged in place, no duplicate insertion side effects
        dragged.t = 480;
        c.sort();
        eq("back in the middle", c.pts.get(1) == dragged, true);

        System.out.println("=== pitch selftest: " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }
}
