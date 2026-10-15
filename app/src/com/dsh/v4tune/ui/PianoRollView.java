package com.dsh.v4tune.ui;

import android.content.Context; import android.graphics.Canvas; import android.graphics.Paint;
import android.graphics.Path; import android.os.Handler; import android.os.Looper;
import android.os.SystemClock; import android.view.MotionEvent; import android.view.View; import android.view.ViewConfiguration;

import com.dsh.v4tune.model.Note; import com.dsh.v4tune.model.Part; import com.dsh.v4tune.model.Song; import com.dsh.v4tune.model.Track;

/**
 * Touch driven piano roll for the V4 tune editor.
 *
 * One finger on the grid grabs and scrolls, a pinch zooms (horizontal = time, vertical = pitch),
 * a double tap resets the zoom. Notes are tapped to select, dragged to move/resize/change
 * velocity and long pressed to delete. The view edits the shared Note/Part objects in place and
 * only reports through {@link Listener}; it never replaces the model objects.
 */
public class PianoRollView extends View {

    /** notified whenever the zoom level changes (pinch, buttons, slider, resize) */
    public interface ZoomListener {
        void onZoomChanged(float pxPerTick);
        /** the horizontal window moved or resized: pxPerTick plus the tick at the left edge */
        default void onViewChanged(float pxPerTick, float scrollTick) { }
    }

    /** keeps the parameter lane aligned with this roll, whatever moved it */
    private void notifyView() {
        if (zoomListener != null) zoomListener.onViewChanged(pxPerTick, scrollTick);
    }

