package com.dsh.v4tune.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import com.dsh.v4tune.model.AudioClip;

import java.util.ArrayList;
import java.util.List;

/**
 * Arrangement strip above the piano roll (v12.5): a ruler plus one lane per track.
 *
 * <p>Each lane shows a thumbnail of its own content, scaled to the whole song: notes are drawn as
 * small blocks at their pitch, audio clips as their waveform. The lane is also the place where the
 * track is selected (tap), reconfigured (long press) and moved on the timeline (horizontal drag).</p>
 */
public class TrackStripView extends View {

    public interface Listener {
        void onLaneSelected(int index);
        void onLaneLongPress(int index);
        void onLaneShift(int index, long deltaTicks);
        void onLaneMute(int index, boolean mute);
        void onLaneSolo(int index, boolean solo);
        /** ruler tap/drag: the tick the user wants to jump to */
        void onSeek(long tick);
        /** select mode: the lane was dragged to another row */
        void onLaneMove(int from, int to);
    }

    /** One row of the strip. Built by the activity; positions are absolute ticks. */
    public static final class Lane {
        public String name = "";
        public String sub = "";
        public boolean audio;
        public boolean mute, solo;
        public final List<long[]> notes = new ArrayList<long[]>();   // {start, dur, key}
        public final List<AudioClip> clips = new ArrayList<AudioClip>();
        public int color;
    }

    private static final int[] PALETTE = {
            0xFF35C8FF, 0xFFFF5FA2, 0xFF6FE39B, 0xFFFFB454, 0xFFB08CFF, 0xFF4ED7D0
    };

    private final List<Lane> lanes = new ArrayList<Lane>();
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint noteP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path tri = new Path();

    private float density;
    private float rulerH, rowH, headW, msW, nameW, laneX;
    private long maxTick = 1920 * 8;
    /** one bar in ticks, from the project time signature (was a hard coded 4/4) */
    private long barTicks = 1920L;
    private int bpm100 = 12000;
    private long playhead;
    private int selected = 0;

    /** horizontal window handed over by the piano roll: same px/tick, same tick at the gutter */
    private boolean windowSet;
    private float viewPpt = 0.25f, viewScroll = 0f, viewLeft = 0f;

    private float downX, downY;
    private long downAt;
    private int pressed = -1;
    private int hitMs = 0;                 // 1 = M, 2 = S, 0 = none
    private boolean longFired, dragging, seeking;
    private boolean editMode = true;
    private boolean draggingLane;
    private int dropIndex = -1;
    private long shiftTicks;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private final Runnable longPress = new Runnable() {
        public void run() {
            if (pressed < 0 || longFired || dragging) return;
            longFired = true;
            if (listener != null) listener.onLaneLongPress(pressed);
            invalidate();
        }
    };

    /** select mode: hold a lane for a moment to pick it up, then drag it to another row */
    private final Runnable laneDrag = new Runnable() {
        public void run() {
            if (pressed < 0 || editMode || seeking) return;
            draggingLane = true;
            dropIndex = pressed;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
        }
    };

    public TrackStripView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        rulerH = dp(18f);
        rowH = dp(38f);
        headW = dp(16f);
        msW = dp(26f);
        nameW = dp(88f);
        laneX = headW + msW + nameW;

