package com.dsh.v4tune.io;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;

/**
 * BriVoice keeps everything the user cares about in ONE folder at the root of shared storage:
 *
 * <pre>
 *   /sdcard/BriVoice_root/
 *       projects/     saved .vsqx
 *       voicebanks/   installed .v4voice packs (drop files here from a PC or file manager)
 *       export/       exported WAV
 * </pre>
 *
 * <p>Android names the APK's own install directory itself (/data/app/...) and an app cannot rename
 * that, so this "root" folder is the closest thing an app is allowed to own. Writing there needs
 * the "all files access" grant on Android 11+; until the user gives it, the app falls back to its
 * own external directory so nothing is ever lost.</p>
 */
public final class Storage {

    public static final String ROOT_NAME = "BriVoice_root";

    private Storage() {
    }

    /** true when the app may write straight into shared storage */
    public static boolean canWriteRoot(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
            return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** the root folder when it is usable, otherwise the app's own external directory */
    public static File root(Context ctx) {
        if (canWriteRoot(ctx)) {
            File f = publicRoot();
            if (ensureDir(f)) return f;
        }
        File ext = ctx.getExternalFilesDir(null);
        File f = new File(ext != null ? ext : ctx.getFilesDir(), ROOT_NAME);
        ensureDir(f);
        return f;
    }

    /** the public root, whether or not it can be written right now (used for messages) */
    public static File publicRoot() {
        return new File(Environment.getExternalStorageDirectory(), ROOT_NAME);
    }

    public static File projects(Context ctx) {
        return sub(ctx, "projects");
    }

    public static File voicebanks(Context ctx) {
        return sub(ctx, "voicebanks");
    }

    public static File exports(Context ctx) {
        return sub(ctx, "export");
    }

    public static File sub(Context ctx, String name) {
        File f = new File(root(ctx), name);
        ensureDir(f);
        return f;
    }

    /**
     * Creates the whole tree. Returns false when the PUBLIC root is not usable - i.e. when the
     * "all files access" grant is still missing - so the caller can offer the one-time prompt.
     * The fallback folder is created either way, so no work is ever lost.
     */
    public static boolean ensureAll(Context ctx) {
        File r = root(ctx);                                    // creates the fallback when needed
        boolean created = ensureDir(r) && ensureDir(new File(r, "projects"))
                && ensureDir(new File(r, "voicebanks")) && ensureDir(new File(r, "export"));
        return created && canWriteRoot(ctx);
    }

    private static boolean ensureDir(File d) {
        try {
            if (d.isDirectory()) return true;
            return d.mkdirs() || d.isDirectory();
        } catch (Throwable t) {
            return false;
        }
    }

    /** picks a free file name, never overwriting an existing one */
    public static File unique(File dir, String base, String ext) {
        String safe = (base == null ? "untitled" : base).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) safe = "untitled";
        File f = new File(dir, safe + ext);
        int n = 1;
        while (f.exists() && n < 1000) {
            f = new File(dir, safe + "_" + n + ext);
            n++;
        }
        return f;
    }
}
