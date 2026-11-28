package com.dsh.v4tune.ui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.widget.ScrollView;

/**
 * A ScrollView that never grows taller than a fraction of the screen.
 *
 * <p>Why this exists: every secondary screen (menus, settings, AI sheet) is built as one tall
 * LinearLayout and handed to an AlertDialog. An AlertDialog window is WRAP_CONTENT, and a plain
 * ScrollView measured inside it gets an UNSPECIFIED height, so it "wraps" to the whole content
 * and the window ends up taller than the screen — the bottom rows (and the button row) simply
 * fall off the display with no way to scroll to them. Capping the measured height here makes the
 * window fit and turns the overflow into normal scrolling.</p>
 */
public class FitScrollView extends ScrollView {

    /** share of the screen height a dialog body may use; the rest is title + button row */
    private static final float DEFAULT_FRAC = 0.56f;

    private final float frac;

    public FitScrollView(Context c) {
        this(c, DEFAULT_FRAC);
    }

    public FitScrollView(Context c, float frac) {
        super(c);
        this.frac = frac;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int cap = Math.max(1, (int) (dm.heightPixels * frac));
        int mode = MeasureSpec.getMode(heightSpec);
        int size = MeasureSpec.getSize(heightSpec);
        if (mode != MeasureSpec.UNSPECIFIED && size > 0 && size < cap) cap = size;
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
    }
}
