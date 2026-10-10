package com.dsh.v4tune.io;

import com.dsh.v4tune.synth.VoicePack;
import com.dsh.v4tune.synth.VoiceModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * On-disk home of the imported singer packs: one {@code <id>.v4voice} file per pack inside a
 * directory the caller owns (on Android {@code new File(getFilesDir(), "singers")}).
 *
 * <p>Plain {@code java.io} only, so the class also runs on a desktop JVM. The directory is created
 * on demand, the file name comes from the <em>parsed model id</em> (never from the user supplied
 * file name) and is sanitized to {@code [A-Za-z0-9._-]}, so a hostile pack cannot write outside the
 * directory.</p>
 */
public class PackStore {

    private static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024;   // voicebanks may be up to 2 GB
    private static final int MAX_ID = 96;

    /** Windows device names that cannot be used as a file name (even with an extension). */
    private static final HashSet<String> RESERVED = new HashSet<String>();

    static {
        String[] fixed = {"CON", "PRN", "AUX", "NUL"};
        for (String n : fixed) RESERVED.add(n);
        for (int i = 1; i <= 9; i++) {
            RESERVED.add("COM" + i);
            RESERVED.add("LPT" + i);
        }
    }

    private final File dir;

    public PackStore(File dir) {
        this.dir = dir != null ? dir : new File(".");
    }

    /**
     * Where BriVoice keeps installed voicebanks: the app-specific EXTERNAL directory, so a
     * voicebank may be gigabytes without eating the internal data partition. No storage
     * permission is needed for this location on any supported Android version.
     */
    public static File defaultDir(android.content.Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        File base = (ext != null) ? ext : ctx.getFilesDir();
        File d = new File(base, "voicebanks");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** the directory actually in use (for display) */
    public File directory() { return dir; }

    /**
     * Loads every {@code *.v4voice} in the directory, sorted by display name (then id). Files that
     * do not parse are skipped silently; use {@link VoicePack#load(File)} to report them.
     */
    public List<VoicePack.Result> loadAll() {
        ArrayList<VoicePack.Result> out = new ArrayList<VoicePack.Result>();
        ensureDir();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(VoicePack.EXT)) continue;
            VoicePack.Result r;
            try {
                r = VoicePack.load(f);
            } catch (Throwable t) {
                continue;                     // broken file: skip
            }
            if (r != null && r.ok() && r.model != null && !r.model.id.isEmpty()) out.add(r);
        }
        Collections.sort(out, new Comparator<VoicePack.Result>() {
            public int compare(VoicePack.Result a, VoicePack.Result b) {
                String an = a.model.name, bn = b.model.name;
                int c = an.compareTo(bn);
                return c != 0 ? c : a.model.id.compareTo(b.model.id);
            }
        });
        return out;
    }

    /**
     * Copies {@code in} into the store. The stream must be a {@code .v4voice} zip; it is fully
     * consumed but not closed. The written file is named after the id of the parsed model.
     *
     * @param fileName user visible name, only used in error messages
     * @param overwrite replace an already installed pack with the same id
     * @return the parsed model, or an error text when the pack is invalid / cannot be written
     */
    public VoicePack.Result importStream(InputStream in, String fileName, boolean overwrite) {
        String hint = hintOf(fileName);
        if (in == null) return err(hint + "no data");
        if (!ensureDir()) return err(hint + "cannot create " + dir.getAbsolutePath());
        File tmp = new File(dir, ".import-" + Long.toHexString(System.nanoTime()) + ".tmp");
        boolean keep = false;
        try {
            long total = 0;
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) {
                    total += r;
                    if (total > MAX_IMPORT_BYTES) return err(hint + "pack is larger than 64 MB");
                    fos.write(buf, 0, r);
                }
            } finally {
                fos.close();
            }

            VoicePack.Result parsed;
            FileInputStream fis = new FileInputStream(tmp);
            try {
                parsed = VoicePack.load(fis, fileName);          // hint = the user visible name
            } finally {
                fis.close();
            }
            if (parsed == null || !parsed.ok()) return err(parsed == null ? hint + "parse failed" : parsed.error);
            VoiceModel model = parsed.model;
            if (model == null || model.id == null || model.id.trim().isEmpty()) {
                return err(hint + "pack has no usable id");
            }

            String id = sanitizeId(model.id);
            if (id.isEmpty()) return err(hint + "pack id has no usable characters");
            File target = new File(dir, id + VoicePack.EXT);
            if (target.exists()) {
                if (!overwrite) return err(hint + "a pack with id \"" + id + "\" is already installed");
                if (!target.delete() && target.exists()) return err(hint + "cannot replace " + target.getName());
            }
            if (!tmp.renameTo(target)) copy(tmp, target);        // cross-device / locked fallback
            if (!target.isFile()) return err(hint + "cannot write " + target.getName());
            keep = true;

            VoicePack.Result ok = new VoicePack.Result();
            ok.model = model;
            return ok;
        } catch (Exception e) {
            return err(hint + msg(e));
        } finally {
            if (!keep) {
                try {
                    if (tmp.exists()) tmp.delete();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    /** Deletes the pack with this id. Returns false when nothing was removed. */
    public boolean delete(String packId) {
        String id = sanitizeId(packId);
        if (id.isEmpty()) return false;
        File f = new File(dir, id + VoicePack.EXT);
        return f.isFile() && f.delete();
    }

    /**
     * Canonical file of a pack id. Ids without any usable character map to {@code unnamed.v4voice};
     * {@link #delete(String)} refuses those.
     */
    public File fileFor(String packId) {
        String id = sanitizeId(packId);
        if (id.isEmpty()) id = "unnamed";
        return new File(dir, id + VoicePack.EXT);
    }

    /** Number of {@code .v4voice} files currently in the store. */
    public int size() {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f != null && f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(VoicePack.EXT)) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- internals

    private boolean ensureDir() {
        return dir.isDirectory() || dir.mkdirs() || dir.isDirectory();
    }

    /** Keeps only {@code [A-Za-z0-9._-]}, avoids hidden/relative names and Windows device names. */
    private static String sanitizeId(String id) {
        if (id == null) return "";
        String t = id.trim();
        StringBuilder sb = new StringBuilder(Math.min(t.length(), MAX_ID));
        for (int i = 0; i < t.length() && sb.length() < MAX_ID; i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String s = sb.toString();
        while (s.startsWith(".")) s = "_" + s.substring(1);       // no hidden files, no ".."
        if (s.isEmpty()) return "";
        String upper = s.toUpperCase(Locale.ROOT);
        int dot = upper.indexOf('.');
        if (RESERVED.contains(dot >= 0 ? upper.substring(0, dot) : upper)) s = "_" + s;
        return s;
    }

    private static void copy(File from, File to) throws Exception {
        FileInputStream in = new FileInputStream(from);
        try {
            FileOutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static VoicePack.Result err(String error) {
        VoicePack.Result r = new VoicePack.Result();
        r.model = null;
        r.error = error;
        return r;
    }

    private static String hintOf(String fileName) {
        if (fileName == null) return "";
        String h = fileName.trim();
        if (h.isEmpty()) return "";
        if (h.length() > 120) h = h.substring(0, 120);
        return h + ": ";
    }

    private static String msg(Throwable t) {
        if (t == null) return "unknown error";
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t.getClass().getSimpleName();
        return m;
    }
}
