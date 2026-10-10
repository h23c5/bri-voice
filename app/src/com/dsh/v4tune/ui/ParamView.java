package com.dsh.v4tune.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.dsh.v4tune.i18n.L;
import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;

/**
 * Control parameter curve editor (DYN/BRE/BRI/CLE/OPE/GEN/POR/PIT/PBS/VIB/XSY/GWL).
 *
 * One finger paints values, two fingers pan/zoom the time window, a long press removes the nearest
 * point. The visible tick window is owned by the caller so it can stay locked to the piano roll.
 */
public class ParamView extends View {

    public interface Listener { void onCurveEdited(String paramId); void onParamSelected(String paramId);
        /** the user dragged the tuning lane: the roll should follow the same range */
        void onTickRangeChanged(float from, float to); }

        /** display labels, parallel to {@link #IDS}; the app fills them with the current language */
    public String[] labels;

    public static final String[] IDS = {"DYN", "BRE", "BRI", "CLE", "OPE", "GEN", "POR", "PIT", "PBS", "VIB", "XSY", "GWL"};
    private static final int[] COL = {0xFF35C8FF, 0xFFFFB03A, 0xFFFF8A3D, 0xFF7CE38B, 0xFFFF5FA2,
            0xFFC08CFF, 0xFF4ADEDE, 0xFFFF4D6A, 0xFFFF7A45, 0xFFFFD166, 0xFF6C7BFF, 0xFF8FA0B8};
    private static final double[] VMIN = {0, 0, 0, 0, 0, 0, 0, -2400, 0, 0, 0, 0};
    private static final double[] VMAX = {127, 127, 127, 127, 127, 127, 127, 2400, 24, 127, 127, 127};
    private static final double[] VDEF = {64, 64, 64, 64, 64, 64, 64, 0, 2, 64, 64, 64};
    private static final double[] VSTEP = {16, 16, 16, 16, 16, 16, 16, 600, 4, 16, 16, 16};

    private static final int MODE_NONE = 0, MODE_PAINT = 1, MODE_PAN = 2, MODE_CHIP = 3, MODE_INERT = 4,
            MODE_LINE = 5, MODE_DRAGPT = 6,
            MODE_DRAGVIEW = 7;

    /** drawing tools offered by the floating bar */
    public static final int TOOL_PENCIL = 0, TOOL_LINE = 1, TOOL_SELECT = 2;
    private int tool = TOOL_PENCIL;
    /** one-finger pan of the tuning lane (select tool) */
    private float panStartX, panStartTick;
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
    private int selIdx = -1;

    private float density;
    private float chipH, chipPad, chipGap, areaTop;
    private final float[] chipX = new float[IDS.length];
    private final float[] chipW = new float[IDS.length];
    private float chipsTotal, chipScroll;

    private Part part;
    private Song song;
    private String paramId = "DYN";
    private Listener listener;
    private long playheadTick = -1L;

    private float pxPerTick = 0.25f;
    private float tickStart;
    private boolean pendingRange;
    private long pendingFrom, pendingTo;

    private Paint bgP, partBgP, outP, edgeP, gridP, defP, curveP, fillP, ptP, hotP, playP, chipBgP, chipTxtP;
    private Paint labelP, labelBgP, valP, valBgP, emptyP, chipOnP, chipBarP, chipLineP;
    private Paint.FontMetrics valFm, labelFm, chipFm;
    private float valMid, labelMid, chipMid;
    private final Path curvePath = new Path();
    private final Path fillPath = new Path();
    private final char[] numBuf = new char[24];

    private int mode = MODE_NONE;
    private float downX, downY, lastX, downChipScroll;
    private boolean moved, longFired, paintDirty, hasLast;
    private float lastPaintX;
    private long lastPaintTick, lastTick;
    private double lastPaintVal, lastVal;
    private int chipHit = -1;
    private float prevMidX, prevSpan;
    private int slop, longPressMs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable longPress = new Runnable() {
        public void run() { if (mode != MODE_PAINT || moved) return; longFired = true; mode = MODE_INERT;
            removeNearest(downX, downY);
        }
    };

    public ParamView(Context c) { super(c); init(c); }

