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
        run(act, dirText, nameText, null);
    }

    /** extra is the zip diagnostic block, shown in the failure sheet */
    private static void run(final Activity act, final String dirText, final String nameText, final String extra) {
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
                Throwable errT = null;
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
                    errT = t;
                    try {
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(Environment.getExternalStorageDirectory(), "BriVoice_root/last_utau_error.txt"));
                        java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.OutputStreamWriter(fo, "UTF-8"));
                        t.printStackTrace(pw); pw.flush(); pw.close();
                    } catch (Throwable ignored) { }
                }
                final String e = err;
                final String inf = info;
                final File f = out;
                final Throwable fexc = errT;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (e != null || f == null) {
                            StringBuilder d = new StringBuilder();
                            d.append("声库生成失败\n").append("目录: ").append(dir.getAbsolutePath()).append('\n');
                            d.append("名称: ").append(bank).append('\n');
                            File oto = findOto(dir, 0);
                            d.append("oto.ini: ").append(oto == null ? "未找到" : oto.getAbsolutePath()).append('\n');
                            d.append("文件数: ").append(countFiles(dir)).append('\n');
                            d.append("错误: ").append(e).append('\n');
                            if (extra != null && extra.length() > 0) d.append("\n--- 压缩包诊断 ---\n").append(extra);
                            showFailure(act, "声库生成失败", d.toString(), fexc);
                            return;
                        }
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
        final StringBuilder diag = new StringBuilder(uriInfo(act, uri));
        new Thread(new Runnable() {
            public void run() {
                File best = null;
                int bestScore = -1;
                String bestEnc = null;
                String bestOto = null;
                Throwable last = null;
                String[] encs = {"UTF-8", "Shift_JIS", "GBK"};
                long ts = System.currentTimeMillis();
                for (int i = 0; i < encs.length; i++) {
                    File d = new File(act.getCacheDir(), "utau_zip_" + ts + "_" + i);
                    try {
                        int n = extractZip(act, uri, d, encs[i]);
                        File oto = findOto(d, 0);
                        int entries = oto == null ? 0 : countOtoEntries(oto);
                        int score = oto == null ? 0 : otoMatchScore(oto);
                        diag.append(encs[i]).append(": 解压 ").append(n).append(" 个文件，oto.ini=")
                                .append(oto == null ? "未找到" : ("找到，条目 " + entries + "，文件名匹配 " + score)).append('\n');
                        if (oto != null && n > 0) {
                            if (score > bestScore) {
                                if (best != null) deleteTree(best);
                                best = d;
                                bestScore = score;
                                bestEnc = encs[i];
                                bestOto = oto.getAbsolutePath();
                            } else {
                                deleteTree(d);
                            }
                            if (score >= 8 || (score > 0 && score * 2 >= entries)) break;
                        } else {
                            deleteTree(d);
                        }
                    } catch (Throwable t) {
                        last = t;
                        diag.append(encs[i]).append(": 解压失败 ").append(t).append('\n');
                        deleteTree(d);
                    }
                }
                final File b = best;
                final Throwable fe = last;
                final String enc = bestEnc;
                final String otoPath = bestOto;
                if (b != null) {
                    diag.append("采用编码: ").append(enc).append("，oto.ini: ").append(otoPath).append('\n');
                } else {
                    diag.append("\n压缩包内文件样本：\n").append(zipSample(act, uri, 30)).append('\n');
                }
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (b == null) {
                            showFailure(act, "压缩包无法识别", diag.toString(), fe);
                            return;
                        }
                        VoicebankWizard.run(act, b.getAbsolutePath(), "", diag.toString());
                    }
                });
            }
        }, "utau-unzip").start();
    }

    /** extract with a chosen filename charset; returns the number of files written */
    private static int extractZip(Activity act, Uri uri, File base, String charset) throws Exception {
        if (!base.exists()) base.mkdirs();
        InputStream in = act.getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("无法读取压缩包（系统没有给文件流）");
        ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(in), java.nio.charset.Charset.forName(charset));
        byte[] buf = new byte[8192];
        ZipEntry ze;
        int n = 0;
        String baseCanon = base.getCanonicalPath() + File.separator;
        try {
            while ((ze = zis.getNextEntry()) != null) {
                String name = ze.getName();
                if (name == null || name.length() == 0) continue;
                name = name.replace('\\', '/');                 // Windows-built zips often use backslashes
                while (name.startsWith("/")) name = name.substring(1);
                if (name.length() == 0) continue;
                File out = new File(base, name);
                String canon = out.getCanonicalPath();
                if (!canon.startsWith(baseCanon)) continue;     // zip-slip guard
                if (ze.isDirectory() || name.endsWith("/")) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fo = new FileOutputStream(out);
                int r;
                while ((r = zis.read(buf)) > 0) fo.write(buf, 0, r);
                fo.close();
                n++;
            }
        } finally {
            try { zis.close(); } catch (Throwable ignored) { }
            try { in.close(); } catch (Throwable ignored) { }
        }
        return n;
    }

    /** count "file=alias,..." lines in an oto.ini */
    private static int countOtoEntries(File oto) {
        try {
            java.util.List<String> lines = otoLines(oto);
            int n = 0;
            for (String line : lines) if (line.indexOf('=') > 0) n++;
            return n;
        } catch (Throwable t) { return 0; }
    }

    /** how many oto.ini entries resolve to an extracted file; used to pick the right zip charset */
    private static int otoMatchScore(File oto) {
        try {
            java.util.List<String> lines = otoLines(oto);
            File dir = oto.getParentFile();
            int matched = 0;
            for (String line : lines) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String name = line.substring(0, eq).trim();
                if (name.length() == 0) continue;
                if (resolveByName(dir, name) != null) matched++;
            }
            return matched;
        } catch (Throwable t) { return 0; }
    }

    private static java.util.List<String> otoLines(File oto) throws Exception {
        byte[] b = java.nio.file.Files.readAllBytes(oto.toPath());
        String s;
        if (b.length > 2 && (b[0] == (byte)0xFF && b[1] == (byte)0xFE)) {
            s = new String(b, "UTF-16LE");
        } else if (b.length > 2 && (b[0] == (byte)0xFE && b[1] == (byte)0xFF)) {
            s = new String(b, "UTF-16BE");
        } else {
            try {
                java.nio.charset.CharsetDecoder dec = java.nio.charset.Charset.forName("UTF-8").newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                s = dec.decode(java.nio.ByteBuffer.wrap(b)).toString();
            } catch (Throwable t) {
                s = new String(b, "Shift_JIS");
            }
        }
        if (s.startsWith("\uFEFF")) s = s.substring(1);
        String[] a = s.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        return java.util.Arrays.asList(a);
    }

    private static File resolveByName(File dir, String name) {
        File f = new File(dir, name);
        if (f.isFile()) return f;
        File hit = findFileRecursive(dir, new File(name).getName(), 0);
        return hit;
    }

    private static File findFileRecursive(File dir, String baseName, int depth) {
        if (dir == null || depth > 6) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) if (k.isFile() && k.getName().equalsIgnoreCase(baseName)) return k;
        for (File k : kids) if (k.isDirectory()) {
            File hit = findFileRecursive(k, baseName, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private static int countFiles(File dir) {
        if (dir == null) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File k : kids) n += k.isDirectory() ? countFiles(k) : 1;
        return n;
    }

    private static String zipSample(Activity act, Uri uri, int max) {
        StringBuilder b = new StringBuilder();
        try {
            InputStream in = act.getContentResolver().openInputStream(uri);
            if (in == null) return "(无法读取文件列表)";
            ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(in), java.nio.charset.Charset.forName("UTF-8"));
            ZipEntry ze;
            int n = 0;
            while ((ze = zis.getNextEntry()) != null && n < max) {
                b.append("  ").append(ze.getName()).append(ze.isDirectory() ? "/" : "").append('\n');
                n++;
            }
            try { zis.close(); } catch (Throwable ignored) { }
            if (n == 0) b.append("  (空压缩包或无法读取条目)\n");
        } catch (Throwable t) {
            b.append("  (读取条目失败: ").append(t.getMessage()).append(")\n");
        }
        return b.toString();
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

    /** detailed failure sheet with a one-tap copy button (user feedback needs the full report) */
    private static void showFailure(Activity act, String title, String detail, Throwable t) {
        StringBuilder b = new StringBuilder();
        b.append(detail == null ? "" : detail).append("\n\n");
        b.append("--- 设备信息 ---\n");
        b.append("Android: ").append(android.os.Build.VERSION.RELEASE).append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        b.append("机型: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL).append("\n");
        b.append("BriVoice: ").append(appVersion(act)).append("\n");
        b.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())).append("\n");
        if (t != null) {
            b.append("\n--- 异常 ---\n");
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            b.append(sw.toString());
        }
        final String report = b.toString();
        try {
            java.io.File dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "BriVoice_root");
            if (!dir.exists()) dir.mkdirs();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(dir, "last_voicebank_error.txt"));
            fo.write(report.getBytes("UTF-8")); fo.flush(); fo.close();
        } catch (Throwable ignored) { }

        TextView tv = new TextView(act);
        tv.setText(report);
        tv.setTextSize(12f);
        tv.setTextIsSelectable(true);
        tv.setPadding(36, 24, 36, 24);
        android.widget.ScrollView sc = new android.widget.ScrollView(act);
        sc.addView(tv);
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(sc)
                .setPositiveButton("复制日志", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("BriVoice voicebank error", report));
                            toast(act, "日志已复制，可直接粘贴反馈");
                        } catch (Throwable t) { toast(act, "复制失败: " + t.getMessage()); }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static String appVersion(Activity act) {
        try {
            android.content.pm.PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) { return "unknown"; }
    }

    private static String uriInfo(Activity act, Uri uri) {
        StringBuilder b = new StringBuilder();
        b.append("URI: ").append(uri == null ? "null" : uri.toString()).append("\n");
        try {
            android.database.Cursor c = act.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (c.moveToFirst()) {
                    if (ni >= 0) b.append("文件名: ").append(c.getString(ni)).append("\n");
                    if (si >= 0) b.append("大小: ").append(c.getLong(si)).append(" bytes\n");
                }
                c.close();
            }
        } catch (Throwable ignored) { }
        return b.toString();
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }
}
