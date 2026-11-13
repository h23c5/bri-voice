package com.dsh.v4tune.ui;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Step-by-step first-run guide. Each step dims the screen, cuts a hole around the button it
 * talks about and points at it with an arrow, so a new user can see exactly where to tap.
 */
public final class OnboardingOverlay extends FrameLayout {

    public static final class Step {
        public final View target;
        public final String title;
        public final String body;
        public Step(View target, String title, String body) {
            this.target = target;
            this.title = title;
            this.body = body;
        }
    }

    private final Activity act;
    private final Step[] steps;
    private final Spotlight spot;
    private final LinearLayout card;
    private final TextView counter, title, body;
    private final Button prev, next, skip;
    private int index = 0;
    private boolean laidOut = false;

    public static void show(Activity act, Step[] steps) {
        if (act == null || steps == null || steps.length == 0) return;
        ViewGroup root = (ViewGroup) act.findViewById(android.R.id.content);
        if (root == null) return;
        OnboardingOverlay ov = new OnboardingOverlay(act, steps);
        root.addView(ov, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private OnboardingOverlay(Activity a, Step[] steps) {
        super(a);
        this.act = a;
        this.steps = steps;
        setClickable(true);
        setBackgroundColor(Color.TRANSPARENT);

        spot = new Spotlight(a);
        addView(spot, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#F21A1D26"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#3A4256"));
        card.setBackground(bg);

        counter = new TextView(a);
        counter.setTextColor(Color.parseColor("#7FD7FF"));
        counter.setTextSize(11.5f);
        card.addView(counter);

        title = new TextView(a);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(4), 0, dp(4));
        card.addView(title);

        body = new TextView(a);
        body.setTextColor(Color.parseColor("#C9D2E3"));
        body.setTextSize(13f);
        body.setLineSpacing(dp(2), 1f);
        card.addView(body);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        skip = new Button(a);
        skip.setText("跳过");
        skip.setAllCaps(false);
        skip.setTextColor(Color.parseColor("#9FB0C8"));
        skip.setBackgroundColor(Color.TRANSPARENT);
        skip.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        row.addView(skip);
        View spacer = new View(a);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        prev = new Button(a);
        prev.setText("上一步");
        prev.setAllCaps(false);
        prev.setTextColor(Color.WHITE);
        prev.setBackgroundColor(Color.parseColor("#2A3142"));
        prev.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { if (index > 0) { index--; bind(); } }
        });
        row.addView(prev);
        next = new Button(a);
        next.setText("下一步");
        next.setAllCaps(false);
        next.setTextColor(Color.parseColor("#10131A"));
        next.setBackgroundColor(Color.parseColor("#7FD7FF"));
        next.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (index >= steps.length - 1) finish();
                else { index++; bind(); }
            }
        });
        row.addView(next);
        card.addView(row);

        addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        bind();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private RectF rectOf(View v) {
        if (v == null || v.getWidth() == 0) return null;
        int[] t = new int[2], c = new int[2];
        v.getLocationOnScreen(t);
        getLocationOnScreen(c);
        float pad = dp(8);
        return new RectF(t[0] - c[0] - pad, t[1] - c[1] - pad,
                t[0] - c[0] + v.getWidth() + pad, t[1] - c[1] + v.getHeight() + pad);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (!laidOut && getWidth() > 0 && getHeight() > 0) {
            laidOut = true;
            post(new Runnable() { public void run() { bind(); } });
        }
    }

    private void bind() {
        Step st = steps[index];
        counter.setText("新手引导  " + (index + 1) + " / " + steps.length);
        title.setText(st.title);
        body.setText(st.body);
        prev.setVisibility(index == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(index == steps.length - 1 ? "开始使用" : "下一步");
        spot.setTarget(rectOf(st.target));
        // put the card on the side opposite the highlighted control
        RectF r = rectOf(st.target);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        lp.gravity = (r != null && r.centerY() > getHeight() / 2f) ? Gravity.TOP : Gravity.BOTTOM;
        lp.topMargin = dp(36);
        lp.bottomMargin = dp(30);
        lp.leftMargin = dp(14);
        lp.rightMargin = dp(14);
        int maxW = Math.min(dp(560), getWidth() - dp(28));
        if (maxW > 0) lp.width = maxW;
        card.setLayoutParams(lp);
    }

    private void finish() {
        try { act.getSharedPreferences("brivoice", Activity.MODE_PRIVATE).edit().putBoolean("onboarded", true).apply(); } catch (Throwable ignored) { }
        ViewGroup p = (ViewGroup) getParent();
        if (p != null) p.removeView(this);
    }

    /** dark scrim with a clear hole + ring + arrow around the current target */
    private final class Spotlight extends View {
        private final Paint dim = new Paint();
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private RectF target;

        Spotlight(Activity a) {
            super(a);
            dim.setColor(Color.parseColor("#B8000000"));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2.5f));
            ring.setColor(Color.parseColor("#7FD7FF"));
            arrow.setColor(Color.parseColor("#7FD7FF"));
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setClickable(true);
        }

        void setTarget(RectF r) { target = r; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (target == null) { c.drawColor(Color.parseColor("#B8000000")); return; }
            float rad = dp(10);
            // four rectangles leave a hole without needing a layer / CLEAR pass
            c.drawRect(0, 0, w, target.top, dim);
            c.drawRect(0, target.bottom, w, h, dim);
            c.drawRect(0, target.top, target.left, target.bottom, dim);
            c.drawRect(target.right, target.top, w, target.bottom, dim);
            c.drawRoundRect(target, rad, rad, ring);
            // arrow pointing at the hole from the card side
            boolean below = target.centerY() > h / 2f;
            float ax = Math.max(dp(24), Math.min(w - dp(24), target.centerX()));
            Path p = new Path();
            if (below) {
                float y = Math.max(target.top - dp(10), dp(10));
                p.moveTo(ax, target.top - dp(2));
                p.lineTo(ax - dp(7), y - dp(9));
                p.lineTo(ax + dp(7), y - dp(9));
            } else {
                float y = Math.min(target.bottom + dp(10), h - dp(10));
                p.moveTo(ax, target.bottom + dp(2));
                p.lineTo(ax - dp(7), y + dp(9));
                p.lineTo(ax + dp(7), y + dp(9));
            }
            p.close();
            c.drawPath(p, arrow);
        }
    }
}
