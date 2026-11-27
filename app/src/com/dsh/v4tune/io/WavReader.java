package com.dsh.v4tune.io;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Defensive RIFF/WAVE reader for the audio-clip tracks.
 *
 * <p>Supported input: PCM 8/16/24/32 bit integer and 32/64 bit IEEE float, mono or stereo,
 * any sample rate ({@link #OUT_RATE} output, linear interpolation), and
 * {@code WAVE_FORMAT_EXTENSIBLE} (0xFFFE) with a PCM or float sub-format. Unknown chunks
 * (LIST, fact, JUNK, bext, ...) are skipped, odd chunk sizes honour the RIFF pad byte, and the
 * {@code fmt} chunk may appear after the {@code data} chunk.</p>
 *
 * <p>Hostile input never throws and never allocates without bound: chunk sizes that lie, a
 * {@code data} chunk longer than the file, empty data, illegal bit depths, more than two
 * channels and unreadable streams all come back as {@link AudioData#error}, and
 * {@code maxFrames} caps the decoded length (reported through {@link AudioData#truncated}).</p>
 *
 * <p>Plain {@code java.io} only - no android classes - so it compiles and runs on a desktop
 * JVM as well as on Android.</p>
 */
public final class WavReader {

    /** Sample rate of every returned clip; other rates are resampled to this. */
    public static final int OUT_RATE = 44100;
    /** Frame cap applied when {@code maxFrames <= 0}: 2 minutes at {@link #OUT_RATE}. */
    public static final int DEFAULT_MAX_FRAMES = OUT_RATE * 120;
    /**
     * Frame cap for {@link #exportPcm} when no explicit cap is given: one hour at
     * {@link #OUT_RATE}. The old in-heap decode was capped at 3 minutes because the samples had to
     * fit in memory; a streamed sidecar only costs disk, so backing tracks are no longer cut.
     */
    public static final int MAX_PCM_FRAMES = OUT_RATE * 60 * 60;
    /** fmt chunks are small; a larger declared size is treated as a lie and clamped. */
    private static final int FMT_MAX = 256;
    /** Upper bound for buffering a data chunk that shows up before the fmt chunk. */
    private static final long MAX_STASH = 16L * 1024 * 1024;
    /** Read block size while decoding samples. */
    private static final int IO_BLOCK = 32768;
    /** Highest accepted sample rate; anything above is a corrupt header. */
    private static final long MAX_RATE = 1000000L;

    private WavReader() {
    }

    // ------------------------------------------------------------------ public API

    /**
     * Decodes a WAV image from a stream. The caller keeps ownership of the stream (it is not
     * closed here).
     *
     * @param in        RIFF/WAVE bytes
     * @param maxFrames maximum number of 44100 Hz frames to keep; {@code <= 0} selects
     *                  {@link #DEFAULT_MAX_FRAMES}
     * @return decoded audio, or a description of the problem in {@link AudioData#error}
     */
    public static AudioData read(InputStream in, int maxFrames) {
        if (in == null) return AudioData.fail("no input stream");
        int cap = (maxFrames > 0) ? maxFrames : DEFAULT_MAX_FRAMES;
        try {
            Header h = scan(in, true);
            if (h.fmt == null) return AudioData.fail("missing fmt chunk (no format information)");
            if (h.stash != null) {
                Bytes b = h.stash;
                if (b.n <= 0) return AudioData.fail("data chunk is empty (0 bytes of audio)");
                boolean cut = b.n < h.dataBytes;
                return decode(new ByteArrayInputStream(b.b, 0, b.n), b.n, h.fmt, cap, cut);
            }
            if (h.dataOffset < 0) return AudioData.fail("missing data chunk");
            return decode(in, h.dataBytes, h.fmt, cap, false);
        } catch (WavError e) {
            return AudioData.fail(e.getMessage());
        } catch (Throwable t) {
            return AudioData.fail("unreadable WAV: " + t);
        }
    }

    /**
     * Decodes a WAV file. Two passes over the file (header scan, then the data chunk), so the
     * chunk order does not matter and no audio is buffered before the format is known.
     *
     * @param f         file to read
     * @param maxFrames maximum number of 44100 Hz frames to keep; {@code <= 0} selects
     *                  {@link #DEFAULT_MAX_FRAMES}
     * @return decoded audio, or a description of the problem in {@link AudioData#error}
     */
    public static AudioData readFile(File f, int maxFrames) {
        if (f == null) return AudioData.fail("no file");
        int cap = (maxFrames > 0) ? maxFrames : DEFAULT_MAX_FRAMES;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            Header h = scan(in, false);
            closeQuietly(in);
            in = null;
            if (h.fmt == null) return AudioData.fail("missing fmt chunk (no format information)");
            if (h.dataOffset < 0) return AudioData.fail("missing data chunk");
            in = new FileInputStream(f);
            long skipped = skipFully(in, h.dataOffset);
            if (skipped < h.dataOffset) {
                return AudioData.fail("truncated file: data chunk starts at byte " + h.dataOffset
                        + " but the file ends earlier");
            }
            return decode(in, h.dataBytes, h.fmt, cap, false);
        } catch (WavError e) {
            return AudioData.fail(e.getMessage());
        } catch (Throwable t) {
            return AudioData.fail("cannot read " + f.getPath() + ": " + t);
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * Streaming counterpart of {@link #read}: decodes a WAV into a header-less int16 stereo
     * {@link PcmFile} sidecar while holding at most one block of samples in memory.
     *
     * <p>This is what lifts the old 180 s import cap: the clip audio no longer has to fit in the
     * heap, so a four minute backing track costs disk space instead of tens of megabytes of
     * floats, and the waveform thumbnail comes out of the same pass.</p>
     *
     * @param in        RIFF/WAVE bytes (the caller keeps ownership, the stream is not closed here)
     * @param out       destination sidecar file
     * @param maxFrames output frame cap, {@code <= 0} selects {@link #MAX_PCM_FRAMES}
     * @return what was written, or the reason it failed in {@link PcmInfo#error}
     */
    public static PcmInfo exportPcm(InputStream in, java.io.File out, int maxFrames) {
        if (in == null) return PcmInfo.fail("no input stream");
        if (out == null) return PcmInfo.fail("no destination file");
        final int cap = (maxFrames > 0) ? maxFrames : MAX_PCM_FRAMES;
        java.io.FileOutputStream fos = null;
        try {
            Header h = scan(in, true);
            if (h.fmt == null) return PcmInfo.fail("missing fmt chunk (no format information)");
            final Fmt f = h.fmt;
            long dataBytes;
            if (h.stash != null) {
                if (h.stash.n <= 0) return PcmInfo.fail("data chunk is empty (0 bytes of audio)");
                in = new ByteArrayInputStream(h.stash.b, 0, h.stash.n);
                dataBytes = h.stash.n;
            } else {
                if (h.dataOffset < 0) return PcmInfo.fail("missing data chunk");
                dataBytes = h.dataBytes;
            }
            final int frameBytes = f.sampleBytes * f.channels;
            if (dataBytes < frameBytes) {
                return PcmInfo.fail("data chunk too small: " + dataBytes + " byte(s), one "
                        + f.channels + "x" + f.bits + "-bit frame needs " + frameBytes);
            }
            final long declared = dataBytes / frameBytes;
            final double step = (f.rate == OUT_RATE) ? 1.0 : (double) f.rate / OUT_RATE;
            long outTotal = (f.rate == OUT_RATE) ? declared : Math.round(declared / step);
            boolean cut = outTotal > cap;
            if (outTotal > cap) outTotal = cap;
            if (outTotal <= 0) return PcmInfo.fail("no audio frames");

            final int buckets = PcmFile.PEAK_BUCKETS;
            float[] peaks = new float[buckets];
            final int srcBlock = Math.max(2, IO_BLOCK / frameBytes);
            float[] bl = new float[srcBlock + 2];
            float[] br = (f.channels == 2) ? new float[srcBlock + 2] : null;
            byte[] raw = new byte[srcBlock * frameBytes];
            long bufStart = 0;          // absolute source frame held in bl[0]
            int have = 0;               // valid source frames in bl
            long readBudget = dataBytes;
            boolean sourceEnded = false;

            fos = new java.io.FileOutputStream(out);
            java.io.DataOutputStream os = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(fos, 1 << 16));
            final int outChunk = (int) Math.max(1L, Math.min(4096L,
                    (long) Math.floor(srcBlock / Math.max(1.0, step))));
            float[] ol = new float[outChunk];
            float[] orr = new float[outChunk];
            long k = 0;
            while (k < outTotal) {
                int n = (int) Math.min(outChunk, outTotal - k);
                long needLo = (long) Math.floor(k * step);
                long needHi = (long) Math.floor((k + n - 1) * step) + 1L;
                if (needLo > bufStart) {                       // slide the window forward
                    int drop = (int) Math.min(needLo - bufStart, have);
                    if (drop > 0) {
                        System.arraycopy(bl, drop, bl, 0, have - drop);
                        if (br != null) System.arraycopy(br, drop, br, 0, have - drop);
                        bufStart += drop;
                        have -= drop;
                    }
                }
                while (bufStart + have <= needHi && !sourceEnded) {
                    int space = bl.length - have;
                    if (space <= 0) break;
                    int want = (int) Math.min(Math.min((long) space, (long) srcBlock),
                            Math.max(0L, readBudget / frameBytes));
                    if (want <= 0) { sourceEnded = true; break; }
                    int got = readFully(in, raw, 0, want * frameBytes);
                    int frames = got / frameBytes;
                    if (frames <= 0) { sourceEnded = true; break; }
                    readBudget -= (long) frames * frameBytes;
                    decodeFrames(raw, frames, f, bl, br, have);
                    have += frames;
                    if (frames < want) sourceEnded = true;
                }
                // Only a missing *first* sample means the source really ended early. The last block
                // of a complete file has no lookahead frame; that one is clamped to the last sample
                // instead of being reported as a truncation (it used to show "已截断" for every file).
                if (have <= 0 || needLo >= bufStart + have) { cut = true; break; }
                for (int i = 0; i < n; i++) {
                    double p = (k + i) * step;
                    long i0 = (long) p;
                    double frac = p - i0;
                    int idx = (int) (i0 - bufStart);
                    if (idx < 0) idx = 0;
                    if (idx >= have) idx = have - 1;
                    int idx2 = Math.min(idx + 1, have - 1);
                    float a = bl[idx], b = bl[idx2];
                    float sl = (float) (a + ((double) b - a) * frac);
                    float sr;
                    if (br == null) sr = sl;
                    else {
                        float c0 = br[idx], c1 = br[idx2];
                        sr = (float) (c0 + ((double) c1 - c0) * frac);
                    }
                    ol[i] = sl;
                    orr[i] = sr;
                }
                for (int i = 0; i < n; i++) {
                    int bk = (int) ((double) (k + i) * buckets / (double) outTotal);
                    if (bk < 0) bk = 0; else if (bk >= buckets) bk = buckets - 1;
                    float a = Math.abs(ol[i]);
                    if (a > peaks[bk]) peaks[bk] = a;
                    float c = Math.abs(orr[i]);
                    if (c > peaks[bk]) peaks[bk] = c;
                }
                for (int i = 0; i < n; i++) {
                    int l = (int) (ol[i] * 32767.0f);
                    int r = (int) (orr[i] * 32767.0f);
                    if (l > 32767) l = 32767; else if (l < -32768) l = -32768;
                    if (r > 32767) r = 32767; else if (r < -32768) r = -32768;
                    os.write(l & 0xFF); os.write((l >> 8) & 0xFF);
                    os.write(r & 0xFF); os.write((r >> 8) & 0xFF);
                }
                k += n;
            }
            os.flush();
            os.close();
            fos = null;
            float maxPeak = 0f;
            for (int i = 0; i < buckets; i++) if (peaks[i] > maxPeak) maxPeak = peaks[i];
            if (maxPeak > 0f) for (int i = 0; i < buckets; i++) peaks[i] /= maxPeak;
            return new PcmInfo(out, (int) k, f.rate, f.channels, cut, null, peaks);
        } catch (WavError e) {
            PcmFile.delete(out);
            return PcmInfo.fail(e.getMessage());
        } catch (Throwable t) {
            PcmFile.delete(out);
            return PcmInfo.fail("unreadable WAV: " + t);
        } finally {
            if (fos != null) { try { fos.close(); } catch (Throwable ignored) { } }
        }
    }

    // ------------------------------------------------------------------ result type

    /**
     * Decoded clip: {@code left}/{@code right} hold exactly {@link #frames} samples at
     * {@link #sampleRate} (44100 Hz after a successful read). {@code right == null} means mono.
     * On failure {@code error} is a readable message, {@code frames == 0} and the arrays are
     * empty.
     */
    public static final class AudioData {
        /** Left channel, {@link #frames} valid samples. Never null. */
        public final float[] left;
        /** Right channel, or null when the clip is mono. */
        public final float[] right;
        /** Rate of the returned samples: 44100 on success, 0 on failure. */
        public final int sampleRate;
        /** Valid samples in {@link #left} / {@link #right}. */
        public final int frames;
        /** Null when the read succeeded, otherwise a readable reason. */
        public final String error;
        /** True when the source held more audio than {@code maxFrames} allowed. */
        public final boolean truncated;
        /** Sample rate of the source file, kept for information (0 on failure). */
        public final int sourceRate;

        public AudioData(float[] left, float[] right, int sampleRate, int frames, String error) {
            this(left, right, sampleRate, frames, error, false, sampleRate);
        }

        public AudioData(float[] left, float[] right, int sampleRate, int frames, String error,
                         boolean truncated, int sourceRate) {
            if (left == null) left = new float[0];
            int n = Math.max(0, Math.min(frames, left.length));
            if (right != null) n = Math.min(n, right.length);
            this.left = left;
            this.right = right;
            this.sampleRate = sampleRate;
            this.frames = n;
            this.error = error;
            this.truncated = truncated;
            this.sourceRate = sourceRate;
        }

        /** True when the clip can be played. */
        public boolean ok() {
            return error == null;
        }

        /** 1 for mono, 2 for stereo. */
        public int channels() {
            return right == null ? 1 : 2;
        }

        /** True when the clip carries a single channel. */
        public boolean isMono() {
            return right == null;
        }

        /** Seconds of audio in this clip. */
        public double seconds() {
            return sampleRate > 0 ? frames / (double) sampleRate : 0.0;
        }

        static AudioData fail(String msg) {
            return new AudioData(new float[0], null, 0, 0,
                    (msg == null || msg.length() == 0) ? "unreadable WAV" : msg, false, 0);
        }
    }

    /**
     * What {@link #exportPcm} produced: the sidecar file, its length and a ready waveform
     * thumbnail. {@code error} is null on success.
     */
    public static final class PcmInfo {
        /** Sidecar file holding int16 stereo 44100 Hz frames; null on failure. */
        public final java.io.File file;
        /** Frames written to the sidecar (44100 Hz). */
        public final int frames;
        /** Sample rate of the source file (kept for information). */
        public final int sourceRate;
        /** Channels of the source file. */
        public final int channels;
        /** True when the source held more audio than the cap allowed (or ended early). */
        public final boolean truncated;
        /** Null on success, otherwise a readable reason. */
        public final String error;
        /** Normalised 0..1 waveform thumbnail ({@link PcmFile#PEAK_BUCKETS} buckets). */
        public final float[] peaks;

        PcmInfo(java.io.File file, int frames, int sourceRate, int channels, boolean truncated,
                String error, float[] peaks) {
            this.file = file;
            this.frames = frames;
            this.sourceRate = sourceRate;
            this.channels = channels;
            this.truncated = truncated;
            this.error = error;
            this.peaks = (peaks == null) ? new float[0] : peaks;
        }

        public boolean ok() {
            return error == null;
        }

        /** Seconds of audio written to the sidecar. */
        public double seconds() {
            return frames / (double) OUT_RATE;
        }

        static PcmInfo fail(String msg) {
            return new PcmInfo(null, 0, 0, 0, false,
                    (msg == null || msg.length() == 0) ? "unreadable WAV" : msg, null);
        }
    }

    // ------------------------------------------------------------------ header scan

    /** Parsed fmt chunk. */
    private static final class Fmt {
        final int tag;        // 1 = PCM, 3 = IEEE float
        final int channels;
        final int rate;
        final int bits;
        final int sampleBytes;

        Fmt(int tag, int channels, int rate, int bits, int sampleBytes) {
            this.tag = tag;
            this.channels = channels;
            this.rate = rate;
            this.bits = bits;
            this.sampleBytes = sampleBytes;
        }
    }

    /** A growable byte buffer filled while hunting for a late fmt chunk. */
    private static final class Bytes {
        byte[] b = new byte[0];
        int n;
    }

    /** What the chunk walker found. */
    private static final class Header {
        Fmt fmt;
        long pos;            // bytes consumed from the stream start
        long dataOffset = -1;
        long dataBytes = -1;
        Bytes stash;         // data payload kept because fmt had not been seen yet
    }

    /**
     * Walks the RIFF chunk list. With {@code stashData} the stream is left at the data payload
     * when fmt was already seen, otherwise that payload is buffered so a late fmt can still be
     * honoured. Without {@code stashData} (file mode) payloads are skipped instead.
     */
    private static Header scan(InputStream in, boolean stashData) throws IOException, WavError {
        Header h = new Header();
        byte[] head = new byte[12];
        if (readFully(in, head, 0, 12) < 12) {
            throw new WavError("not a RIFF/WAVE file: shorter than 12 bytes");
        }
        h.pos = 12;
        if (!tagIs(head, 0, "RIFF") || !tagIs(head, 8, "WAVE")) {
            throw new WavError("not a RIFF/WAVE file (bad magic)");
        }
        byte[] ch = new byte[8];
        while (readFully(in, ch, 0, 8) == 8) {
            h.pos += 8;
            String id = tagOf(ch, 0);
            long size = u32(ch, 4);           // unsigned, so a lying size stays positive
            if ("fmt ".equals(id)) {
                int want = (int) Math.min(size, (long) FMT_MAX);
                byte[] b = new byte[want];
                if (readFully(in, b, 0, want) < want) throw new WavError("truncated fmt chunk");
                h.pos += want;
                h.fmt = parseFmt(b, want);
                long rest = (size - want) + (size & 1L);
                if (skipFully(in, rest) < rest) throw new WavError("truncated fmt chunk");
                h.pos += rest;
                if (h.stash != null) return h;   // late fmt: the data payload is already kept
            } else if ("data".equals(id)) {
                h.dataOffset = h.pos;
                h.dataBytes = size;
                if (h.fmt != null) return h;     // stream stays at the payload
                if (!stashData) {
                    long need = size + (size & 1L);
                    h.pos += skipFully(in, need);
                } else {
                    Bytes kept = readBounded(in, size, MAX_STASH);
                    h.stash = kept;
                    h.pos += kept.n;
                    long rest = size - kept.n;
                    if (rest > 0) {
                        long need = rest + (rest & 1L);
                        h.pos += skipFully(in, need);
                    } else if ((size & 1L) != 0) {
                        h.pos += skipFully(in, 1);
                    }
                    h.dataBytes = size;
                }
            } else {
                long need = size + (size & 1L);
                h.pos += skipFully(in, need);
            }
        }
        return h;
    }

    /** Validates a fmt chunk payload (including the extensible sub-format GUID prefix). */
    private static Fmt parseFmt(byte[] b, int len) throws WavError {
        if (len < 16) throw new WavError("fmt chunk too small (" + len + " bytes, need 16)");
        int tag = u16(b, 0);
        int channels = u16(b, 2);
        long rate = u32(b, 4);
        u16(b, 12);                       // blockAlign: recomputed from channels x bytes
        int bits = u16(b, 14);
        boolean extensible = (tag == 0xFFFE);
        if (extensible) {
            if (len < 40) {
                throw new WavError("WAVE_FORMAT_EXTENSIBLE fmt chunk too small (" + len
                        + " bytes, need 40)");
            }
            int cbSize = u16(b, 16);
            if (cbSize < 22) {
                throw new WavError("WAVE_FORMAT_EXTENSIBLE with invalid extension size " + cbSize);
            }
            tag = u16(b, 24);                 // first two bytes of the sub-format GUID
            if (tag == 0xFFFE || tag == 0) {
                throw new WavError("WAVE_FORMAT_EXTENSIBLE with unsupported sub-format tag " + tag);
            }
        }
        if (channels != 1 && channels != 2) {
            throw new WavError("unsupported channel count " + channels + " (mono or stereo only)");
        }
        if (rate <= 0 || rate > MAX_RATE) throw new WavError("invalid sample rate " + rate);
        int sampleBytes;
        if (tag == 1) {
            if (bits != 8 && bits != 16 && bits != 24 && bits != 32) {
                throw new WavError("invalid PCM bit depth " + bits + " (8/16/24/32 only)");
            }
            sampleBytes = bits / 8;
        } else if (tag == 3) {
            if (bits != 32 && bits != 64) {
                throw new WavError("invalid IEEE float bit depth " + bits + " (32/64 only)");
            }
            sampleBytes = bits / 8;
        } else {
            throw new WavError((extensible ? "WAVE_FORMAT_EXTENSIBLE with unsupported sub-format tag "
                    : "unsupported WAV format tag ") + tag + " (PCM 1 / float 3 only)");
        }
        return new Fmt(tag, channels, (int) rate, bits, sampleBytes);
    }

    // ------------------------------------------------------------------ sample decoding

    /**
     * Reads at most {@code dataBytes} of PCM, decodes up to the frames needed for {@code cap}
     * output frames and resamples to {@link #OUT_RATE}.
     */
    private static AudioData decode(InputStream in, long dataBytes, Fmt f, int cap,
                                    boolean truncatedHint) throws IOException, WavError {
        int frameBytes = f.sampleBytes * f.channels;
        if (dataBytes <= 0) throw new WavError("data chunk is empty (0 bytes of audio)");
        if (dataBytes < frameBytes) {
            throw new WavError("data chunk too small: " + dataBytes + " byte(s), one "
                    + f.channels + "x" + f.bits + "-bit frame needs " + frameBytes);
        }
        long declaredFrames = dataBytes / frameBytes;
        long srcNeed = sourceFramesFor(cap, f.rate);

        int init = (int) Math.min(Math.min(declaredFrames, srcNeed), 1L << 16);
        if (init < 1) init = 1;
        float[] l = new float[init];
        float[] r = (f.channels == 2) ? new float[init] : null;

        int n = 0;
        long budget = dataBytes;
        int blockFrames = Math.max(1, IO_BLOCK / frameBytes);
        byte[] buf = new byte[blockFrames * frameBytes];
        while (n < srcNeed && budget >= frameBytes) {
            long want = Math.min(blockFrames, Math.min(srcNeed - n, budget / frameBytes));
            if (want <= 0) break;
            int wantBytes = (int) (want * frameBytes);
            int got = readFully(in, buf, 0, wantBytes);
            if (got <= 0) break;
            budget -= got;
            int gotFrames = got / frameBytes;
            if (gotFrames <= 0) break;                       // trailing partial frame
            if (n + gotFrames > l.length) {
                int grow = (int) Math.min(Math.max((long) l.length * 2, (long) n + gotFrames), srcNeed);
                l = Arrays.copyOf(l, grow);
                if (r != null) r = Arrays.copyOf(r, grow);
            }
            decodeFrames(buf, gotFrames, f, l, r, n);
            n += gotFrames;
            if (got < wantBytes) break;                      // file ended early
        }
        if (n <= 0) {
            throw new WavError("no complete audio frame in the data chunk (file truncated?)");
        }
        boolean truncated = truncatedHint || declaredFrames > n;
        if (f.rate == OUT_RATE) {
            float[] ol = Arrays.copyOf(l, n);
            float[] or = (r == null) ? null : Arrays.copyOf(r, n);
            return new AudioData(ol, or, OUT_RATE, n, null, truncated, f.rate);
        }
        int outFrames = (int) Math.max(1L, Math.round(n * (double) OUT_RATE / f.rate));
        if (outFrames > cap) outFrames = cap;          // maxFrames is a hard limit
        float[] ol = resample(l, n, outFrames, f.rate);
        float[] or = (r == null) ? null : resample(r, n, outFrames, f.rate);
        return new AudioData(ol, or, OUT_RATE, outFrames, null, truncated, f.rate);
    }

    /** Source frames needed to produce {@code outFrames} output frames (plus interpolation slack). */
    private static long sourceFramesFor(int outFrames, int srcRate) {
        if (outFrames <= 0) return 0;
        if (srcRate == OUT_RATE) return outFrames;
        double ratio = (double) srcRate / OUT_RATE;
        double need = (outFrames - 1) * ratio + 2.0 + ratio;
        return Math.max(1L, (long) Math.ceil(need));
    }

    /** Linear-interpolation resampler; sample {@code i} is read at source position {@code i*step}. */
    private static float[] resample(float[] src, int n, int outFrames, int srcRate) {
        float[] out = new float[outFrames];
        if (n <= 0) return out;
        if (n == 1) {
            Arrays.fill(out, src[0]);
            return out;
        }
        double step = (double) srcRate / OUT_RATE;
        int last = n - 1;
        for (int i = 0; i < outFrames; i++) {
            double p = i * step;
            int i0 = (int) p;
            if (i0 >= last) {
                out[i] = src[last];
                continue;
            }
            double frac = p - i0;
            double a = src[i0];
            out[i] = (float) (a + ((double) src[i0 + 1] - a) * frac);
        }
        return out;
    }

    /** Converts {@code frames} interleaved sample frames from {@code b} into the float arrays. */
    private static void decodeFrames(byte[] b, int frames, Fmt f, float[] l, float[] r, int off) {
        int p = 0;
        boolean stereo = (f.channels == 2);
        if (f.tag == 3) {
            if (f.bits == 32) {
                for (int i = 0; i < frames; i++) {
                    l[off + i] = finite(Float.intBitsToFloat((int) u32(b, p)));
                    p += 4;
                    if (stereo) {
                        r[off + i] = finite(Float.intBitsToFloat((int) u32(b, p)));
                        p += 4;
                    }
                }
            } else {
                for (int i = 0; i < frames; i++) {
                    l[off + i] = finite((float) Double.longBitsToDouble(u64(b, p)));
                    p += 8;
                    if (stereo) {
                        r[off + i] = finite((float) Double.longBitsToDouble(u64(b, p)));
                        p += 8;
                    }
                }
            }
            return;
        }
        if (f.bits == 8) {
            for (int i = 0; i < frames; i++) {
                l[off + i] = (float) ((b[p++] & 0xFF) - 128) / 128.0f;   // 8 bit PCM is unsigned
                if (stereo) r[off + i] = (float) ((b[p++] & 0xFF) - 128) / 128.0f;
            }
        } else if (f.bits == 16) {
            for (int i = 0; i < frames; i++) {
                l[off + i] = (float) (short) u16(b, p) / 32768.0f;
                p += 2;
                if (stereo) {
                    r[off + i] = (float) (short) u16(b, p) / 32768.0f;
                    p += 2;
                }
            }
        } else if (f.bits == 24) {
            for (int i = 0; i < frames; i++) {
                l[off + i] = (float) s24(b, p) / 8388608.0f;
                p += 3;
                if (stereo) {
                    r[off + i] = (float) s24(b, p) / 8388608.0f;
                    p += 3;
                }
            }
        } else {
            for (int i = 0; i < frames; i++) {
                l[off + i] = (float) ((int) u32(b, p) / 2147483648.0);
                p += 4;
                if (stereo) {
                    r[off + i] = (float) ((int) u32(b, p) / 2147483648.0);
                    p += 4;
                }
            }
        }
    }

    /** NaN/infinity cannot be mixed or written; clamp them to silence/rails. */
    private static float finite(float v) {
        if (Float.isNaN(v)) return 0.0f;
        if (v > 1.0f) return (v == Float.POSITIVE_INFINITY) ? 1.0f : v;
        if (v < -1.0f) return (v == Float.NEGATIVE_INFINITY) ? -1.0f : v;
        return v;
    }

    // ------------------------------------------------------------------ io helpers

    private static final class WavError extends Exception {
        private static final long serialVersionUID = 1L;

        WavError(String msg) {
            super(msg);
        }
    }

    /** Buffers at most {@code maxBytes} of a chunk payload, growing geometrically. */
    private static Bytes readBounded(InputStream in, long size, long maxBytes) throws IOException {
        Bytes out = new Bytes();
        long cap = Math.min(size, maxBytes);
        if (cap <= 0) return out;
        byte[] buf = new byte[(int) Math.min(cap, 1L << 16)];
        int len = 0;
        while (len < cap) {
            if (len == buf.length) {
                int nlen = (int) Math.min(cap, Math.max(1L, (long) buf.length * 2));
                if (nlen <= len) break;
                buf = Arrays.copyOf(buf, nlen);
            }
            int n = in.read(buf, len, buf.length - len);
            if (n < 0) break;
            if (n == 0) {
                int c = in.read();
                if (c < 0) break;
                buf[len++] = (byte) c;
                continue;
            }
            len += n;
        }
        out.b = buf;
        out.n = len;
        return out;
    }

    /** Reads exactly {@code len} bytes unless the stream ends first; returns the byte count. */
    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(b, off + got, len - got);
            if (n < 0) break;
            if (n == 0) {
                int c = in.read();
                if (c < 0) break;
                b[off + got++] = (byte) c;
                continue;
            }
            got += n;
        }
        return got;
    }

    /** Skips up to {@code n} bytes; returns the number actually skipped. */
    private static long skipFully(InputStream in, long n) throws IOException {
        long done = 0;
        byte[] tmp = null;
        while (done < n) {
            long s = in.skip(n - done);
            if (s > 0) {
                done += s;
                continue;
            }
            if (tmp == null) tmp = new byte[8192];
            int want = (int) Math.min((long) tmp.length, n - done);
            int got = in.read(tmp, 0, want);
            if (got < 0) break;
            if (got == 0) continue;
            done += got;
        }
        return done;
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    // ------------------------------------------------------------------ byte helpers

    private static boolean tagIs(byte[] b, int o, String tag) {
        for (int i = 0; i < 4; i++) if ((b[o + i] & 0xFF) != tag.charAt(i)) return false;
        return true;
    }

    private static String tagOf(byte[] b, int o) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) {
            int v = b[o + i] & 0xFF;
            c[i] = (v >= 32 && v < 127) ? (char) v : '?';
        }
        return new String(c);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static long u64(byte[] b, int o) {
        return u32(b, o) | (u32(b, o + 4) << 32);
    }

    /** 24 bit little-endian signed sample, sign extended. */
    private static int s24(byte[] b, int o) {
        int v = (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16);
        return (v << 8) >> 8;
    }
}
