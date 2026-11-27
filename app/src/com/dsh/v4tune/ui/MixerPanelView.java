package com.dsh.v4tune.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.dsh.v4tune.audio.Mixer;

/**
 * Cubase-style mixer: one vertical channel strip per track plus a master strip, drawn on a canvas
 * so the layout can match a real console (trim knob, insert slots, pan, M/S, fader with a dB readout
 * and a live meter, name plate).
 *
 * <p>Only the controls the engine actually implements are live: trim (-12..+12 dB, applied as a
 * pre-gain in AudioEngine), pan, mute/solo, the fader (the mixer gain) and the master fader. The
 * insert slots are real on the master strip (the plugin chain lives there) and empty on tracks,
 * because this engine has one global plugin chain rather than per-channel inserts - an empty slot
 * says so instead of pretending.</p>
 */
public class MixerPanelView extends View {

    /** What the view needs from the editor. */
    public interface Host {
        Mixer mixer();
        int trackCount();
        String trackName(int i);
        boolean isCurrent(int i);
        float peak(int track);          // 0..1, last render
        float masterPeak();
        void onChanged(String what);
        void onEmptyInsert(int track);
        int insertCount();
        String insertName(int slot);
        boolean insertBypassed(int slot);
        void onToggleInsert(int slot);
    }

    private static final int Z_NONE = 0, Z_TRIM = 1, Z_INS1 = 2, Z_INS2 = 3, Z_PAN = 4,
            Z_MUTE = 5, Z_SOLO = 6, Z_FADER = 7, Z_TRIM_DN = 8, Z_TRIM_UP = 9;

    private static final float FADER_MIN_DB = -60f, FADER_MAX_DB = 6f, TRIM_MAX_DB = 12f;

    /** One tap on the nudge buttons next to the knob. */
    private static final float TRIM_STEP_DB = 1f;

    private final Path arrow = new Path();

    private final Host host;
    private final Paint bg = Theme.paint(Theme.SURFACE, 0f, true);
    private final Paint bgCur = Theme.paint(Theme.SURFACE_HI, 0f, true);
    private final Paint bgMaster = Theme.paint(0xFF123542, 0f, true);
    private final Paint line = Theme.paint(Theme.STROKE, Theme.dpi(getContext(), 1f), false);
    private final Paint text = Theme.textPaint(Theme.TEXT, Theme.dpi(getContext(), 8.5f), false);
    private final Paint dim = Theme.textPaint(Theme.TEXT_DIM, Theme.dpi(getContext(), 7.5f), false);
    private final Paint tiny = Theme.textPaint(Theme.TEXT_DIM, Theme.dpi(getContext(), 6.5f), false);
    private final Paint nameP = Theme.textPaint(0xFF07090D, Theme.dpi(getContext(), 8.5f), true);
    private final RectF r = new RectF();
    private final RectF cap = new RectF();

    private float stripW, stripGap, padX;

    // drag state
    private int dragStrip = -1, dragZone = Z_NONE;
    private float dragStartY, dragStartDb;

