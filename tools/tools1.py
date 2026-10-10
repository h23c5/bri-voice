# -*- coding: utf-8 -*-
"""[v3.2] pencil / line / select tools floating in the top-right of the parameter area."""
import io
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"
p = root + r"\app\src\com\dsh\v4tune\ui\ParamView.java"
s = io.open(p, encoding="utf-8").read()

# --- fields -------------------------------------------------------------------
old_mode = "    private static final int MODE_NONE = 0, MODE_PAINT = 1, MODE_PAN = 2, MODE_CHIP = 3, MODE_INERT = 4;"
new_mode = """    private static final int MODE_NONE = 0, MODE_PAINT = 1, MODE_PAN = 2, MODE_CHIP = 3, MODE_INERT = 4,
            MODE_LINE = 5, MODE_DRAGPT = 6;

    /** drawing tools offered by the floating bar */
    public static final int TOOL_PENCIL = 0, TOOL_LINE = 1, TOOL_SELECT = 2;
    private int tool = TOOL_PENCIL;
    private final float[] toolX = new float[3];
    private final float[] toolW = new float[3];
    private float toolTop, toolBot, toolLeft;
    private int toolHit = -1;
    private int toolPressed = -1;
    private Paint toolBgP, toolOnP, toolTxtP, toolTxtOnP;
    private float toolTxtMid;
    private Paint.FontMetrics toolFm;
    /** straight-line preview while the line tool is dragging */
    private boolean linePreview;
    private long lineT0, lineT1;
    private double lineV0, lineV1;
    /** index of the point picked by the select tool, -1 when nothing is selected */
    private int selIdx = -1;"""
assert old_mode in s
s = s.replace(old_mode, new_mode, 1)

# --- paints -------------------------------------------------------------------
old_paint = "        ptP = Theme.paint(Theme.ACCENT, 0f, true);"
new_paint = """        ptP = Theme.paint(Theme.ACCENT, 0f, true);
        toolBgP = Theme.paint(0xE01A1E28, 0f, true);
        toolOnP = Theme.paint(Theme.ACCENT, 0f, true);
        toolTxtP = Theme.paint(Theme.TEXT_DIM, dp(10.5f), true);
        toolTxtOnP = Theme.paint(Theme.ON_PRIMARY, dp(10.5f), true);
        toolFm = toolTxtP.getFontMetrics();
        toolTxtMid = -(toolFm.ascent + toolFm.descent) * 0.5f;"""
assert old_paint in s
s = s.replace(old_paint, new_paint, 1)

# --- drawing: floating bar + preview + selection ring -------------------------
old_draw = """        drawChips(c, w);"""
new_draw = """        drawChips(c, w);
        drawToolBar(c, w, top);
        if (linePreview) {
            float x0 = xOfTick(lineT0), x1 = xOfTick(lineT1);
            curveP.setColor(Theme.lighten(base(), 0.1f));
            curveP.setStrokeWidth(dp(2f));
            c.drawLine(x0, yOfV(top, bot, lineV0), x1, yOfV(top, bot, lineV1), curveP);
        }
        if (tool == TOOL_SELECT && selIdx >= 0) {
            ControlCurve cc = (part == null) ? null : part.ctrlOrNull(paramId);
            if (cc != null && selIdx < cc.pts.size()) {
                ControlCurve.P sp = cc.pts.get(selIdx);
                float sx = xOfTick(sp.t), sy = yOfV(top, bot, sp.v);
                hotP.setColor(Theme.ACCENT2);
                hotP.setStyle(Paint.Style.STROKE);
                hotP.setStrokeWidth(dp(2f));
                c.drawCircle(sx, sy, dp(7f), hotP);
                hotP.setStyle(Paint.Style.FILL);
            }
        }"""
assert old_draw in s
s = s.replace(old_draw, new_draw, 1)

# --- the bar itself -----------------------------------------------------------
old_drawchips = "    private void drawChips(Canvas c, int w) {"
new_bar = """    /** three little buttons floating over the top-right corner of the curve area */
    private void drawToolBar(Canvas c, int w, float top) {
        String[] names = {L.t("param.tool.pencil"), L.t("param.tool.line"), L.t("param.tool.select")};
        float h = dp(22f), gap = dp(4f), pad = dp(6f);
        float bw = 0f;
        for (String n : names) bw = Math.max(bw, toolTxtP.measureText(n) + dp(14f));
        toolTop = top + dp(6f);
        toolBot = toolTop + h;
        toolLeft = w - pad - (bw * 3 + gap * 2);
        float x = toolLeft;
        for (int i = 0; i < 3; i++) {
            toolX[i] = x;
            toolW[i] = bw;
            boolean on = (tool == i);
            toolBgP.setColor(on ? Theme.ACCENT : 0xE01A1E28);
            Theme.rr(c, x, toolTop, x + bw, toolBot, dp(7f), toolBgP);
            toolTxtP.setColor(on ? Theme.ON_PRIMARY : Theme.TEXT_DIM);
            toolTxtP.setTextAlign(Paint.Align.CENTER);
            c.drawText(names[i], x + bw * 0.5f, toolTop + h * 0.5f + toolTxtMid,
                    on ? toolTxtOnPColor() : toolTxtP);
            toolTxtP.setTextAlign(Paint.Align.LEFT);
            x += bw + gap;
        }
    }

    /** colour used for the active tool label */
    private Paint toolTxtOnPColor() {
        toolTxtOnP.setColor(Theme.ON_PRIMARY);
        toolTxtOnP.setTextAlign(Paint.Align.CENTER);
        return toolTxtOnP;
    }

    private int toolAt(float x, float y) {
        if (y < toolTop || y > toolBot) return -1;
        for (int i = 0; i < 3; i++) {
            if (x >= toolX[i] && x <= toolX[i] + toolW[i]) return i;
        }
        return -1;
    }

    private void drawChips(Canvas c, int w) {"""
assert old_drawchips in s
s = s.replace(old_drawchips, new_bar, 1)

io.open(p, "w", encoding="utf-8", newline="").write(s)
print("tool bar drawn")