    private void init(Context c) { density = c.getResources().getDisplayMetrics().density; chipH = dp(30f);
        chipPad = dp(11f);
        chipGap = dp(6f);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = Math.max(4, vc.getScaledTouchSlop());
        longPressMs = ViewConfiguration.getLongPressTimeout();
        setFocusable(true);
        setFocusableInTouchMode(true);
        float hair = Math.max(1f, density * 0.75f);
        bgP = Theme.paint(Theme.BG, 0f, true);
        partBgP = Theme.paint(Theme.PANEL_DK, 0f, true);
        outP = Theme.paint(0x88000000, 0f, true);
        edgeP = Theme.paint(Theme.withAlpha(Theme.ACCENT2, 0x99), hair, false);
        gridP = Theme.paint(Theme.GRID, hair, false);
        defP = Theme.dashPaint(Theme.withAlpha(Theme.TEXT_DIM, 0x88), hair, dp(5f), dp(5f));
        curveP = Theme.paint(Theme.ACCENT, Math.max(1.5f, density * 1.6f), false);
        curveP.setStrokeCap(Paint.Cap.ROUND);
        curveP.setStrokeJoin(Paint.Join.ROUND);
        fillP = Theme.paint(0x2235C8FF, 0f, true);
        ptP = Theme.paint(Theme.ACCENT, 0f, true);
        toolBgP = Theme.paint(0xE01A1E28, 0f, true);
        toolOnP = Theme.paint(Theme.ACCENT, 0f, true);
        toolTxtP = Theme.paint(Theme.TEXT_DIM, dp(10.5f), true);
        toolTxtOnP = Theme.paint(Theme.ON_PRIMARY, dp(10.5f), true);
        toolFm = toolTxtP.getFontMetrics();
        toolTxtMid = -(toolFm.ascent + toolFm.descent) * 0.5f;
        hotP = Theme.paint(Theme.SELECT, 0f, true);
        playP = Theme.paint(Theme.PLAYHEAD, Math.max(1.5f, density * 1.4f), false);
        chipBgP = Theme.paint(Theme.CHIP_BG, 0f, true);
        chipTxtP = Theme.textPaint(Theme.TEXT_DIM, dp(11f), true);
        chipTxtP.setTextAlign(Paint.Align.CENTER);
        labelP = Theme.textPaint(Theme.TEXT_DIM, dp(9f), false);
        labelBgP = Theme.paint(0x80000000, 0f, true);
        valP = Theme.textPaint(Theme.TEXT, dp(11f), true);
        valBgP = Theme.paint(0xE6000000, 0f, true);
        valFm = valP.getFontMetrics();
        valMid = (valFm.ascent + valFm.descent) * 0.5f;
        labelFm = labelP.getFontMetrics();
        labelMid = (labelFm.ascent + labelFm.descent) * 0.5f;
        chipFm = chipTxtP.getFontMetrics();
        chipMid = (chipFm.ascent + chipFm.descent) * 0.5f;
        chipOnP = Theme.paint(Theme.ACCENT, Math.max(1.2f, density * 1.3f), false);
        chipBarP = Theme.paint(Theme.PANEL, 0f, true);
        chipLineP = Theme.paint(Theme.BAR, Math.max(1f, density), false);
        emptyP = Theme.textPaint(Theme.TEXT_DIM, dp(13f), false);
        emptyP.setTextAlign(Paint.Align.CENTER);
        layoutChips();
    }

    private float dp(float v) { return v * density; }

    // --------------------------------------------------------------- public API

    public void setPart(Part p) { part = p; hasLast = false; paintDirty = false; chipHit = -1;
        invalidate();
    }

    public void setSong(Song s) { song = s; clampRange(); invalidate(); }

    public void setLabels(String[] l) { labels = l; invalidate(); }

    public void setParamId(String id) { if (id == null || id.length() == 0) id = "DYN";
        if (id.equals(paramId)) return;
        paramId = id;
        hasLast = false;
        ensureChipVisible(pi());
        invalidate();
    }

    public String getParamId() { return paramId; }

    public void setListener(Listener l) { listener = l; }

    public void setPlayheadTick(long tick) { if (playheadTick == tick) return; playheadTick = tick; invalidate(); }

