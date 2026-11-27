package com.dsh.v4tune.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.dsh.v4tune.i18n.L;

/**
 * Left icon rail (v12.4 layout): one vertical strip of square icon buttons with a small caption,
 * so the piano roll can use almost the whole width instead of a wide text toolbar.
 *
 * <p>Every icon is drawn from the same 24x24 stroke grid and the same paint, which is what keeps
 * the set looking like one family; adding a button means adding a path here, not a new drawable.</p>
 */
public class IconRail extends View {

    public interface Listener { void onRailItem(int item); }

    public static final int ITEM_NEW = 0, ITEM_OPEN = 1, ITEM_SAVE = 2, ITEM_PLAY = 3,
            ITEM_UNDO = 4, ITEM_REDO = 5, ITEM_AI = 6, ITEM_MORE = 7;
    private static final int COUNT = 8;

    private final String[] labels = new String[COUNT];
    private final Paint bgP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();

    private float density;
    private float btn, step, topPad, labelBaseline;
    private boolean playing;
    private int pressed = -1;
    private Listener listener;

    public IconRail(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        labels[ITEM_NEW] = L.t("toolbar.new");
        labels[ITEM_OPEN] = L.t("toolbar.open");
        labels[ITEM_SAVE] = L.t("toolbar.save");
        labels[ITEM_PLAY] = L.t("rail.play");
        labels[ITEM_UNDO] = L.t("toolbar.undo");
        labels[ITEM_REDO] = L.t("toolbar.redo");
        labels[ITEM_AI] = L.t("transport.ai");
        labels[ITEM_MORE] = L.t("start.more");

        bgP.setColor(Theme.PANEL);
        iconP.setStyle(Paint.Style.STROKE);
        iconP.setStrokeWidth(dp(1.7f));
        iconP.setStrokeCap(Paint.Cap.ROUND);
        iconP.setStrokeJoin(Paint.Join.ROUND);
        iconP.setColor(Theme.TEXT_DIM);
        labelP.setTextSize(dp(9f));
        labelP.setTextAlign(Paint.Align.CENTER);
        labelP.setColor(Theme.TEXT_DIM);
        pressP.setColor(Theme.withAlpha(Theme.ACCENT, 0x33));
        setFocusable(true);
    }

    public void setListener(Listener l) { listener = l; }

    public void setPlaying(boolean p) {
        if (playing == p) return;
        playing = p;
        labels[ITEM_PLAY] = L.t(p ? "rail.stop" : "rail.play");
        invalidate();
    }

    private float dp(float v) { return v * density; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize((int) dp(64f), wSpec);
        int h = resolveSize((int) dp(56f) * COUNT + (int) dp(64f), hSpec);
        setMeasuredDimension(w, h);
    }

    /** the launcher icon, drawn once at rail size; null means "no icon, fall back to the letter" */
    private android.graphics.Bitmap logoTex;
    private boolean logoTried;