        bg.setColor(Theme.PANEL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1f));
        line.setColor(Theme.GRID);
        text.setTextSize(dp(9f));
        noteP.setStyle(Paint.Style.FILL);
        waveP.setStyle(Paint.Style.STROKE);
        waveP.setStrokeWidth(dp(1f));
        accentP.setStyle(Paint.Style.FILL);
        accentP.setColor(Theme.ACCENT);
        chipP.setStyle(Paint.Style.FILL);
        setFocusable(true);
    }

    public void setListener(Listener l) { listener = l; }

    /**
     * Edit mode keeps every direct manipulation (M/S, long press menu, time shift).
     * Select mode turns those off so a stray drag cannot change the song; the only
     * gestures left are picking a lane and dragging it to another row.
     */
    public void setEditMode(boolean edit) { editMode = edit; invalidate(); }

    public void setPlayhead(long tick) {
        if (playhead != tick) { playhead = tick; invalidate(); }
    }

    public void setSongLength(long tick) {
        long v = Math.max(1920L * 4L, tick);
        if (maxTick != v) { maxTick = v; invalidate(); }
    }

    public void setTempo(int bpm100v) {
        if (bpm100 != bpm100v) { bpm100 = Math.max(1, bpm100v); invalidate(); }
    }

    /**
     * Adopt the piano roll's horizontal window, so one tick is drawn at the same screen x in both
     * views: same px/tick (zoom), same tick at the left edge (scroll), same gutter width.
     */
    /** one bar in ticks, from the project time signature (ruler used to assume 4/4) */
    public void setBarTicks(long t) {
        if (t < 240L) t = 240L;
        if (t == barTicks) return;
        barTicks = t;
        invalidate();
    }

    public void followHorizontal(float pxPerTick, float scrollTick, float leftInset) {
        if (pxPerTick <= 0f || Float.isNaN(pxPerTick) || Float.isInfinite(pxPerTick)) return;
        boolean changed = !windowSet || pxPerTick != viewPpt || scrollTick != viewScroll
                || leftInset != viewLeft;
        windowSet = true;
        viewPpt = pxPerTick;
        viewScroll = scrollTick;
        viewLeft = leftInset;
        if (changed) invalidate();
    }

    /** Replaces the lanes and re-measures when the row count changed. */
    public void setLanes(List<Lane> list, int selectedIndex) {
        lanes.clear();
        if (list != null) lanes.addAll(list);
        selected = selectedIndex;
        boolean countChanged = lastCount != lanes.size();
        lastCount = lanes.size();
        if (countChanged) requestLayout();
        invalidate();
    }

    private int lastCount = -1;

    private float dp(float v) { return v * density; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize(getSuggestedMinimumWidth(), wSpec);
        int h = (int) (rulerH + rowH * Math.max(1, lanes.size()) + dp(2f));
        setMeasuredDimension(w, resolveSize(h, hSpec));
    }

    /** px per tick; the whole-song fit only until the piano roll hands its window over */
    private float pxPerTick() {
        if (windowSet) return viewPpt;
        float w = Math.max(dp(20f), getWidth() - laneX - dp(4f));
        return w / (float) Math.max(1L, maxTick);
    }

    /** screen x of a tick - identical to the piano roll once the window has been adopted */
    private float xOfTick(long tick) {
        if (windowSet) return viewLeft + (tick - viewScroll) * viewPpt;
        return laneX + Math.max(0L, Math.min(maxTick, tick)) * pxPerTick();
    }

    private float tickAtX(float x) {
        if (windowSet) return viewScroll + (x - viewLeft) / viewPpt;
        return (x - laneX) / pxPerTick();
    }

    private long snap(long ticks) {
        long beat = 480L;
        return Math.round(ticks / (double) beat) * beat;
    }

    /** ruler tap/drag: hand the tick under the finger to the activity (it owns the seek) */
    private void seekAt(float x) {
        if (x < laneX) return;                    // the header column is not the timeline
        long t = (long) tickAtX(x);
        if (t < 0L) t = 0L;
        if (t > maxTick) t = maxTick;
        if (listener != null) listener.onSeek(t);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        if (w <= 0) return;
        c.drawColor(Theme.BG);
        c.drawRect(0f, 0f, w, getHeight(), bg);

        drawRuler(c, w);
        float top = rulerH;
        for (int i = 0; i < lanes.size(); i++) {
            drawLane(c, i, top + i * rowH, w);
        }
        if (draggingLane && dropIndex >= 0) {   // where the lane will land in select mode
            float y = rulerH + (dropIndex + (dropIndex > pressed ? 1 : 0)) * rowH;
            c.drawLine(laneX, y, w, y, Theme.paint(Theme.ACCENT, dp(2f), false));
        }
        drawPlayhead(c, w);
    }

    private void drawRuler(Canvas c, int w) {
        c.drawRect(0f, 0f, w, rulerH, Theme.paint(Theme.PANEL_DK, 0f, true));
        Paint p = Theme.paint(Theme.TEXT_DIM, dp(8f), true);
        Paint barP = Theme.paint(Theme.BAR, dp(1f), false);
        Paint beatP = Theme.paint(Theme.BEAT, dp(1f), false);
        long bar = barTicks;
        float ppt = pxPerTick();
        if (ppt <= 0f) return;

        // step the grid so lines and labels never crowd, however far the roll is zoomed out
        long lineStep = 1L;
        while (bar * lineStep * ppt < dp(6f) && lineStep < (1L << 10)) lineStep <<= 1;
        long labelStep = lineStep;
        while (bar * labelStep * ppt < dp(38f) && labelStep < (1L << 10)) labelStep <<= 1;

        long from = (long) Math.floor(tickAtX(laneX) / bar) * bar;
        if (from < 0L) from = 0L;
        long to = (long) Math.ceil(tickAtX(w) / bar) * bar;
        if (to > maxTick + bar) to = maxTick + bar;

        for (long t = from; t <= to; t += bar * lineStep) {
            float x = xOfTick(t);
            if (x >= laneX) c.drawLine(x, 0f, x, getHeight(), barP);
            if (x < laneX || x > w) continue;
            if ((t / bar + 1L) % labelStep == 0L) {
                c.drawText(String.valueOf(t / bar + 1L), x + dp(3f), rulerH - dp(5f), p);
            }
        }
        if (bar * ppt >= dp(26f)) {               // beat ticks only when a bar is wide enough
            for (long t = from; t <= to; t += bar) {
                for (int b = 1; b < 4; b++) {
                    float bx = xOfTick(t + b * 480L);
                    if (bx >= laneX && bx <= w) c.drawLine(bx, rulerH - dp(4f), bx, rulerH, beatP);
                }
            }
        }
    }

    private void drawLane(Canvas c, int i, float top, float w) {
        Lane ln = lanes.get(i);
        boolean on = i == selected;
        float bottom = top + rowH;
        c.drawRect(0f, top, w, bottom, Theme.paint(i % 2 == 0 ? 0xFF171B24 : 0xFF14171F, 0f, true));
        c.drawRect(laneX, top, w, bottom, Theme.paint(Theme.withAlpha(ln.color, 0x10), 0f, true));
        if (on) {
            c.drawRect(0f, top, dp(2f), bottom, accentP);
            c.drawRect(0f, top, w, top + dp(1.2f), accentP);
        }
        c.drawLine(laneX - dp(1f), top, laneX - dp(1f), bottom, line);

        // index column
        Paint num = Theme.paint(on ? Theme.ACCENT : Theme.TEXT_DIM, dp(10f), true);
        c.drawText(String.valueOf(i + 1), headW * 0.5f - dp(3f), top + rowH * 0.5f + dp(3.5f), num);

        float mLeft = headW + dp(2f);
        float mTop = top + dp(8f);
        float box = dp(13f);
        drawMs(c, mLeft, mTop, box, "M", ln.mute, Theme.ACCENT2);
        drawMs(c, mLeft, mTop + box + dp(2f), box, "S", ln.solo, Theme.ACCENT);

        // thumbnail clipped to the lane body
        c.save();
        c.clipRect(laneX, top + dp(1f), w, bottom);
        if (dragging && i == pressed && shiftTicks != 0L) {
            c.translate(shiftTicks * pxPerTick(), 0f);
        }
        drawNotes(c, ln, top, bottom);
        drawClips(c, ln, top, bottom, w);

        c.restore();

        // name column: two tight lines so a long track name never covers the thumbnail
        float nx = headW + msW + dp(3f);
        c.save();
        c.clipRect(nx, top, laneX - dp(2f), bottom);
        String title = ln.name == null || ln.name.isEmpty() ? ("Track " + (i + 1)) : ln.name;
        Paint nameP = Theme.paint(on ? Theme.ACCENT : Theme.TEXT, dp(9.5f), true);
        c.drawText(title, nx, top + dp(14f), nameP);
        if (ln.sub != null && !ln.sub.isEmpty()) {
            Paint subp = Theme.paint(Theme.TEXT_DIM, dp(7.5f), true);
            c.drawText(ln.sub, nx, top + dp(26f), subp);
        }
        c.restore();
        c.drawLine(0f, bottom, w, bottom, line);
    }

    private void drawMs(Canvas c, float x, float y, float box, String label, boolean lit, int litColor) {
        Paint p = Theme.paint(lit ? litColor : Theme.CHIP_BG, 0f, true);
        r.set(x, y, x + box, y + box);
        c.drawRoundRect(r, dp(2.5f), dp(2.5f), p);
        Paint t = Theme.paint(lit ? 0xFF0B0E14 : Theme.TEXT_DIM, dp(7.5f), true);
        c.drawText(label, x + box * 0.5f - t.measureText(label) * 0.5f, y + box * 0.72f, t);
    }

    private void drawNotes(Canvas c, Lane ln, float top, float bottom) {
        if (ln.notes.isEmpty()) return;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (long[] n : ln.notes) {
            int k = (int) n[2];
            if (k < lo) lo = k;
            if (k > hi) hi = k;
        }
        if (hi - lo < 6) { int mid = (hi + lo) / 2; lo = mid - 3; hi = mid + 3; }
        float yTop = top + dp(5f), yBottom = bottom - dp(4f);
        float h = Math.max(dp(2f), Math.min(dp(5f), (yBottom - yTop) / (hi - lo + 1) * dp(3f)));
        noteP.setColor(ln.color);
        noteP.setAlpha(220);
        int w = getWidth();
        for (long[] n : ln.notes) {
            float x0 = xOfTick(n[0]);
            float x1 = xOfTick(n[0] + Math.max(1L, n[1]));
            if (x1 < laneX || x0 > w) continue;
            if (x1 - x0 < dp(2f)) x1 = x0 + dp(2f);
            float y = yBottom - (n[2] - lo) * (yBottom - yTop) / Math.max(1, hi - lo) - h;
            c.drawRoundRect(x0, y, x1, y + h, dp(1.2f), dp(1.2f), noteP);
        }
    }

    private void drawClips(Canvas c, Lane ln, float top, float bottom, float w) {
        float yTop = top + dp(6f), yBottom = bottom - dp(5f);
        for (AudioClip cl : ln.clips) {
            if (cl == null) continue;
            float x0 = xOfTick(cl.startTick);
            float x1 = xOfTick(cl.endTick(bpm100));
            if (x1 - x0 < dp(4f)) x1 = x0 + dp(4f);
            if (x1 < laneX || x0 > w) continue;
            c.drawRoundRect(Math.max(x0, laneX), yTop, Math.min(x1, w), yBottom, dp(3f), dp(3f),
                    Theme.paint(Theme.withAlpha(ln.color, 0x33), 0f, true));
            waveP.setColor(ln.color);
            if (cl.data != null && cl.data.ok() && cl.data.left != null) {
                float[] s = cl.data.left;
                int n = s.length;
                int steps = Math.max(1, (int) ((Math.min(x1, w) - Math.max(x0, laneX)) / dp(2f)));
                float mid = (yTop + yBottom) * 0.5f;
                float amp = (yBottom - yTop) * 0.45f;
                for (int i = 0; i < steps; i++) {
                    int from = i * n / steps, to = Math.max(from + 1, (i + 1) * n / steps);
                    float peak = 0f;
                    for (int j = from; j < to; j += Math.max(1, (to - from) / 8)) {
                        peak = Math.max(peak, Math.abs(s[j]));
                    }
                    float x = Math.max(x0, laneX) + i * dp(2f);
                    if (x > w) break;
                    float hh = Math.max(dp(0.6f), peak * amp);
                    c.drawLine(x, mid - hh, x, mid + hh, waveP);
                }
            }
        }
    }

    private void drawPlayhead(Canvas c, int w) {
        float x = xOfTick(playhead);
        if (x < laneX || x > w) return;
        Paint p = Theme.paint(Theme.PLAYHEAD, dp(1.5f), false);
        c.drawLine(x, 0f, x, getHeight(), p);
        tri.reset();
        tri.moveTo(x - dp(4f), 0f);
        tri.lineTo(x + dp(4f), 0f);
        tri.lineTo(x, dp(6f));
        tri.close();
        c.drawPath(tri, Theme.paint(Theme.PLAYHEAD, 0f, true));
    }

    private int laneAt(float y) {
        if (y < rulerH) return -1;
        int i = (int) ((y - rulerH) / rowH);
        return (i >= 0 && i < lanes.size()) ? i : -1;
    }

    private int msAt(float x, float y) {
        if (x < headW + dp(2f) || x > headW + dp(2f) + dp(13f)) return 0;
        int i = laneAt(y);
        if (i < 0) return 0;
        float top = rulerH + i * rowH + dp(8f);
        if (y >= top && y <= top + dp(13f)) return 1;
        if (y >= top + dp(15f) && y <= top + dp(28f)) return 2;
        return 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (y < rulerH) {                 // the time ruler is the seek area
                    seeking = true;
                    pressed = -1;
                    hitMs = 0;
                    handler.removeCallbacks(longPress);
                    seekAt(x);
                    invalidate();
                    return true;
                }
                pressed = laneAt(y);
                hitMs = (editMode && pressed >= 0) ? msAt(x, y) : 0;
                downX = x;
                downY = y;
                downAt = System.currentTimeMillis();
                longFired = false;
                dragging = false;
                draggingLane = false;
                dropIndex = -1;
                shiftTicks = 0L;
                handler.removeCallbacks(longPress);
                handler.removeCallbacks(laneDrag);
                if (editMode && pressed >= 0 && hitMs == 0 && x >= laneX) handler.postDelayed(longPress, 480L);
                if (!editMode && pressed >= 0 && x >= laneX) handler.postDelayed(laneDrag, 320L);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (seeking) { seekAt(x); return true; }
                float dx = x - downX, dy = y - downY;
                if (pressed < 0) return true;
                if (!editMode) {                 // select mode: reorder only after the lane was picked up
                    if (draggingLane) {
                        int to = pressed + Math.round(dy / rowH);
                        dropIndex = Math.max(0, Math.min(lanes.size() - 1, to));
                        invalidate();
                    }
                    return true;
                }
                if (hitMs != 0) return true;
                if (!dragging && Math.abs(dx) > dp(8f) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    dragging = true;
                    handler.removeCallbacks(longPress);
                }
                if (dragging) {
                    shiftTicks = snap((long) (dx / pxPerTick()));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (seeking) {
                    seeking = false;
                    invalidate();
                    return true;
                }
                if (!editMode) {              // select mode: pick a lane or drop it on a new row
                    handler.removeCallbacks(longPress);
                    handler.removeCallbacks(laneDrag);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    int p = pressed;
                    int to = dropIndex;
                    boolean moved = draggingLane;
                    pressed = -1;
                    draggingLane = false;
                    dropIndex = -1;
                    hitMs = 0;
                    invalidate();
                    if (p >= 0 && listener != null) {
                        if (moved && to >= 0 && to != p) listener.onLaneMove(p, to);
                        else listener.onLaneSelected(p);
                    }
                    return true;
                }
                handler.removeCallbacks(longPress);
                int p = pressed;
                pressed = -1;
                boolean wasDrag = dragging;
                long shift = shiftTicks;
                dragging = false;
                shiftTicks = 0L;
                invalidate();
                if (p >= 0 && !longFired) {
                    if (hitMs == 1 && listener != null) listener.onLaneMute(p, !lanes.get(p).mute);
                    else if (hitMs == 2 && listener != null) listener.onLaneSolo(p, !lanes.get(p).solo);
                    else if (wasDrag && shift != 0L && listener != null) listener.onLaneShift(p, shift);
                    else if (listener != null) listener.onLaneSelected(p);
                }
                hitMs = 0;
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                seeking = false;
                draggingLane = false;
                dropIndex = -1;
                handler.removeCallbacks(longPress);
                handler.removeCallbacks(laneDrag);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                pressed = -1;
                hitMs = 0;
                dragging = false;
                shiftTicks = 0L;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    /** palette colour for a track index, shared with the piano roll accent */
    public static int colorFor(int index) {
        return PALETTE[Math.abs(index) % PALETTE.length];
    }
}
