package com.dsh.v4tune.voicebank;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.v4tune.i18n.L;

import java.io.File;
import java.util.ArrayList;

/**
 * The in-app voicebank wizard: point it at a bank folder, name the pack, and it runs the ported
 * converter (VoicebankBuilder) off the UI thread and writes the .v4voice into the voicebank
 * folder. No samples and no network are involved - the pack is measured parametric data.
 *
 * <p>The folder is typed rather than picked because a bank is a directory, and the app already
 * holds MANAGE_EXTERNAL_STORAGE, so a plain path is the shortest reliable route.
 */
public final class VoicebankWizard {

    private VoicebankWizard() { }

    public static void start(final Activity act) {
        final EditText path = new EditText(act);
        path.setInputType(InputType.TYPE_CLASS_TEXT);
        path.setHint("/sdcard/VoiceDB/BK8H76TAEHXWSKDB");
        final EditText name = new EditText(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 8, 24, 8);
        box.addView(label(act, L.t("vb.bank.dir")));
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        path.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button browse = new android.widget.Button(act);
        browse.setText(L.t("vb.browse"));
        browse.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                browseForFolder(act, new File(Environment.getExternalStorageDirectory(), ""), path);
            }
        });
        row.addView(path);
        row.addView(browse);
        box.addView(row);
        box.addView(label(act, L.t("vb.bank.name")));
        box.addView(name);
        new AlertDialog.Builder(act)
                .setTitle(L.t("vb.title"))
                .setView(box)
                .setPositiveButton(L.t("vb.build"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        run(act, path.getText().toString().trim(), name.getText().toString().trim());
                    }
                })
                .setNegativeButton(L.t("update.later"), null)
                .show();
    }

    /** A plain folder browser over /sdcard - the app already holds all-files access. */
    static void browseForFolder(final Activity act, final File dir, final EditText target) {
        final java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        final java.util.ArrayList<File> dirs = new java.util.ArrayList<File>();
        File[] kids = dir.listFiles();
        if (kids != null) {
            java.util.Arrays.sort(kids, new java.util.Comparator<File>() {
                public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
            });
            for (File f : kids) if (f.isDirectory() && !f.getName().startsWith(".")) { dirs.add(f); names.add(f.getName() + "/"); }
        }
        if (dir.getParentFile() != null) { dirs.add(0, dir.getParentFile()); names.add(0, ".."); }
        final String[] items = names.toArray(new String[0]);
        new AlertDialog.Builder(act)
                .setTitle(dir.getAbsolutePath())
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { browseForFolder(act, dirs.get(w), target); }
                })
                .setPositiveButton(L.t("vb.pick"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { target.setText(dir.getAbsolutePath()); }
                })
                .setNegativeButton(L.t("update.later"), null)
                .show();
    }

    private static TextView label(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setPadding(0, 12, 0, 4);
        return t;
    }

    private static void run(final Activity act, final String dirText, final String nameText) {
        final File dir = new File(dirText);
        if (!dir.isDirectory()) { toast(act, L.t("vb.bad.dir")); return; }
        final String bank = nameText.length() > 0 ? nameText : dir.getName();
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setMessage(L.t("vb.working"));
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File out = null;
                try {
                    File ddb = findDdb(dir);
                    if (ddb == null) throw new IllegalStateException(L.t("vb.no.ddb"));
                    VoicebankBuilder.Options o = new VoicebankBuilder.Options();
                    VoicebankWriter w = VoicebankBuilder.build(ddb, bank, bank.toUpperCase(java.util.Locale.US), o);
                    File vdir = new File(Environment.getExternalStorageDirectory(), "BriVoice_root/voicebanks");
                    if (!vdir.exists()) vdir.mkdirs();
                    out = new File(vdir, w.id + ".v4voice");
                    w.writeZip(out, null);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final String e = err;
                final File f = out;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (e != null || f == null) { toast(act, L.t("vb.failed") + ": " + e); return; }
                        toast(act, L.t("vb.done") + ": " + f.getName() + " (" + (f.length() / 1024) + " KB)");
                    }
                });
            }
        }, "voicebank-build").start();
    }

    /** The .ddb is what carries the frames; the folder may nest it a level down. */
    static File findDdb(File dir) {
        return findDdb(dir, 0);
    }

    private static File findDdb(File dir, int depth) {
        if (dir == null || depth > 3) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        ArrayList<File> dirs = new ArrayList<File>();
        for (File f : kids) {
            if (f.isFile() && f.getName().toLowerCase(java.util.Locale.US).endsWith(".ddb")) return f;
            if (f.isDirectory()) dirs.add(f);
        }
        for (File d : dirs) {
            File hit = findDdb(d, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }
}
