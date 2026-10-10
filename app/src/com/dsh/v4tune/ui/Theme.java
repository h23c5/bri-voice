package com.dsh.v4tune.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.HashMap;

/**
 * Shared dark palette, density helpers and a small anti-aliased Paint cache for the tuning UI.
 *
 * Plain framework only (no AndroidX, no resources). Views should still keep their own Paint
 * fields for anything touched inside onDraw; {@link #cached} is meant for one-off decorations.
 */
public final class Theme {

    private Theme() { }

    // ------------------------------------------------------------------ colours
    /** window background */
    public static final int BG        = 0xFF12141A;
    /** panels / rulers / chips */
    public static final int PANEL     = 0xFF1A1E28;
    /** recessed panel */
    public static final int PANEL_DK  = 0xFF15181F;
    /** primary text */
    public static final int TEXT      = 0xFFE8ECF5;
    /** secondary text */
    public static final int TEXT_DIM  = 0xFF8A93A6;
    /** primary accent (cyan) */
    public static final int ACCENT    = 0xFF35C8FF;
    /** secondary accent (pink) */
    public static final int ACCENT2   = 0xFFFF5FA2;
    /** note grid lines */
    public static final int GRID      = 0xFF1E2230;
    /** beat lines */
    public static final int BEAT      = 0xFF232838;
    /** bar lines */
    public static final int BAR       = 0xFF2C3350;
    /** playhead */
    public static final int PLAYHEAD  = 0xFFFF4D6A;
    /** selection outline */
    public static final int SELECT    = 0xFFFFFFFF;

    /** shading behind black piano keys */
    public static final int ROW_BLACK = 0xFF171A23;
    /** shading / line for C rows */
    public static final int ROW_C     = 0xFF2A3145;
    public static final int KEY_WHITE = 0xFFC7CEDB;
    public static final int KEY_BLACK = 0xFF20242E;
    public static final int KEY_LINE  = 0xFF0E1016;
    /** text drawn on the light keys */
    public static final int KEY_TEXT  = 0xFF232838;
    /** chip background */
    public static final int CHIP_BG   = 0xFF232838;

    // ------------------------------------------------------------- surfaces (v2.6 look)
    /** window background, one step darker than the panels so they read as cards */
    public static final int BG_DEEP   = 0xFF0B0D13;
    /** raised surface: buttons, toolbars */
    public static final int SURFACE   = 0xFF1B2130;
    /** hovered / pressed surface */
    public static final int SURFACE_HI = 0xFF263041;
    /** hairline border drawn around cards and buttons */
    public static final int STROKE    = 0x24FFFFFF;
    /** divider between two sections of chrome */
    public static final int DIVIDER   = 0x14FFFFFF;
    /** accent used for the primary action */
    public static final int PRIMARY   = 0xFF2FBDF0;
    /** text on top of the primary colour */
    public static final int ON_PRIMARY = 0xFF06202B;

