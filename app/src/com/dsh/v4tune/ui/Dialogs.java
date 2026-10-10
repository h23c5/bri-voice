package com.dsh.v4tune.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The look shared by every secondary screen (menus, pickers, lists).
 *
 * <p>Instead of the stock list dialog - flat text, no hierarchy, no hint of what is currently
 * selected - every menu is built from {@link Row}s: a coloured badge, a title, an optional grey
 * subtitle and a tick on the active entry, inside the rounded night card.</p>
 */
public final class Dialogs {

    private Dialogs() { }

    /** badge colours, cycled per row */
    public static final int[] PALETTE = {0xFF35C8FF, 0xFFFF8A3D, 0xFF7CE38B, 0xFFC08CFF, 0xFFFFD166,
            0xFFFF5FA2, 0xFF4ADEDE};

    /**
     * A one-glyph badge taken from the label itself, so a menu needs no per-entry artwork:
     * "导入声库" gets 导, "New Track" gets N. Leading decoration (+, ●, spaces) is skipped.
     */
    public static String badgeFor(String label) {
        if (label == null) return null;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                String g = String.valueOf(c);
                return (c < 128) ? g.toUpperCase(java.util.Locale.US) : g;
            }
        }
        return null;
    }

    public static int dp(Activity a, float v) { return Theme.dpi(a, v); }

    /** one line of a menu */
    public static final class Row {
        String badge, title, subtitle;
        int color = Theme.ACCENT;
        boolean on;
        Runnable action;
    }

    public static Row row(String badge, int color, String title, String subtitle, boolean on, Runnable action) {
        Row r = new Row();
        r.badge = badge;
        r.color = color;
        r.title = title;
        r.subtitle = subtitle;
        r.on = on;
        r.action = action;
        return r;
    }

    public static Row row(String title, Runnable action) {
        return row(null, Theme.ACCENT, title, null, false, action);
    }

    /** the theme that gives dialogs the night card background */
    public static int styleId(Activity a) {
        return a.getResources().getIdentifier("BriDialog", "style", a.getPackageName());
    }

    public static AlertDialog.Builder builder(Activity a) {
        int st = styleId(a);
        return (st != 0) ? new AlertDialog.Builder(a, st) : new AlertDialog.Builder(a);
    }

    /** a menu: header, then rows with badges and a tick on the active one */
    public static AlertDialog menu(final Activity a, String title, String subtitle, List<Row> rows) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);

        if (!TextUtils.isEmpty(title)) {
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.VERTICAL);
            head.setPadding(dp(a, 20f), dp(a, 16f), dp(a, 20f), dp(a, 11f));
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextColor(Theme.TEXT);
            t.setTextSize(18.5f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(t);
            if (!TextUtils.isEmpty(subtitle)) {
                TextView s = new TextView(a);
                s.setText(subtitle);
                s.setTextColor(Theme.TEXT_DIM);
                s.setTextSize(12.5f);
                s.setPadding(0, dp(a, 3f), 0, 0);
                head.addView(s);
            }
            root.addView(head);
            View line = new View(a);
            line.setBackgroundColor(0x222FBDF0);
            root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1f))));
        }

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(a, 8f), dp(a, 8f), dp(a, 8f), dp(a, 10f));
        for (Row r : rows) list.addView(rowView(a, r));

        ScrollView sc = new ScrollView(a);
        sc.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog d = builder(a).setView(root).create();
        for (int i = 0; i < list.getChildCount(); i++) {
            final Row r = rows.get(i);
            if (r.action == null) continue;
            list.getChildAt(i).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { d.dismiss(); r.action.run(); }
            });
        }
        d.show();
        android.view.Window w = d.getWindow();
        if (w != null) {
            android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
            w.setLayout((int) (dm.widthPixels * 0.86f), ViewGroup.LayoutParams.WRAP_CONTENT);
            sc.getLayoutParams().height = (int) (dm.heightPixels * 0.62f);
        }
        return d;
    }

    /** a message dialog with the same card and a proper accent button row */
    public static AlertDialog message(final Activity a, String title, String body) {
        return builder(a)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton(com.dsh.v4tune.i18n.L.t("dlg.ok"), null)
                .show();
    }

    private static View rowView(Activity a, Row r) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, 12f), dp(a, 9f), dp(a, 14f), dp(a, 9f));
        row.setMinimumHeight(dp(a, 54f));
        row.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 12f));
        bg.setColor(r.on ? 0x222FBDF0 : 0x00000000);
        if (r.on) bg.setStroke(Math.max(1, dp(a, 1f)), 0x552FBDF0);
        row.setBackground(bg);
        row.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));

        if (!TextUtils.isEmpty(r.badge)) {
            TextView b = new TextView(a);
            b.setText(r.badge);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(14f);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextColor(r.color);
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor((r.color & 0x00FFFFFF) | 0x33000000);
            oval.setStroke(Math.max(1, dp(a, 1f)), (r.color & 0x00FFFFFF) | 0x99000000);
            b.setBackground(oval);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 34f), dp(a, 34f));
            lp.rightMargin = dp(a, 12f);
            row.addView(b, lp);
        }

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(r.title);
        t.setTextColor(r.on ? Theme.ACCENT : Theme.TEXT);
        t.setTextSize(15.5f);
        col.addView(t);
        if (!TextUtils.isEmpty(r.subtitle)) {
            TextView s = new TextView(a);
            s.setText(r.subtitle);
            s.setTextColor(Theme.TEXT_DIM);
            s.setTextSize(12f);
            s.setPadding(0, dp(a, 2f), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView c = new TextView(a);
        c.setText(r.on ? "\u2713" : "");
        c.setTextColor(Theme.ACCENT);
        c.setTextSize(17f);
        c.setPadding(dp(a, 8f), 0, 0, 0);
        row.addView(c);
        return row;
    }

    /** convenience: build the row list */
    public static List<Row> rows() { return new ArrayList<Row>(); }
}