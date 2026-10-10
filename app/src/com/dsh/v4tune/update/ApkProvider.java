package com.dsh.v4tune.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves the downloaded update APK as {@code content://com.bri.voice.apk/<file name>}.
 *
 * <p>Since Android 7 a {@code file://} URI may not be handed to another app, so the installer
 * needs a content provider. This one exposes exactly one directory - the update folder - and
 * nothing else, and it is not exported: only granted intents can read from it.
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.bri.voice.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The only entry point: a single file name inside the update directory. */
    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0) throw new FileNotFoundException("no file name");
        if (name.contains("/") || name.contains("..")) throw new FileNotFoundException("bad name");
        File f = new File(Updater.updateDir(), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
