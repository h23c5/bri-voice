package com.dsh.v4tune.voicebank;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Stage A of the voicebank converter: find and decode the harmonic frames inside a bank's .ddb.
 *
 * <p>Ported line by line from tools/voicebank_pack.py (scan_frm2_offsets at 319, decode_frame at
 * 350); every constant below is a measured value from that file. The gate parameters f0Lo/f0Hi
 * default to the caller's values because the Python defaults live in F0_MIN_HZ/F0_MAX_HZ - pass
 * them explicitly so a mismatch is visible rather than silent.
 */
public final class VoicebankFrames {

    public static final byte[] FRM2_MAGIC = new byte[]{'F', 'R', 'M', '2'};
    public static final int FRM2_HEADER = 32;
    public static final int FRM2_ROW = 350;
    public static final int FRM2_MAG_ROW = 350;
    /** bytes needed per record: 32 + 4 * (350 + 350) = 2832 */
    public static final int REC_BYTES = FRM2_HEADER + 4 * (FRM2_ROW + FRM2_MAG_ROW);
    public static final double MAG_FILLER = 10000.0;
    public static final double HARMONIC_MAX_HZ = 22050.0;
    public static final int MIN_HARMONICS = 10;
    public static final double SPACING_MAX_REL = 0.08;
    /** F0_MIN_HZ, F0_MAX_HZ = 50.0, 1500.0 in the Python source */
    public static final double F0_MIN_HZ = 50.0;
    public static final double F0_MAX_HZ = 1500.0;

    private VoicebankFrames() { }

    /** One accepted frame: f0, the harmonic frequencies, their magnitudes in dB, spacing error. */
    public static final class Frame {
        public final double f0;
        public final double[] freqs;
        public final double[] magsDb;
        public final double spacingErr;

        Frame(double f0, double[] freqs, double[] magsDb, double spacingErr) {
            this.f0 = f0;
            this.freqs = freqs;
            this.magsDb = magsDb;
            this.spacingErr = spacingErr;
        }
    }

    /**
     * Linear scan for the FRM2 tag in eight windows spread over the file, exactly like the
     * Python fallback index: records are gap free, so a tag hit inside a window is a real
     * record start. Never reads more than budgetBytes.
     */
    public static long[] scanFrm2Offsets(File ddb, long ddbSize, long budgetBytes) throws Exception {
        final int windows = 8;
        long win = Math.max(1, budgetBytes / windows);
        ArrayList<Long> hits = new ArrayList<Long>();
        RandomAccessFile f = new RandomAccessFile(ddb, "r");
        try {
            for (int w = 0; w < windows; w++) {
                long start = windows > 1 ? (long) ((ddbSize - win) * (w / (double) (windows - 1))) : 0;
                if (start < 0) start = 0;
                f.seek(start);
                int len = (int) Math.min(win, ddbSize - start);
                if (len <= 8) continue;
                byte[] buf = new byte[len];
                f.readFully(buf);
                for (int i = 0; i + 8 <= buf.length; ) {
                    int at = indexOf(buf, FRM2_MAGIC, i);
                    if (at < 0) break;
                    long size = u32(buf, at + 4);
                    if (size >= 1024 && size <= (1L << 20)) hits.add(start + at);
                    i = at + 4;
                }
            }
        } finally {
            f.close();
        }
        long[] out = new long[hits.size()];
        for (int i = 0; i < out.length; i++) out[i] = hits.get(i);
        Arrays.sort(out);
        int n = 0;
        for (int i = 0; i < out.length; i++) if (i == 0 || out[i] != out[i - 1]) out[n++] = out[i];
        return Arrays.copyOf(out, n);
    }

    /** Reads one record and decodes it, or null when the acceptance gates reject it. */
    public static Frame readFrame(RandomAccessFile f, long offset, double f0Lo, double f0Hi) throws Exception {
        return readFrame(f, offset, f0Lo, f0Hi, MIN_HARMONICS);
    }

    public static Frame readFrame(RandomAccessFile f, long offset, double f0Lo, double f0Hi, int minHarmonics) throws Exception {
        byte[] rec = new byte[REC_BYTES];
        f.seek(offset);
        f.readFully(rec);
        return decode(rec, f0Lo, f0Hi, minHarmonics);
    }

    /** decode_frame with the bank defaults (F0_MIN_HZ .. F0_MAX_HZ). */
    public static Frame decode(byte[] rec) { return decode(rec, F0_MIN_HZ, F0_MAX_HZ); }

    /** decode_frame: 32 byte header, float[0..350) frequencies, float[350..700) magnitudes. */
    public static Frame decode(byte[] rec, double f0Lo, double f0Hi) {
        return decode(rec, f0Lo, f0Hi, MIN_HARMONICS);
    }

    /**
     * The harmonics section uses a LOOSER gate than the formants path (HARM_MIN_HARMONICS = 8,
     * f0 inside 80..1200) without changing the v1 behaviour - see the decode_frame docstring.
     */
    public static Frame decode(byte[] rec, double f0Lo, double f0Hi, int minHarmonics) {
        if (rec == null || rec.length < REC_BYTES) return null;
        for (int i = 0; i < 4; i++) if (rec[i] != FRM2_MAGIC[i]) return null;

        float[] body = new float[2 * FRM2_ROW];
        for (int i = 0; i < body.length; i++) body[i] = f32(rec, FRM2_HEADER + 4 * i);

        int k = 0;
        double prev = 0.0;
        for (int i = 0; i < FRM2_ROW; i++) {
            double v = body[i];
            if (!(v > prev) || v > HARMONIC_MAX_HZ * 1.01) break;
            prev = v;
            k++;
        }
        if (k < minHarmonics) return null;

        double[] freqs = new double[k];
        double[] mags = new double[k];
        for (int i = 0; i < k; i++) {
            freqs[i] = body[i];
            mags[i] = body[FRM2_MAG_ROW + i];
        }
        double f0 = freqs[0];
        if (!(f0 >= f0Lo && f0 <= f0Hi)) return null;
        for (int i = 0; i < k; i++) {
            if (!isFinite(mags[i])) return null;
            if (Math.abs(mags[i] - MAG_FILLER) < 1e-3) return null;   // unused slot marker
            if (mags[i] < -200.0) return null;
        }
        double err = 0.0;
        for (int i = 0; i < k; i++) {
            double d = Math.abs(freqs[i] - f0 * (i + 1)) / freqs[i];
            if (d > err) err = d;
        }
        if (err > SPACING_MAX_REL) return null;
        return new Frame(f0, freqs, mags, err);
    }

    // ------------------------------------------------------------------ little helpers

    static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    static float f32(byte[] b, int off) {
        return Float.intBitsToFloat((int) u32(b, off));
    }

    static boolean isFinite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
