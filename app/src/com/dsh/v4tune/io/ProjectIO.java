package com.dsh.v4tune.io;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.dsh.v4tune.audio.AudioEngine;
import com.dsh.v4tune.format.VsqxReader;
import com.dsh.v4tune.format.VsqxWriter;
import com.dsh.v4tune.model.Song;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Opening / saving projects and exporting audio. */
public class ProjectIO {

    public static final String MIME_VSQX = "application/octet-stream";
    public static final String MIME_WAV = "audio/wav";

    /** true when the file looks like a voicebank pack rather than a project */
    public static boolean isVoicePackName(String name) {
        return name != null && name.toLowerCase(java.util.Locale.US).endsWith(".v4voice");
    }

    /** Read a project from any content Uri. Detects VSQX / VSQ / MIDI from the content itself. */
    /** Open a Uri (content:// from a picker or file manager, or a plain file:// path). */
    public static InputStream openAny(Context ctx, Uri uri) throws Exception {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
        } catch (Throwable ignored) {
            in = null;
        }
        if (in == null && uri != null && "file".equals(uri.getScheme()) && uri.getPath() != null) {
            // scoped storage blocks contentResolver for plain paths on Android 10+; try the path directly
            try {
                in = new java.io.FileInputStream(uri.getPath());
            } catch (Throwable ignored) {
            }
        }
        if (in == null) {
            throw new Exception("系统不允许 BriVoice 直接读取这个路径（Android 分区存储限制）。\n"
                    + "请改用应用内的「打开」按钮，或从文件管理器用「打开方式 → BriVoice」打开。");
        }
        return in;
    }

    public static Song open(Context ctx, Uri uri) throws Exception {
        InputStream in = openAny(ctx, uri);
        if (in == null) throw new Exception("无法打开文件");
        try {
            byte[] head = new byte[512];
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) all.write(buf, 0, r);
            in.close();
            byte[] data = all.toByteArray();
            String txt = new String(data, 0, Math.min(data.length, 400), "UTF-8");
            if (txt.contains("<vsq4") || txt.contains("<vsq3") || txt.trim().startsWith("<?xml")) {
                return VsqxReader.read(new java.io.ByteArrayInputStream(data));
            }
            if (txt.contains("[Common]") || txt.contains("[MasterTrack]")) {
                return com.dsh.v4tune.format.Vsq2Reader.read(new java.io.ByteArrayInputStream(data));
            }
            if (data.length > 14 && data[0] == 'M' && data[1] == 'T' && data[2] == 'h' && data[3] == 'd') {
                return com.dsh.v4tune.format.MidiReader.read(new java.io.ByteArrayInputStream(data));
            }
            throw new Exception("不支持的文件格式（支持 .vsqx / .vsq / .mid）");
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    public static Song openPath(File f) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) all.write(buf, 0, r);
            return VsqxReader.read(new java.io.ByteArrayInputStream(all.toByteArray()));
        } finally { try { in.close(); } catch (Exception ignored) {} }
    }

    public static void save(Context ctx, Uri uri, Song s) throws Exception {
        OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt");
        if (out == null) throw new Exception("无法写入文件");
        try {
            VsqxWriter.write(s, out);
            out.flush();
        } finally {
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    public static void savePath(File f, Song s) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            VsqxWriter.write(s, out);
            out.flush();
        } finally { out.close(); }
    }

    public static File exportDir(Context ctx) {
        File d = Storage.exports(ctx);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File exportWav(Context ctx, String base, AudioEngine.Rendered r) throws Exception {
        File d = exportDir(ctx);
        File f = new File(d, sanitize(base) + ".wav");
        AudioEngine.writeWav(f, r);
        return f;
    }

    public static File exportMp3Unsupported() { return null; }

    public static String sanitize(String s) {
        if (s == null || s.trim().length() == 0) return "output";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ("\\/:*?\"<>|".indexOf(c) >= 0 || c < 32) b.append('_');
            else b.append(c);
        }
        String r = b.toString().trim();
        return r.length() > 60 ? r.substring(0, 60) : r;
    }

    public static File autosaveFile(Context ctx, String name) {
        File d = new File(ctx.getFilesDir(), "autosave");
        if (!d.exists()) d.mkdirs();
        return new File(d, sanitize(name) + ".vsqx");
    }

    public static void share(Context ctx, File f, String mime) {
        try {
            Uri u = Uri.parse("file://" + f.getAbsolutePath());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(Intent.createChooser(i, "分享"));
        } catch (Throwable t) {
            android.widget.Toast.makeText(ctx, "分享失败: " + t.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