    public MixerPanelView(Context c, Host h) {
        super(c);
        this.host = h;
        stripW = Theme.dpi(c, 74f);
        stripGap = Theme.dpi(c, 5f);
        padX = Theme.dpi(c, 8f);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private int strips() { return host.trackCount() + 1; }   // + master

    private float stripLeft(int s) { return padX + s * (stripW + stripGap); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int desiredW = (int) (padX * 2 + strips() * (stripW + stripGap) - stripGap);
        int w = resolveSize(desiredW, wSpec);
        int h = resolveSize((int) Theme.dpi(getContext(), 430f), hSpec);
        setMeasuredDimension(Math.max(desiredW, w), h);
    }

    // ------------------------------------------------------------------ layout

    private float yLabelGain() { return Theme.dpi(getContext(), 16f); }
    private float knobCy() { return Theme.dpi(getContext(), 44f); }
    private float knobR() { return Theme.dpi(getContext(), 19f); }
    private float yTrimValue() { return Theme.dpi(getContext(), 74f); }
    private float yIns1() { return Theme.dpi(getContext(), 82f); }
    private float yIns2() { return Theme.dpi(getContext(), 100f); }
    private float insH() { return Theme.dpi(getContext(), 15f); }
    private float yPanLabel() { return Theme.dpi(getContext(), 128f); }
    private float yPanBar() { return Theme.dpi(getContext(), 140f); }
    private float yPanText() { return Theme.dpi(getContext(), 162f); }
    private float yMs() { return Theme.dpi(getContext(), 174f); }
    private float msH() { return Theme.dpi(getContext(), 18f); }
    private float yDbBox() { return Theme.dpi(getContext(), 200f); }
    private float dbBoxH() { return Theme.dpi(getContext(), 18f); }
    private float yFader() { return Theme.dpi(getContext(), 226f); }
    private float nameH() { return Theme.dpi(getContext(), 22f); }
    private float faderBottom() { return getHeight() - nameH() - Theme.dpi(getContext(), 6f); }

    private static float dbToGain(float db) { return (float) Math.pow(10.0, db / 20.0); }

    private static float gainToDb(float g) {
        if (g <= 0.0001f) return FADER_MIN_DB;
        float db = (float) (20.0 * Math.log10(g));
        return Math.max(FADER_MIN_DB, Math.min(FADER_MAX_DB, db));
    }

    private static String dbText(float db) {
        if (db <= FADER_MIN_DB + 0.01f) return "-inf";
        return (db >= 0 ? "+" : "") + String.format(java.util.Locale.US, "%.1f", db);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        Mixer mx = host.mixer();
        if (mx == null) return;
        int n = host.trackCount();
        for (int s = 0; s <= n; s++) drawStrip(c, mx, s, s == n);
    }

    private void drawStrip(Canvas c, Mixer mx, int s, boolean master) {
        float x = stripLeft(s), w = stripW;
        r.set(x, 0f, x + w, getHeight() - Theme.dpi(getContext(), 2f));
        c.drawRoundRect(r, Theme.dpi(getContext(), 4f), Theme.dpi(getContext(), 4f),
                master ? bgMaster : (host.isCurrent(s) && !master ? bgCur : bg));
        c.drawRoundRect(r, Theme.dpi(getContext(), 4f), Theme.dpi(getContext(), 4f), line);

        float cx = x + w * 0.5f;
        c.drawText("gain", cx - dim.measureText("gain") * 0.5f, yLabelGain(), dim);

        // ---- trim knob
        float kx = cx, ky = knobCy(), kr = knobR();
        Paint arcBg = Theme.paint(Theme.CHIP_BG, Theme.dpi(getContext(), 3f), false);
        c.drawCircle(kx, ky, kr, arcBg);
        float trimDb = master ? gainToDb(mx.getMasterGain()) : mx.getTrimDb(s);
        float frac = (trimDb + TRIM_MAX_DB) / (2f * TRIM_MAX_DB);     // 0..1
        Paint arc = Theme.paint(Theme.ACCENT, Theme.dpi(getContext(), 3f), false);
        RectF ring = new RectF(kx - kr, ky - kr, kx + kr, ky + kr);
        c.drawArc(ring, 135f, 270f * frac, false, arc);
        double ang = Math.toRadians(135 + 270 * frac);
        c.drawLine(kx, ky, (float) (kx + Math.cos(ang) * kr * 0.72f),
                (float) (ky + Math.sin(ang) * kr * 0.72f),
                Theme.paint(Theme.TEXT, Theme.dpi(getContext(), 1.6f), false));
        String tv = dbText(trimDb);
        c.drawText(tv, cx - text.measureText(tv) * 0.5f, yTrimValue(), text);

        // ---- nudge buttons beside the knob: left = down, right = up
        drawNudge(c, cx - trimBtnDx(), ky, false);
        drawNudge(c, cx + trimBtnDx(), ky, true);

        // ---- insert slots
        drawInsert(c, x, yIns1(), "inst 1", s, 0, master);
        drawInsert(c, x, yIns2(), "inst 2", s, 1, master);

        // ---- pan
        c.drawText("pan", cx - dim.measureText("pan") * 0.5f, yPanLabel(), dim);
        float barL = x + Theme.dpi(getContext(), 10f), barR = x + w - Theme.dpi(getContext(), 10f);
        Paint barP = Theme.paint(Theme.CHIP_BG, Theme.dpi(getContext(), 2f), false);
        c.drawLine(barL, yPanBar(), barR, yPanBar(), barP);
        c.drawLine(cx, yPanBar() - Theme.dpi(getContext(), 4f), cx, yPanBar() + Theme.dpi(getContext(), 4f),
                Theme.paint(Theme.TEXT_DIM, Theme.dpi(getContext(), 1f), false));
        float pan = master ? mx.getMasterPan() : mx.getPan(s);
        float px = cx + pan * (barR - cx);
        c.drawCircle(px, yPanBar(), Theme.dpi(getContext(), 4f), Theme.paint(Theme.ACCENT, 0f, true));
        float ty = yPanText();
        c.drawText("L", barL - tiny.measureText("L") * 0.5f, ty, tiny);
        c.drawText("C", cx - tiny.measureText("C") * 0.5f, ty, tiny);
        c.drawText("R", barR - tiny.measureText("R") * 0.5f, ty, tiny);

        // ---- mute / solo
        float msW = (w - Theme.dpi(getContext(), 14f)) * 0.5f;
        boolean muted = !master && mx.isMuted(s);
        boolean soloed = !master && mx.isSolo(s);
        drawMsBox(c, x + Theme.dpi(getContext(), 6f), yMs(), msW, msH(), "M", muted, 0xFFF0B429);
        drawMsBox(c, x + Theme.dpi(getContext(), 8f) + msW, yMs(), msW, msH(), "S", soloed, 0xFF35C8FF);

        // ---- fader + meter
        float db = master ? gainToDb(mx.getMasterGain()) : gainToDb(mx.getGain(s));
        float boxY = yDbBox(), boxH = dbBoxH();
        r.set(x + Theme.dpi(getContext(), 8f), boxY, x + w - Theme.dpi(getContext(), 8f), boxY + boxH);
        c.drawRoundRect(r, Theme.dpi(getContext(), 2f), Theme.dpi(getContext(), 2f),
                Theme.paint(Theme.BG_DEEP, 0f, true));
        c.drawRoundRect(r, Theme.dpi(getContext(), 2f), Theme.dpi(getContext(), 2f),
                Theme.paint(Theme.STROKE, Theme.dpi(getContext(), 1f), false));
        String dbs = dbText(db);
        c.drawText(dbs, r.centerX() - text.measureText(dbs) * 0.5f, boxY + boxH * 0.72f, text);

        float ft = yFader(), fb = faderBottom();
        float fx = x + Theme.dpi(getContext(), 22f);
        c.drawLine(fx, ft, fx, fb, Theme.paint(Theme.CHIP_BG, Theme.dpi(getContext(), 3f), false));
        float fFrac = (db - FADER_MIN_DB) / (FADER_MAX_DB - FADER_MIN_DB);
        float fy = fb - fFrac * (fb - ft);
        c.drawLine(fx, fy, fx, fb, Theme.paint(Theme.ACCENT, Theme.dpi(getContext(), 3f), false));
        cap.set(fx - Theme.dpi(getContext(), 9f), fy - Theme.dpi(getContext(), 4f),
                fx + Theme.dpi(getContext(), 9f), fy + Theme.dpi(getContext(), 4f));
        c.drawRoundRect(cap, Theme.dpi(getContext(), 2f), Theme.dpi(getContext(), 2f),
                Theme.paint(0xFFDCE6F5, 0f, true));

        // meter (L/R stacked)
        float mLeft = fx + Theme.dpi(getContext(), 12f), mW = Theme.dpi(getContext(), 4f);
        float peak = master ? host.masterPeak() : host.peak(s);
        float mh = Math.max(0f, Math.min(1f, peak)) * (fb - ft);
        c.drawRoundRect(new RectF(mLeft, ft, mLeft + mW, fb),
                Theme.dpi(getContext(), 1.5f), Theme.dpi(getContext(), 1.5f),
                Theme.paint(Theme.BG_DEEP, 0f, true));
        if (mh > 0f) {
            Paint mp = Theme.paint(peak > 0.95f ? 0xFFFF4D6A : (peak > 0.75f ? 0xFFF0B429 : 0xFF7CE38B),
                    0f, true);
            c.drawRoundRect(new RectF(mLeft, fb - mh, mLeft + mW, fb),
                    Theme.dpi(getContext(), 1.5f), Theme.dpi(getContext(), 1.5f), mp);
        }

        // ---- name plate
        float nbT = getHeight() - nameH() - Theme.dpi(getContext(), 2f);
        int colour = master ? 0xFF22D3EE : TrackStripView.colorFor(s);
        r.set(x, nbT, x + w, nbT + nameH());
        c.drawRoundRect(r, Theme.dpi(getContext(), 0f), Theme.dpi(getContext(), 0f),
                Theme.paint(colour, 0f, true));
        String nm = master ? "MASTER" : shortName(host.trackName(s));
        c.drawText(nm, cx - nameP.measureText(nm) * 0.5f, nbT + nameH() * 0.72f, nameP);
    }

    private void drawInsert(Canvas c, float x, float y, String slot, int s, int which, boolean master) {
        float w = stripW, h = insH();
        r.set(x + Theme.dpi(getContext(), 5f), y, x + w - Theme.dpi(getContext(), 5f), y + h);
        c.drawRoundRect(r, Theme.dpi(getContext(), 2f), Theme.dpi(getContext(), 2f),
                Theme.paint(Theme.BG_DEEP, 0f, true));
        boolean used = master && which < host.insertCount();
        boolean bypassed = used && host.insertBypassed(which);
        Paint led = Theme.paint(!used ? Theme.CHIP_BG : (bypassed ? 0xFFF0B429 : 0xFF7CE38B), 0f, true);
        c.drawCircle(r.left + Theme.dpi(getContext(), 6f), r.centerY(), Theme.dpi(getContext(), 2.2f), led);
        String label = used ? host.insertName(which) : slot;
        String cut = ellipsize(label, tiny, w - Theme.dpi(getContext(), 18f));
        tiny.setColor(used ? Theme.TEXT : Theme.TEXT_DIM);
        c.drawText(cut, r.left + Theme.dpi(getContext(), 11f), r.centerY() + Theme.dpi(getContext(), 2.6f), tiny);
    }

    // ---- small +/- (up/down) buttons flanking the knob -------------------

    private float trimBtnDx() { return knobR() + Theme.dpi(getContext(), 7f); }
    private float trimBtnW() { return Theme.dpi(getContext(), 14f); }
    private float trimBtnH() { return Theme.dpi(getContext(), 20f); }

    private void drawNudge(Canvas c, float cx, float cy, boolean up) {
        float w = trimBtnW() * 0.5f, h = trimBtnH() * 0.5f;
        r.set(cx - w, cy - h, cx + w, cy + h);
        c.drawRoundRect(r, Theme.dpi(getContext(), 3f), Theme.dpi(getContext(), 3f),
                Theme.paint(Theme.CHIP_BG, 0f, true));
        c.drawRoundRect(r, Theme.dpi(getContext(), 3f), Theme.dpi(getContext(), 3f), line);
        float a = Theme.dpi(getContext(), 3.2f);
        arrow.reset();
        if (up) {
            arrow.moveTo(cx, cy - a);
            arrow.lineTo(cx + a * 0.85f, cy + a * 0.65f);
            arrow.lineTo(cx - a * 0.85f, cy + a * 0.65f);
        } else {
            arrow.moveTo(cx, cy + a);
            arrow.lineTo(cx + a * 0.85f, cy - a * 0.65f);
            arrow.lineTo(cx - a * 0.85f, cy - a * 0.65f);
        }
        arrow.close();
        c.drawPath(arrow, Theme.paint(Theme.ACCENT, 0f, true));
    }

    /** 0 = miss, Z_TRIM_DN = knob down, Z_TRIM_UP = knob up. */
    private int trimBtnAt(int s, float x, float y) {
        float cx = stripLeft(s) + stripW * 0.5f, cy = knobCy();
        float hw = trimBtnW() * 0.5f + Theme.dpi(getContext(), 3f);
        float hh = trimBtnH() * 0.5f + Theme.dpi(getContext(), 3f);
        if (y < cy - hh || y > cy + hh) return Z_NONE;
        if (Math.abs(x - (cx - trimBtnDx())) <= hw) return Z_TRIM_DN;
        if (Math.abs(x - (cx + trimBtnDx())) <= hw) return Z_TRIM_UP;
        return Z_NONE;
    }

    private void nudgeTrim(Mixer mx, int s, boolean master, int dir) {
        float db = master ? gainToDb(mx.getMasterGain()) : mx.getTrimDb(s);
        if (db <= FADER_MIN_DB + 0.01f) db = 0f;
        db = Math.max(-TRIM_MAX_DB, Math.min(TRIM_MAX_DB, db + dir * TRIM_STEP_DB));
        if (master) mx.setMasterGain(dbToGain(db)); else mx.setTrimDb(s, db);
        host.onChanged("trim " + dbText(db) + " dB");
        invalidate();
    }

    private String ellipsize(String s, Paint p, float max) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        String cut = s;
        while (cut.length() > 1 && p.measureText(cut + "…") > max) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    private void drawMsBox(Canvas c, float x, float y, float w, float h, String label, boolean lit, int litColor) {
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, Theme.dpi(getContext(), 2f), Theme.dpi(getContext(), 2f),
                Theme.paint(lit ? litColor : Theme.CHIP_BG, 0f, true));
        Paint t = Theme.textPaint(lit ? 0xFF0B0E14 : Theme.TEXT_DIM, Theme.dpi(getContext(), 8f), true);
        c.drawText(label, r.centerX() - t.measureText(label) * 0.5f, y + h * 0.74f, t);
    }