    @Override
    protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        notifyView();
    }

    public interface Listener { void onSelectionChanged(Note n); void onNoteEdited(Note n, String what);
        /** a clip drag begins: the caller snapshots the project for undo */
        void onClipDragStart();
        /** a clip was dragged to a new start tick */
        void onClipEdited(com.dsh.v4tune.model.AudioClip c, long oldTick);
        void onRequestLyricEdit(Note n);
        /** a piano key was played (single tap or glissando drag) - audition that pitch */
        void onKeyAudition(int key);
        /** the user dragged the playback cursor to this tick */
        void onPlayheadMoved(long tick);
    }

    /** values passed to {@link Listener#onNoteEdited(Note, String)} */
    public static final String WHAT_ADD = "add"; public static final String WHAT_MOVE = "move";
    public static final String WHAT_RESIZE = "resize"; public static final String WHAT_DELETE = "delete";
    public static final String WHAT_LYRIC = "lyric"; public static final String WHAT_VEL = "vel";

    private static final String[] PC_NAME = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final String[] KEY_NAME = new String[128]; private static final boolean[] IS_BLACK = new boolean[128];
    static {
        for (int i = 0; i < 128; i++) { int pc = i % 12; KEY_NAME[i] = PC_NAME[pc] + (i / 12 - 1);
            IS_BLACK[i] = pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
        }
    }

    private static final int MODE_NONE = 0, MODE_SCROLL = 1, MODE_MOVE = 2, MODE_RESIZE = 3,
            MODE_VEL = 4, MODE_ZOOM = 5, MODE_KEYS = 6, MODE_INERT = 7, MODE_PLAYHEAD = 8;
    private static final int MODE_CLIP = 9;

    private static final long MIN_DUR = 30L; private static final long FIRST_SPAN = 3840L; private static final float FIRST_SEMIS = 26f;

    // ---------------------------------------------------------------- geometry
    private float density; private float keyW, rulerH; private float pxPerTick = 0.25f;
    private float pxPerSemitone = 24f; private float scrollTick; private float topKey = 84f;
    private boolean zoomInited, vInited; private boolean pendingRange; private long pendingFrom, pendingTo;
    private float velZone, resizeEdge, minHitW, minHitH;

    // ------------------------------------------------------------------- model
    private Song song; private int trackIndex; private Listener listener; private Note selected;
    private ZoomListener zoomListener;
    private int snapTicks = 120; private boolean snapEnabled = true; private long playheadTick = -1L;
    private int lastAuditionKey = -1;   // glissando throttle for the piano-key strip

    // --------------------------------------------------------------- bar cache
    private long[] barTicks = new long[64]; private int[] barBeats = new int[64]; private int barCount;
    private boolean barsDirty = true;

    // ------------------------------------------------------------------ paints
    private Paint gridP, beatP, barP, rowBlackP, rowCP, keyWhiteP, keyBlackP, keyLineP, keyTextP;
    private Paint noteP, noteEdgeP, velP, selP, playP, playFillP, lyricP, nameP, rulerP, nameBgP;
    private Paint rulerTickP, barTextP, emptyP, badgeP, badgeBgP, partEdgeP;
    private Paint.FontMetrics lyricFm, keyFm, rulerFm, badgeFm; private float lyricMid, keyMid, rulerMid, badgeMid;

    private final Path marker = new Path(); private final char[] numBuf = new char[24];

    // ------------------------------------------------------------------ audio clip lane

    /** imported audio clips of the current track, drawn as blocks in the lane at the bottom */
    private java.util.List<com.dsh.v4tune.model.AudioClip> clips =
            new java.util.ArrayList<com.dsh.v4tune.model.AudioClip>();
    private float clipH;
    private Paint clipP, clipTxtP, clipSelP;
    private com.dsh.v4tune.model.AudioClip selClip, dragClip;
    private long dragOrigTick, dragGrabTick;

    /** the lane occupies the bottom strip of the roll */
    public void setClips(java.util.List<com.dsh.v4tune.model.AudioClip> list) {
        clips = (list == null) ? new java.util.ArrayList<com.dsh.v4tune.model.AudioClip>() : list;
        invalidate();
    }

    private float clipTop() { return Math.max(rulerH + dp(20f), getHeight() - clipH); }

    /** the clip under x in the lane, or null */
    private com.dsh.v4tune.model.AudioClip clipAt(float x) {
        int bpm = song == null || song.tempos.isEmpty() ? 12000 : (int) song.tempos.get(0)[1];
        for (int i = clips.size() - 1; i >= 0; i--) {
            com.dsh.v4tune.model.AudioClip c = clips.get(i);
            if (c == null) continue;
            float x0 = xOfTick(c.startTick);
            float x1 = xOfTick(c.endTick(bpm));
            if (x1 - x0 < dp(6f)) x1 = x0 + dp(6f);
            if (x >= x0 - dp(2f) && x <= x1 + dp(2f)) return c;
        }
        return null;
    }

    private void drawClipLane(Canvas c, float gl, float gr, float gb) {
        float top = clipTop();
        if (gb - top < dp(10f)) return;
        c.drawRect(0f, top, gr, gb, clipP == null ? Theme.paint(Theme.PANEL_DK, 0f, true) : clipBg());
        c.drawLine(gl, top, gr, top, Theme.paint(Theme.STROKE, dp(1f), false));
        int bpm = song == null || song.tempos.isEmpty() ? 12000 : (int) song.tempos.get(0)[1];
        for (com.dsh.v4tune.model.AudioClip cl : clips) {
            if (cl == null) continue;
            float x0 = xOfTick(cl.startTick), x1 = xOfTick(cl.endTick(bpm));
            if (x1 - x0 < dp(4f)) x1 = x0 + dp(4f);
            if (x1 < gl || x0 > gr) continue;
            float left = Math.max(x0, gl), right = Math.min(x1, gr);
            boolean on = (cl == selClip);
            Theme.rr(c, left + 1f, top + dp(3f), right - 1f, gb - dp(3f), dp(4f),
                    Theme.paint(on ? Theme.ACCENT2 : 0xFF3A5A8C, 0f, true));
            String label = (cl.name == null ? "audio" : cl.name);
            if (right - left > dp(30f)) {
                c.save();
                c.clipRect(left + dp(4f), top, right - dp(2f), gb);
                c.drawText(label, left + dp(5f), gb - dp(8f), clipText(on));
                c.restore();
            }
        }
    }

    private Paint clipBgPaint;

    private Paint clipBg() {
        if (clipBgPaint == null) clipBgPaint = Theme.paint(Theme.PANEL_DK, 0f, true);
        return clipBgPaint;
    }

    private Paint clipText(boolean on) {
        if (clipTxtP == null) clipTxtP = Theme.paint(Theme.TEXT, dp(11f), true);
        clipTxtP.setColor(on ? 0xFF06202B : Theme.TEXT);
        return clipTxtP;
    }
    // ------------------------------------------------------------------- touch
    private int mode = MODE_NONE; private float downX, downY, lastX, lastY, downScroll, downTop;
    private boolean moved, multi, dbl, changed; private long lastTapTime; private long downTime, lastDeleteAt; private float lastTapX, lastTapY;
    private Note hitNote, justCreated; private Part hitPart; private long origPos, origDur, justCreatedAt;
    private int origKey, origVel; private float prevMidX, prevMidY, prevSpanX, prevSpanY;
    private float selL, selT, selR, selB; private boolean selSeen; private int slop, longPressMs;
    private final Handler handler = new Handler(Looper.getMainLooper()); private final NoteHit hit = new NoteHit();

    private static final class NoteHit { Note n; Part p; boolean resize; boolean vel; }

    private final Runnable longPress = new Runnable() {
        public void run() { if (mode != MODE_MOVE || hitNote == null || moved) return; Note n = hitNote;
            Part p = (hitPart != null) ? hitPart : ownerOf(n); if (p != null) p.notes.remove(n);
            if (selected == n) { selected = null; if (listener != null) listener.onSelectionChanged(null); }
            mode = MODE_INERT; changed = false; lastDeleteAt = SystemClock.uptimeMillis();
            if (listener != null) listener.onNoteEdited(n, WHAT_DELETE); invalidate();
        }
    };

    public PianoRollView(Context c) { super(c); init(c); }

    private void init(Context c) { density = c.getResources().getDisplayMetrics().density; keyW = dp(64f); clipH = dp(26f);
        rulerH = dp(24f); pxPerSemitone = dp(22f); topKey = 84f; velZone = dp(11f); resizeEdge = dp(16f); minHitW = dp(16f); minHitH = dp(14f);
        ViewConfiguration vc = ViewConfiguration.get(c); slop = Math.max(4, vc.getScaledTouchSlop());
        longPressMs = ViewConfiguration.getLongPressTimeout(); setFocusable(true);
        setFocusableInTouchMode(true); float hair = Math.max(1f, density * 0.75f);
        gridP = Theme.paint(Theme.GRID, hair, false); beatP = Theme.paint(Theme.BEAT, hair, false);
        barP = Theme.paint(Theme.BAR, Math.max(1f, density), false);
        rowBlackP = Theme.paint(Theme.ROW_BLACK, 0f, true); rowCP = Theme.paint(Theme.ROW_C, hair, false);
        keyWhiteP = Theme.paint(Theme.KEY_WHITE, 0f, true); keyBlackP = Theme.paint(Theme.KEY_BLACK, 0f, true);
        keyLineP = Theme.paint(Theme.KEY_LINE, hair, false);
        keyTextP = Theme.textPaint(Theme.KEY_TEXT, dp(9f), true); keyTextP.setTextAlign(Paint.Align.RIGHT);
        keyFm = keyTextP.getFontMetrics(); keyMid = (keyFm.ascent + keyFm.descent) * 0.5f;
        noteP = Theme.paint(Theme.ACCENT, 0f, true); noteEdgeP = Theme.paint(0x44000000, Math.max(1f, density), false);
        velP = Theme.paint(0x55000000, 0f, true); selP = Theme.paint(Theme.SELECT, Math.max(1.5f, density * 1.6f), false);
        playP = Theme.paint(Theme.PLAYHEAD, Math.max(1.5f, density * 1.4f), false); playFillP = Theme.paint(Theme.PLAYHEAD, 0f, true);
        lyricP = Theme.textPaint(0xE60A0F16, dp(10f), false); lyricP.setTextAlign(Paint.Align.CENTER);
        lyricFm = lyricP.getFontMetrics(); lyricMid = (lyricFm.ascent + lyricFm.descent) * 0.5f;
        rulerP = Theme.paint(Theme.PANEL, 0f, true); nameBgP = Theme.paint(Theme.PANEL_DK, 0f, true);
        rulerTickP = Theme.paint(Theme.TEXT_DIM, hair, false);
        barTextP = Theme.textPaint(Theme.TEXT_DIM, dp(9f), true); rulerFm = barTextP.getFontMetrics();
        rulerMid = (rulerFm.ascent + rulerFm.descent) * 0.5f; nameP = Theme.textPaint(Theme.ACCENT, dp(10f), true);
        emptyP = Theme.textPaint(Theme.TEXT_DIM, dp(13f), false); emptyP.setTextAlign(Paint.Align.CENTER);
        badgeP = Theme.textPaint(Theme.TEXT, dp(10f), true); badgeFm = badgeP.getFontMetrics();
        badgeMid = (badgeFm.ascent + badgeFm.descent) * 0.5f; badgeBgP = Theme.paint(0xE6000000, 0f, true);
        partEdgeP = Theme.dashPaint(Theme.withAlpha(Theme.ACCENT2, 0x99), Math.max(1f, density), dp(4f), dp(4f));
    }

    private float dp(float v) { return v * density; }

    // --------------------------------------------------------------- public API

    /**
     * setSong() resets the scroll position and the zoom - right when a project is loaded, wrong for
     * an edit. This variant puts the view back afterwards, so dragging a clip or a note no longer
     * makes the timeline jump to the start.
     */
    public void setSongKeepView(Song s) {
        float keepScroll = scrollTick, keepPx = pxPerTick, keepTop = topKey;
        setSong(s);
        scrollTick = keepScroll;
        pxPerTick = keepPx;
        topKey = keepTop;
        zoomInited = true;
        vInited = true;
        clampScroll();
        invalidate();
    }
    public void setSong(Song s) { song = s; selected = null; justCreated = null; hitNote = null; hitPart = null;
        barsDirty = true; zoomInited = false; vInited = false; pendingRange = false; scrollTick = 0f;
        zoomInited = applyDefaultZoom(); clampScroll();
        if (listener != null) listener.onSelectionChanged(null); invalidate();
    }

    public void setTrackIndex(int i) { trackIndex = (i < 0) ? 0 : i; barsDirty = true; validateSelection();
        invalidate();
    }

    public void setListener(Listener l) { listener = l; }

    public void setZoomListener(ZoomListener l) { zoomListener = l; }

    /** tell the UI about a new zoom level, whatever changed it */
    private void notifyZoom() {
        if (zoomListener != null) {
            try {
                zoomListener.onZoomChanged(pxPerTick);
            zoomListener.onViewChanged(pxPerTick, scrollTick);
            } catch (Throwable ignored) {
            }
        }
    }


    public void setSnapTicks(int t) { snapTicks = (t < 0) ? 0 : t; invalidate(); }

    public int getSnapTicks() { return snapTicks; }

    public void setPlayheadTick(long tick) { if (playheadTick == tick) return; playheadTick = tick;
        if (tick >= 0L && getWidth() > keyW + 4f) { float x = xOfTick(tick); float margin = dp(28f);
            if (x < keyW + margin || x > getWidth() - margin) {
                scrollTick = tick - (getWidth() - keyW) * 0.25f / pxPerTick; clampScroll();
            }
        }
        invalidate();
    }

    public void refresh() { barsDirty = true; validateSelection(); invalidate(); }

    public Note getSelectedNote() { return selected; }

    /** Centre the view on a note and select it (used when reporting an overlapping note). */
    public void focusNote(Note n) {
        if (n == null) return;
        selected = n;
        long pad = 1920L;
        setVisibleTickRange(Math.max(0L, n.pos - pad), n.pos + Math.max(1L, n.dur) + pad);
        topKey = n.key + 6f;
        vInited = true;
        clampScroll();
        invalidate();
    }

    public void addNoteAtPlayhead(int key) { Part p = activePart(); if (p == null) return;
        long tick = (playheadTick >= 0L) ? snap(playheadTick) : snap((long) scrollTick);
        if (tick < 0L) tick = 0L; Note n = insertNote(p, tick, key); if (n == null) return; justCreated = n;
        justCreatedAt = SystemClock.uptimeMillis(); select(n); if (listener != null) listener.onNoteEdited(n, WHAT_ADD); invalidate();
    }

    /** the part duration must cover its notes: the renderer sizes its buffer from it */
    private void syncPartDur(Part p) {
        if (p == null) return;
        long end = p.pos + 480L;
        for (Note n : p.notes) end = Math.max(end, n.pos + n.dur);
        if (end - p.pos > p.dur) p.dur = end - p.pos;
    }

    public void deleteSelected() { Note n = selected; if (n == null) return; Part p = ownerOf(n);
        if (p != null) p.notes.remove(n); selected = null; if (justCreated == n) justCreated = null;
        lastDeleteAt = SystemClock.uptimeMillis();
        if (listener != null) { listener.onSelectionChanged(null); listener.onNoteEdited(n, WHAT_DELETE); }
        invalidate();
    }

    /** horizontal zoom only (used by the zoom slider) */
    public void setZoomX(float x) {
        float cx = keyW + (getWidth() - keyW) * 0.5f;
        float tAt = tickAtX(cx);
        pxPerTick = clampTickPx(x);
        scrollTick = tAt - (cx - keyW) / pxPerTick;
        clampScroll();
        zoomInited = true;
        notifyZoom();
        invalidate();
    }

    /** zoom in/out around the centre of the visible area */
    public void zoomBy(float factor) {
        float cx = keyW + (getWidth() - keyW) * 0.5f;
        float cy = rulerH + (getHeight() - rulerH) * 0.5f;
        float tAt = tickAtX(cx), kAt = keyAtY(cy);
        pxPerTick = clampTickPx(pxPerTick * factor);
        scrollTick = tAt - (cx - keyW) / pxPerTick;
        clampScroll();
        zoomInited = true;
        invalidate();
    }

    public void setZoom(float pxPerTick, float pxPerSemitone) { float cx = keyW + (getWidth() - keyW) * 0.5f;
        float cy = rulerH + (getHeight() - rulerH) * 0.5f; float tAt = tickAtX(cx), kAt = keyAtY(cy);
        this.pxPerTick = clampTickPx(pxPerTick); this.pxPerSemitone = clampKeyPx(pxPerSemitone);
        scrollTick = tAt - (cx - keyW) / this.pxPerTick; topKey = kAt + (cy - rulerH) / this.pxPerSemitone;
        zoomInited = true; vInited = true; clampScroll(); invalidate();
    }

    public float getPxPerTick() { return pxPerTick; }

    public void setVisibleTickRange(long from, long to) { if (to <= from) to = from + 1L; pendingFrom = from;
        pendingTo = to; pendingRange = true; applyPendingRange(); invalidate();
    }

    public long getVisibleTickStart() { return (long) Math.floor(scrollTick); }

    public long getVisibleTickEnd() { return (long) Math.ceil(scrollTick + visibleTicks()); }

    public void setGridSnapEnabled(boolean b) { snapEnabled = b; invalidate(); }

    // ------------------------------------------------------------- model access

    private Track currentTrack() { return song == null ? null : song.track(trackIndex); }

    private Part activePart() { Track t = currentTrack(); if (t == null || t.parts.isEmpty()) return null;
        if (playheadTick >= 0L) { Part p = t.partAt(playheadTick); if (p != null) return p; }
        if (selected != null) {
            for (int i = 0; i < t.parts.size(); i++) {
                if (t.parts.get(i).notes.contains(selected)) return t.parts.get(i);
            }
        }
        return t.parts.get(0);
    }

    private Part ownerOf(Note n) { Track t = currentTrack(); if (t == null || n == null) return null;
        for (int i = 0; i < t.parts.size(); i++) { if (t.parts.get(i).notes.contains(n)) return t.parts.get(i); }
        return null;
    }

    private void validateSelection() {
        if (selected != null && ownerOf(selected) == null) { selected = null;
            if (listener != null) listener.onSelectionChanged(null);
        }
    }

    private Note insertNote(Part p, long tick, int key) { if (p == null) return null; Note n = new Note();
        n.pos = tick < 0L ? 0L : tick; n.key = clampKey(key);
        n.dur = Math.max(480L, snapEnabled ? snapTicks : 0L); n.vel = 64; n.lyric = inheritLyric(p, n.pos);
        syncPartDur(p);
        p.notes.add(n); p.sortNotes(); return n;
    }

    private String inheritLyric(Part p, long tick) { String best = null; long bt = Long.MIN_VALUE;
        for (int i = 0; i < p.notes.size(); i++) { Note o = p.notes.get(i);
            if (o.pos < tick && o.pos > bt && o.lyric != null && o.lyric.length() > 0) { bt = o.pos; best = o.lyric;
            }
        }
        if (best != null) return best;
        for (int i = p.notes.size() - 1; i >= 0; i--) { Note o = p.notes.get(i);
            if (o.lyric != null && o.lyric.length() > 0) return o.lyric;
        }
        return "a";
    }

    private void select(Note n) { if (selected == n) return; selected = n;
        if (listener != null) listener.onSelectionChanged(n); invalidate();
    }

    private static int clampKey(int k) { if (k < 0) return 0; if (k > 127) return 127; return k; }

    // --------------------------------------------------------------- transform

    private static long floorTo(long v, long s) { long r = v % s; if (r < 0L) r += s; return v - r; }

    private float visibleTicks() { float w = (getWidth() - keyW) / pxPerTick; return w < 1f ? 1f : w; }

    private float visibleSemis() { float h = (getHeight() - rulerH) / pxPerSemitone; return h < 1f ? 1f : h; }

    private float xOfTick(long t) { return keyW + (t - scrollTick) * pxPerTick; }

    private float tickAtX(float x) { return scrollTick + (x - keyW) / pxPerTick; }

    private float yOfKey(int k) { return rulerH + (topKey - k) * pxPerSemitone; }

    private float keyAtY(float y) { return topKey - (y - rulerH) / pxPerSemitone; }

    private long snap(long t) { if (!snapEnabled || snapTicks <= 0) return t; long s = snapTicks;
        if (t >= 0L) return ((t + s / 2L) / s) * s; return -(((-t + s / 2L) / s) * s);
    }

    private float clampTickPx(float v) { if (Float.isNaN(v) || Float.isInfinite(v) || v <= 0f) return 0.25f;
        if (v < 0.0015f) return 0.0015f; if (v > 8f) return 8f; return v;
    }

    private float clampKeyPx(float v) { float lo = dp(3f), hi = dp(72f);
        if (Float.isNaN(v) || Float.isInfinite(v) || v <= 0f) return dp(22f); if (v < lo) return lo; if (v > hi) return hi; return v;
    }

    private boolean applyDefaultZoom() { if (getWidth() <= keyW + 2f || getHeight() <= rulerH + 2f) return false;
        pxPerTick = clampTickPx((getWidth() - keyW) / (float) FIRST_SPAN); scrollTick = 0f; clampScroll(); return true;
    }

    private void applyDefaultKeyZoom() { if (getHeight() <= rulerH + 2f) return;
        pxPerSemitone = clampKeyPx((getHeight() - rulerH) / FIRST_SEMIS);
        topKey = 72f + visibleSemis() * 0.5f; vInited = true;
    }

    private void applyPendingRange() { float gw = getWidth() - keyW;
        if (gw <= 1f || pendingTo <= pendingFrom) return;
        pxPerTick = clampTickPx(gw / (float) (pendingTo - pendingFrom)); scrollTick = pendingFrom; zoomInited = true; clampScroll();
    }

    private void clampScroll() { float vis = visibleTicks(); float maxStart = 1920f;
        if (song != null) maxStart = Math.max((float) song.maxTick(), 1920f) + vis * 0.5f;
        float minStart = -vis * 0.25f; if (scrollTick < minStart) scrollTick = minStart;
        if (scrollTick > maxStart) scrollTick = maxStart; if (Float.isNaN(scrollTick) || Float.isInfinite(scrollTick)) scrollTick = 0f;
        float vs = visibleSemis(); float minTop = vs - 6f, maxTop = 133f;
        if (minTop > maxTop) { topKey = minTop;
        } else { if (topKey < minTop) topKey = minTop; if (topKey > maxTop) topKey = maxTop; }
        if (Float.isNaN(topKey) || Float.isInfinite(topKey)) topKey = 84f;
        // Everything that moves the horizontal window - dragging, pinching, the slider, a new
        // project, a resize - ends up here, so this is the one place that keeps the tuning lane
        // aligned with the note grid. (onScrollChanged never fires: panning only changes scrollTick,
        // it does not call scrollTo.)
        notifyView();
    }

    private void zoomAt(float fx, float fy, float sx, float sy) { float tAt = tickAtX(fx), kAt = keyAtY(fy);
        pxPerTick = clampTickPx(pxPerTick * sx); pxPerSemitone = clampKeyPx(pxPerSemitone * sy); notifyZoom();
        scrollTick = tAt - (fx - keyW) / pxPerTick; topKey = kAt + (fy - rulerH) / pxPerSemitone; clampScroll();
    }

    /** play the key under the finger (also used for a glissando when dragging along the keyboard) */
    private void auditionKeyAt(float y) {
        int k = clampKey((int) Math.floor(keyAtY(y)));
        if (k == lastAuditionKey) return;
        lastAuditionKey = k;
        if (listener != null) listener.onKeyAudition(k);
    }

    /** drag the playback cursor */
    private void movePlayheadTo(float tick) {
        long t = (long) tick;
        if (t < 0L) t = 0L;
        if (!snapEnabled) { /* free positioning while dragging the cursor */ }
        if (playheadTick == t) return;
        playheadTick = t;
        if (listener != null) listener.onPlayheadMoved(t);
        invalidate();
    }

    private void centreOnKey(int key) { topKey = clampKey(key) + visibleSemis() * 0.5f; clampScroll(); invalidate();
    }

    private void resetZoom(float x, float y) { float tAt = tickAtX(x), kAt = keyAtY(y);
        pxPerTick = clampTickPx((getWidth() - keyW) / (float) FIRST_SPAN);
        pxPerSemitone = clampKeyPx((getHeight() - rulerH) / FIRST_SEMIS);
        scrollTick = tAt - (x - keyW) / pxPerTick; topKey = kAt + (y - rulerH) / pxPerSemitone;
        zoomInited = true; vInited = true; clampScroll(); notifyZoom(); invalidate();
    }

    // ------------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) { final int action = e.getActionMasked();
        final int pc = e.getPointerCount(); final float x = e.getX(), y = e.getY();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                cancelPress(); downX = lastX = x; downY = lastY = y; downScroll = scrollTick;
                downTop = topKey; moved = false; multi = false; changed = false; dbl = isDoubleTap(x, y);
                downTime = SystemClock.uptimeMillis();
                if (dbl) { handleDoubleTap(x, y); mode = MODE_INERT; return true; }
                if (!clips.isEmpty() && y >= clipTop() && x > keyW) {
                    com.dsh.v4tune.model.AudioClip hit = clipAt(x);
                    selClip = hit;
                    if (hit != null) {
                        dragClip = hit; dragOrigTick = hit.startTick;
                        dragGrabTick = (long) tickAtX(x) - hit.startTick;
                        mode = MODE_CLIP;
                        if (listener != null) listener.onClipDragStart();
                    } else {
                        mode = MODE_SCROLL;
                    }
                    invalidate();
                    return true;
                }
                if (x < keyW && y > rulerH) { mode = MODE_KEYS; auditionKeyAt(y);
                } else if (y <= rulerH) { mode = MODE_PLAYHEAD; movePlayheadTo(tickAtX(x));
                } else if (playheadTick >= 0L && Math.abs(x - xOfTick(playheadTick)) <= dp(11f)) {
                    mode = MODE_PLAYHEAD; movePlayheadTo(tickAtX(x));
                } else { NoteHit h = hitTest(x, y);
                    if (h != null) { hitNote = h.n; hitPart = h.p; select(hitNote); origPos = h.n.pos;
                        origDur = h.n.dur; origKey = h.n.key; origVel = h.n.vel;
                        mode = h.vel ? MODE_VEL : (h.resize ? MODE_RESIZE : MODE_MOVE); startLongPress();
                    } else { mode = MODE_SCROLL; }
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == MODE_ZOOM) { if (pc >= 2) doPinch(e); return true; }
                if (mode == MODE_INERT || mode == MODE_NONE) return true;
                if (!moved) { if (Math.abs(x - downX) <= slop && Math.abs(y - downY) <= slop) return true;
                    moved = true; cancelPress();
                }
                if (mode == MODE_CLIP && dragClip != null) {
                    dragClip.startTick = Math.max(0L, snap((long) tickAtX(x) - dragGrabTick));
                    changed = true;
                    invalidate();
                    return true;
                }
                if (mode == MODE_SCROLL) { scrollTick = downScroll - (x - downX) / pxPerTick;
                    topKey = downTop + (y - downY) / pxPerSemitone; clampScroll(); invalidate();
                } else if (mode == MODE_KEYS) { topKey = downTop + (y - downY) / pxPerSemitone; clampScroll();
                    auditionKeyAt(y); invalidate();
                } else if (mode == MODE_PLAYHEAD) { movePlayheadTo(tickAtX(x));
                } else if (mode == MODE_MOVE && hitNote != null) { long d = (long) ((x - downX) / pxPerTick);
                    long np = snap(origPos + d); if (np < 0L) np = 0L;
                    int nk = clampKey(origKey + Math.round((downY - y) / pxPerSemitone));
                    if (np != hitNote.pos || nk != hitNote.key) { hitNote.pos = np; hitNote.key = nk;
                        changed = true; invalidate();
                    }
                } else if (mode == MODE_RESIZE && hitNote != null) { long d = (long) ((x - downX) / pxPerTick);
                    long nd = snap(origDur + d); if (nd < MIN_DUR) nd = MIN_DUR;
                    if (nd != hitNote.dur) { hitNote.dur = nd; syncPartDur(hitPart); changed = true; invalidate(); }
                } else if (mode == MODE_VEL && hitNote != null) {
                    int nv = origVel + Math.round((downY - y) / (pxPerSemitone * 4f) * 127f);
                    if (nv < 0) nv = 0; if (nv > 127) nv = 127;
                    if (nv != hitNote.vel) { hitNote.vel = nv; changed = true; invalidate(); }
                }
                lastX = x; lastY = y; return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                cancelPress(); commitEdit(); multi = true; mode = MODE_ZOOM;
                prevMidX = (e.getX(0) + e.getX(1)) * 0.5f; prevMidY = (e.getY(0) + e.getY(1)) * 0.5f;
                prevSpanX = Math.abs(e.getX(0) - e.getX(1)); prevSpanY = Math.abs(e.getY(0) - e.getY(1)); invalidate(); return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                mode = MODE_INERT; multi = true; return true;
            }
            case MotionEvent.ACTION_UP: {
                cancelPress();
                if (mode == MODE_CLIP) {
                    com.dsh.v4tune.model.AudioClip c2 = dragClip;
                    dragClip = null; mode = MODE_NONE;
                    if (c2 != null && c2.startTick != dragOrigTick && listener != null) {
                        listener.onClipEdited(c2, dragOrigTick);
                    }
                    invalidate(); performClick(); return true;
                }
                if (!multi && !dbl) {
                    if (mode == MODE_PLAYHEAD) { /* cursor stay where dropped */ }
                    else if (mode == MODE_SCROLL && !moved && y > rulerH && x > keyW
                            && SystemClock.uptimeMillis() - downTime < ViewConfiguration.getLongPressTimeout()
                            && SystemClock.uptimeMillis() - lastDeleteAt > 400L) { createNoteAt(x, y);
                    } else if (mode == MODE_KEYS) { /* audition only: the keyboard must not move when a key is tapped */ }
                    else { commitEdit(); }
                }
                mode = MODE_NONE; multi = false; dbl = false; lastAuditionKey = -1; performClick(); return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                cancelPress(); commitEdit(); mode = MODE_NONE; multi = false; dbl = false; return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    private void doPinch(MotionEvent e) { float mx = (e.getX(0) + e.getX(1)) * 0.5f;
        float my = (e.getY(0) + e.getY(1)) * 0.5f; float sxn = Math.abs(e.getX(0) - e.getX(1));
        float syn = Math.abs(e.getY(0) - e.getY(1)); float fx = 1f, fy = 1f;
        if (prevSpanX > dp(12f) && sxn > dp(12f)) fx = sxn / prevSpanX; if (prevSpanY > dp(12f) && syn > dp(12f)) fy = syn / prevSpanY;
        if (Math.abs(fx - 1f) < 0.0015f) fx = 1f; if (Math.abs(fy - 1f) < 0.0015f) fy = 1f;
        if (fx != 1f || fy != 1f) zoomAt(mx, my, fx, fy); float pdx = mx - prevMidX, pdy = my - prevMidY;
        if (pdx != 0f || pdy != 0f) { scrollTick -= pdx / pxPerTick; topKey += pdy / pxPerSemitone; clampScroll(); }
        prevMidX = mx; prevMidY = my; prevSpanX = sxn; prevSpanY = syn; invalidate();
    }

    private boolean isDoubleTap(float x, float y) { long now = SystemClock.uptimeMillis();
        boolean d = (now - lastTapTime <= ViewConfiguration.getDoubleTapTimeout())
                && Math.abs(x - lastTapX) <= dp(30f)
                && Math.abs(y - lastTapY) <= dp(30f);
        lastTapTime = d ? 0L : now; lastTapX = x; lastTapY = y; return d;
    }

    private void handleDoubleTap(float x, float y) { cancelPress();
        if (x < keyW) { if (y > rulerH) centreOnKey((int) Math.floor(keyAtY(y))); return; }
        if (y <= rulerH) { resetZoom(x, y); return; }
        NoteHit h = hitTest(x, y);
        if (h == null) { resetZoom(x, y); return; }
        if (h.n == justCreated
                && SystemClock.uptimeMillis() - justCreatedAt < ViewConfiguration.getDoubleTapTimeout() + 80L) {
            removeTransient(h.n, h.p); resetZoom(x, y); return;
        }
        select(h.n); if (listener != null) listener.onRequestLyricEdit(h.n);
    }

    private void removeTransient(Note n, Part p) { if (n == null) return; Part op = (p != null) ? p : ownerOf(n);
        if (op != null) op.notes.remove(n); if (justCreated == n) justCreated = null;
        lastDeleteAt = SystemClock.uptimeMillis();
        if (selected == n) { selected = null; if (listener != null) listener.onSelectionChanged(null); }
        if (listener != null) listener.onNoteEdited(n, WHAT_DELETE); invalidate();
    }

    private void createNoteAt(float x, float y) { Part p = activePart(); if (p == null) return;
        long tick = snap((long) tickAtX(x)); if (tick < 0L) tick = 0L;
        Note n = insertNote(p, tick, (int) Math.floor(keyAtY(y))); if (n == null) return; justCreated = n;
        justCreatedAt = SystemClock.uptimeMillis(); select(n); if (listener != null) listener.onNoteEdited(n, WHAT_ADD); invalidate();
    }

    private void commitEdit() { int m = mode; if (m != MODE_MOVE && m != MODE_RESIZE && m != MODE_VEL) return;
        Note n = hitNote; boolean did = changed; changed = false; mode = MODE_NONE;
        if (n == null || !did) return; Part p = (hitPart != null) ? hitPart : ownerOf(n); if (p != null) p.sortNotes();
        if (listener != null) {
            listener.onNoteEdited(n, m == MODE_MOVE ? WHAT_MOVE : (m == MODE_RESIZE ? WHAT_RESIZE : WHAT_VEL));
        }
        invalidate();
    }

    private NoteHit hitTest(float x, float y) { Track t = currentTrack(); if (t == null) return null;
        Part act = activePart(); if (act != null && hitIn(act, x, y)) return hit;
        for (int i = t.parts.size() - 1; i >= 0; i--) { Part p = t.parts.get(i); if (p == act) continue;
            if (hitIn(p, x, y)) return hit;
        }
        return null;
    }

    private boolean hitIn(Part p, float x, float y) {
        for (int i = p.notes.size() - 1; i >= 0; i--) { Note n = p.notes.get(i);
            float x0 = xOfTick(n.pos), x1 = xOfTick(n.end()); float w = x1 - x0;
            if (w < minHitW) { float cx = (x0 + x1) * 0.5f; x0 = cx - minHitW * 0.5f; x1 = cx + minHitW * 0.5f;
            } else { x0 -= dp(3f); x1 += dp(3f); }
            float y0 = yOfKey(n.key + 1), y1 = yOfKey(n.key); float h = y1 - y0;
            if (h < minHitH) { float cy = (y0 + y1) * 0.5f; y0 = cy - minHitH * 0.5f; y1 = cy + minHitH * 0.5f;
            } else { y0 -= dp(2f); y1 += dp(2f); }
            if (x >= x0 && x <= x1 && y >= y0 && y <= y1) { hit.n = n; hit.p = p; float real = x1 - x0;
                hit.resize = (x >= x1 - resizeEdge) && real > resizeEdge * 2.2f;
                hit.vel = !hit.resize && (x <= x0 + velZone) && real > resizeEdge * 1.6f; return true;
            }
        }
        return false;
    }

    private void startLongPress() { handler.postDelayed(longPress, longPressMs); }

    private void cancelPress() { handler.removeCallbacks(longPress); }

    // -------------------------------------------------------------- bar ruler

    private void addBar(long tick, int beat) {
        if (barCount == barTicks.length) { int nc = barCount * 2; long[] nt = new long[nc];
            System.arraycopy(barTicks, 0, nt, 0, barCount); int[] nb = new int[nc];
            System.arraycopy(barBeats, 0, nb, 0, barCount); barTicks = nt; barBeats = nb;
        }
        barTicks[barCount] = tick; barBeats[barCount] = beat; barCount++;
    }

    private void rebuildBars() { barsDirty = false; barCount = 0; if (song == null) return;
        long limit = Math.max(1920L, song.maxTick()) + 1920L; if (limit > 4000000L) limit = 4000000L; long t = 0L; int guard = 0;
        while (t < limit && guard++ < 8192) { int m = 4, de = 4;
            if (!song.timeSigs.isEmpty()) { int[] sig = song.timeSigAt(t);
                if (sig != null && sig.length > 3) {
                    if (sig[1] > 0) m = sig[1]; if (sig[3] > 0) de = sig[3];
                }
            }
            long barLen = (long) m * (4L * 480L) / de; if (barLen < 60L) barLen = 60L;
            long beat = barLen / m; if (beat < 20L) beat = 20L; addBar(t, (int) beat); t += barLen;
        }
    }

    private int barIndexAt(long tick) { int lo = 0, hi = barCount - 1, res = 0;
        while (lo <= hi) { int mid = (lo + hi) >>> 1;
            if (barTicks[mid] <= tick) { res = mid; lo = mid + 1;
            } else { hi = mid - 1; }
        }
        return res;
    }

    // ----------------------------------------------------------------- drawing

    @Override
    protected void onMeasure(int wSpec, int hSpec) { int w = resolveSize((int) dp(320f), wSpec);
        int h = resolveSize((int) dp(240f), hSpec); setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh);
        if (pendingRange) applyPendingRange(); else if (!zoomInited) zoomInited = applyDefaultZoom();
        if (!vInited) applyDefaultKeyZoom(); clampScroll();
    }

    @Override
    protected void onDraw(Canvas c) { final int w = getWidth(), h = getHeight(); if (w <= 0 || h <= 0) return;
        if (barsDirty) rebuildBars(); final float gl = keyW, gt = rulerH, gr = w, gb = h;
        final Track t = currentTrack(); final Part act = activePart(); c.drawColor(Theme.BG);
        selSeen = false; c.save(); c.clipRect(gl, gt, gr, gb); drawGrid(c, gl, gt, gr, gb);
        if (act != null) drawPartEdges(c, act, gt, gb); if (t != null) drawNotes(c, t, act, gl, gt, gr, gb);
        if (!clips.isEmpty()) drawClipLane(c, gl, gr, gb);
        if (selSeen) Theme.rr(c, selL - dp(1f), selT - dp(1f), selR + dp(1f), selB + dp(1f), dp(4f), selP);
        c.restore(); c.save(); c.clipRect(0f, gt, keyW, gb); drawKeyboard(c, gb); c.restore(); c.save();
        c.clipRect(0f, 0f, w, gt); drawRuler(c, w); c.restore(); drawPlayhead(c, gl, gb);
        if (t == null) { c.drawText("no track", (gl + gr) * 0.5f, (gt + gb) * 0.5f, emptyP);
        } else if (act == null) { c.drawText("no part", (gl + gr) * 0.5f, (gt + gb) * 0.5f, emptyP); }
        if (mode == MODE_VEL && hitNote != null) drawVelBadge(c);
    }

    private void drawGrid(Canvas c, float gl, float gt, float gr, float gb) {
        int kLo = (int) Math.floor(keyAtY(gb)); int kHi = (int) Math.ceil(keyAtY(gt)); if (kLo < 0) kLo = 0; if (kHi > 127) kHi = 127;
        for (int k = kLo; k <= kHi; k++) { if (!IS_BLACK[k]) continue; float y0 = yOfKey(k + 1), y1 = yOfKey(k);
            if (y1 < gt || y0 > gb) continue; c.drawRect(gl, y0, gr, y1, rowBlackP);
        }
        for (int k = kLo; k <= kHi; k++) { if (k % 12 != 0) continue; float y = yOfKey(k);
            if (y < gt || y > gb) continue; c.drawLine(gl, y, gr, y, rowCP);
        }
        long v0 = (long) Math.floor(tickAtX(gl)); long v1 = (long) Math.ceil(tickAtX(gr)); long step = snapEnabled ? snapTicks : 0L;
        if (step > 0L && step * pxPerTick >= dp(9f)) { long t = floorTo(v0, step); int guard = 0;
            for (; t <= v1 && guard < 8192; t += step, guard++) { float x = xOfTick(t);
                if (x < gl || x > gr) continue; c.drawLine(x, gt, x, gb, gridP);
            }
        }
        if (barCount > 0) { int i = barIndexAt(v0); int guard = 0;
            for (; i < barCount && guard < 8192; i++, guard++) { long bt = barTicks[i]; if (bt > v1) break;
                int bs = barBeats[i];
                if (bs > 0 && bs * pxPerTick >= dp(11f)) {
                    long end = (i + 1 < barCount) ? barTicks[i + 1] : bt + (long) bs * 8L;
                    for (long tb = bt + bs; tb < end; tb += bs) { float x = xOfTick(tb);
                        if (x >= gl && x <= gr) c.drawLine(x, gt, x, gb, beatP);
                    }
                }
                float bx = xOfTick(bt); if (bx >= gl - 1f && bx <= gr + 1f) c.drawLine(bx, gt, bx, gb, barP);
            }
        }
    }

    private void drawPartEdges(Canvas c, Part act, float gt, float gb) {
        float x0 = xOfTick(act.pos), x1 = xOfTick(act.end()); if (x0 >= keyW && x0 <= getWidth()) c.drawLine(x0, gt, x0, gb, partEdgeP);
        if (x1 >= keyW && x1 <= getWidth()) c.drawLine(x1, gt, x1, gb, partEdgeP);
    }

    private static int trackColor(int idx) { float f = (idx <= 0) ? 0f : ((idx % 4) / 3f);
        return Theme.blend(Theme.ACCENT, Theme.ACCENT2, f);
    }

    private void drawNotes(Canvas c, Track t, Part act, float gl, float gt, float gr, float gb) {
        long t0 = (long) tickAtX(gl) - 8L; long t1 = (long) tickAtX(gr) + 8L; int base = trackColor(trackIndex);
        for (int i = 0; i < t.parts.size(); i++) { Part p = t.parts.get(i); if (p == act) continue;
            drawPartNotes(c, p, base, false, t0, t1, gt, gb);
        }
        if (act != null) drawPartNotes(c, act, base, true, t0, t1, gt, gb);
    }

    private void drawPartNotes(Canvas c, Part p, int base, boolean active,
                               long t0, long t1, float gt, float gb) { if (p == null) return;
        float keyHi = keyAtY(gt), keyLo = keyAtY(gb); int n = p.notes.size();
        for (int i = 0; i < n; i++) { Note note = p.notes.get(i); if (note.pos > t1 || note.end() < t0) continue;
            if (note.key < keyLo - 1f || note.key > keyHi + 1f) continue;
            float x0 = xOfTick(note.pos), x1 = xOfTick(note.end()); if (x1 - x0 < dp(2f)) x1 = x0 + dp(2f);
            float y0 = yOfKey(note.key + 1), y1 = yOfKey(note.key);
            float inset = pxPerSemitone > dp(6f) ? dp(1f) : 0f; y0 += inset; y1 -= inset;
            if (y1 - y0 < dp(2f)) y1 = y0 + dp(2f); float rad = Math.min(dp(4f), (y1 - y0) * 0.35f);
            float vf = note.vel / 127f; int col = Theme.lighten(base, 0.10f * vf);
            col = Theme.mulAlpha(col, (active ? 0.94f : 0.32f) * (0.62f + 0.38f * vf)); noteP.setColor(col);
            Theme.rr(c, x0, y0, x1, y1, rad, noteP); c.drawLine(x0 + rad, y1, x1 - rad, y1, noteEdgeP);
            float vw = x1 - x0, vh = y1 - y0;
            if (vw > dp(13f) && vh > dp(8f)) { float mh = (vh - dp(2.5f)) * vf;
                c.drawRect(x0 + dp(1.5f), y1 - dp(1.25f) - mh, x0 + dp(3.25f), y1 - dp(1.25f), velP);
            }
            // lyric label: shrink the font to the row height so it shows on dense phone layouts too
            if (active && vw > dp(11f) && vh > dp(5f)) {
                String ly = note.lyric;
                if (ly != null && ly.length() > 0) {
                    float ts = Math.min(lyricP.getTextSize(), vh * 0.92f);
                    if (ts < dp(4f)) ts = dp(4f);
                    boolean scale = Math.abs(ts - lyricP.getTextSize()) > 0.5f;
                    if (scale) lyricP.setTextSize(ts);
                    if (scale) { lyricFm = lyricP.getFontMetrics(); lyricMid = (lyricFm.ascent + lyricFm.descent) * 0.5f; }
                    c.save();
                    c.clipRect(x0 + dp(1f), y0, x1 - dp(1f), y1);
                    c.drawText(ly, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f - lyricMid, lyricP);
                    c.restore();
                    if (scale) { lyricP.setTextSize(dp(10f)); lyricFm = lyricP.getFontMetrics();
                        lyricMid = (lyricFm.ascent + lyricFm.descent) * 0.5f; }
                }
            }
            if (active && note == selected) { selSeen = true; selL = x0; selT = y0; selR = x1; selB = y1; }
        }
    }

    private void drawKeyboard(Canvas c, float gb) { int kLo = (int) Math.floor(keyAtY(gb));
        int kHi = (int) Math.ceil(keyAtY(rulerH)); if (kLo < 0) kLo = 0; if (kHi > 127) kHi = 127;
        float minLabel = keyTextP.getTextSize() * 1.15f;
        for (int k = kLo; k <= kHi; k++) { float y0 = yOfKey(k + 1), y1 = yOfKey(k);
            if (y1 < rulerH || y0 > gb) continue;
            if (IS_BLACK[k]) { c.drawRect(0f, y0, keyW * 0.62f, y1, keyBlackP);
                c.drawLine(keyW * 0.62f, y0, keyW * 0.62f, y1, keyLineP);
            } else { c.drawRect(0f, y0, keyW, y1, keyWhiteP); c.drawLine(0f, y1, keyW, y1, keyLineP);
                if (k % 12 == 0 && (y1 - y0) >= minLabel) {
                    c.drawText(KEY_NAME[k], keyW - dp(5f), (y0 + y1) * 0.5f - keyMid, keyTextP);
                }
            }
        }
        c.drawLine(0f, rulerH, keyW, rulerH, keyLineP); c.drawLine(keyW - dp(0.7f), rulerH, keyW - dp(0.7f), gb, barP);
    }

    private void drawRuler(Canvas c, int w) { c.drawRect(0f, 0f, w, rulerH, rulerP);
        float baseline = rulerH * 0.5f - rulerMid + dp(0.5f);
        if (barCount > 0) { float t1 = tickAtX(w); int first = barIndexAt((long) tickAtX(keyW)); int step = 1;
            if (first + 1 < barCount) { float spacing = (barTicks[first + 1] - barTicks[first]) * pxPerTick;
                while (spacing * step < dp(46f) && step < 64) step *= 2;
            }
            for (int i = first; i < barCount; i++) { long bt = barTicks[i]; float bx = xOfTick(bt);
                if (bx > w) break; if (bx < keyW - 1f) continue; c.drawLine(bx, rulerH - dp(5f), bx, rulerH, rulerTickP);
                if (i % step == 0) { int len = fmtInt(i + 1);
                    c.drawText(numBuf, 0, len, bx + dp(3.5f), baseline, barTextP);
                }
            }
        }
        c.drawLine(0f, rulerH - dp(0.7f), w, rulerH - dp(0.7f), barP); c.save();
        c.clipRect(0f, 0f, keyW, rulerH); c.drawRect(0f, 0f, keyW, rulerH, nameBgP);
        Track t = currentTrack(); Part ap = activePart(); String nm = null;
        if (ap != null && ap.name != null && ap.name.length() > 0) nm = ap.name;
        else if (t != null && t.name != null && t.name.length() > 0) nm = t.name; if (nm == null) nm = "-";
        c.drawText(nm, dp(6f), baseline, nameP); c.restore();
    }

    private void drawPlayhead(Canvas c, float gl, float gb) { if (playheadTick < 0L) return;
        float x = xOfTick(playheadTick); if (x < gl - 1f || x > getWidth() + 1f) return;
        c.drawLine(x, 0f, x, gb, playP); marker.reset(); marker.moveTo(x - dp(5f), 0f);
        marker.lineTo(x + dp(5f), 0f); marker.lineTo(x, dp(7f)); marker.close(); c.drawPath(marker, playFillP);
    }

    private void drawVelBadge(Canvas c) { Note n = hitNote; if (n == null) return;
        float x = xOfTick(n.pos), y = yOfKey(n.key + 1); int len = fmtInt(n.vel);
        float tw = badgeP.measureText(numBuf, 0, len); float bw = tw + dp(12f), bh = dp(17f);
        float bx = Math.min(Math.max(x, keyW + dp(2f)), Math.max(keyW + dp(2f), getWidth() - bw - dp(2f)));
        float by = Math.max(rulerH + dp(2f), y - bh - dp(3f)); Theme.rr(c, bx, by, bx + bw, by + bh, dp(3f), badgeBgP);
        c.drawText(numBuf, 0, len, bx + dp(6f), by + bh * 0.5f - badgeMid, badgeP);
    }

    private int fmtInt(long v) { int n = 0;
        if (v < 0L) { numBuf[n++] = '-'; v = -v; }
        int start = n;
        if (v == 0L) { numBuf[n++] = '0';
        } else {
            while (v > 0L && n < numBuf.length - 1) { numBuf[n++] = (char) ('0' + (int) (v % 10L)); v /= 10L; }
        }
        for (int i = start, j = n - 1; i < j; i++, j--) { char tmp = numBuf[i]; numBuf[i] = numBuf[j];
            numBuf[j] = tmp;
        }
        return n;
    }
}