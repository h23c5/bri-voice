package com.dsh.v4tune.synth;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Minimal RIFF/WAVE writer (16-bit PCM, little endian) with soft clipping.
 * Plain java.io only, so it runs unchanged on a desktop JVM and on Android.
 */
public class WavWriter {

    private static final int HEADER_BYTES = 44;

    private WavWriter() {
    }

    /** Writes a mono 16-bit WAV file. */
    public static void writeMono16(File f, float[] samples, int sampleRate) throws IOException {
        writeBytes(f, toBytesMono16(samples, sampleRate));
    }

    /** Writes an interleaved stereo 16-bit WAV file; the shorter array bounds the length. */
    public static void writeStereo16(File f, float[] l, float[] r, int sampleRate) throws IOException {
        if (l == null) l = new float[0];
        if (r == null) r = new float[0];
        int n = Math.min(l.length, r.length);
        int sr = sanitizeRate(sampleRate);
        byte[] out = new byte[HEADER_BYTES + n * 4];
        header(out, 2, sr, n * 4);
        int p = HEADER_BYTES;
        for (int i = 0; i < n; i++) {
            int a = toPcm16(l[i]);
            int b = toPcm16(r[i]);
            out[p++] = (byte) (a & 0xFF);
            out[p++] = (byte) ((a >> 8) & 0xFF);
            out[p++] = (byte) (b & 0xFF);
            out[p++] = (byte) ((b >> 8) & 0xFF);
        }
        writeBytes(f, out);
    }

    /** Returns a complete mono 16-bit WAV file image, header included. */
    public static byte[] toBytesMono16(float[] samples, int sampleRate) {
        int n = (samples == null) ? 0 : samples.length;
        int sr = sanitizeRate(sampleRate);
        byte[] out = new byte[HEADER_BYTES + n * 2];
        header(out, 1, sr, n * 2);
        int p = HEADER_BYTES;
        for (int i = 0; i < n; i++) {
            int v = toPcm16(samples[i]);
            out[p++] = (byte) (v & 0xFF);
            out[p++] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    // ---------------------------------------------------------------- internals

    private static int sanitizeRate(int sampleRate) {
        return (sampleRate <= 0) ? 44100 : sampleRate;
    }

    private static void writeBytes(File f, byte[] data) throws IOException {
        if (f == null) throw new IOException("null output file");
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(data);
        } finally {
            os.close();
        }
    }

    /** Fills the 44-byte canonical RIFF/WAVE/fmt/data header. */
    private static void header(byte[] out, int channels, int sampleRate, int dataBytes) {
        int byteRate = sampleRate * channels * 2;
        int blockAlign = channels * 2;
        putAscii(out, 0, "RIFF");
        putInt32(out, 4, 36 + dataBytes);
        putAscii(out, 8, "WAVE");
        putAscii(out, 12, "fmt ");
        putInt32(out, 16, 16);                 // PCM fmt chunk size
        putInt16(out, 20, 1);                  // format = PCM
        putInt16(out, 22, channels);
        putInt32(out, 24, sampleRate);
        putInt32(out, 28, byteRate);
        putInt16(out, 32, blockAlign);
        putInt16(out, 34, 16);                 // bits per sample
        putAscii(out, 36, "data");
        putInt32(out, 40, dataBytes);
    }

    private static void putAscii(byte[] out, int off, String s) {
        for (int i = 0; i < s.length(); i++) out[off + i] = (byte) s.charAt(i);
    }

    private static void putInt32(byte[] out, int off, int v) {
        out[off] = (byte) (v & 0xFF);
        out[off + 1] = (byte) ((v >> 8) & 0xFF);
        out[off + 2] = (byte) ((v >> 16) & 0xFF);
        out[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putInt16(byte[] out, int off, int v) {
        out[off] = (byte) (v & 0xFF);
        out[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    /** Soft clipper: Pade approximation of tanh, exactly 1.0 at |x| >= 3. */
    private static double softClip(double x) {
        if (Double.isNaN(x)) return 0.0;
        if (x >= 3.0) return 1.0;
        if (x <= -3.0) return -1.0;
        double x2 = x * x;
        return x * (27.0 + x2) / (27.0 + 9.0 * x2);
    }

    private static int toPcm16(float s) {
        double y = softClip(s);
        int q = (int) Math.round(y * 32767.0);
        if (q > 32767) q = 32767;
        if (q < -32768) q = -32768;
        return q;
    }
}
