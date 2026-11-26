package com.dsh.v4tune.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.dsh.v4tune.i18n.L;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The start screen (v12.3): what the app shows before the piano roll.
 *
 * <p>Four ways in - new project, open project, singer manager, more - and the five most recent
 * projects at the bottom, so a half-finished song is one tap away instead of a file picker.</p>
 *
 * <p>It owns no project state: every action closes the screen and calls back into the activity,
 * which is the only place that knows how to replace the song or open a file. "More" is a second
 * page inside the same full-screen dialog, so the user never loses their place.</p>
 */
public final class StartScreen {

    /** what the start screen needs from the activity */
    public interface Host {
        void startNew();
        void startOpen();
        void startSingers();

        void startSettings();
        void startTutorial();
        void startGitee();
        void startBilibili();
        void startAbout();

        /** up to five entries, newest first */
        List<Recent> startRecents();

        void startRecent(Recent r);
    }

    /** one row of the recent list */
    public static final class Recent {
        public String label = "";
        public String path = "";
        public long time;
        public long bytes;

        public Recent() { }

        public Recent(String label, String path, long time, long bytes) {
            this.label = label;
            this.path = path;
            this.time = time;
            this.bytes = bytes;
        }
    }

    private final Activity a;
    private final Host host;
    private Dialog dialog;

    public StartScreen(Activity activity, Host host) {
        this.a = activity;
        this.host = host;
    }