    private android.graphics.Bitmap logoBitmap() {
        if (logoTried) return logoTex;
        logoTried = true;
        try {
            String pkg = getContext().getPackageName();
            int id = getResources().getIdentifier("ic_launcher", "mipmap", pkg);
            if (id == 0) id = getResources().getIdentifier("ic_launcher_round", "mipmap", pkg);
            if (id == 0) return null;
            android.graphics.drawable.Drawable d = getResources().getDrawable(id);
            int sz = Math.max(8, Math.round(dp(26f) * 2f));
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(sz, sz,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas bc = new android.graphics.Canvas(b);
            d.setBounds(0, 0, sz, sz);
            d.draw(bc);
            logoTex = b;
        } catch (Throwable ignored) {
        }
        return logoTex;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        btn = Math.min(dp(46f), w - dp(10f));
        // fit all eight buttons plus the logo into whatever height the rail got; never clip
        float avail = h - dp(56f) - dp(8f);
        step = Math.min(dp(58f), avail / COUNT);
        if (step < dp(38f)) step = dp(38f);
        topPad = dp(56f);
        labelBaseline = 0f;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        c.drawColor(Theme.BG_DEEP);
        c.drawRect(dp(1f), 0f, w - dp(1f), getHeight(), bgP);

        // logo mark: the launcher icon itself, falling back to the initial if it cannot be loaded
        android.graphics.Bitmap mark = logoBitmap();
        if (mark != null) {
            float sz = dp(26f);
            r.set(w * 0.5f - sz * 0.5f, dp(22f), w * 0.5f + sz * 0.5f, dp(22f) + sz);
            c.drawBitmap(mark, null, r, logoP);
        } else {
            logoP.setColor(Theme.ACCENT);
            logoP.setTextSize(dp(19f));
            logoP.setTextAlign(Paint.Align.CENTER);
            logoP.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            c.drawText("B", w * 0.5f, dp(36f), logoP);
        }

        float cx = w * 0.5f;
        for (int i = 0; i < COUNT; i++) {
            float cy = topPad + step * i + step * 0.5f;
            r.set(cx - btn * 0.5f, cy - btn * 0.5f, cx + btn * 0.5f, cy + btn * 0.5f);
            boolean on = i == ITEM_PLAY && playing;
            if (on) {
                bgP.setColor(Theme.ACCENT);
                c.drawRoundRect(r, dp(12f), dp(12f), bgP);
                bgP.setColor(Theme.PANEL);
            } else if (i == pressed) {
                c.drawRoundRect(r, dp(12f), dp(12f), pressP);
            }
            iconP.setColor(on ? Theme.ON_PRIMARY : Theme.TEXT);
            drawIcon(c, i, cx, cy, on);
            labelP.setColor(on ? Theme.ACCENT : Theme.TEXT_DIM);
            c.drawText(labels[i], cx, cy + btn * 0.5f - dp(4f), labelP);
        }
    }

    private void drawIcon(Canvas c, int item, float cx, float cy, boolean on) {
        float s = dp(19f) / 24f;              // icon box is 19 dp, drawn on a 24 unit grid
        c.save();
        c.translate(cx - dp(9.5f), cy - dp(11.5f));
        c.scale(s, s);
        path.reset();
        switch (item) {
            case ITEM_NEW:
                c.drawLine(12, 5, 12, 19, iconP);
                c.drawLine(5, 12, 19, 12, iconP);
                break;
            case ITEM_OPEN:
                path.moveTo(3, 8); path.lineTo(10, 8); path.lineTo(12, 11); path.lineTo(21, 11);
                path.lineTo(21, 19); path.lineTo(3, 19); path.close();
                c.drawPath(path, iconP);
                break;
            case ITEM_SAVE:
                c.drawRect(4, 4, 20, 20, iconP);
                c.drawRect(8, 4, 16, 10, iconP);
                c.drawRect(7, 14, 17, 20, iconP);
                break;
            case ITEM_PLAY:
                if (on) { c.drawRect(7, 7, 17, 17, iconP); }
                else {
                    path.moveTo(7, 5); path.lineTo(19, 12); path.lineTo(7, 19); path.close();
                    c.drawPath(path, iconP);
                }
                break;
            case ITEM_UNDO:
                path.moveTo(8, 7); path.lineTo(4, 11); path.lineTo(8, 15);
                c.drawPath(path, iconP);
                path.reset();
                path.moveTo(4, 11); path.lineTo(14, 11);
                path.quadTo(20, 11, 20, 16);
                c.drawPath(path, iconP);
                break;
            case ITEM_REDO:
                path.moveTo(16, 7); path.lineTo(20, 11); path.lineTo(16, 15);
                c.drawPath(path, iconP);
                path.reset();
                path.moveTo(20, 11); path.lineTo(10, 11);
                path.quadTo(4, 11, 4, 16);
                c.drawPath(path, iconP);
                break;
            case ITEM_AI:
                path.moveTo(12, 3); path.lineTo(14, 9.5f); path.lineTo(20.5f, 12);
                path.lineTo(14, 14.5f); path.lineTo(12, 21); path.lineTo(10, 14.5f);
                path.lineTo(3.5f, 12); path.lineTo(10, 9.5f); path.close();
                c.drawPath(path, iconP);
                break;
            default:                          // more
                c.drawCircle(6, 12, 1.7f, iconP);
                c.drawCircle(12, 12, 1.7f, iconP);
                c.drawCircle(18, 12, 1.7f, iconP);
                break;
        }
        c.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int hit = hitItem(y);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (hit != pressed) { pressed = hit; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                int p = pressed;
                pressed = -1;
                invalidate();
                if (p >= 0 && p == hit) {
                    performClick();
                    if (listener != null) listener.onRailItem(p);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private int hitItem(float y) {
        if (step <= 0f) return -1;
        int i = (int) ((y - topPad) / step);
        if (i < 0 || i >= COUNT) return -1;
        float cy = topPad + step * i + step * 0.5f;
        if (Math.abs(y - cy) > btn * 0.5f) return -1;
        return i;
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
