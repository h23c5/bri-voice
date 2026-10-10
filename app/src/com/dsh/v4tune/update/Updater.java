package com.dsh.v4tune.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Self update from Gitee.
 *
 * <p>The repository carries a small manifest (update.json) next to the code:
 * <pre>
 * { "versionCode": 52, "versionName": "7.1", "apkUrl": "https://.../BriVoice-7.1.apk",
 *   "sha256": "...", "notes": "what changed" }
 * </pre>
 *
 * <p>The app reads that file, compares {@code versionCode} with the installed one, downloads the
 * APK when it is newer, checks the SHA-256, and hands the file to the system installer. Nothing
 * is installed silently: Android always shows its own confirmation dialog.
 *
 * <p>Pure Java 8 plus {@code org.json}, which ships with the platform, so the desktop build of
 * this file works too - see {@code tools/update_selftest/UpdateSelftest.java}.
 */
public final class Updater {

    /** Manifest in the repository; the raw endpoint of gitee.com is reachable from the app. */
    public static final String MANIFEST_URL =
            "https://gitee.com/h23c5/bri-voice/raw/main/update.json";
    /** Fallback when the raw endpoint is blocked: the public contents API returns it base64 coded. */
    public static final String MANIFEST_API =
            "https://gitee.com/api/v5/repos/h23c5/bri-voice/contents/update.json?ref=main";

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;

    private Updater() { }

    /** One release as described by the manifest. */
    public static final class Entry {
        public int versionCode;
        public String versionName = "";
        public String apkUrl = "";
        public String sha256 = "";
        public String notes = "";

        public String toString() {
            return versionName + " (" + versionCode + ")";
        }
    }

    /** Result of a check: {@code entry} is null when the installed build is already current. */
    public interface CheckCb { void done(Entry entry, String error); }

    /** Download progress in per-mille, then {@code error} null on success. */
    public interface DownloadCb { void progress(int permille); void done(File apk, String error); }

    // ------------------------------------------------------------------ installed version

    public static int installedVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String installedVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ check

    /** Runs off the UI thread; the callback is called on a worker thread. */
    public static void check(final Context ctx, final CheckCb cb) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String body = fetch(MANIFEST_URL);
                    Entry e = parse(body);
                    if (e == null) e = parse(fromApi());
                    if (e == null || e.apkUrl.length() == 0) { cb.done(null, "manifest unreadable"); return; }
                    if (e.versionCode <= installedVersionCode(ctx)) { cb.done(null, null); return; }
                    cb.done(e, null);
                } catch (Throwable t) {
                    cb.done(null, String.valueOf(t.getMessage()));
                }
            }
        }, "update-check").start();
    }

    /** The same manifest through the contents API, which returns it base64 encoded. */
    private static String fromApi() {
        try {
            JSONObject o = new JSONObject(fetch(MANIFEST_API));
            String b64 = o.optString("content", "").replaceAll("\\s", "");
            if (b64.length() == 0) return null;
            byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            return new String(raw, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Tolerant reader: only the fields the updater needs are taken. */
    static Entry parse(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json.trim());
            Entry e = new Entry();
            e.versionCode = o.optInt("versionCode", 0);
            e.versionName = o.optString("versionName", "");
            e.apkUrl = o.optString("apkUrl", "");
            e.sha256 = o.optString("sha256", "").toLowerCase(java.util.Locale.US);
            e.notes = o.optString("notes", "");
            if (e.versionCode <= 0 || e.apkUrl.length() == 0) return null;
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ download

    /** Where downloads land: next to the projects, so the file survives a reinstall. */
    public static File updateDir() {
        File f = new File(Environment.getExternalStorageDirectory(), "BriVoice_root/update");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static void download(final Entry e, final DownloadCb cb) {
        new Thread(new Runnable() {
            public void run() {
                File out = new File(updateDir(), "BriVoice-" + e.versionName + ".apk");
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(e.apkUrl).openConnection();
                    c.setConnectTimeout(CONNECT_TIMEOUT);
                    c.setReadTimeout(READ_TIMEOUT);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "BriVoice-updater");
                    int total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    FileOutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int n;
                    int last = -1;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int permille = (int) (got * 1000L / total);
                            if (permille != last && permille % 10 == 0) { last = permille; cb.progress(permille); }
                        }
                    }
                    os.close();
                    in.close();
                    c.disconnect();
                    if (e.sha256.length() == 64) {
                        String have = sha256(out);
                        if (!have.equalsIgnoreCase(e.sha256)) {
                            out.delete();
                            cb.done(null, "checksum mismatch");
                            return;
                        }
                    }
                    cb.done(out, null);
                } catch (Throwable t) {
                    out.delete();
                    cb.done(null, String.valueOf(t.getMessage()));
                }
            }
        }, "update-download").start();
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

    // ------------------------------------------------------------------ install

    /** Hands the APK to the system installer; the user still confirms. */
    public static void install(Context ctx, File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + apk.getName()),
                "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    // ------------------------------------------------------------------ http

    static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "BriVoice-updater");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        int code = c.getResponseCode();
        if (code != 200) throw new java.io.IOException("HTTP " + code);
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new String(out.toByteArray(), "UTF-8");
    }
}