    /** build and show the full-screen start screen */
    public void show() {
        dialog = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawableResource(android.R.color.black);
        dialog.setContentView(shell());
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) { dialog = null; }
        });
        dialog.show();
    }

    public boolean isShowing() { return dialog != null && dialog.isShowing(); }

    public void dismiss() {
        if (dialog != null) {
            try { dialog.dismiss(); } catch (Throwable ignored) { }
            dialog = null;
        }
    }

    // ------------------------------------------------------------------ pages

    private View shell() {
        ScrollView sc = new ScrollView(a);
        sc.setBackgroundColor(Theme.BG_DEEP);
        sc.setFillViewport(true);
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        outer.setPadding(dp(20), dp(22), dp(20), dp(22));
        sc.addView(outer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(columnWidth(),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        outer.addView(page, plp);
        mainPage(page);
        return sc;
    }

    private int columnWidth() {
        int screen = a.getResources().getDisplayMetrics().widthPixels;
        int max = dp(640);
        return Math.min(screen - dp(40), max);
    }

    private void mainPage(final LinearLayout page) {
        page.removeAllViews();

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        TextView name = new TextView(a);
        name.setText("BriVoice");
        name.setTextColor(Theme.ACCENT);
        name.setTextSize(31f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(name);
        TextView ver = new TextView(a);
        ver.setText(L.t("start.version"));
        ver.setTextColor(Theme.TEXT_DIM);
        ver.setTextSize(12.5f);
        ver.setPadding(dp(10), 0, 0, dp(5));
        head.addView(ver);
        page.addView(head);

        TextView sub = new TextView(a);
        sub.setText(L.t("start.subtitle"));
        sub.setTextColor(Theme.TEXT_DIM);
        sub.setTextSize(12.5f);
        sub.setPadding(0, dp(6), 0, dp(16));
        page.addView(sub);

        // ---------------------------------------------------------- 2 x 2 entry grid
        LinearLayout row1 = row();
        row1.addView(card(L.t("start.new"), L.t("start.new.sub"), Theme.PRIMARY,
                new View.OnClickListener() {
                    public void onClick(View v) { go(); host.startNew(); }
                }));
        row1.addView(card(L.t("start.open"), L.t("start.open.sub"), Theme.ACCENT2,
                new View.OnClickListener() {
                    public void onClick(View v) { go(); host.startOpen(); }
                }));
        page.addView(row1);

        LinearLayout row2 = row();
        row2.addView(card(L.t("start.singers"), L.t("start.singers.sub"), 0xFF7CE38B,
                new View.OnClickListener() {
                    public void onClick(View v) { go(); host.startSingers(); }
                }));
        row2.addView(card(L.t("start.more"), L.t("start.more.sub"), 0xFFFFB454,
                new View.OnClickListener() {
                    public void onClick(View v) { morePage(page); }
                }));
        page.addView(row2);

        // ---------------------------------------------------------- recents
        TextView title = new TextView(a);
        title.setText(L.t("start.recent"));
        title.setTextColor(Theme.TEXT);
        title.setTextSize(14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(2), dp(22), 0, dp(8));
        page.addView(title);

        List<Recent> list = host.startRecents();
        if (list == null) list = new ArrayList<Recent>();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.card(a, Theme.SURFACE));
        if (list.isEmpty()) {
            TextView empty = new TextView(a);
            empty.setText(L.t("start.empty"));
            empty.setTextColor(Theme.TEXT_DIM);
            empty.setTextSize(12.5f);
            empty.setPadding(dp(14), dp(16), dp(14), dp(16));
            box.addView(empty);
        } else {
            int n = Math.min(5, list.size());
            for (int i = 0; i < n; i++) {
                box.addView(recentRow(list.get(i)));
                if (i < n - 1) box.addView(Theme.divider(a));
            }
        }
        page.addView(box);

        TextView foot = new TextView(a);
        foot.setText(L.t("start.foot"));
        foot.setTextColor(Theme.TEXT_DIM);
        foot.setTextSize(11f);
        foot.setPadding(dp(2), dp(10), 0, 0);
        page.addView(foot);
    }

    private void morePage(final LinearLayout page) {
        page.removeAllViews();

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Button back = small(L.t("start.back"));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { mainPage(page); }
        });
        head.addView(back);
        TextView name = new TextView(a);
        name.setText("  " + L.t("start.more"));
        name.setTextColor(Theme.TEXT);
        name.setTextSize(20f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(name);
        page.addView(head);

        TextView sub = new TextView(a);
        sub.setText(L.t("start.more.sub2"));
        sub.setTextColor(Theme.TEXT_DIM);
        sub.setTextSize(12.5f);
        sub.setPadding(dp(2), dp(8), 0, dp(16));
        page.addView(sub);

        page.addView(clickRow(L.t("start.settings"), L.t("start.settings.sub"), new View.OnClickListener() {
            public void onClick(View v) { go(); host.startSettings(); }
        }));
        page.addView(clickRow(L.t("start.tutorial"), L.t("start.tutorial.sub"), new View.OnClickListener() {
            public void onClick(View v) { go(); host.startTutorial(); }
        }));
        page.addView(clickRow(L.t("start.gitee"), L.t("start.gitee.sub"), new View.OnClickListener() {
            public void onClick(View v) { host.startGitee(); }
        }));
        page.addView(clickRow(L.t("start.bili"), L.t("start.bili.sub"), new View.OnClickListener() {
            public void onClick(View v) { host.startBilibili(); }
        }));
        page.addView(clickRow(L.t("start.about"), L.t("start.about.sub"), new View.OnClickListener() {
            public void onClick(View v) { go(); host.startAbout(); }
        }));

        TextView foot = new TextView(a);
        foot.setText(L.t("start.more.foot"));
        foot.setTextColor(Theme.TEXT_DIM);
        foot.setTextSize(11f);
        foot.setPadding(dp(2), dp(12), 0, 0);
        page.addView(foot);
    }

    // ------------------------------------------------------------------ widgets

    private void go() {
        dismiss();
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, 0, 0, dp(10));
        return r;
    }

    private View card(String title, String subtitle, int accent, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackground(Theme.card(a, Theme.SURFACE));
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(5), 0, dp(5), 0);
        box.setLayoutParams(lp);

        View bar = new View(a);
        bar.setBackgroundColor(accent);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(3),
                ViewGroup.LayoutParams.MATCH_PARENT);
        blp.setMargins(0, dp(2), dp(12), dp(2));
        box.addView(bar, blp);

        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Theme.TEXT);
        t.setTextSize(16.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(t);
        TextView s = new TextView(a);
        s.setText(subtitle);
        s.setTextColor(Theme.TEXT_DIM);
        s.setTextSize(11.5f);
        s.setPadding(0, dp(4), 0, 0);
        text.addView(s);
        box.addView(text);
        return box;
    }

    private View recentRow(final Recent r) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { go(); host.startRecent(r); }
        });

        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(r.label);
        t.setTextColor(Theme.TEXT);
        t.setTextSize(14f);
        text.addView(t);
        TextView meta = new TextView(a);
        meta.setText(metaOf(r));
        meta.setTextColor(Theme.TEXT_DIM);
        meta.setTextSize(11f);
        meta.setPadding(0, dp(3), 0, 0);
        text.addView(meta);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(a);
        arrow.setText(L.t("start.open.row"));
        arrow.setTextColor(Theme.PRIMARY);
        arrow.setTextSize(12f);
        row.addView(arrow);
        return row;
    }

    private String metaOf(Recent r) {
        StringBuilder sb = new StringBuilder();
        if (r.time > 0) {
            sb.append(String.format(Locale.US, L.t("start.meta.saved"), new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(r.time))));
        }
        if (r.bytes > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(r.bytes >= 1024 ? (r.bytes / 1024) + " KB" : r.bytes + " B");
        }
        if (sb.length() == 0) sb.append(L.t("start.meta.local"));
        return sb.toString();
    }

    private View clickRow(String title, String subtitle, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.card(a, Theme.SURFACE));
        box.setPadding(dp(15), dp(13), dp(15), dp(13));
        box.setClickable(true);
        box.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(9));
        box.setLayoutParams(lp);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Theme.TEXT);
        t.setTextSize(15.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        TextView s = new TextView(a);
        s.setText(subtitle);
        s.setTextColor(Theme.TEXT_DIM);
        s.setTextSize(11.5f);
        s.setPadding(0, dp(4), 0, 0);
        box.addView(s);
        return box;
    }

    private Button small(String text) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        b.setTextColor(Theme.TEXT);
        b.setBackground(Theme.button(a, false));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        return b;
    }

    private int dp(float v) { return Theme.dpi(a, v); }

    /** trilingual label kept for future screens that reuse this class */
    static String t(String key) { return L.t(key); }
}