    private String shortName(String n) {
        if (n == null || n.isEmpty()) return "";
        int cut = n.indexOf(" · ");
        return cut > 0 ? n.substring(0, cut) : n;
    }

    // ------------------------------------------------------------------ touch

    private int stripAt(float x) {
        int s = (int) ((x - padX) / (stripW + stripGap));
        if (s < 0 || s >= strips()) return -1;
        float left = stripLeft(s);
        if (x < left || x > left + stripW) return -1;
        return s;
    }

    private int zoneAt(float y) {
        float d = Theme.dpi(getContext(), 1f);
        if (y >= knobCy() - knobR() - 8 * d && y <= knobCy() + knobR() + 2 * d) return Z_TRIM;
        if (y >= yIns1() && y <= yIns1() + insH()) return Z_INS1;
        if (y >= yIns2() && y <= yIns2() + insH()) return Z_INS2;
        if (y >= yPanBar() - 10 * d && y <= yPanText() + 2 * d) return Z_PAN;
        if (y >= yMs() && y <= yMs() + msH()) return Z_MUTE;     // left half = M, right half = S
        if (y >= yDbBox() && y <= faderBottom() + 6 * d) return Z_FADER;
        return Z_NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        Mixer mx = host.mixer();
        if (mx == null) return false;
        int n = host.trackCount();
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int s = stripAt(x);
                if (s < 0) return false;
                boolean master = s == n;
                int nudge = trimBtnAt(s, x, y);
                if (nudge != Z_NONE) { nudgeTrim(mx, s, master, nudge == Z_TRIM_UP ? 1 : -1); return true; }
                int z = zoneAt(y);
                if (z == Z_MUTE) {                              // left half M, right half S
                    float half = stripLeft(s) + stripW * 0.5f;
                    if (x > half) z = Z_SOLO;
                }
                if (z == Z_INS1 || z == Z_INS2) {
                    int slot = z == Z_INS1 ? 0 : 1;
                    if (master && slot < host.insertCount()) host.onToggleInsert(slot);
                    else host.onEmptyInsert(master ? -1 : s);
                    return true;
                }
                if (z == Z_MUTE) {
                    if (master) { host.onChanged("总线没有静音，用推子"); return true; }
                    mx.setMute(s, !mx.isMuted(s));
                    host.onChanged((mx.isMuted(s) ? "已静音 " : "取消静音 ") + shortName(host.trackName(s)));
                    invalidate();
                    return true;
                }
                if (z == Z_SOLO) {
                    if (master) { host.onChanged("总线没有独奏"); return true; }
                    mx.setSolo(s, !mx.isSolo(s));
                    host.onChanged((mx.isSolo(s) ? "独奏 " : "取消独奏 ") + shortName(host.trackName(s)));
                    invalidate();
                    return true;
                }
                if (z == Z_PAN) {
                    dragStrip = s; dragZone = z;
                    applyPan(mx, s, master, x);
                    return true;
                }
                if (z == Z_TRIM || z == Z_FADER) {
                    dragStrip = s; dragZone = z; dragStartY = y;
                    dragStartDb = master ? gainToDb(mx.getMasterGain())
                            : (z == Z_TRIM ? mx.getTrimDb(s) : gainToDb(mx.getGain(s)));
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragStrip < 0) return true;
                boolean master = dragStrip == n;
                if (dragZone == Z_PAN) { applyPan(mx, dragStrip, master, x); return true; }
                float dy = dragStartY - y;                       // up = louder
                float span = dragZone == Z_TRIM ? TRIM_MAX_DB * 2f : (FADER_MAX_DB - FADER_MIN_DB);
                float db = dragStartDb + dy * (span / Theme.dpi(getContext(), 150f));
                if (dragZone == Z_TRIM) {
                    if (master) mx.setMasterGain(dbToGain(Math.max(-12f, Math.min(12f, db))));
                    else mx.setTrimDb(dragStrip, db);
                } else {
                    db = Math.max(FADER_MIN_DB, Math.min(FADER_MAX_DB, db));
                    if (master) mx.setMasterGain(dbToGain(db));
                    else mx.setGain(dragStrip, dbToGain(db));
                }
                host.onChanged((dragZone == Z_TRIM ? "trim " : "推子 ") + dbText(db) + " dB");
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragStrip = -1; dragZone = Z_NONE;
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private void applyPan(Mixer mx, int s, boolean master, float x) {
        float left = stripLeft(s) + Theme.dpi(getContext(), 10f);
        float right = stripLeft(s) + stripW - Theme.dpi(getContext(), 10f);
        float centre = stripLeft(s) + stripW * 0.5f;
        float p = (x - centre) / Math.max(1f, right - centre);
        p = Math.max(-1f, Math.min(1f, p));
        if (master) mx.setMasterPan(p); else mx.setPan(s, p);
        String side = p < -0.02f ? ("L" + (int) Math.round(-p * 100)) : (p > 0.02f ? ("R" + (int) Math.round(p * 100)) : "C");
        host.onChanged("pan " + side);
        invalidate();
    }
}
