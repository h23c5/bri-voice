package com.dsh.v4tune.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.dsh.v4tune.i18n.L;

import java.util.List;

/**
 * The voicebank manager: every imported bank in one list, with the two things a bank needs -
 * rename and delete - right on its row, and the import / bundle actions below.
 *
 * <p>The dialog never edits the library itself. Each action closes it and calls back into the
 * activity, which owns the store, the confirmation dialogs and the reload; that keeps exactly one
 * place (MainActivity) that knows how to mutate singers, and lets it reopen the manager on the
 * refreshed list.</p>
 */
public final class VoicebankManager {

    /** everything the dialog needs from the activity, so it holds no state of its own */
    public interface Host {
        List<com.dsh.v4tune.synth.VoiceModel> banks();
        String currentName();
        String name(com.dsh.v4tune.synth.VoiceModel v);
        String info(com.dsh.v4tune.synth.VoiceModel v);
        boolean neural(com.dsh.v4tune.synth.VoiceModel v);
        boolean isCurrent(com.dsh.v4tune.synth.VoiceModel v);
        void select(com.dsh.v4tune.synth.VoiceModel v);
        void rename(com.dsh.v4tune.synth.VoiceModel v);
        void delete(com.dsh.v4tune.synth.VoiceModel v);
        void importFile();
        void importFolder();
        void help();
        void exportBundle();
        void importBundle();
        /** v12.2: export one model on its own */
        void exportTune();
        void exportBriNetingBase();
    }

    private VoicebankManager() { }

    public static AlertDialog show(final Activity a, final Host host) {
        final int pad = dp(a, 16f);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(a, 14f), pad, dp(a, 4f));

        TextView title = new TextView(a);
        title.setText(L.t("bank.title"));
        title.setTextColor(Theme.TEXT);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        final List<com.dsh.v4tune.synth.VoiceModel> banks = host.banks();
        TextView sub = new TextView(a);
        sub.setText(L.t("bank.sub", banks.size(), host.currentName()));
        sub.setTextColor(Theme.TEXT_DIM);
        sub.setTextSize(12.5f);
        sub.setPadding(0, dp(a, 3f), 0, dp(a, 8f));
        root.addView(sub);

