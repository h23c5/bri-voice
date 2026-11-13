package com.dsh.v4tune.voicebank;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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

    /** request code for the UTAU zip picker (handled in MainActivity.onActivityResult) */
    public static final int REQ_ZIP = 1006;

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
        android.widget.Button zipBtn = new android.widget.Button(act);
        zipBtn.setText("选 UTAU 压缩包（.zip）");
        zipBtn.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) { pickZip(act); }
        });
        box.addView(zipBtn);
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
    public static void browseForFolder(final Activity act, final File dir, final EditText target) {
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
        File oto = findOto(dir, 0);
        final File bankDir = oto != null ? oto.getParentFile() : dir;
        final String bank = nameText.length() > 0 ? nameText : bankDir.getName();
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setMessage(L.t("vb.working"));
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                String info = null;
                File out = null;
                try {
                    VoicebankWriter w;
                    File otoNow = findOto(dir, 0);
                    if (otoNow != null) {
                        w = UtauBankBuilder.build(otoNow.getParentFile(), bank);
                    } else {
                        File ddb = findDdb(dir);
                        if (ddb == null) throw new IllegalStateException(L.t("vb.no.ddb") + " / oto.ini");
                        VoicebankBuilder.Options o = new VoicebankBuilder.Options();
                        w = VoicebankBuilder.build(ddb, bank, bank.toUpperCase(java.util.Locale.US), o);
                    }
                    File vdir = new File(Environment.getExternalStorageDirectory(), "BriVoice_root/voicebanks");
                    if (!vdir.exists()) vdir.mkdirs();
                    out = new File(vdir, w.id + ".v4voice");
                    w.writeZip(out, null);
                    int bandCount = 0;
                    for (ArrayList<VoicebankPack.Band> bl : w.phonemes.values()) bandCount += bl.size();
                    info = "声库：" + w.name + "\n元音：" + w.phonemes.size() + " 个\n频段：" + bandCount
                            + " 条\n辅音谱：" + w.noise.size() + " 个\n文件：" + out.getName()
                            + "（" + (out.length() / 1024) + " KB）";
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                    try {
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(Environment.getExternalStorageDirectory(), "BriVoice_root/last_utau_error.txt"));
                        java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.OutputStreamWriter(fo, "UTF-8"));
                        t.printStackTrace(pw); pw.flush(); pw.close();
                    } catch (Throwable ignored) { }
                }
                final String e = err;
                final String inf = info;
                final File f = out;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (e != null || f == null) { toast(act, L.t("vb.failed") + ": " + e); return; }
                        new AlertDialog.Builder(act)
                                .setTitle("声库已生成")
                                .setMessage(inf)
                                .setPositiveButton("好", null)
                                .show();
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

    /** pick a UTAU bank distributed as a zip; the result comes back through MainActivity */
    static void pickZip(Activity act) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try { act.startActivityForResult(i, REQ_ZIP); }
        catch (Throwable t) { toast(act, "无法打开文件选择器: " + t.getMessage()); }
    }

    /** Called from MainActivity when the zip picker returns. Extracts into cache, then builds. */
    public static void importZip(final Activity act, final Uri uri) {
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setMessage("正在解压 UTAU 声库...");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                File base = null;
                String err = null;
                try {
                    String[] encs = {"UTF-8", "Shift_JIS", "GBK"};
                    long ts = System.currentTimeMillis();
                    for (int i = 0; i < encs.length; i++) {
                        File d = new File(act.getCacheDir(), "utau_zip_" + ts + "_" + i);
                        try {
                            int n = extractZip(act, uri, d, encs[i]);
                            if (n > 0 && findOto(d, 0) != null) { base = d; break; }
                        } catch (Throwable t) {
                            err = String.valueOf(t.getMessage());
                        }
                        deleteTree(d);
                    }
                    if (base == null) throw new IllegalStateException(err != null ? err : "压缩包里没有 oto.ini");
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final File b = base;
                final String e = err;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (e != null || b == null) { toast(act, "导入失败: " + e); return; }
                        VoicebankWizard.run(act, b.getAbsolutePath(), "");
                    }
                });
            }
        }, "utau-unzip").start();
    }

    /** extract with a chosen filename charset; returns the number of files written */
    private static int extractZip(Activity act, Uri uri, File base, String charset) throws Exception {
        if (!base.exists()) base.mkdirs();
        InputStream in = act.getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("无法读取压缩包");
        ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(in), java.nio.charset.Charset.forName(charset));
        byte[] buf = new byte[8192];
        ZipEntry ze;
        int n = 0;
        while ((ze = zis.getNextEntry()) != null) {
            String name = ze.getName();
            if (name == null || name.length() == 0) continue;
            File out = new File(base, name);
            String canon = out.getCanonicalPath();
            if (!canon.startsWith(base.getCanonicalPath())) continue;
            if (ze.isDirectory()) { out.mkdirs(); continue; }
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fo = new FileOutputStream(out);
            int r;
            while ((r = zis.read(buf)) > 0) fo.write(buf, 0, r);
            fo.close();
            n++;
        }
        zis.close();
        return n;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** find oto.ini, looking a few folders deep (banks are often .../voice/<name>/oto.ini) */
    static File findOto(File dir, int depth) {
        if (dir == null || depth > 4) return null;
        File direct = new File(dir, "oto.ini");
        if (direct.isFile()) return direct;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        java.util.Arrays.sort(kids);
        for (File k : kids) {
            if (!k.isDirectory() || k.getName().startsWith(".")) continue;
            File hit = findOto(k, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }
}
