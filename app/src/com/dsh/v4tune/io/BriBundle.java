package com.dsh.v4tune.io;

import android.content.Context;

import com.dsh.v4tune.ai.LocalModelStore;
import com.dsh.v4tune.ai.LocalTuner;
import com.dsh.v4tune.synth.NeuralSpectrum;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The .bri model bundle: one file that carries the on-device models the user trained or downloaded,
 * so they survive a reinstall, a phone swap or an experiment gone wrong.
 *
 * <p>Contents (a plain zip, readable with any unzip tool):</p>
 * <pre>
 * manifest.json                        format marker + one line per entry (size and SHA-256)
 * model/britune.bin                    BriTune-Net weights (self-learned when present)
 * model/brineting-base.v4neural        the BriNeting universal acoustic base
 * </pre>
 *
 * <p>Import never half-applies: every entry is extracted, its size and SHA-256 are checked, and the
 * bytes are parsed with the same loaders the app uses before a single file on disk is replaced. A
 * bundle from another version, a truncated download or a hand-edited file therefore leaves the
 * installed models exactly as they were. {@link #verify(Context, File)} runs the same checks without
 * installing anything, so an export can be read back before it is handed to the user.</p>
 */
public final class BriBundle {

    public static final String EXT = ".bri";

    private static final String MANIFEST = "manifest.json";
    private static final String MAGIC = "BRIPACK1";
    private static final int FORMAT = 1;

    private static final String KIND_TUNE = "britune";
    private static final String KIND_BASE = "brineting-base";

    private BriBundle() { }

    /** one model inside a bundle */
    public static final class Part {
        public String kind = "";
        public String path = "";
        public File file;
        public long size;
        public String sha256 = "";
    }

    /** what a bundle holds; after {@link #importFrom} it also says what was installed */
    public static final class Result {
        public int tuneBytes;
        public int baseBytes;
        public boolean selfTrained;
        public String text = "";
        /** extracted weights, consumed by the caller of parse() */
        File tuneTmp;
        File baseTmp;
    }

    // ---------------------------------------------------------------- locations

    /**
     * The BriNeting universal base. Same path MainActivity keeps its models in; spelled out here so
     * a bundle can be written or read without an Activity.
     */
    public static File baseFile(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "neural"), "_universal.v4neural");
    }

    /** the BriTune-Net weights currently in use: self-learned first, then the downloaded release */
    public static File tuneFile(Context ctx) {
        File u = LocalModelStore.userFile(ctx);
        if (u != null && u.length() > 1024) return u;
        File f = LocalModelStore.file(ctx);
        return (f != null && f.length() > 1024) ? f : null;
    }

    public static boolean selfTrained(Context ctx) {
        File u = LocalModelStore.userFile(ctx);
        return u != null && u.length() > 1024;
    }

    /** the models that would go into a bundle right now (empty when nothing was trained yet) */
    public static List<Part> parts(Context ctx) {
        List<Part> out = new ArrayList<Part>();
        File t = tuneFile(ctx);
        if (t != null) {
            Part p = new Part();
            p.kind = KIND_TUNE;
            p.path = "model/britune.bin";
            p.file = t;
            p.size = t.length();
            p.sha256 = sha256(t);
            out.add(p);
        }
        File b = baseFile(ctx);
        if (b.isFile() && b.length() > 0) {
            Part p = new Part();
            p.kind = KIND_BASE;
            p.path = "model/brineting-base.v4neural";
            p.file = b;
            p.size = b.length();
            p.sha256 = sha256(b);
            out.add(p);
        }
        return out;
    }

    /** human readable inventory, one line per model (for the export dialog) */
    public static String summary(Context ctx) {
        StringBuilder sb = new StringBuilder();
        File t = tuneFile(ctx);
        if (t == null) {
            sb.append("BriTune-Net：未安装");
        } else {
            sb.append("BriTune-Net：").append(kb(t.length()));
            if (selfTrained(ctx)) sb.append("（自学习）");
        }
        sb.append("\n");
        File b = baseFile(ctx);
        if (!b.isFile() || b.length() == 0) {
            sb.append("BriNeting 基座：未训练");
        } else {
            sb.append("BriNeting 基座：").append(kb(b.length()));
        }
        return sb.toString();
    }

    private static String kb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
    }

    // ---------------------------------------------------------------- export

    /**
     * Writes a bundle to {@code raw}. The stream is closed by this call, so the caller only has to
     * hand over a fresh {@code ContentResolver} stream.
     *
     * @return the number of models written
     */
    public static int export(Context ctx, OutputStream raw) throws IOException {
        List<Part> parts = parts(ctx);
        if (parts.isEmpty()) {
            throw new IOException("没有可导出的模型：先训练 BriNeting 基座，或下载 BriTune-Net 模型");
        }
        String manifest = manifestOf(ctx, parts);
        ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(raw));
        try {
            z.putNextEntry(new ZipEntry(MANIFEST));
            z.write(manifest.getBytes("UTF-8"));
            z.closeEntry();
            for (Part p : parts) {
                z.putNextEntry(new ZipEntry(p.path));
                FileInputStream in = new FileInputStream(p.file);
                try {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                }
                z.closeEntry();
            }
            z.finish();
        } finally {
            try { z.close(); } catch (Throwable ignored) { }
        }
        return parts.size();
    }

    private static String manifestOf(Context ctx, List<Part> parts) throws IOException {
        try {
            JSONArray arr = new JSONArray();
            for (Part p : parts) {
                JSONObject o = new JSONObject();
                o.put("kind", p.kind);
                o.put("path", p.path);
                o.put("size", p.size);
                o.put("sha256", p.sha256);
                arr.put(o);
            }
            JSONObject man = new JSONObject();
            man.put("magic", MAGIC);
            man.put("format", FORMAT);
            man.put("app", "BriVoice");
            man.put("created", System.currentTimeMillis());
            man.put("selfTrained", selfTrained(ctx));
            man.put("entries", arr);
            return man.toString(2);
        } catch (org.json.JSONException e) {
            throw new IOException("无法写出清单：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- import / verify

    /**
     * Restores the models carried by {@code raw}. Nothing on disk is touched until every entry has
     * passed the size/SHA checks and the real loaders accept it.
     *
     * @param raw the picked document; closed by this call
     */
    public static Result importFrom(Context ctx, InputStream raw) throws IOException {
        File cache = tempDir(ctx);
        File zip = new File(cache, "bundle.zip");
        try {
            copy(raw, zip, true);
            Result r = parse(ctx, zip);
            try {
                apply(ctx, r);
            } finally {
                drop(r);
            }
            return r;
        } finally {
            zip.delete();
        }
    }

    /** runs every check of an import without installing anything (export read-back) */
    public static Result verify(Context ctx, File zip) throws IOException {
        Result r = parse(ctx, zip);
        drop(r);
        return r;
    }

    private static File tempDir(Context ctx) throws IOException {
        File cache = new File(ctx.getCacheDir(), "bri");
        if (!cache.isDirectory() && !cache.mkdirs() && !cache.isDirectory()) {
            throw new IOException("无法建立临时目录");
        }
        return cache;
    }

    /** reads, checks and parses a bundle; returns extracted files in the result */
    private static Result parse(Context ctx, File zip) throws IOException {
        File cache = tempDir(ctx);
        Result r = new Result();
        ZipFile zf = null;
        boolean ok = false;
        try {
            zf = new ZipFile(zip);
            ZipEntry me = zf.getEntry(MANIFEST);
            if (me == null) throw new IOException("不是 BriVoice 模型备份（缺少清单）");
            byte[] mb;
            InputStream mi = zf.getInputStream(me);
            try { mb = readAll(mi); } finally { try { mi.close(); } catch (Throwable ignored) { } }
            JSONObject man = new JSONObject(new String(mb, "UTF-8"));
            if (!MAGIC.equals(man.optString("magic"))) {
                throw new IOException("不是 BriVoice 模型备份（文件标识不符）");
            }
            int fmt = man.optInt("format", 0);
            if (fmt < 1 || fmt > FORMAT) throw new IOException("备份格式版本不支持：" + fmt);
            JSONArray arr = man.optJSONArray("entries");
            if (arr == null || arr.length() == 0) throw new IOException("备份里没有模型条目");

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String kind = o.optString("kind");
                String path = o.optString("path");
                long size = o.optLong("size", -1);
                String sha = o.optString("sha256");
                ZipEntry e = zf.getEntry(path);
                if (e == null) throw new IOException("备份缺少条目：" + path);
                File t = new File(cache, "entry-" + i + ".tmp");
                InputStream in = zf.getInputStream(e);
                try { copy(in, t, false); } finally { try { in.close(); } catch (Throwable ignored) { } }
                if (size >= 0 && t.length() != size) {
                    t.delete();
                    throw new IOException("备份条目不完整：" + kind + "（" + t.length() + " / " + size + " 字节）");
                }
                if (sha != null && sha.length() > 0 && !sha.equalsIgnoreCase(sha256(t))) {
                    t.delete();
                    throw new IOException("备份已损坏（校验不符）：" + kind);
                }
                if (KIND_TUNE.equals(kind)) r.tuneTmp = t;
                else if (KIND_BASE.equals(kind)) r.baseTmp = t;
            }
            if (r.tuneTmp == null && r.baseTmp == null) throw new IOException("备份里没有可用的模型条目");

            // parse with the real loaders before anything installed is replaced
            if (r.tuneTmp != null) {
                LocalTuner.load(r.tuneTmp);          // validates the BRTN header and shapes
                r.tuneBytes = (int) r.tuneTmp.length();
            }
            if (r.baseTmp != null) {
                NeuralSpectrum.load(r.baseTmp);      // validates the BriNeting base
                r.baseBytes = (int) r.baseTmp.length();
            }
            r.selfTrained = man.optBoolean("selfTrained", false);
            r.text = describe(r);
            ok = true;
            return r;
        } catch (org.json.JSONException e) {
            throw new IOException("备份清单无法解析：" + e.getMessage());
        } finally {
            if (zf != null) { try { zf.close(); } catch (Throwable ignored) { } }
            if (!ok) drop(r);
        }
    }

    /**
     * installs what {@link #parse} validated. The base goes first (it stands alone), then the tuning
     * weights through the store, which marks them "imported" and drops weights tied to the old base.
     */
    private static void apply(Context ctx, Result r) throws IOException {
        if (r.baseTmp != null) install(r.baseTmp, baseFile(ctx));
        if (r.tuneTmp != null) {
            FileInputStream fin = new FileInputStream(r.tuneTmp);
            try { LocalModelStore.importFrom(ctx, fin); } finally { try { fin.close(); } catch (Throwable ignored) { } }
            r.tuneBytes = (int) LocalModelStore.file(ctx).length();
        }
    }

    private static void drop(Result r) {
        if (r == null) return;
        if (r.tuneTmp != null) { r.tuneTmp.delete(); r.tuneTmp = null; }
        if (r.baseTmp != null) { r.baseTmp.delete(); r.baseTmp = null; }
    }

    private static String describe(Result r) {
        StringBuilder sb = new StringBuilder();
        if (r.tuneBytes > 0) sb.append("BriTune-Net ").append(kb(r.tuneBytes));
        if (r.baseBytes > 0) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append("BriNeting 基座 ").append(kb(r.baseBytes));
        }
        return sb.toString();
    }

    /** replaces {@code dst} only after the whole copy landed, so a full disk leaves the old file */
    private static void install(File src, File dst) throws IOException {
        File dir = dst.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法建立目录：" + dir.getName());
        }
        File tmp = new File(dst.getPath() + ".new");
        copy(new FileInputStream(src), tmp, true);
        if (dst.exists() && !dst.delete()) {
            tmp.delete();
            throw new IOException("无法替换 " + dst.getName());
        }
        if (!tmp.renameTo(dst)) {
            copy(new FileInputStream(tmp), dst, true);
            tmp.delete();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** copies {@code in} into a stream; the source is closed only when asked to */
    private static void copy(InputStream in, OutputStream out, boolean closeOut) throws IOException {
        try {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            if (closeOut) { try { out.close(); } catch (Throwable ignored) { } }
        }
    }

    private static void copy(InputStream in, File out, boolean closeIn) throws IOException {
        FileOutputStream fo = new FileOutputStream(out);
        try { copy(in, fo, true); } finally { if (closeIn) { try { in.close(); } catch (Throwable ignored) { } } }
    }

    /** SHA-256 of a file, lowercase hex */
    public static String sha256(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            return hex(md.digest());
        } catch (Throwable t) {
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
