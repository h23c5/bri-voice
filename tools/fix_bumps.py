# -*- coding: utf-8 -*-
"""[fix] remove the leftover jitter bumps with a Ramer-Douglas-Peucker pass on stroke end."""
import io
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"

c = root + r"\app\src\com\dsh\v4tune\model\ControlCurve.java"
s = io.open(c, encoding="utf-8").read()
if "public int simplify(" not in s:
    anchor = "    public double clamp(double v)"
    s = s.replace(anchor, '''    /**
     * Drops points that sit within {@code eps} of the straight line between their surviving
     * neighbours (Ramer-Douglas-Peucker).
     *
     * <p>{@link #compact} only merges points whose *values* are close, so a jittery drag still left
     * small bumps behind: neighbouring points wobble by more than the merge threshold while still
     * hugging the same line. This pass measures the deviation from the line itself, which is what
     * the eye sees, and keeps every real corner.</p>
     *
     * @return the number of points removed
     */
    public int simplify(double eps) {
        int n = pts.size();
        if (n < 3 || eps <= 0) return 0;
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        int[] stack = new int[2 * n];
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = n - 1;
        while (sp >= 2) {
            int b = stack[--sp];
            int a = stack[--sp];
            if (b - a < 2) continue;
            double tb = pts.get(b).t, vb = pts.get(b).v;
            double ta = pts.get(a).t, va = pts.get(a).v;
            double dt = tb - ta;
            double worst = -1;
            int wi = -1;
            for (int i = a + 1; i < b; i++) {
                double f = (dt == 0) ? 0 : (pts.get(i).t - ta) / dt;
                double line = va + (vb - va) * f;
                double d = Math.abs(pts.get(i).v - line);
                if (d > worst) { worst = d; wi = i; }
            }
            if (wi > 0 && worst > eps) {
                keep[wi] = true;
                stack[sp++] = a;
                stack[sp++] = wi;
                stack[sp++] = wi;
                stack[sp++] = b;
            }
        }
        int removed = 0;
        for (int i = n - 1; i >= 0; i--) {
            if (!keep[i]) { pts.remove(i); removed++; }
        }
        return removed;
    }

''' + anchor, 1)
    io.open(c, "w", encoding="utf-8", newline="").write(s)
    print("simplify added")

p = root + r"\app\src\com\dsh\v4tune\ui\ParamView.java"
t = io.open(p, encoding="utf-8").read()
old = '''        if (part != null) { ControlCurve c = part.ctrlOrNull(paramId);
            if (c != null) { c.sort(); c.compact((vmax() - vmin()) / 300.0); }
        }'''
new = '''        if (part != null) { ControlCurve c = part.ctrlOrNull(paramId);
            if (c != null) {
                c.sort();
                c.compact((vmax() - vmin()) / 300.0);
                // then straighten out the wobble the finger left behind: ~1 unit on a 0..127 scale,
                // i.e. about one pixel on screen, so real corners survive and jitter does not
                c.simplify((vmax() - vmin()) / 110.0);
            }
        }'''
assert old in t, "finishStroke"
t = t.replace(old, new, 1)
io.open(p, "w", encoding="utf-8", newline="").write(t)
print("stroke simplification wired")