    public void refresh() { invalidate(); }

    public void setTickRange(long from, long to) { if (to <= from) to = from + 1L; pendingFrom = from;
        pendingTo = to;
        pendingRange = true;
        applyPendingRange();
        invalidate();
    }

    public long getTickStart() { return (long) Math.floor(tickStart); }

    public long getTickEnd() { return (long) Math.ceil(tickStart + visibleTicks()); }

    // ------------------------------------------------------------------ helpers

    private int pi() { for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(paramId)) return i; return -1; }

    private int col() { int i = pi(); return i < 0 ? Theme.ACCENT : COL[i]; }

    private double vmin() { int i = pi(); return i < 0 ? 0 : VMIN[i]; }

    private double vmax() { int i = pi(); return i < 0 ? 127 : VMAX[i]; }

    private double vdef() { int i = pi(); return i < 0 ? 64 : VDEF[i]; }

    private double vstep() { int i = pi(); return i < 0 ? 16 : VSTEP[i]; }

    private double clampV(double v) { double a = vmin(), b = vmax(); if (v < a) return a; if (v > b) return b;
        return v;
    }

    private float visibleTicks() { float v = getWidth() / pxPerTick; return v < 1f ? 1f : v; }

    private float xOfTick(long t) { return (t - tickStart) * pxPerTick; }

    private float tickAtX(float x) { return tickStart + x / pxPerTick; }

    private float clampPx(float v) { if (Float.isNaN(v) || Float.isInfinite(v) || v <= 0f) return 0.25f;
        if (v < 0.0008f) return 0.0008f;
        if (v > 8f) return 8f;
        return v;
    }

    private void applyPendingRange() { if (getWidth() <= 1 || pendingTo <= pendingFrom) return;
        pxPerTick = clampPx(getWidth() / (float) (pendingTo - pendingFrom));
        tickStart = pendingFrom;
        clampRange();
    }

    private void clampRange() { float vis = visibleTicks(); float maxStart = 1920f;
        if (song != null) maxStart = Math.max((float) song.maxTick(), 1920f) + vis * 0.5f;
        float minStart = -vis * 0.25f;
        if (tickStart < minStart) tickStart = minStart;
        if (tickStart > maxStart) tickStart = maxStart;
        if (Float.isNaN(tickStart) || Float.isInfinite(tickStart)) tickStart = 0f;
    }

    private float yOfV(float top, float bot, double v) { double a = vmin(), b = vmax();
        double f = (b - a) <= 0 ? 0 : (v - a) / (b - a);
        if (f < 0) f = 0;
        if (f > 1) f = 1;
        return (float) (bot - f * (bot - top));
    }

    private double vAtY(float top, float bot, float y) { double a = vmin(), b = vmax();
        if (bot - top <= 0f) return a;
        double f = (bot - y) / (double) (bot - top);
        if (f < 0) f = 0;
        if (f > 1) f = 1;
        return a + f * (b - a);
    }

    private long clampTick(long t) { if (part == null) return t;
        long a = Math.min(part.pos, part.end()), b = Math.max(part.pos, part.end());
        if (t < a) return a;
        if (t > b) return b;
        return t;
    }

    private static int firstIndexAt(ControlCurve c, long t) { int lo = 0, hi = c.pts.size() - 1, res = 0;
        while (lo <= hi) { int m = (lo + hi) >>> 1;
            if (c.pts.get(m).t <= t) { res = m; lo = m + 1;
            } else { hi = m - 1; }
        }
        return res;
    }

    // ------------------------------------------------------------------- chips

    private void layoutChips() { float x = dp(6f);
        for (int i = 0; i < IDS.length; i++) { float w = chipTxtP.measureText(IDS[i]) + chipPad * 2f;
            if (w < dp(42f)) w = dp(42f);
            chipX[i] = x;
            chipW[i] = w;
            x += w + chipGap;
        }
        chipsTotal = x;
    }

    private void clampChipScroll() { float max = chipsTotal - getWidth(); if (max < 0f) max = 0f;
        if (chipScroll < 0f) chipScroll = 0f;
        if (chipScroll > max) chipScroll = max;
    }

    private void ensureChipVisible(int i) { if (i < 0) return; float l = chipX[i], r = l + chipW[i];
        if (l - chipScroll < dp(6f)) chipScroll = l - dp(6f);
        else if (r - chipScroll > getWidth() - dp(6f)) chipScroll = r - getWidth() + dp(6f);
        clampChipScroll();
    }

    private int chipAt(float x) { float cx = x + chipScroll;
        for (int i = 0; i < IDS.length; i++) { if (cx >= chipX[i] && cx <= chipX[i] + chipW[i]) return i; }
        return -1;
    }

    private void selectChip(int i) { if (i < 0 || i >= IDS.length) return; setParamId(IDS[i]);
        if (listener != null) listener.onParamSelected(paramId);
    }

    // ------------------------------------------------------------------- edits

    /** index of the point closest to (x,y) within the pick radius, or -1 */
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

    private void put(long t, double v) { if (part == null) return; ControlCurve c = part.ctrl(paramId);
        if (c.pts.isEmpty()) { c.min = vmin(); c.max = vmax(); c.def = vdef(); }
        // reuse the point that is already within ~2.5 px instead of adding another one: dragging
        // back and forth over the same spot used to pile up hundreds of points
        long gap = (long) Math.max(1d, Math.round(dp(2.5f) / Math.max(0.02f, pxPerTick)));
        c.putNear(t, v, gap);
        paintDirty = true;
    }

    private void markLast(long t, double v) { hasLast = true; lastTick = t; lastVal = v; }

    private void paintTo(float x, float y, float top, float bot) { long t = clampTick((long) tickAtX(x));
        double v = clampV(vAtY(top, bot, y));
        float dx = x - lastPaintX;
        if (Math.abs(dx) >= dp(4f)) { long its = (long) Math.max(1d, Math.round(dp(4f) / pxPerTick));
            long d = t - lastPaintTick;
            if (d != 0L) { long steps = Math.abs(d) / its; if (steps > 512L) steps = 512L;
                for (long i = 1L; i <= steps; i++) { long tt = lastPaintTick + (d > 0L ? i * its : -i * its);
                    double f = (double) (tt - lastPaintTick) / (double) d;
                    put(tt, clampV(lastPaintVal + (v - lastPaintVal) * f));
                }
            }
            put(t, v);
            lastPaintTick = t;
            lastPaintVal = v;
            lastPaintX = x;
        }
        markLast(t, v);
        invalidate();
    }

    private void finishStroke() { if (!paintDirty) return; paintDirty = false;
        if (part != null) { ControlCurve c = part.ctrlOrNull(paramId);
            if (c != null) {
                c.sort();
                c.compact((vmax() - vmin()) / 300.0);
                // then straighten out the wobble the finger left behind: ~1 unit on a 0..127 scale,
                // i.e. about one pixel on screen, so real corners survive and jitter does not
                c.simplify((vmax() - vmin()) / 110.0);
            }
        }
        if (listener != null) listener.onCurveEdited(paramId);
    }

    private void removeNearest(float x, float y) { if (part == null) return;
        ControlCurve c = part.ctrlOrNull(paramId);
        if (c == null || c.pts.isEmpty()) return;
        float lim = dp(24f);
        float best = lim;
        int bi = -1;
        for (int i = 0; i < c.pts.size(); i++) { ControlCurve.P p = c.pts.get(i);
            float dx = xOfTick(p.t) - x, dy = yOfV(areaTop, getHeight(), p.v) - y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < best) { best = d; bi = i; }
        }
        if (bi < 0) return;
        ControlCurve.P old = c.pts.get(bi);
        c.removeAt(bi);
        if (hasLast && lastTick == old.t) hasLast = false;
        if (listener != null) listener.onCurveEdited(paramId);
        invalidate();
    }

    // ------------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) { final int action = e.getActionMasked();
        final float x = e.getX(), y = e.getY();
        final float top = areaTop, bot = Math.max(areaTop + 1f, getHeight());
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                cancelPress();
                downX = lastX = x;
                downY = y;
                downChipScroll = chipScroll;
                moved = false;
                longFired = false;
                chipHit = -1;
                if (y >= toolTop && y <= toolBot && (toolHit = toolAt(x, y)) >= 0) {
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
                // nothing under the finger: dragging the lane scrolls the timeline instead
                mode = MODE_DRAGVIEW;
                panStartX = x;
                panStartTick = tickStart;
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
        if (y < chipH) { mode = MODE_CHIP; chipHit = chipAt(x);
                } else { mode = MODE_PAINT; lastPaintX = x; lastPaintTick = clampTick((long) tickAtX(x));
                    lastPaintVal = clampV(vAtY(top, bot, y));
                    handler.postDelayed(longPress, longPressMs);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == MODE_PAN) { doPan(e); return true; }
                if (mode == MODE_CHIP) { if (!moved && Math.abs(x - downX) > slop) moved = true;
                    if (moved) { chipScroll = downChipScroll - (x - downX); clampChipScroll(); invalidate(); }
                    return true;
                }
                if (mode == MODE_LINE) {
                    lineT1 = clampTick((long) tickAtX(x));
                    lineV1 = clampV(vAtY(top, bot, y));
                    invalidate();
                    return true;
                }
                if (mode == MODE_DRAGVIEW) {
                    tickStart = panStartTick - (x - panStartX) / pxPerTick;
                    clampRange();
                    if (listener != null) listener.onTickRangeChanged(tickStart, tickStart + visibleTicks());
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
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                cancelPress();
                finishStroke();
                mode = MODE_PAN;
                prevMidX = (e.getX(0) + e.getX(1)) * 0.5f;
                prevSpan = Math.abs(e.getX(0) - e.getX(1));
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                mode = MODE_INERT;
                return true;
            }
            case MotionEvent.ACTION_UP: {
                cancelPress();
                if (toolPressed >= 0) {              // a tap on the floating bar only switches tools
                    if (!moved && toolAt(x, y) == toolPressed) {
                        tool = toolPressed;
                        selIdx = -1;
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
                if (mode == MODE_DRAGVIEW) {
                    mode = MODE_NONE;
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
                } else if (mode == MODE_PAINT && !longFired) { long t = clampTick((long) tickAtX(x));
                    double v = clampV(vAtY(top, bot, y));
                    put(t, v);
                    markLast(t, v);
                    finishStroke();
                    invalidate();
                }
                mode = MODE_NONE;
                chipHit = -1;
                performClick();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                cancelPress();
                finishStroke();
                mode = MODE_NONE;
                chipHit = -1;
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    private void doPan(MotionEvent e) { float mx = (e.getX(0) + e.getX(1)) * 0.5f;
        float span = Math.abs(e.getX(0) - e.getX(1));
        float f = (prevSpan > dp(12f) && span > dp(12f)) ? span / prevSpan : 1f;
        if (Math.abs(f - 1f) < 0.002f) f = 1f;
        float anchor = tickAtX(mx);
        if (f != 1f) { pxPerTick = clampPx(pxPerTick * f); tickStart = anchor - mx / pxPerTick; }
        float dx = mx - prevMidX;
        if (dx != 0f) tickStart -= dx / pxPerTick;
        clampRange();
        prevMidX = mx;
        prevSpan = span;
        invalidate();
    }

    private void cancelPress() { handler.removeCallbacks(longPress); }

    // ----------------------------------------------------------------- drawing

    @Override
    protected void onMeasure(int wSpec, int hSpec) { int w = resolveSize((int) dp(320f), wSpec);
        int h = resolveSize((int) dp(140f), hSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); areaTop = chipH;
        if (pendingRange) applyPendingRange();
        clampRange();
        clampChipScroll();
    }

    @Override
    protected void onDraw(Canvas c) { final int w = getWidth(), h = getHeight(); if (w <= 0 || h <= 0) return;
        areaTop = chipH;
        float top = areaTop, bot = h;
        if (bot <= top + dp(8f)) { drawChips(c, w); return; }
        c.drawColor(Theme.BG);
        c.save();
        c.clipRect(0f, top, w, bot);
        drawArea(c, w, top, bot);
        c.restore();
        drawChips(c, w);
        drawToolBar(c, w, top);
        if (linePreview) {
            float x0 = xOfTick(lineT0), x1 = xOfTick(lineT1);
            curveP.setColor(Theme.ACCENT2);
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
        }
    }

    private void drawArea(Canvas c, int w, float top, float bot) { c.drawRect(0f, top, w, bot, bgP);
        if (part == null) { c.drawText("no part", w * 0.5f, (top + bot) * 0.5f, emptyP); return; }
        float px0 = xOfTick(part.pos), px1 = xOfTick(part.end());
        float l = Math.max(0f, px0), r = Math.min((float) w, px1);
        if (r > l) c.drawRect(l, top, r, bot, partBgP);
        if (l > 0f) c.drawRect(0f, top, l, bot, outP);
        if (r < w) c.drawRect(r, top, w, bot, outP);
        if (px0 >= 0f && px0 <= w) c.drawLine(px0, top, px0, bot, edgeP);
        if (px1 >= 0f && px1 <= w) c.drawLine(px1, top, px1, bot, edgeP);
        double vmn = vmin(), vmx = vmax(), st = vstep();
        if (st > 0) { int guard = 0;
            for (double v = Math.ceil(vmn / st) * st; v < vmx && guard < 512; v += st, guard++) {
                float y = yOfV(top, bot, v);
                if (y > top + 0.5f && y < bot - 0.5f) c.drawLine(0f, y, w, y, gridP);
            }
        }
        float yd = yOfV(top, bot, vdef());
        if (yd > top && yd < bot) c.drawLine(0f, yd, w, yd, defP);
        int base = col();
        ControlCurve cc = part.ctrlOrNull(paramId);
        long t0 = (long) Math.floor(tickAtX(0f)) - 1L;
        long t1 = (long) Math.ceil(tickAtX((float) w)) + 1L;
        if (cc != null && !cc.pts.isEmpty()) { int i0 = firstIndexAt(cc, t0); curvePath.reset();
            curvePath.moveTo(0f, yOfV(top, bot, cc.valueAt(t0)));
            int n = cc.pts.size();
            for (int i = i0; i < n; i++) { ControlCurve.P p = cc.pts.get(i);
                curvePath.lineTo(xOfTick(p.t), yOfV(top, bot, p.v));
                if (p.t > t1) break;
            }
            curvePath.lineTo((float) w, yOfV(top, bot, cc.valueAt(t1)));
            fillPath.set(curvePath);
            fillPath.lineTo((float) w, bot);
            fillPath.lineTo(0f, bot);
            fillPath.close();
            fillP.setColor(Theme.mulAlpha(base, 0.16f));
            c.drawPath(fillPath, fillP);
            curveP.setColor(base);
            c.drawPath(curvePath, curveP);
            ptP.setColor(Theme.lighten(base, 0.25f));
            float pr = Math.max(dp(2f), Math.min(dp(3.5f), (bot - top) * 0.012f));
            float lastDotX = -1e9f;
            for (int i = i0; i < n; i++) { ControlCurve.P p = cc.pts.get(i); if (p.t < t0) continue;
                if (p.t > t1) break;
                float dx = xOfTick(p.t);
                if (dx - lastDotX < pr * 1.6f) continue;      // dots would merge into a smear
                c.drawCircle(dx, yOfV(top, bot, p.v), pr, ptP);
                lastDotX = dx;
            }
        }
        if (hasLast) { float hx = xOfTick(lastTick), hy = yOfV(top, bot, lastVal);
            if (hx >= 0f && hx <= w) c.drawCircle(hx, hy, dp(5f), hotP);
        }
        if (playheadTick >= 0L) { float x = xOfTick(playheadTick);
            if (x >= 0f && x <= w) c.drawLine(x, top, x, bot, playP);
        }
        drawValueLabels(c, top, bot);
        if (hasLast) drawValueBadge(c, w, top, bot);
    }

    private void drawValueLabels(Canvas c, float top, float bot) { valueLabel(c, vmax(), top, bot);
        valueLabel(c, vdef(), top, bot);
        valueLabel(c, vmin(), top, bot);
    }

    private void valueLabel(Canvas c, double v, float top, float bot) { float y = yOfV(top, bot, v);
        if (y < top + dp(2f) || y > bot - dp(2f)) return;
        int len = fmtInt(Math.round(v));
        float tw = labelP.measureText(numBuf, 0, len);
        float bh = dp(13f);
        float by = Math.min(Math.max(y - bh * 0.5f, top + dp(1f)), bot - bh - dp(1f));
        Theme.rr(c, dp(3f), by, dp(3f) + tw + dp(8f), by + bh, dp(2.5f), labelBgP);
        c.drawText(numBuf, 0, len, dp(7f), by + bh * 0.5f - labelMid, labelP);
    }

    private void drawValueBadge(Canvas c, int w, float top, float bot) { int len = fmtInt(Math.round(lastVal));
        String nm = (pi() < 0) ? paramId : IDS[pi()];
        float nw = valP.measureText(nm), vw = valP.measureText(numBuf, 0, len);
        float bw = nw + vw + dp(18f), bh = dp(20f);
        float hx = xOfTick(lastTick), hy = yOfV(top, bot, lastVal);
        float bx = Math.min(Math.max(hx + dp(9f), dp(2f)), Math.max(dp(2f), w - bw - dp(2f)));
        float by = Math.min(Math.max(hy - bh - dp(6f), top + dp(2f)), bot - bh - dp(2f));
        Theme.rr(c, bx, by, bx + bw, by + bh, dp(4f), valBgP);
        valP.setColor(Theme.TEXT_DIM);
        c.drawText(nm, bx + dp(7f), by + bh * 0.5f - valMid, valP);
        valP.setColor(col());
        c.drawText(numBuf, 0, len, bx + dp(7f) + nw + dp(4f), by + bh * 0.5f - valMid, valP);
    }

    /** three little buttons floating over the top-right corner of the curve area */
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

    private void drawChips(Canvas c, int w) { c.drawRect(0f, 0f, w, chipH, chipBarP);
        float top = dp(5f), bot = chipH - dp(5f);
        int act = pi();
        for (int i = 0; i < IDS.length; i++) { float cx = chipX[i] - chipScroll; float cw = chipW[i];
            if (cx > w || cx + cw < 0f) continue;
            boolean on = (i == act);
            int base = COL[i];
            if (on) { chipBgP.setColor(Theme.mulAlpha(base, 0.28f));
                Theme.rr(c, cx, top, cx + cw, bot, dp(7f), chipBgP);
                chipOnP.setColor(base);
                Theme.rr(c, cx + dp(1f), top + dp(1f), cx + cw - dp(1f), bot - dp(1f), dp(6f), chipOnP);
                chipTxtP.setColor(Theme.lighten(base, 0.35f));
            } else { chipBgP.setColor(Theme.CHIP_BG); Theme.rr(c, cx, top, cx + cw, bot, dp(7f), chipBgP);
                chipTxtP.setColor(Theme.TEXT_DIM);
            }
            float baseline = (top + bot) * 0.5f - chipMid;
            c.drawText(IDS[i], cx + cw * 0.5f, baseline, chipTxtP);
            ControlCurve cc = (part == null) ? null : part.ctrlOrNull(IDS[i]);
            if (cc != null && !cc.pts.isEmpty()) {
                chipBgP.setColor(on ? Theme.lighten(base, 0.4f) : Theme.mulAlpha(base, 0.85f));
                Theme.rr(c, cx + dp(6f), bot - dp(2.5f), cx + cw - dp(6f), bot - dp(0.5f), dp(1f), chipBgP);
            }
        }
        c.drawLine(0f, chipH - dp(0.7f), w, chipH - dp(0.7f), chipLineP);
    }

    private int fmtInt(long v) { int n = 0;
        if (v < 0L) { numBuf[n++] = '-'; v = -v; }
        int start = n;
        if (v == 0L) { numBuf[n++] = '0';
        } else {
            while (v > 0L && n < numBuf.length - 1) { numBuf[n++] = (char) ('0' + (int) (v % 10L)); v /= 10L; }
        }
        for (int i = start, j = n - 1; i < j; i++, j--) { char t = numBuf[i]; numBuf[i] = numBuf[j]; numBuf[j] = t;
        }
        return n;
    }
}