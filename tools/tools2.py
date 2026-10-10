# -*- coding: utf-8 -*-
"""[v3.2] touch handling for the pencil / line / select tools."""
import io
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"
p = root + r"\app\src\com\dsh\v4tune\ui\ParamView.java"
s = io.open(p, encoding="utf-8").read()

# preview colour must not depend on a local of drawArea
s = s.replace("curveP.setColor(Theme.lighten(base(), 0.1f));",
              "curveP.setColor(Theme.ACCENT2);", 1)

# ---- ACTION_DOWN: the tool bar wins, then the tool decides --------------------
old_down = "        if (y < chipH) { mode = MODE_CHIP; chipHit = chipAt(x);"
new_down = """        if (y >= toolTop && y <= toolBot && (toolHit = toolAt(x, y)) >= 0) {
            toolPressed = toolHit;
            mode = MODE_INERT;
            performClick();
            return true;
        }
        if (tool == TOOL_SELECT) {
            selIdx = nearestPoint(x, y, top, bot);
            if (selIdx >= 0) {
                mode = MODE_DRAGPT;
                cancelPress();
                invalidate();
            } else {
                mode = MODE_INERT;
            }
            return true;
        }
        if (tool == TOOL_LINE) {
            lineT0 = lineT1 = clampTick((long) tickAtX(x));
            lineV0 = lineV1 = clampV(vAtY(top, bot, y));
            linePreview = true;
            mode = MODE_LINE;
            invalidate();
            return true;
        }
        if (y < chipH) { mode = MODE_CHIP; chipHit = chipAt(x);"""
assert old_down in s, "down"
s = s.replace(old_down, new_down, 1)

# ---- ACTION_MOVE -------------------------------------------------------------
old_move = """                if (mode == MODE_PAINT) {
                    if (!moved) { if (Math.abs(x - downX) <= slop && Math.abs(y - downY) <= slop) return true;
                        moved = true;
                        cancelPress();
                    }
                    paintTo(x, y, top, bot);
                    lastX = x;
                    return true;
                }"""
new_move = """                if (mode == MODE_LINE) {
                    lineT1 = clampTick((long) tickAtX(x));
                    lineV1 = clampV(vAtY(top, bot, y));
                    invalidate();
                    return true;
                }
                if (mode == MODE_DRAGPT) {
                    ControlCurve cc = (part == null) ? null : part.ctrlOrNull(paramId);
                    if (cc != null && selIdx >= 0 && selIdx < cc.pts.size()) {
                        cc.pts.get(selIdx).v = clampV(vAtY(top, bot, y));
                        paintDirty = true;
                        invalidate();
                    }
                    return true;
                }
                if (mode == MODE_PAINT) {
                    if (!moved) { if (Math.abs(x - downX) <= slop && Math.abs(y - downY) <= slop) return true;
                        moved = true;
                        cancelPress();
                    }
                    paintTo(x, y, top, bot);
                    lastX = x;
                    return true;
                }"""
assert old_move in s, "move"
s = s.replace(old_move, new_move, 1)

# ---- ACTION_UP ---------------------------------------------------------------
old_up = """            case MotionEvent.ACTION_UP: {
                cancelPress();
                if (mode == MODE_CHIP) { if (!moved && chipHit >= 0 && chipAt(x) == chipHit) selectChip(chipHit);
                } else if (mode == MODE_PAINT && !longFired) { long t = clampTick((long) tickAtX(x));"""
new_up = """            case MotionEvent.ACTION_UP: {
                cancelPress();
                if (toolPressed >= 0) {              // a tap on the floating bar only switches tools
                    if (!moved && toolAt(x, y) == toolPressed) {
                        tool = toolPressed;
                        selIdx = -1;
                        status hint
                    }
                    toolPressed = -1;
                    mode = MODE_NONE;
                    invalidate();
                    performClick();
                    return true;
                }
                if (mode == MODE_LINE) {
                    commitLine(top, bot);
                    linePreview = false;
                    mode = MODE_NONE;
                    invalidate();
                    performClick();
                    return true;
                }
                if (mode == MODE_DRAGPT) {
                    finishStroke();
                    mode = MODE_NONE;
                    invalidate();
                    performClick();
                    return true;
                }
                if (mode == MODE_CHIP) { if (!moved && chipHit >= 0 && chipAt(x) == chipHit) selectChip(chipHit);
                } else if (mode == MODE_PAINT && !longFired) { long t = clampTick((long) tickAtX(x));"""
assert old_up in s, "up"
s = s.replace(old_up, new_up, 1)
s = s.replace("                        status hint\n", "", 1)

# ---- helpers -----------------------------------------------------------------
old_helper = "    private void put(long t, double v) {"
new_helper = """    /** index of the point closest to (x,y) within the pick radius, or -1 */
    private int nearestPoint(float x, float y, float top, float bot) {
        ControlCurve c = (part == null) ? null : part.ctrlOrNull(paramId);
        if (c == null || c.pts.isEmpty()) return -1;
        float best = dp(16f);
        int bi = -1;
        for (int i = 0; i < c.pts.size(); i++) {
            ControlCurve.P pt = c.pts.get(i);
            float dx = xOfTick(pt.t) - x, dy = yOfV(top, bot, pt.v) - y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < best) { best = d; bi = i; }
        }
        return bi;
    }

    /** line tool: lay a straight, evenly sampled segment between the two ends */
    private void commitLine(float top, float bot) {
        long a = Math.min(lineT0, lineT1), b = Math.max(lineT0, lineT1);
        double va = (lineT0 <= lineT1) ? lineV0 : lineV1;
        double vb = (lineT0 <= lineT1) ? lineV1 : lineV0;
        if (b <= a) { put(a, va); }
        else {
            long its = (long) Math.max(1d, Math.round(dp(4f) / Math.max(0.02f, pxPerTick)));
            for (long t = a; t < b; t += its) {
                double f = (double) (t - a) / (double) (b - a);
                put(t, va + (vb - va) * f);
            }
            put(b, vb);
        }
        finishStroke();
        if (listener != null) listener.onCurveEdited(paramId);
    }

    private void put(long t, double v) {"""
assert old_helper in s
s = s.replace(old_helper, new_helper, 1)
io.open(p, "w", encoding="utf-8", newline="").write(s)
print("touch handling done")

# i18n keys for the three tools
l = root + r"\app\src\com\dsh\v4tune\i18n\L.java"
u = io.open(l, encoding="utf-8").read()
if '"param.tool.pencil"' not in u:
    marker = "\n    };\n"
    i = u.find("private static final String[][] TABLE = {")
    j = u.find(marker, i)
    rows = ('        {"param.tool.pencil", "\u753b\u7b14", "Draw", "\u30da\u30f3"},\n'
            '        {"param.tool.line", "\u76f4\u7ebf", "Line", "\u76f4\u7dda"},\n'
            '        {"param.tool.select", "\u9009\u62e9", "Select", "\u9078\u629e"},\n')
    u = u[:j] + "\n" + rows.rstrip("\n") + u[j:]
    io.open(l, "w", encoding="utf-8", newline="").write(u)
    print("i18n keys added")

# L import in ParamView
t = io.open(p, encoding="utf-8").read()
if "import com.dsh.v4tune.i18n.L;" not in t:
    t = t.replace("import com.dsh.v4tune.model.ControlCurve;", "import com.dsh.v4tune.i18n.L;\nimport com.dsh.v4tune.model.ControlCurve;", 1)
    io.open(p, "w", encoding="utf-8", newline="").write(t)
    print("L imported")
