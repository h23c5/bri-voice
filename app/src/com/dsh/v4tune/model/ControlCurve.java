package com.dsh.v4tune.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** VOCALOID control-parameter curve: sorted (tick, value) points with linear interpolation. */
public class ControlCurve {
    public static class P {
        public long t;
        public double v;
        public P(long t, double v) { this.t = t; this.v = v; }
        public P copy() { return new P(t, v); }
    }

    public final ArrayList<P> pts = new ArrayList<P>();
    public double def = 64;
    public double min = 0, max = 127;

    public ControlCurve() {}
    public ControlCurve(double def, double min, double max) { this.def = def; this.min = min; this.max = max; }

    public boolean isEmpty() { return pts.isEmpty(); }

    public void sort() {
        Collections.sort(pts, new Comparator<P>() {
            public int compare(P a, P b) { return Long.compare(a.t, b.t); }
        });
    }

    public void add(long t, double v) {
        v = clamp(v);
        for (int i = 0; i < pts.size(); i++) {
            if (pts.get(i).t == t) { pts.get(i).v = v; return; }
            if (pts.get(i).t > t) { pts.add(i, new P(t, v)); return; }
        }
        pts.add(new P(t, v));
    }

    /**
     * Sets the value at {@code t}, reusing an existing point within {@code gap} ticks.
     *
     * <p>Without this a back-and-forth drag added one point per touch event: the events land on
     * neighbouring ticks, nothing merged, and the curve turned into a picket fence of hundreds of
     * points drawn on top of each other. Merging inside a couple of pixels keeps the point count
     * proportional to what the user can actually see.</p>
     *
     * @return true when an existing point was updated, false when a new one was inserted
     */
    public boolean putNear(long t, double v, long gap) {
        int best = -1;
        long bd = Long.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            long d = Math.abs(pts.get(i).t - t);
            if (d < bd) { bd = d; best = i; }
        }
        if (best >= 0 && bd <= Math.max(0L, gap)) {
            pts.get(best).v = clamp(v);
            return true;
        }
        add(t, v);
        return false;
    }

    /** insert/replace without clamping (used by the file reader) */
    public void putRaw(long t, double v) {
        for (int i = 0; i < pts.size(); i++) {
            if (pts.get(i).t == t) { pts.get(i).v = v; return; }
            if (pts.get(i).t > t) { pts.add(i, new P(t, v)); return; }
        }
        pts.add(new P(t, v));
    }

    /**
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

    public double clamp(double v) { return v < min ? min : (v > max ? max : v); }

    public void removeAt(int i) { if (i >= 0 && i < pts.size()) pts.remove(i); }

    public double valueAt(long t) {
        int n = pts.size();
        if (n == 0) return def;
        if (t <= pts.get(0).t) return pts.get(0).v;
        if (t >= pts.get(n - 1).t) return pts.get(n - 1).v;
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (pts.get(mid).t <= t) lo = mid; else hi = mid;
        }
        P a = pts.get(lo), b = pts.get(hi);
        if (b.t == a.t) return b.v;
        double f = (double) (t - a.t) / (double) (b.t - a.t);
        return a.v + (b.v - a.v) * f;
    }

    public ControlCurve copy() {
        ControlCurve c = new ControlCurve(def, min, max);
        for (P p : pts) c.pts.add(p.copy());
        return c;
    }

    /** Remove all points that cannot affect the audible result, keeps file size sane. */
    public void compact(double eps) {
        if (pts.size() < 3) return;
        ArrayList<P> out = new ArrayList<P>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            P a = out.get(out.size() - 1), b = pts.get(i), c = pts.get(i + 1);
            if (c.t == a.t) { out.add(b); continue; }
            double lin = a.v + (c.v - a.v) * (double) (b.t - a.t) / (double) (c.t - a.t);
            if (Math.abs(lin - b.v) > eps) out.add(b);
        }
        out.add(pts.get(pts.size() - 1));
        pts.clear();
        pts.addAll(out);
    }

    public List<P> points() { return pts; }
}
