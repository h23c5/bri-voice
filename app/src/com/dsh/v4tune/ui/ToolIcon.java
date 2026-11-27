package com.dsh.v4tune.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * A small line-icon button for the top bar. Everything is drawn here instead of using text, so the
 * icons stay readable at dp(26) and pick up the theme colours. {@code active} only changes the
 * colour (accent when a toggle is on), never the shape, so the bar does not jump when toggled.
 */
public class ToolIcon extends View {
    public static final int METRONOME = 0;
    public static final int SNAP = 1;
    public static final int MIXER = 2;
    public static final int TO_START = 3;
    public static final int TO_END = 4;

    private final int kind;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private boolean active;

    public ToolIcon(Context c, int kind) {
        super(c);
        this.kind = kind;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Theme.dpi(c, 1.5f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        solid.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    public void setActive(boolean a) {
        if (active != a) { active = a; invalidate(); }
    }

    public boolean isActive() { return active; }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        int col = active ? Theme.ACCENT : Theme.TEXT_DIM;
        line.setColor(col);
        solid.setColor(col);
        switch (kind) {
            case METRONOME: {
                // trapezoid body + pendulum arm
                float bw = s * 0.26f, bh = s * 0.30f;
                path.reset();
                path.moveTo(cx - bw, cy + bh);
                path.lineTo(cx + bw, cy + bh);
                path.lineTo(cx + bw * 0.30f, cy - bh);
                path.lineTo(cx - bw * 0.30f, cy - bh);
                path.close();
                cv.drawPath(path, line);
                cv.drawLine(cx - bw * 0.10f, cy + bh * 0.62f, cx + bw * 0.62f, cy - bh * 0.72f, line);
                cv.drawCircle(cx + bw * 0.62f, cy - bh * 0.72f, s * 0.045f, solid);
                break;
            }
            case SNAP: {
                // magnet: U shaped body with two poles
                // horseshoe magnet: half ring on top, two legs down, dark poles at the leg ends
                float r = s * 0.23f;
                rect.set(cx - r, cy - r, cx + r, cy + r);
                cv.drawArc(rect, 180f, 180f, false, line);        // upper half ring (opening down)
                cv.drawLine(cx - r, cy, cx - r, cy + r * 0.62f, line);
                cv.drawLine(cx + r, cy, cx + r, cy + r * 0.62f, line);
                float t = s * 0.055f;
                cv.drawRect(cx - r - t, cy + r * 0.62f, cx - r + t, cy + r * 0.62f + s * 0.12f, solid);
                cv.drawRect(cx + r - t, cy + r * 0.62f, cx + r + t, cy + r * 0.62f + s * 0.12f, solid);
                break;
            }
            case MIXER: {
                // three faders with a knob each at a different height
                float gap = s * 0.22f;
                float top = cy - s * 0.28f, bot = cy + s * 0.28f;
                float knobY[] = {cy + s * 0.10f, cy - s * 0.12f, cy + s * 0.22f};
                for (int i = 0; i < 3; i++) {
                    float x = cx + (i - 1) * gap;
                    cv.drawLine(x, top, x, bot, line);
                    rect.set(x - s * 0.06f, knobY[i] - s * 0.045f, x + s * 0.06f, knobY[i] + s * 0.045f);
                    cv.drawRoundRect(rect, s * 0.03f, s * 0.03f, solid);
                }
                break;
            }
            case TO_START: {
                // |◀  : bar on the left, triangle pointing left
                float x0 = cx - s * 0.26f;
                float x1 = cx + s * 0.24f;
                float half = s * 0.22f;
                cv.drawLine(x0, cy - half, x0, cy + half, line);
                path.reset();
                path.moveTo(x1, cy - half);
                path.lineTo(x1, cy + half);
                path.lineTo(x0 + s * 0.05f, cy);
                path.close();
                cv.drawPath(path, solid);
                break;
            }
            case TO_END: {
                // ▶| : triangle pointing right, bar on the right
                float x0 = cx - s * 0.24f;
                float x1 = cx + s * 0.26f;
                float half = s * 0.22f;
                cv.drawLine(x1, cy - half, x1, cy + half, line);
                path.reset();
                path.moveTo(x0, cy - half);
                path.lineTo(x0, cy + half);
                path.lineTo(x1 - s * 0.05f, cy);
                path.close();
                cv.drawPath(path, solid);
                break;
            }
            default:
                break;
        }
    }
}