        View line = new View(a);
        line.setBackgroundColor(0x222FBDF0);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1f))));

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(a, 6f), 0, dp(a, 6f));
        if (banks.isEmpty()) {
            TextView empty = new TextView(a);
            empty.setText(L.t("bank.empty"));
            empty.setTextColor(Theme.TEXT_DIM);
            empty.setTextSize(13f);
            empty.setPadding(dp(a, 4f), dp(a, 10f), dp(a, 4f), dp(a, 12f));
            list.addView(empty);
        }
        final AlertDialog[] self = new AlertDialog[1];
        for (com.dsh.v4tune.synth.VoiceModel v : banks) {
            list.addView(bankRow(a, host, v, self));
        }

        ScrollView sc = new ScrollView(a);
        sc.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(sc, scLp);

        root.addView(sectionLabel(a, L.t("bank.actions")));
        root.addView(actionRow(a,
                action(a, L.t("bank.import.file"), true, new View.OnClickListener() {
                    public void onClick(View x) { close(self); host.importFile(); }
                }),
                action(a, L.t("bank.import.folder"), false, new View.OnClickListener() {
                    public void onClick(View x) { close(self); host.importFolder(); }
                })));
        root.addView(actionRow(a,
                action(a, L.t("bank.export.bri"), false, new View.OnClickListener() {
                    public void onClick(View x) { close(self); host.exportBundle(); }
                }),
                action(a, L.t("bank.import.bri"), false, new View.OnClickListener() {
                    public void onClick(View x) { close(self); host.importBundle(); }
                })));
        root.addView(actionRow(a,
                action(a, L.t("bank.export.tune"), false, new View.OnClickListener() {
                    public void onClick(View x) { close(self); host.exportTune(); }
                }),
                action(a, L.t("bank.export.base"), false, new View.OnClickListener() {
                    public void onClick(View x) { close(self); host.exportBriNetingBase(); }
                })));
        root.addView(actionRow(a,
                action(a, L.t("bank.help"), false, new View.OnClickListener() {
                    public void onClick(View x) { close(self); host.help(); }
                }),
                new View(a)));

        AlertDialog d = Dialogs.builder(a).setView(root)
                .setNegativeButton(L.t("dlg.close"), null)
                .create();
        self[0] = d;
        d.show();
        android.view.Window w = d.getWindow();
        if (w != null) {
            android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
            w.setLayout((int) (dm.widthPixels * 0.9f), ViewGroup.LayoutParams.WRAP_CONTENT);
            int h = (int) (dm.heightPixels * 0.44f);
            if (h < dp(a, 150f)) h = dp(a, 150f);
            sc.getLayoutParams().height = h;
        }
        return d;
    }

    private static void close(AlertDialog[] self) {
        try { if (self[0] != null) self[0].dismiss(); } catch (Throwable ignored) { }
    }

    /** one bank: name + engine tag, technical summary, and its own rename / delete buttons */
    private static View bankRow(final Activity a, final Host host,
                                final com.dsh.v4tune.synth.VoiceModel v, final AlertDialog[] self) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 10f), dp(a, 8f), dp(a, 10f), dp(a, 8f));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 11f));
        bg.setColor(host.isCurrent(v) ? 0x222FBDF0 : 0x14FFFFFF);
        if (host.isCurrent(v)) bg.setStroke(Math.max(1, dp(a, 1f)), 0x552FBDF0);
        box.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(a, 6f);
        box.setLayoutParams(blp);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(a);
        name.setText(host.name(v));
        name.setTextColor(host.isCurrent(v) ? Theme.ACCENT : Theme.TEXT);
        name.setTextSize(15f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tag = new TextView(a);
        tag.setText(host.neural(v) ? L.t("bank.tag.neural") : L.t("bank.tag.trad"));
        tag.setTextColor(0xFFFFFFFF);
        tag.setTextSize(9.5f);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setIncludeFontPadding(false);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(a, 4f));
        pill.setColor(host.neural(v) ? 0xFF2F9BFF : 0xFF5A6478);
        tag.setBackground(pill);
        tag.setPadding(dp(a, 5f), dp(a, 1f), dp(a, 5f), dp(a, 2f));
        head.addView(tag);
        box.addView(head);

        TextView info = new TextView(a);
        info.setText(host.info(v));
        info.setTextColor(Theme.TEXT_DIM);
        info.setTextSize(11.5f);
        info.setPadding(0, dp(a, 2f), 0, dp(a, 6f));
        box.addView(info);

        LinearLayout ops = new LinearLayout(a);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.addView(chip(a, L.t("bank.rename"), new View.OnClickListener() {
            public void onClick(View x) { close(self); host.rename(v); }
        }));
        ops.addView(chip(a, L.t("bank.delete"), new View.OnClickListener() {
            public void onClick(View x) { close(self); host.delete(v); }
        }));
        box.addView(ops);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { close(self); host.select(v); }
        });
        return box;
    }

    private static TextView chip(Activity a, String text, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(Theme.ACCENT);
        t.setTextSize(12f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(a, 10f), dp(a, 4f), dp(a, 10f), dp(a, 5f));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 8f));
        bg.setColor(0x1F2FBDF0);
        bg.setStroke(Math.max(1, dp(a, 1f)), 0x332FBDF0);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(a, 8f);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    private static TextView sectionLabel(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(Theme.TEXT_DIM);
        t.setTextSize(11.5f);
        t.setPadding(0, dp(a, 6f), 0, dp(a, 3f));
        return t;
    }

    private static LinearLayout actionRow(Activity a, View left, View right) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(a, 2f), 0, dp(a, 2f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(a, 6f);
        r.addView(left, lp);
        r.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private static Button action(Activity a, String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        b.setTextColor(primary ? Theme.ON_PRIMARY : Theme.TEXT);
        if (primary) b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(Theme.button(a, primary));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(dp(a, 6f), dp(a, 6f), dp(a, 6f), dp(a, 6f));
        b.setOnClickListener(l);
        return b;
    }

    private static int dp(Activity a, float v) { return Theme.dpi(a, v); }
}
