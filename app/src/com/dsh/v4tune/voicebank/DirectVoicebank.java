package com.dsh.v4tune.voicebank;

import android.content.Context;

import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * "Use the real voicebank directly": point BriVoice at a V4 folder ({@code *.ddb}) or a UTAU
 * folder ({@code oto.ini + wav}) and it builds the measured model in one step, without asking the
 * user to make and then import a .v4voice file.
 *
 * <p>The generated model is cached under {@code filesDir/direct_packs} keyed by the source path
 * and its mtime/size, so the next launch is instant and the singer stays in the list. The cache is
 * still measured spectral data: original samples are never copied into it.
 */
public final class DirectVoicebank {

    private DirectVoicebank() { }

    public static File cacheDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "direct_packs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Build (or reuse) a model from a folder / zip and return it. Blocking: call off the UI thread. */
    public static VoiceModel load(Context ctx, File source) throws Exception {
        if (source == null || !source.exists()) throw new IllegalArgumentException("路径不存在");
        File dir = source;
        if (source.isFile()) {
            dir = extractZip(ctx, source);
        }
        File oto = findOto(dir, 0);
        File ddb = oto == null ? findExt(dir, ".ddb", 0) : null;
        if (oto == null && ddb == null) {
            throw new IllegalStateException("没有找到 oto.ini 或 .ddb（V4 / UTAU 声库源文件）");
        }
        String key = digest(source.getAbsolutePath() + "|" + source.lastModified() + "|" + source.length());
        File pack = new File(cacheDir(ctx), "direct_" + key + VoicePack.EXT);
        if (pack.isFile()) {
            VoicePack.Result cached = VoicePack.load(pack);
            if (cached != null && cached.ok()) return cached.model;
        }
        String name = dir.getName();
        VoicebankWriter w;
        if (oto != null) {
            w = UtauBankBuilder.build(oto.getParentFile(), name);
            w.notes = "Loaded directly from a UTAU voicebank folder (oto.ini + wav).";
        } else {
            w = VoicebankBuilder.build(ddb, name, "", new VoicebankBuilder.Options());
            w.notes = "Loaded directly from a VOCALOID4 voicebank folder (.ddb frames).";
        }
        w.writeZip(pack, VoicebankWriter.DEFAULT_LICENSE
                + "Direct-use cache: generated from the source voicebank on this device.\n");
        VoicePack.Result r = VoicePack.load(pack);
        if (r == null || !r.ok()) {
            if (pack.exists()) pack.delete();
            throw new IllegalStateException(r == null ? "声库解析失败" : r.error);
        }
        r.model.description = (r.model.description == null ? "" : r.model.description)
                + " · 直接载入（缓存）";
        return r.model;
    }

    // ------------------------------------------------------------------ discovery

    private static File findOto(File dir, int depth) {
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

    private static File findExt(File dir, String ext, int depth) {
        if (dir == null || depth > 4) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        java.util.Arrays.sort(kids);
        for (File k : kids) {
            if (k.isFile() && k.getName().toLowerCase(java.util.Locale.US).endsWith(ext)) return k;
        }
        for (File k : kids) {
            if (!k.isDirectory() || k.getName().startsWith(".")) continue;
            File hit = findExt(k, ext, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private static File extractZip(Context ctx, File zip) throws Exception {
        File base = new File(ctx.getCacheDir(), "direct_src_" + System.currentTimeMillis());
        if (!base.exists()) base.mkdirs();
        String[] encs = {"UTF-8", "Shift_JIS", "GBK"};
        File last = base;
        for (int i = 0; i < encs.length; i++) {
            File d = new File(base, "try" + i);
            int n = unzip(zip, d, encs[i]);
            if (n > 0 && (findOto(d, 0) != null || findExt(d, ".ddb", 0) != null)) return d;
            last = d;
        }
        throw new IllegalStateException("压缩包里没有 oto.ini 或 .ddb");
    }

    private static int unzip(File zip, File base, String charset) throws Exception {
        if (!base.exists()) base.mkdirs();
        InputStream in = new FileInputStream(zip);
        ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(in),
                java.nio.charset.Charset.forName(charset));
        byte[] buf = new byte[8192];
        ZipEntry ze;
        int n = 0;
        String root = base.getCanonicalPath();
        while ((ze = zis.getNextEntry()) != null) {
            String name = ze.getName();
            if (name == null || name.length() == 0) continue;
            File out = new File(base, name);
            if (!out.getCanonicalPath().startsWith(root)) continue;
            if (ze.isDirectory()) { out.mkdirs(); continue; }
            File p = out.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            FileOutputStream fo = new FileOutputStream(out);
            int r;
            while ((r = zis.read(buf)) > 0) fo.write(buf, 0, r);
            fo.close();
            n++;
        }
        zis.close();
        return n;
    }

    private static String digest(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] b = md.digest(s.getBytes("UTF-8"));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) out.append(String.format("%02x", b[i] & 0xFF));
        return out.toString();
    }

    // ------------------------------------------------------------------ UI

    public interface LoadCb {
        void done(VoiceModel model, String error);
    }

    /** Dialog: pick a V4 / UTAU folder and use it as a singer without a .v4voice step. */
    public static void start(final android.app.Activity act, final LoadCb cb) {
        final android.widget.EditText path = new android.widget.EditText(act);
        path.setSingleLine(true);
        path.setHint("/sdcard/VoiceDB/某声库文件夹");
        android.widget.LinearLayout box = new android.widget.LinearLayout(act);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        android.widget.TextView desc = new android.widget.TextView(act);
        desc.setText("选一个文件夹即可：V4 声库（*.ddb）或 UTAU 声库（oto.ini + wav）。\n"
                + "不需要先转换成 .v4voice；载入后会缓存为当前歌姬，下次启动仍在。\n"
                + "只测量频谱参数，不复制原始录音。");
        desc.setTextSize(12.5f);
        box.addView(desc);

        android.widget.LinearLayout row = new android.widget.LinearLayout(act);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        path.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button browse = new android.widget.Button(act);
        browse.setText("浏览…");
        browse.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                VoicebankWizard.browseForFolder(act, new java.io.File(
                        android.os.Environment.getExternalStorageDirectory(), ""), path);
            }
        });
        row.addView(path);
        row.addView(browse);
        box.addView(row);

        new android.app.AlertDialog.Builder(act)
                .setTitle("直接使用声库（V4 / UTAU 源文件）")
                .setView(box)
                .setPositiveButton("载入", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        run(act, path.getText().toString().trim(), cb);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void run(final android.app.Activity act, final String path, final LoadCb cb) {
        if (path == null || path.length() == 0) {
            if (cb != null) cb.done(null, "请先选一个声库文件夹");
            return;
        }
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(act);
        pd.setMessage("正在直接载入声库…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                VoiceModel model = null;
                String err = null;
                try {
                    model = load(act, new java.io.File(path));
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final VoiceModel fm = model;
                final String fe = err;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (cb != null) cb.done(fm, fe);
                    }
                });
            }
        }, "direct-voicebank").start();
    }

}