    /**
     * A rounded button: filled with {@link #PRIMARY} for the main action, otherwise a raised
     * surface with a hairline border. A pressed state and (on API 21+) a ripple give touch
     * feedback that the stock {@code Button} background does not provide.
     */
    public static android.graphics.drawable.Drawable button(Context c, boolean primary) {
        float r = dp(c, 9f);
        android.graphics.drawable.GradientDrawable base = new android.graphics.drawable.GradientDrawable();
        base.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        base.setCornerRadius(r);
        if (primary) {
            base.setColor(PRIMARY);
            base.setStroke(Math.max(1, dpi(c, 0.6f)), 0x55FFFFFF);
        } else {
            base.setColor(SURFACE);
            base.setStroke(Math.max(1, dpi(c, 0.8f)), STROKE);
        }
        android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
        pressed.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(r);
        pressed.setColor(primary ? lighten(PRIMARY, 0.12f) : 0xFF27324A);
        pressed.setStroke(Math.max(1, dpi(c, 0.8f)), primary ? 0x77FFFFFF : 0x33FFFFFF);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{android.R.attr.state_focused}, pressed);
        sl.addState(new int[]{}, base);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            android.graphics.drawable.RippleDrawable rip = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(primary ? 0x40FFFFFF : 0x30AAD4FF), sl, null);
            return rip;
        }
        return sl;
    }

    /** A rounded card used for the status bar and the parameter strip. */
    public static android.graphics.drawable.Drawable card(Context c, int fill) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(c, 10f));
        g.setColor(fill);
        g.setStroke(Math.max(1, dpi(c, 0.8f)), STROKE);
        return g;
    }

    /** 1 px divider view between two sections of the chrome. */
    public static android.view.View divider(Context c) {
        android.view.View v = new android.view.View(c);
        v.setBackgroundColor(DIVIDER);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dpi(c, 0.7f))));
        return v;
    }

    // ---------------------------------------------------------------- dimensions
    /** density independent pixels -> pixels. */
    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** density independent pixels -> rounded pixels. */
    public static int dpi(Context c, float v) {
        return Math.round(dp(c, v));
    }

    // -------------------------------------------------------------------- paints
    /**
     * New anti-aliased paint.
     *
     * @param colour     ARGB colour
     * @param strokeWidth stroke width in pixels (ignored when fill is true)
     * @param fill       true for FILL style, false for STROKE
     */
    public static Paint paint(int colour, float strokeWidth, boolean fill) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setColor(colour);
        p.setStrokeWidth(strokeWidth);
        if (fill) {
            p.setStyle(Paint.Style.FILL);
        } else {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        return p;
    }

    /** Text paint with sub-pixel positioning on. */
    public static Paint textPaint(int colour, float sizePx, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setColor(colour);
        p.setTextSize(sizePx);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    /** Dashed stroke paint (already carries its path effect). */
    public static Paint dashPaint(int colour, float strokeWidth, float on, float off) {
        Paint p = paint(colour, strokeWidth, false);
        p.setPathEffect(new DashPathEffect(new float[]{on, off}, 0f));
        return p;
    }

    private static final HashMap<Long, Paint> CACHE = new HashMap<Long, Paint>(64);

    /**
     * Shared Paint for the given description. Cheap, but it boxes a key per call, so keep it out
     * of per-frame loops and use per-view fields there.
     */
    public static Paint cached(int colour, float strokeWidth, boolean fill) {
        long k = ((long) colour << 33) ^ ((long) Float.floatToIntBits(strokeWidth) << 1) ^ (fill ? 1L : 0L);
        Long key = Long.valueOf(k);
        Paint p = CACHE.get(key);
        if (p == null) {
            p = paint(colour, strokeWidth, fill);
            CACHE.put(key, p);
        }
        return p;
    }

    // ------------------------------------------------------------- colour helpers
    /** Straight ARGB lerp; t is clamped to 0..1. */
    public static int blend(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = aa + (int) ((ba - aa) * t);
        int rr = ar + (int) ((br - ar) * t);
        int rg = ag + (int) ((bg - ag) * t);
        int rb = ab + (int) ((bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    /** Same colour with a replaced alpha byte (0..255). */
    public static int withAlpha(int colour, int alpha) {
        if (alpha < 0) alpha = 0;
        if (alpha > 255) alpha = 255;
        return (colour & 0x00FFFFFF) | (alpha << 24);
    }

    /** Multiply the existing alpha by f (0..1+). */
    public static int mulAlpha(int colour, float f) {
        int a = (int) ((colour >>> 24) * f);
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return (colour & 0x00FFFFFF) | (a << 24);
    }

    /** Move the colour towards white. */
    public static int lighten(int colour, float f) {
        return blend(colour, 0xFFFFFFFF, f);
    }

    /** Move the colour towards black. */
    public static int darken(int colour, float f) {
        return blend(colour, 0xFF000000, f);
    }

    // ------------------------------------------------------------ canvas shortcuts
    /** Rounded rectangle without allocating a RectF. */
    public static void rr(Canvas c, float l, float t, float r, float b, float rad, Paint p) {
        if (r < l) { float s = l; l = r; r = s; }
        if (b < t) { float s = t; t = b; b = s; }
        c.drawRoundRect(l, t, r, b, rad, rad, p);
    }
}
