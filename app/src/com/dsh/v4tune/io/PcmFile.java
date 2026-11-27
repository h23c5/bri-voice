package com.dsh.v4tune.io;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * A decoded clip kept on disk instead of in RAM.
 *
 * <p>Importing a four minute backing track used to hold ~63 MB of float samples in the heap (and
 * the 180 s cap was there to keep that bounded). The clip audio now lives in a header-less
 * interleaved <b>int16 stereo 44100 Hz</b> sidecar file and every mix reads only the frames it
 * needs, so an import is limited by disk, not by the VM heap, and there is no length cap.</p>
 *
 * <p>Plain {@code java.io}: no android classes, so it also runs on a desktop JVM.</p>
 */
public final class PcmFile {

    /** Sample rate of every sidecar file. */
    public static final int RATE = 44100;
    /** Channels written per frame (always stereo, mono sources are duplicated). */
    public static final int CHANNELS = 2;
    /** Bytes of one frame: 2 channels x 16 bit. */
    public static final int BYTES_PER_FRAME = CHANNELS * 2;
    /** Buckets in a stored waveform thumbnail. */
    public static final int PEAK_BUCKETS = 512;

    private PcmFile() {
    }

    /** A fresh file inside {@code dir}; the name is unique so clips never overwrite each other. */
    public static File newFile(File dir, String tag) {
        if (dir == null) return null;
        if (!dir.exists()) dir.mkdirs();
        String base = (tag == null || tag.length() == 0) ? "clip" : tag;
        return new File(dir, base + "-" + System.currentTimeMillis() + "-"
                + (int) (Math.random() * 100000) + ".pcm");
    }

    /** Frame count stored in a sidecar file (0 when it is missing or empty). */
    public static int frames(File f) {
        if (f == null || !f.isFile()) return 0;
        long bytes = f.length();
        long frames = bytes / BYTES_PER_FRAME;
        return frames > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) frames;
    }

    /** Deletes a sidecar file, ignoring failures (a missing file is not an error). */
    public static void delete(File f) {
        if (f == null) return;
        try { if (f.isFile()) f.delete(); } catch (Throwable ignored) { }
    }

    /**
     * Adds frames of a clip into a mix buffer, reading only the requested range from disk.
     *
     * @param f       sidecar file
     * @param clipOff first clip frame to read (may be negative, the destination is cropped)
     * @param outL    left/mono destination (required)
     * @param outR    right destination, may be null
     * @param dst     destination frame for clip frame {@code clipOff}
     * @param maxOut  number of destination frames still available
     * @return frames mixed
     */
    public static int mix(File f, int clipOff, float gain, float[] outL, float[] outR,
                          int dst, int maxOut) {
        if (f == null || outL == null || gain == 0.0f) return 0;
        int total = frames(f);
        int off = clipOff;
        int at = dst;
        if (off < 0) { at -= off; off = 0; }
        if (at < 0) { off -= at; at = 0; }
        int n = Math.min(total - off, outL.length - at);
        if (outR != null) n = Math.min(n, outR.length - at);
        if (maxOut > 0) n = Math.min(n, maxOut);
        if (n <= 0) return 0;
        RandomAccessFile raf = null;
        int done = 0;
        try {
            raf = new RandomAccessFile(f, "r");
            raf.seek((long) off * BYTES_PER_FRAME);
            byte[] buf = new byte[Math.min(n, 8192) * BYTES_PER_FRAME];
            while (done < n) {
                int want = Math.min(n - done, 8192);
                int got = raf.read(buf, 0, want * BYTES_PER_FRAME);
                if (got <= 0) break;
                int frames = got / BYTES_PER_FRAME;
                for (int i = 0; i < frames; i++) {
                    int base = i * BYTES_PER_FRAME;
                    int l = (buf[base] & 0xFF) | (buf[base + 1] << 8);
                    int r = (buf[base + 2] & 0xFF) | (buf[base + 3] << 8);
                    float fl = (short) l / 32768.0f * gain;
                    outL[at + done + i] += fl;
                    if (outR != null) outR[at + done + i] += (short) r / 32768.0f * gain;
                }
                done += frames;
                if (frames < want) break;
            }
            return done;
        } catch (Throwable t) {
            // a missing or unreadable sidecar must not take the mix down: that clip is just silent
            return done;
        } finally {
            closeQuietly(raf);
        }
    }

    private static void closeQuietly(RandomAccessFile f) {
        if (f == null) return;
        try { f.close(); } catch (Throwable ignored) { }
    }
}
