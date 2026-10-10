# -*- coding: utf-8 -*-
"""[fix] a drag must update the nearest point instead of stacking a new one on every event,
   and the dots are only drawn when they would not overlap."""
import io
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"

# 1) the model grows a "merge into a nearby point" operation
c = root + r"\app\src\com\dsh\v4tune\model\ControlCurve.java"
s = io.open(c, encoding="utf-8").read()
if "public void putNear(" not in s:
    anchor = "    /** insert/replace without clamping (used by the file reader) */"
    s = s.replace(anchor, '''    /**
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

''' + anchor, 1)
    io.open(c, "w", encoding="utf-8", newline="").write(s)
    print("putNear added")

# 2) the pencil uses it
p = root + r"\app\src\com\dsh\v4tune\ui\ParamView.java"
t = io.open(p, encoding="utf-8").read()
old = '''    private void put(long t, double v) { if (part == null) return; ControlCurve c = part.ctrl(paramId);
        if (c.pts.isEmpty()) { c.min = vmin(); c.max = vmax(); c.def = vdef(); }
        c.putRaw(t, v);
        paintDirty = true;
    }'''
new = '''    private void put(long t, double v) { if (part == null) return; ControlCurve c = part.ctrl(paramId);
        if (c.pts.isEmpty()) { c.min = vmin(); c.max = vmax(); c.def = vdef(); }
        // reuse the point that is already within ~2.5 px instead of adding another one: dragging
        // back and forth over the same spot used to pile up hundreds of points
        long gap = (long) Math.max(1d, Math.round(dp(2.5f) / Math.max(0.02f, pxPerTick)));
        c.putNear(t, v, gap);
        paintDirty = true;
    }'''
assert old in t, "put()"
t = t.replace(old, new, 1)

# 3) skip a dot when the previous one is closer than its own diameter
old_dots = '''            for (int i = i0; i < n; i++) { ControlCurve.P p = cc.pts.get(i); if (p.t < t0) continue;
                if (p.t > t1) break;
                c.drawCircle(xOfTick(p.t), yOfV(top, bot, p.v), pr, ptP);
            }'''
new_dots = '''            float lastDotX = -1e9f;
            for (int i = i0; i < n; i++) { ControlCurve.P p = cc.pts.get(i); if (p.t < t0) continue;
                if (p.t > t1) break;
                float dx = xOfTick(p.t);
                if (dx - lastDotX < pr * 1.6f) continue;      // dots would merge into a smear
                c.drawCircle(dx, yOfV(top, bot, p.v), pr, ptP);
                lastDotX = dx;
            }'''
assert old_dots in t, "dot loop"
t = t.replace(old_dots, new_dots, 1)
io.open(p, "w", encoding="utf-8", newline="").write(t)
print("pencil + dots fixed")
