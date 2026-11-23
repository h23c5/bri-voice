package com.dsh.v4tune.ai;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Keeps the BriTune-Net weights out of the APK.
 *
 * <p>The app itself updates through {@link com.dsh.v4tune.update.Updater}; this class pulls the small
 * model file from the repository right after that, into {@code files/model}. The manifest is a tiny
 * JSON next to the weights:
 * <pre>
 * { "version": "1", "size": 54800, "sha256": "...",
 *   "url": "https://gitee.com/h23c5/bri-voice/raw/main/aimodel/britune-v1.bin" }
 * </pre>
 * A file already on disk is reused as-is, so the download happens once.
 */
public final class LocalModelStore {

    public static final String MANIFEST_URL =
            "https://gitee.com/h23c5/bri-voice/raw/main/aimodel/local-model.json";

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    private static LocalTuner.Model cached;

    private LocalModelStore() { }

    public static final class Entry {
        public String version = "";
        public long size;
        public String sha256 = "";
        public String url = "";
    }

    /** Progress is 0..1000; {@code done} carries either a model or a human readable error. */
    public interface Cb {
        void progress(int permille, String stage);
        void done(LocalTuner.Model model, String error);
    }

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "model");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File file(Context ctx) { return new File(dir(ctx), "britune-v1.bin"); }

    /** which manifest version produced the file above; "imported" means the user supplied it */
    public static File versionFile(Context ctx) { return new File(dir(ctx), "local-model.version"); }

    public static String storedVersion(Context ctx) {
        try {
            File f = versionFile(ctx);
            if (!f.exists()) return "";
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { int off = 0; while (off < b.length) { int n = in.read(b, off, b.length - off); if (n <= 0) break; off += n; } }
            finally { try { in.close(); } catch (Throwable ignored) { } }
            return new String(b, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeVersion(Context ctx, String v) {
        try {
            FileOutputStream out = new FileOutputStream(versionFile(ctx));
            try { out.write(v.getBytes("UTF-8")); } finally { try { out.close(); } catch (Throwable ignored) { } }
        } catch (Throwable ignored) { }
    }

    /**
     * Replaces the downloaded weights with a file the user picked in the system file manager. The
     * bytes are validated by {@link LocalTuner#load(File)} before they may replace a working model,
     * and the version marker becomes "imported" so the automatic update check leaves it alone.
     */
    public static synchronized LocalTuner.Model importFrom(Context ctx, InputStream in) throws IOException {
        File tmp = new File(dir(ctx), "britune-import.tmp");
        FileOutputStream os = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try { os.close(); } catch (Throwable ignored) { }
        }
        LocalTuner.Model m;
        try {
            m = LocalTuner.load(tmp);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        File out = file(ctx);
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) {
            copy(tmp, out);
            tmp.delete();
        }
        writeVersion(ctx, "imported");
        // the imported weights must actually be used: self-learning weights belong to the previous
        // base model (possibly a different architecture), so they are dropped while the dataset
        // stays around for the next retrain
        userFile(ctx).delete();
        forget();
        return m;
    }

    /** weights retrained on-device by {@link SelfLearn}; preferred over the shipped file */
    public static File userFile(Context ctx) { return SelfLearn.userModelFile(ctx); }

    public static boolean installed(Context ctx) {
        return file(ctx).length() > 1024 || userFile(ctx).length() > 1024;
    }

    public static boolean selfTrained(Context ctx) { return userFile(ctx).length() > 1024; }

    /**
     * Loads the on-disk model once and keeps it for the rest of the session. The self-learning file
     * wins when it exists, so the app keeps using what the user taught it.
     */
    public static synchronized LocalTuner.Model load(Context ctx) throws IOException {
        if (cached != null) return cached;
        File u = userFile(ctx);
        if (u.exists() && u.length() > 1024) {
            try {
                cached = LocalTuner.load(u);
                return cached;
            } catch (Throwable ignored) {
                // a half written or stale self-learning file must never lock the user out
                u.delete();
            }
        }
        File f = file(ctx);
        if (!f.exists()) throw new IOException("本地调音模型还没下载");
        cached = LocalTuner.load(f);
        return cached;
    }

    /** throws the retrained weights away and goes back to the shipped model */
    public static synchronized void dropUser(Context ctx) {
        userFile(ctx).delete();
        forget();
    }

    public static synchronized void forget() { cached = null; }

    public static String installedInfo(Context ctx) {
        File f = file(ctx);
        int self = SelfLearn.count(SelfLearn.dataFile(ctx));
        String base = f.exists()
                ? String.format(java.util.Locale.US, "%.1f KB", f.length() / 1024.0) : "未下载";
        if (selfTrained(ctx))
            return base + " · 自学习模型（" + self + " 条样本）";
        if (self > 0) return base + " · 待重新训练（" + self + " 条样本）";
        return base;
    }

    public static void delete(Context ctx) {
        file(ctx).delete();
        userFile(ctx).delete();
        SelfLearn.clear(SelfLearn.dataFile(ctx));
        forget();
    }

    /**
     * Makes sure the weights are on disk. Runs on a worker thread; callbacks arrive there too.
     *
     * @param force re-download even when a file looks present
     */
    public static void ensure(final Context ctx, final boolean force, final Cb cb) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (!force && "imported".equals(storedVersion(ctx)) && installed(ctx)) {
                        // the user's own weights outrank whatever the repository publishes
                        LocalTuner.Model m = load(ctx);
                        cb.progress(1000, "已就绪");
                        cb.done(m, null);
                        return;
                    }
                    String prevVersion = storedVersion(ctx);
                    cb.progress(0, "读取模型清单");
                    Entry e = null;
                    String listError = null;
                    try {
                        e = parse(fetch(MANIFEST_URL + "?t=" + System.currentTimeMillis()));
                    } catch (Throwable t) {
                        listError = t.getMessage() == null ? t.toString() : t.getMessage();
                    }
                    // a published version is only worth downloading when it differs from what is on
                    // disk; otherwise every start would pull the same 130 KB again
                    if (!force && installed(ctx) && (e == null || e.version.length() == 0
                            || e.version.equals(storedVersion(ctx)))) {
                        LocalTuner.Model m = load(ctx);
                        cb.progress(1000, "已就绪");
                        cb.done(m, null);
                        return;
                    }
                    if (e == null || e.url.length() == 0) {
                        if (installed(ctx)) {
                            LocalTuner.Model m = load(ctx);
                            cb.progress(1000, "离线，使用已下载的模型");
                            cb.done(m, null);
                            return;
                        }
                        cb.done(null, listError == null ? "模型清单不可用" : ("模型清单不可用：" + listError));
                        return;
                    }
                    File tmp = new File(dir(ctx), "britune-download.tmp");
                    cb.progress(20, "下载模型 " + (e.size > 0 ? (e.size / 1024) + " KB" : ""));
                    download(e, tmp, cb);
                    if (e.sha256.length() == 64) {
                        String have = sha256(tmp);
                        if (!have.equalsIgnoreCase(e.sha256)) {
                            tmp.delete();
                            cb.done(null, "模型校验失败（sha256 不匹配）");
                            return;
                        }
                    }
                    File out = file(ctx);
                    if (out.exists()) out.delete();
                    if (!tmp.renameTo(out)) {
                        copy(tmp, out);
                        tmp.delete();
                    }
                    writeVersion(ctx, e.version);
                    // a new base version invalidates weights self-trained on the old architecture;
                    // the sample dataset is kept so the user can retrain on the new base
                    if (!e.version.equals(prevVersion)) userFile(ctx).delete();
                    forget();
                    LocalTuner.Model m = load(ctx);
                    cb.progress(1000, "已就绪");
                    cb.done(m, null);
                } catch (Throwable t) {
                    cb.done(null, String.valueOf(t.getMessage()));
                }
            }
        }, "local-model").start();
    }

    /** progress in per-mille, used by the blocking variant below */
    public interface P { void onProgress(int permille, String stage); }

    /**
     * Blocking variant for a worker thread that wants the model before it can start; downloads it
     * when missing.
     */
    public static LocalTuner.Model ensureBlocking(final Context ctx, final P pg) throws Exception {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final LocalTuner.Model[] out = new LocalTuner.Model[1];
        final String[] err = new String[1];
        ensure(ctx, false, new Cb() {
            public void progress(int permille, String stage) {
                if (pg != null) pg.onProgress(permille, stage);
            }
            public void done(LocalTuner.Model model, String error) {
                out[0] = model;
                err[0] = error;
                latch.countDown();
            }
        });
        latch.await(150, java.util.concurrent.TimeUnit.SECONDS);
        if (out[0] == null)
            throw new IOException(err[0] == null ? "模型下载超时" : err[0]);
        return out[0];
    }

    private static void download(Entry e, File out, Cb cb) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(e.url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "BriVoice-model");
        int total = c.getContentLength();
        if (total <= 0 && e.size > 0) total = (int) e.size;
        InputStream in = c.getInputStream();
        FileOutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[16 * 1024];
        long got = 0;
        int n, last = -1;
        while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
            got += n;
            if (total > 0) {
                int permille = 20 + (int) (got * 960L / total);
                if (permille >= 1000) permille = 980;
                if (permille / 10 != last / 10) { last = permille; cb.progress(permille, "下载模型"); }
            }
        }
        os.close();
        in.close();
        c.disconnect();
        if (got < 1024) throw new IOException("模型文件过小");
    }

    private static Entry parse(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json.trim());
            Entry e = new Entry();
            e.version = o.optString("version", "");
            e.size = o.optLong("size", 0);
            e.sha256 = o.optString("sha256", "").toLowerCase(java.util.Locale.US);
            e.url = o.optString("url", "");
            if (e.url.length() == 0) return null;
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "BriVoice-model");
        c.setRequestProperty("Cache-Control", "no-cache");
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new String(out.toByteArray(), "UTF-8");
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new java.io.FileInputStream(from);
        FileOutputStream os = new FileOutputStream(to);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        in.close();
        os.close();
    }

    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        in.close();
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
