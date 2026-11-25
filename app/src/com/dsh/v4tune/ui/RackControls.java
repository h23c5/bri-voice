package com.dsh.v4tune.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Hardware-looking controls for the plugin rack (v12.0).
 *
 * <p>The rack used to be a column of SeekBars. That works, but a rack of sliders does not read as
 * gear, and on a phone the label and the bar drift far apart. These views look like the front
 * panel instead: a brushed knob with an engraved scale and a domed cap, a chunky toggle with an
 * LED, and a bevelled plate to mount them on.</p>
 *
 * <p>Plain framework views (no XML, no AndroidX) so they drop straight into the existing dialogs.
 * A knob is gesture driven by a vertical drag - dragging up turns it clockwise, the way a real pot
 * is turned - and the toggle flips on a tap.</p>
 */
public final class RackControls {

    private RackControls() { }

    static int dp(Context c, float v) { return Theme.dpi(c, v); }

    /** linear blend of two ARGB colours; t is clamped to 0..1 */
    static int mix(int a, int b, float t) {
        float u = t < 0f ? 0f : (t > 1f ? 1f : t);
        float v = 1f - u;
        int r = (int) (((a >> 16) & 0xFF) * v + ((b >> 16) & 0xFF) * u);
        int g = (int) (((a >> 8) & 0xFF) * v + ((b >> 8) & 0xFF) * u);
        int bl = (int) ((a & 0xFF) * v + (b & 0xFF) * u);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** 0 at the lower left, clockwise, 270 degrees of travel */
    private static final float START = 225f;
    private static final float SWEEP = 270f;

    // ------------------------------------------------------------------ knob

    /**
     * A rotary knob over 0..1.
     *
     * <p>Draw order is what sells it: engraved tick ring on the plate, then the cap with a top-left
     * radial highlight so it looks domed, then a rim light on the far edge, then the pointer notch,
     * then the readout and the caption. Ticks that the knob has passed light up in the accent
     * colour, so the current value is readable at a glance without reading the number.</p>
     */
    public static final class Knob extends View {

        public interface Listener { void onKnob(float v); }

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float value = 0.5f;
        private float downY = 0f;
        private float downV = 0f;
        private boolean dragging = false;
        private Listener listener;
        private String caption = "";
        private String readout = "";

        public Knob(Context c) {
            super(c);
            setClickable(true);
            setFocusable(true);
        }

        public void setListener(Listener l) { listener = l; }
        public float value() { return value; }

        public void setValue(float v) {
            float n = v < 0f ? 0f : (v > 1f ? 1f : v);
            if (Math.abs(n - value) < 1e-4f) return;
            value = n;
            invalidate();
        }

        /** caption under the knob, readout above it */
        public void setText(String cap, String read) {
            caption = cap == null ? "" : cap;
            readout = read == null ? "" : read;
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getY();
                    downV = value;
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    // 200 dp of vertical travel covers the whole range: about one turn of the hand
                    // on a phone, which is the feel of a real pot rather than a slider.
                    float span = Math.max(1f, dp(getContext(), 200f));
                    float nv = downV + (downY - e.getY()) / span;
                    nv = nv < 0f ? 0f : (nv > 1f ? 1f : nv);
                    if (Math.abs(nv - value) > 1e-4f) {
                        value = nv;
                        invalidate();
                        if (listener != null) listener.onKnob(value);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = resolveSize(dp(getContext(), 92f), wSpec);
            // tall enough that the readout *and* the engraved caption both fit: the caption
            // baseline sits ~34 dp below the cap, so a 116 dp view clipped its descenders.
            int h = resolveSize(dp(getContext(), 136f), hSpec);
            setMeasuredDimension(w, h);
        }

        @Override protected void onDraw(Canvas cv) {
            final Context ctx = getContext();
            float w = getWidth(), h = getHeight();
            float cx = w / 2f;
            float rOuter = Math.min(w * 0.38f, h * 0.32f);
            float cy = h * 0.38f;

            // engraved ring on the plate
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(ctx, 1f));
            p.setShader(null);
            p.setColor(0x2EFFFFFF);
            float ring = rOuter + dp(ctx, 5f);
            oval.set(cx - ring, cy - ring, cx + ring, cy + ring);
            cv.drawArc(oval, START, SWEEP, false, p);

            // scale ticks: passed ones light up, so the value is visible without the number
            for (int i = 0; i <= 10; i++) {
                double ang = Math.toRadians(START + SWEEP * i / 10f);
                float r1 = rOuter + dp(ctx, 3f);
                float r2 = rOuter + dp(ctx, (i % 5 == 0) ? 10f : 7f);
                float x1 = (float) (cx + Math.sin(ang) * r1);
                float y1 = (float) (cy - Math.cos(ang) * r1);
                float x2 = (float) (cx + Math.sin(ang) * r2);
                float y2 = (float) (cy - Math.cos(ang) * r2);
                boolean on = (i / 10f) <= value + 1e-4f;
                p.setColor(on ? Theme.ACCENT : 0x33FFFFFF);
                p.setStrokeWidth(dp(ctx, on ? 2f : 1.2f));
                cv.drawLine(x1, y1, x2, y2, p);
            }

            // the cap: dark metal, highlight offset to the top left so it reads as domed
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx - rOuter * 0.38f, cy - rOuter * 0.45f, rOuter * 1.75f,
                    new int[]{0xFF6E7C93, 0xFF39445A, 0xFF141922},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, rOuter, p);
            p.setShader(null);

            // rim light on the lower edge, opposite the highlight
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(ctx, 1.2f));
            p.setColor(0x24FFFFFF);
            cv.drawCircle(cx, cy, rOuter - dp(ctx, 0.6f), p);

            // pointer notch
            double pang = Math.toRadians(START + SWEEP * value);
            float px1 = (float) (cx + Math.sin(pang) * rOuter * 0.28f);
            float py1 = (float) (cy - Math.cos(pang) * rOuter * 0.28f);
            float px2 = (float) (cx + Math.sin(pang) * rOuter * 0.84f);
            float py2 = (float) (cy - Math.cos(pang) * rOuter * 0.84f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(ctx, 3.4f));
            p.setColor(dragging ? 0xFFFFFFFF : Theme.ACCENT);
            cv.drawLine(px1, py1, px2, py2, p);
            p.setStrokeCap(Paint.Cap.BUTT);

            // readout under the cap
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            if (readout.length() > 0) {
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextSize(dp(ctx, 12f));
                p.setColor(dragging ? 0xFFFFFFFF : 0xFFB9C6D6);
                cv.drawText(readout, cx, cy + rOuter + dp(ctx, 16f), p);
                p.setTypeface(Typeface.DEFAULT);
            }
            if (caption.length() > 0) {
                p.setTextSize(dp(ctx, 11f));
                p.setColor(0xFF808B9E);
                cv.drawText(caption, cx, cy + rOuter + dp(ctx, 32f), p);
            }
        }
    }

    // ---------------------------------------------------------------- toggle

    /**
     * A chunky on/off switch with an LED, drawn as a metal toggle rather than a material slider.
     *
     * <p>The LED is the point: on a rack you read the state from the lamp, not from the position of
     * a thumb. The LED glows (a soft radial halo) when on and sits dead grey when off.</p>
     */
    public static final class Toggle extends View {

        public interface Listener { void onToggle(boolean on); }

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private boolean on = false;
        private Listener listener;
        private String caption = "";

        public Toggle(Context c) {
            super(c);
            setClickable(true);
            setFocusable(true);
        }

        public void setListener(Listener l) { listener = l; }
        public boolean isOn() { return on; }

        public void setOn(boolean b) {
            if (on == b) return;
            on = b;
            invalidate();
        }

        public void setCaption(String s) { caption = s == null ? "" : s; invalidate(); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                on = !on;
                invalidate();
                if (listener != null) listener.onToggle(on);
                return true;
            }
            return e.getActionMasked() == MotionEvent.ACTION_DOWN || super.onTouchEvent(e);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = resolveSize(dp(getContext(), 96f), wSpec);
            int h = resolveSize(dp(getContext(), 44f), hSpec);
            setMeasuredDimension(w, h);
        }

        /**
         * A rocker switch, not a blob: a recessed bezel, a raised cap split by a centre seam, and the
         * half that is "pressed" lit while the other stays in shadow. Flipping the gradient (not just
         * the colours) is what makes it read as a physical rocker rather than a coloured ellipse.
         */
        @Override protected void onDraw(Canvas cv) {
            final Context ctx = getContext();
            float w = getWidth(), h = getHeight();
            float cy = h / 2f;
            float bw = dp(ctx, 30f), bh = dp(ctx, 34f);
            float cx = w - bw / 2f - dp(ctx, 2f);

            // bezel: a dark recess with a hairline, so the cap looks sunk into the panel
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(0xFF0A0D13);
            oval.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
            cv.drawRoundRect(oval, dp(ctx, 5f), dp(ctx, 5f), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(ctx, 1f));
            p.setColor(0x22FFFFFF);
            cv.drawRoundRect(oval, dp(ctx, 5f), dp(ctx, 5f), p);

            // cap: gradient runs whichever way the rocker is tipped
            p.setStyle(Paint.Style.FILL);
            float inset = dp(ctx, 2.5f);
            int litA = 0xFF6A7789, litB = 0xFF2B3341;
            int dimA = 0xFF39424F, dimB = 0xFF151A22;
            int topA = on ? litA : dimA;
            int topB = on ? litB : dimB;
            p.setShader(new LinearGradient(0, cy - bh / 2f, 0, cy + bh / 2f,
                    new int[]{topA, topA, topB, topB},
                    new float[]{0f, 0.48f, 0.52f, 1f}, Shader.TileMode.CLAMP));
            oval.set(cx - bw / 2f + inset, cy - bh / 2f + inset,
                    cx + bw / 2f - inset, cy + bh / 2f - inset);
            cv.drawRoundRect(oval, dp(ctx, 3.5f), dp(ctx, 3.5f), p);
            p.setShader(null);

            // centre seam
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(ctx, 1.2f));
            p.setColor(0x66000000);
            cv.drawLine(oval.left + dp(ctx, 1f), cy, oval.right - dp(ctx, 1f), cy, p);

            // LED: on the left of the bezel, glowing only when engaged
            float lx = cx - bw / 2f - dp(ctx, 9f), ly = cy;
            float lr = dp(ctx, 3f);
            p.setStyle(Paint.Style.FILL);
            if (on) {
                p.setColor(0x30FF6A3D);
                cv.drawCircle(lx, ly, lr * 2.8f, p);
                p.setColor(0x70FF6A3D);
                cv.drawCircle(lx, ly, lr * 1.7f, p);
                p.setColor(0xFFFF7A45);
            } else {
                p.setColor(0xFF232A34);
            }
            cv.drawCircle(lx, ly, lr, p);

            // state text, left of the LED
            if (caption.length() > 0) {
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextSize(dp(ctx, 9f));
                p.setTextAlign(Paint.Align.LEFT);
                p.setColor(on ? 0xFFFFB59A : 0xFF6C7788);
                cv.drawText(caption, dp(ctx, 2f), ly + dp(ctx, 3f), p);
                p.setTypeface(Typeface.DEFAULT);
            }
        }
    }

    // ----------------------------------------------------------------- plate

    /**
     * Draws a bevelled plate behind a rack section: a dark body, a hairline top highlight and a
     * shadow underneath. Pure decoration, so it takes a Canvas instead of being a View.
     */
    public static void plate(Canvas cv, Context ctx, RectF r, float radius) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // drop shadow
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        RectF sh = new RectF(r);
        sh.offset(0, dp(ctx, 2f));
        cv.drawRoundRect(sh, radius, radius, p);
        // body
        p.setShader(new RadialGradient(r.centerX(), r.top, Math.max(r.width(), r.height()) * 0.9f,
                new int[]{0xFF232A36, 0xFF171C25}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRoundRect(r, radius, radius, p);
        p.setShader(null);
        // top highlight and bottom shade, one pixel each
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(0x1FFFFFFF);
        cv.drawRoundRect(new RectF(r.left, r.top, r.right, r.bottom), radius, radius, p);
    }

    /**
     * A plate you can put other views on top of: draws the bevelled body and four screws, and takes
     * no touches itself so the knobs sitting on it keep working.
     */
    public static final class Plate extends View {

        public Plate(Context c) { super(c); setClickable(false); setFocusable(false); }

        @Override protected void onDraw(Canvas cv) {
            Context ctx = getContext();
            float pad = dp(ctx, 1.5f);
            RectF r = new RectF(pad, pad, getWidth() - pad, getHeight() - pad);
            plate(cv, ctx, r, dp(ctx, 12f));
            float inset = dp(ctx, 10f);
            screw(cv, ctx, r.left + inset, r.top + inset);
            screw(cv, ctx, r.right - inset, r.top + inset);
            screw(cv, ctx, r.left + inset, r.bottom - inset);
            screw(cv, ctx, r.right - inset, r.bottom - inset);
        }
    }

    /** a screw head in the corner of a plate - the cheapest possible "this is hardware" cue */
    public static void screw(Canvas cv, Context ctx, float x, float y) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float rr = dp(ctx, 3f);
        p.setColor(0xFF2C3341);
        cv.drawCircle(x, y, rr, p);
        p.setColor(0x30FFFFFF);
        cv.drawCircle(x - rr * 0.2f, y - rr * 0.2f, rr * 0.7f, p);
        p.setColor(0xFF10141B);
        p.setStrokeWidth(dp(ctx, 1.1f));
        cv.drawLine(x - rr * 0.55f, y - rr * 0.35f, x + rr * 0.55f, y + rr * 0.35f, p);
    }
}