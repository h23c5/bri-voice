package com.dsh.v4tune.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Character artwork for the lower-left corner. The user picks any picture; if its border is
 * white/near-white, that background is flood-filled away from the edges only, so white areas
 * inside the character (clothes, highlights) are kept. Transparent PNGs are used as-is.
 */
public final class CharacterArt {

    private CharacterArt() { }

    public static Bitmap fromUri(Context ctx, Uri uri) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法读取图片");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(in, null, bounds);
        in.close();
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("不是有效的图片");
        int scale = 1;
        while (bounds.outWidth / scale > 1600 || bounds.outHeight / scale > 1600) scale *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = scale;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream in2 = ctx.getContentResolver().openInputStream(uri);
        Bitmap bm = BitmapFactory.decodeStream(in2, null, o);
        if (in2 != null) in2.close();
        if (bm == null) throw new Exception("图片解码失败");
        return cutWhite(bm);
    }

    /** flood-fill the border background away, feather the edge, then trim */
    public static Bitmap cutWhite(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w < 4 || h < 4) return src;
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        // sample the border: only treat it as a white background when most of the rim is light
        long rr = 0, gg = 0, bb = 0; int n = 0, light = 0;
        int stepX = Math.max(1, w / 80), stepY = Math.max(1, h / 80);
        for (int x = 0; x < w; x += stepX) {
            for (int y : new int[]{0, h - 1}) {
                int c = px[y * w + x]; int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                rr += r; gg += g; bb += b; n++;
                if (r > 205 && g > 205 && b > 205) light++;
            }
        }
        for (int y = 0; y < h; y += stepY) {
            for (int x : new int[]{0, w - 1}) {
                int c = px[y * w + x]; int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                rr += r; gg += g; bb += b; n++;
                if (r > 205 && g > 205 && b > 205) light++;
            }
        }
        if (n == 0) return src;
        int bgR = (int) (rr / n), bgG = (int) (gg / n), bgB = (int) (bb / n);
        boolean whiteBg = light > n * 0.55 && (bgR + bgG + bgB) / 3 > 195;
        if (!whiteBg) return trim(src);

        byte[] keep = new byte[w * h];       // 1 = still opaque
        byte[] seen = new byte[w * h];
        java.util.Arrays.fill(keep, (byte) 1);
        int[] q = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) { q[tail++] = x; q[tail++] = (h - 1) * w + x; }
        for (int y = 0; y < h; y++) { q[tail++] = y * w; q[tail++] = y * w + w - 1; }
        while (head < tail) {
            int idx = q[head++];
            if (idx < 0 || idx >= w * h || seen[idx] == 1) continue;
            seen[idx] = 1;
            int x = idx % w, y = idx / w;
            int c = px[idx];
            if (!isBg(c, bgR, bgG, bgB)) continue;
            keep[idx] = 0;
            if (x > 0 && tail < q.length) q[tail++] = idx - 1;
            if (x + 1 < w && tail < q.length) q[tail++] = idx + 1;
            if (y > 0 && tail < q.length) q[tail++] = idx - w;
            if (y + 1 < h && tail < q.length) q[tail++] = idx + w;
        }

        // feather: 3x3 mean of the kept mask, applied to alpha so the cut does not look jagged
        int[] alpha = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int orig = (px[idx] >>> 24) & 255;
                if (keep[idx] == 1) { alpha[idx] = orig; continue; }
                int sum = 0, cnt = 0;
                for (int yy = Math.max(0, y - 1); yy <= Math.min(h - 1, y + 1); yy++) {
                    for (int xx = Math.max(0, x - 1); xx <= Math.min(w - 1, x + 1); xx++) {
                        sum += keep[yy * w + xx]; cnt++;
                    }
                }
                int a = (sum * 255) / Math.max(1, cnt);
                alpha[idx] = a < 18 ? 0 : Math.min(orig, a);
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] dst = new int[w * h];
        for (int i = 0; i < dst.length; i++) dst[i] = (px[i] & 0x00FFFFFF) | (alpha[i] << 24);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return trim(out);
    }

    private static boolean isBg(int c, int bgR, int bgG, int bgB) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int dr = Math.abs(r - bgR), dg = Math.abs(g - bgG), db = Math.abs(b - bgB);
        if (Math.max(dr, Math.max(dg, db)) <= 34) return true;
        return r > 232 && g > 232 && b > 232 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 14;
    }

    /** crop fully transparent borders with a small margin */
    private static Bitmap trim(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((px[y * w + x] >>> 24) & 255) > 10) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return src;
        int pad = 4;
        minX = Math.max(0, minX - pad); minY = Math.max(0, minY - pad);
        maxX = Math.min(w - 1, maxX + pad); maxY = Math.min(h - 1, maxY + pad);
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return src;
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    public static void savePng(Context ctx, Bitmap bm) throws Exception {
        File f = new File(ctx.getFilesDir(), "character.png");
        FileOutputStream out = new FileOutputStream(f);
        bm.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.flush(); out.close();
    }

    public static File savedFile(Context ctx) {
        return new File(ctx.getFilesDir(), "character.png");
    }
}
