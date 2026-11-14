package com.dsh.v4tune.voicebank;

import android.app.Activity;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.zip.Inflater;

/**
 * Fallback ZIP extractor for archives that make java.util.zip throw "MALFORMED[1]".
 *
 * <p>That error comes from ZipInputStream's strict UTF-8 decoder when an entry name is not actually
 * UTF-8 even though the zip's UTF-8 flag is set (common with Japanese/Chinese Windows tools). This
 * reader parses local file headers itself and decodes names with replacement chars, so one bad
 * metadata name cannot kill the whole voicebank import.
 */
final class TolerantZip {

    private TolerantZip() { }

    static int extract(Activity act, Uri uri, File base, String charset) throws Exception {
        InputStream in = act.getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("无法读取压缩包");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        in.close();
        byte[] z = bo.toByteArray();
        if (!base.exists()) base.mkdirs();
        Charset cs = Charset.forName(charset);
        String baseCanon = base.getCanonicalPath() + File.separator;
        int p = firstLocalHeader(z);
        int n = 0;
        while (p + 30 <= z.length && u32(z, p) == 0x04034b50) {
            int flags = u16(z, p + 6);
            int method = u16(z, p + 8);
            long comp = u32(z, p + 18);
            int nl = u16(z, p + 26);
            int el = u16(z, p + 28);
            if (p + 30 + nl + el > z.length) break;
            String name = new String(z, p + 30, nl, cs).replace('\\', '/');
            p += 30 + nl + el;
            int dataStart = p;
            if (name.length() == 0) break;
            while (name.startsWith("/")) name = name.substring(1);
            File out = new File(base, name);
            String canon = out.getCanonicalPath();
            boolean dir = name.endsWith("/");
            if (!canon.startsWith(baseCanon)) { p = skipEntry(z, dataStart, method, comp, flags); continue; }
            if (dir) {
                out.mkdirs();
                p = skipEntry(z, dataStart, method, comp, flags);
                continue;
            }
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fo = null;
            try {
                fo = new FileOutputStream(out);
                int consumed;
                if (method == 8) {
                    Inflater inf = new Inflater(true);
                    byte[] ob = new byte[65536];
                    int supplied = 0;
                    try {
                        while (!inf.finished()) {
                            if (inf.needsInput()) {
                                int avail = comp > 0 ? (int) Math.min(comp - supplied, z.length - dataStart - supplied)
                                        : (z.length - dataStart - supplied);
                                if (avail <= 0) break;
                                inf.setInput(z, dataStart + supplied, avail);
                                supplied += avail;
                            }
                            int k = inf.inflate(ob);
                            if (k > 0) fo.write(ob, 0, k);
                            else if (inf.needsDictionary()) break;
                        }
                        consumed = supplied - inf.getRemaining();
                    } finally {
                        inf.end();
                    }
                } else if (method == 0) {
                    if (comp < 0 || comp > z.length - dataStart) comp = z.length - dataStart;
                    fo.write(z, dataStart, (int) comp);
                    consumed = (int) comp;
                } else {
                    throw new IllegalStateException("不支持的压缩方式 " + method + "（" + name + "）");
                }
                p = dataStart + consumed;
                if ((flags & 8) != 0) p = afterDescriptor(z, p);
                n++;
            } finally {
                if (fo != null) try { fo.close(); } catch (Throwable ignored) { }
            }
        }
        return n;
    }

    private static int firstLocalHeader(byte[] z) {
        for (int i = 0; i + 4 <= Math.min(z.length, 65536); i++) if (u32(z, i) == 0x04034b50) return i;
        return 0;
    }

    private static int skipEntry(byte[] z, int dataStart, int method, long comp, int flags) {
        if (method == 0 && comp >= 0) return dataStart + (int) comp;
        // deflated: walk the stream to find the descriptor/next header; most zips have sizes
        if (comp > 0) return dataStart + (int) comp;
        return dataStart;
    }

    private static int afterDescriptor(byte[] z, int p) {
        if (p + 4 <= z.length && u32(z, p) == 0x08074b50) p += 4;
        return Math.min(z.length, p + 12);
    }

    private static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }

    private static long u32(byte[] b, int p) {
        return (long) (b[p] & 255) | ((long) (b[p + 1] & 255) << 8)
                | ((long) (b[p + 2] & 255) << 16) | ((long) (b[p + 3] & 255) << 24);
    }
}
