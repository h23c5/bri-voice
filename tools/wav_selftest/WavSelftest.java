import com.dsh.v4tune.io.WavReader;
import com.dsh.v4tune.model.AudioClip;
import com.dsh.v4tune.model.Song;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * Desktop self test for the WAV import layer: {@link WavReader} (RIFF/WAVE parsing, PCM and
 * float decoding, resampling, hostile input) and {@link AudioClip} (tick length, placement,
 * mixing).
 *
 * <p>Every WAV image used here is built byte by byte in this file, so the test does not rely
 * on the reader to validate itself. Known-answer signals are sine waves and exact PCM codes,
 * and every comparison is a numeric assertion with an explicit tolerance.</p>
 *
 * <p>Usage: {@code java WavSelftest} - prints PASS/FAIL per check and exits 1 on any failure.</p>
 */
public class WavSelftest {

    static final int SR = 44100;
    static int checks = 0, fails = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("WavSelftest - WavReader + AudioClip");
        g1_integerPcm();
        g2_floatAndChunks();
        g3_resample();
        g4_limits();
        g5_errors();
        g6_clip();
        System.out.println();
        System.out.println("WavSelftest: " + checks + " checks, " + (checks - fails)
                + " passed, " + fails + " failed");
        if (fails > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    // ================================================================== 1. integer PCM

    static void g1_integerPcm() {
        group("1. integer PCM decoding");

        // ---- 16 bit mono, 44100 Hz ----------------------------------------
        float[] s = tone(2000, 440.0, SR, 0.8);
        WavReader.AudioData d = read(riff(fmtChunk(1, 1, SR, 16), chunk("data", pcm16(s))), 1 << 20);
        expectOk("16-bit mono 44.1k decodes", d);
        eqI("16-bit frames", 2000, d.frames);
        eqI("16-bit sampleRate (output)", SR, d.sampleRate);
        eqI("16-bit sourceRate (file)", SR, d.sourceRate);
        check("16-bit mono has right == null", d.right == null && d.channels() == 1);
        check("16-bit payload not truncated", !d.truncated);
        eqI("16-bit left.length == frames", d.frames, d.left.length);
        int[] probe = {0, 1, 2, 100, 999, 1999};
        for (int i = 0; i < probe.length; i++) {
            int k = probe[i];
            eqD("16-bit s[" + k + "]", dec16(q16(s[k])), d.left[k], 0.0);
        }

        // ---- 8 bit mono, exact unsigned codes ------------------------------
        byte[] raw8 = new byte[]{0, (byte) 128, (byte) 255, (byte) 64, (byte) 192};
        WavReader.AudioData d8 = read(riff(fmtChunk(1, 1, SR, 8), chunk("data", raw8)), 1024);
        expectOk("8-bit decodes", d8);
        eqI("8-bit frames", 5, d8.frames);
        eqD("8-bit code 0   -> -1.0", -1.0, d8.left[0], 0.0);
        eqD("8-bit code 128 ->  0.0", 0.0, d8.left[1], 0.0);
        eqD("8-bit code 255 -> 127/128", 127.0 / 128.0, d8.left[2], 0.0);
        eqD("8-bit code 64  -> -0.5", -0.5, d8.left[3], 0.0);
        eqD("8-bit code 192 ->  0.5", 0.5, d8.left[4], 0.0);

        // ---- 24 bit stereo, different tone per channel ---------------------
        float[] tl = tone(777, 300.0, SR, 0.6);
        float[] tr = tone(777, 700.0, SR, 0.3);
        WavReader.AudioData d24 = read(
                riff(fmtChunk(1, 2, SR, 24), chunk("data", pcm24(interleave(tl, tr)))), 4096);
        expectOk("24-bit stereo decodes", d24);
        eqI("24-bit frames", 777, d24.frames);
        check("24-bit right channel present", d24.right != null && d24.channels() == 2);
        eqI("24-bit right.length == frames", d24.frames, d24.right.length);
        for (int k = 0; k < 6; k++) {
            eqD("24-bit L[" + k + "]", dec24(q24(tl[k])), d24.left[k], 0.0);
            eqD("24-bit R[" + k + "]", dec24(q24(tr[k])), d24.right[k], 0.0);
        }
        check("24-bit channels are not swapped", Math.abs(d24.left[200] - d24.right[200]) > 0.05,
                "L=" + d24.left[200] + " R=" + d24.right[200]);

        // ---- 32 bit integer ------------------------------------------------
        float[] s32 = tone(500, 1000.0, SR, 0.9);
        WavReader.AudioData d32 = read(riff(fmtChunk(1, 1, SR, 32), chunk("data", pcm32(s32))), 4096);
        expectOk("32-bit int decodes", d32);
        eqI("32-bit frames", 500, d32.frames);
        for (int k = 0; k < 4; k++) {
            int idx = k * 111;
            eqD("32-bit s[" + idx + "]", dec32(q32(s32[idx])), d32.left[idx], 0.0);
        }

        // ---- data chunk shorter than one frame -----------------------------
        WavReader.AudioData dpart = read(riff(fmtChunk(1, 2, SR, 16), chunk("data", new byte[3])), 64);
        expectErr("3-byte data chunk for 4-byte frame", dpart, "too small");
    }

    // ================================================================== 2. float / chunks

    static void g2_floatAndChunks() {
        group("2. IEEE float, WAVE_FORMAT_EXTENSIBLE, chunk layout");

        // ---- 32 bit float stereo, exact bit patterns -----------------------
        float[] fl = new float[]{0.0f, 0.5f, -0.25f, 1.5f, -1.25f, 0.125f};
        float[] fr = new float[]{1.0f, -1.0f, 0.75f, Float.NaN, 0.0f, -0.5f};
        WavReader.AudioData df = read(
                riff(fmtChunk(3, 2, SR, 32), chunk("data", f32(interleave(fl, fr)))), 64);
        expectOk("32-bit float decodes", df);
        eqI("float frames", 6, df.frames);
        eqD("float L[1] == 0.5", 0.5, df.left[1], 0.0);
        eqD("float L[3] == 1.5 (no clipping)", 1.5, df.left[3], 0.0);
        eqD("float L[4] == -1.25", -1.25, df.left[4], 0.0);
        eqD("float R[0] == 1.0", 1.0, df.right[0], 0.0);
        eqD("float NaN becomes silence", 0.0, df.right[3], 0.0);

        // ---- 64 bit float mono ---------------------------------------------
        double[] ds = new double[]{0.0, 0.25, -0.75, 0.9999999};
        byte[] d64 = new byte[8 * ds.length];
        for (int i = 0; i < ds.length; i++) {
            long bits = Double.doubleToLongBits(ds[i]);
            for (int b = 0; b < 8; b++) d64[i * 8 + b] = (byte) (bits >>> (8 * b));
        }
        WavReader.AudioData dd = read(riff(fmtChunk(3, 1, SR, 64), chunk("data", d64)), 64);
        expectOk("64-bit float decodes", dd);
        eqI("64-bit frames", 4, dd.frames);
        eqD("64-bit s[2]", -0.75, dd.left[2], 1e-12);
        eqD("64-bit s[3] (float precision)", 0.9999999, dd.left[3], 1e-7);

        // ---- extensible PCM 24, fmt after a LIST, fact after data ----------
        float[] el = tone(6000, 250.0, 48000, 0.5);
        byte[] extPcm = riff(
                chunk("LIST", concat(ascii("INFO"), ascii("INAM"), le32(4), ascii("test"))),
                fmtExtChunk(1, 1, 48000, 24, 24),
                chunk("fact", le32(6000)),
                chunk("data", pcm24(el)));
        WavReader.AudioData de = read(extPcm, 1 << 20);
        expectOk("EXTENSIBLE PCM 24 decodes (LIST before fmt, fact after data)", de);
        eqI("EXTENSIBLE sourceRate", 48000, de.sourceRate);
        System.out.println("  [data] EXTENSIBLE 48000 Hz x 6000 frames -> " + de.frames
                + " frames (expected " + outLen(6000, 48000) + ")");
        eqI("EXTENSIBLE resampled length", outLen(6000, 48000), de.frames);
        eqD("EXTENSIBLE lands exactly on source frame 480 at out[441]",
                dec24(q24(el[480])), de.left[441], 1e-9);
        double worstE = 0;
        for (int i = 0; i < de.frames; i += 97) {
            double want = 0.5 * Math.sin(2 * Math.PI * 250.0 * i / SR);
            worstE = Math.max(worstE, Math.abs(want - de.left[i]));
        }
        System.out.println("  [data] EXTENSIBLE 48000 -> 44100 250 Hz sine: worst |error| = " + worstE);
        check("EXTENSIBLE 24-bit resampled sine error < 0.005", worstE < 0.005, "worst=" + worstE);
        check("EXTENSIBLE output rate is 44100", de.sampleRate == SR);

        // ---- extensible float 32 with validBits 24 -------------------------
        float[] ef = tone(1000, 500.0, SR, 0.7);
        WavReader.AudioData def = read(
                riff(fmtExtChunk(3, 1, SR, 32, 24), chunk("data", f32(ef))), 4096);
        expectOk("EXTENSIBLE float 32 decodes", def);
        eqD("EXTENSIBLE float sample", ef[7], def.left[7], 0.0);

        // ---- extensible rejections -----------------------------------------
        expectErr("EXTENSIBLE sub-format ADPCM (2)", read(
                riff(fmtExtChunk(2, 1, SR, 16, 16), chunk("data", new byte[64])), 64),
                "sub-format tag 2");
        expectErr("EXTENSIBLE fmt too short (18 bytes)",
                read(riff(shortExtFmtChunk(), chunk("data", pcm16(tone(64, 100.0, SR, 0.5)))), 64),
                "EXTENSIBLE");

        // ---- fmt chunk after the data chunk (streaming path) ---------------
        float[] late = tone(1200, 330.0, 22050, 0.6);
        byte[] lateFmt = riff(chunk("data", pcm16(late)), fmtChunk(1, 1, 22050, 16));
        WavReader.AudioData dl = read(lateFmt, 1 << 20);
        expectOk("fmt after data decodes (stream)", dl);
        eqI("fmt after data length", 2400, dl.frames);
        for (int k = 0; k < 4; k++) {
            int idx = k * 400;
            eqD("fmt-after-data out[" + idx + "]", dec16(q16(late[idx / 2])), dl.left[idx], 1e-6);
        }

        // ---- odd sized chunk needs a pad byte ------------------------------
        byte[] odd = riff(
                chunk("JUNK", new byte[5]),                       // 5 bytes + 1 pad
                fmtChunk(1, 1, SR, 16),
                chunk("LIST", new byte[3]),
                chunk("data", pcm16(tone(300, 200.0, SR, 0.4))));
        WavReader.AudioData dodd = read(odd, 4096);
        expectOk("odd sized chunks with pad byte are skipped", dodd);
        eqI("odd-chunk frames", 300, dodd.frames);

        // ---- truncated file / lying sizes (stream) -------------------------
        byte[] small = pcm16(tone(200, 200.0, SR, 0.4));
        byte[] lie = riff(fmtChunk(1, 1, SR, 16), chunkWithSize("data", 0x7FFFFFF0L, small));
        WavReader.AudioData dlie = read(lie, 1 << 20);
        expectOk("lying huge data size still decodes what is there", dlie);
        eqI("lying huge data size frames", 200, dlie.frames);
        check("lying huge data size flagged truncated", dlie.truncated);

        byte[] lieSmall = riff(fmtChunk(1, 1, SR, 16), chunkWithSize("data", 200L, small));
        WavReader.AudioData dls = read(lieSmall, 1 << 20);
        expectOk("declared data size is honoured", dls);
        eqI("declared 200 bytes -> 100 frames", 100, dls.frames);
        check("declared size honoured -> not truncated", !dls.truncated);

        byte[] noFmt = riff(chunk("data", small));
        expectErr("data chunk without fmt", read(noFmt, 1024), "missing fmt");
        byte[] noData = riff(fmtChunk(1, 1, SR, 16));
        expectErr("fmt chunk without data", read(noData, 1024), "missing data");
    }

    // ================================================================== 3. resampling

    static void g3_resample() {
        group("3. resampling to 44100 Hz");

        // 48000 -> 44100: i*160/147, integer again at i = 147
        float[] s48 = tone(48000, 1000.0, 48000, 0.8);
        WavReader.AudioData d48 = read(
                riff(fmtChunk(1, 1, 48000, 16), chunk("data", pcm16(s48))), 1 << 20);
        expectOk("48000 Hz decodes", d48);
        System.out.println("  [data] 48000 Hz x 48000 frames -> " + d48.frames
                + " frames (expected " + outLen(48000, 48000) + ")");
        eqI("48000 -> 44100 length", outLen(48000, 48000), d48.frames);
        eqI("48000 -> 44100 sourceRate kept", 48000, d48.sourceRate);
        check("48000 -> 44100 length differs from input", d48.frames != 48000);
        double worst = 0;
        for (int k = 0; k < 12; k++) {
            int i = 1000 + k * 3000;
            double want = 0.8 * Math.sin(2 * Math.PI * 1000.0 * i / SR);
            worst = Math.max(worst, Math.abs(want - d48.left[i]));
        }
        System.out.println("  [data] 48000 -> 44100 1 kHz sine: worst |error| = " + worst);
        check("48000 -> 44100 sine error < 0.005", worst < 0.005, "worst=" + worst);
        eqD("48000 -> 44100 lands exactly on source frame 160 at out[147]",
                dec16(q16(s48[160])), d48.left[147], 1e-9);

        // 22050 -> 44100: every source sample is kept, midpoints interpolated
        float[] s22 = tone(2205, 440.0, 22050, 0.5);
        WavReader.AudioData d22 = read(
                riff(fmtChunk(1, 1, 22050, 16), chunk("data", pcm16(s22))), 1 << 20);
        expectOk("22050 Hz decodes", d22);
        System.out.println("  [data] 22050 Hz x 2205 frames -> " + d22.frames
                + " frames (expected " + outLen(2205, 22050) + ")");
        eqI("22050 -> 44100 doubles the length", 4410, d22.frames);
        for (int k = 0; k < 5; k++) {
            eqD("22050 -> 44100 out[2*" + k + "] equals source", dec16(q16(s22[k])), d22.left[2 * k], 1e-9);
        }
        double mid = (dec16(q16(s22[3])) + dec16(q16(s22[4]))) / 2.0;
        eqD("22050 -> 44100 out[7] is the midpoint", mid, d22.left[7], 1e-6);

        // 96000 -> 44100
        float[] s96 = tone(9600, 800.0, 96000, 0.6);
        WavReader.AudioData d96 = read(
                riff(fmtChunk(1, 1, 96000, 16), chunk("data", pcm16(s96))), 1 << 20);
        expectOk("96000 Hz decodes", d96);
        System.out.println("  [data] 96000 Hz x 9600 frames -> " + d96.frames
                + " frames (expected " + outLen(9600, 96000) + ")");
        eqI("96000 -> 44100 length", outLen(9600, 96000), d96.frames);

        // 8000 -> 44100
        float[] s8k = tone(800, 300.0, 8000, 0.5);
        WavReader.AudioData d8k = read(
                riff(fmtChunk(1, 1, 8000, 16), chunk("data", pcm16(s8k))), 1 << 20);
        expectOk("8000 Hz decodes", d8k);
        System.out.println("  [data] 8000 Hz x 800 frames -> " + d8k.frames
                + " frames (expected " + outLen(800, 8000) + ")");
        eqI("8000 -> 44100 length", outLen(800, 8000), d8k.frames);
        double worst8 = 0;
        for (int i = 0; i < d8k.frames; i += 37) {
            double want = 0.5 * Math.sin(2 * Math.PI * 300.0 * i / SR);
            worst8 = Math.max(worst8, Math.abs(want - d8k.left[i]));
        }
        System.out.println("  [data] 8000 -> 44100 300 Hz sine: worst |error| = " + worst8);
        check("8000 -> 44100 sine error < 0.02", worst8 < 0.02, "worst=" + worst8);

        // identity: 44100 must be copied bit for bit
        float[] sid = tone(3000, 523.25, SR, 0.75);
        WavReader.AudioData did = read(
                riff(fmtChunk(1, 1, SR, 16), chunk("data", pcm16(sid))), 1 << 20);
        expectOk("44100 Hz decodes", did);
        eqI("44100 identity length", 3000, did.frames);
        int diff = 0;
        for (int i = 0; i < 3000; i++) {
            if (did.left[i] != dec16(q16(sid[i]))) diff++;
        }
        eqI("44100 identity is sample exact (mismatches)", 0, diff);
    }

    // ================================================================== 4. limits

    static void g4_limits() {
        group("4. maxFrames limit and memory safety");

        float[] s = tone(44100, 440.0, SR, 0.8);
        byte[] w = riff(fmtChunk(1, 1, SR, 16), chunk("data", pcm16(s)));

        WavReader.AudioData c1 = read(w, 1000);
        expectOk("maxFrames=1000 decodes", c1);
        eqI("maxFrames=1000 frames", 1000, c1.frames);
        check("maxFrames=1000 marks truncated", c1.truncated);
        eqD("maxFrames=1000 keeps the head", dec16(q16(s[999])), c1.left[999], 0.0);

        byte[] w48 = riff(fmtChunk(1, 1, 48000, 16), chunk("data", pcm16(tone(48000, 440.0, 48000, 0.8))));
        WavReader.AudioData c2 = read(w48, 1000);
        expectOk("maxFrames with 48000 Hz source decodes", c2);
        eqI("maxFrames=1000 at 48000 Hz -> 1000 frames", 1000, c2.frames);

        WavReader.AudioData c3 = read(w, 44100);
        expectOk("maxFrames == file length decodes", c3);
        eqI("maxFrames == file length keeps all frames", 44100, c3.frames);
        check("maxFrames == file length is not truncated", !c3.truncated);

        WavReader.AudioData c4 = read(w, 0);
        expectOk("maxFrames=0 uses the default cap", c4);
        eqI("maxFrames=0 keeps a short file whole", 44100, c4.frames);
        check("maxFrames=0 not truncated for a short file", !c4.truncated);

        WavReader.AudioData c5 = read(w, 1);
        expectOk("maxFrames=1 decodes", c5);
        eqI("maxFrames=1 frame", 1, c5.frames);
        check("maxFrames=1 truncated", c5.truncated && c5.left.length == 1);

        // a *huge* declared data chunk must not allocate anything close to it
        byte[] huge = riff(fmtChunk(1, 2, SR, 16), chunkWithSize("data", 0xFFFFFFF0L, new byte[400]));
        long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        WavReader.AudioData dh = read(huge, 1 << 20);
        long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        expectOk("declared 4 GiB data chunk still decodes", dh);
        eqI("4 GiB declared -> 100 stereo frames", 100, dh.frames);
        check("4 GiB declared flagged truncated", dh.truncated);
        check("4 GiB declared did not allocate > 64 MiB", after - before < 64L * 1024 * 1024,
                "delta=" + (after - before));
    }

    // ================================================================== 5. errors

    static void g5_errors() throws Exception {
        group("5. error reporting and fuzzing");

        expectErr("empty stream", read(new byte[0], 1024), "shorter than 12");
        expectErr("bad magic RIFX", read(riffBadMagic("RIFX"), 1024), "bad magic");
        expectErr("bad WAVE id", read(riffBadMagic("WAVX"), 1024), "bad magic");
        expectErr("unsupported format tag 6 (A-law)",
                read(riff(fmtChunk(6, 1, SR, 8), chunk("data", new byte[64])), 64), "format tag 6");
        expectErr("invalid PCM bit depth 12",
                read(riff(fmtChunk(1, 1, SR, 12), chunk("data", new byte[64])), 64), "bit depth 12");
        expectErr("invalid float bit depth 16",
                read(riff(fmtChunk(3, 1, SR, 16), chunk("data", new byte[64])), 64), "float bit depth 16");
        expectErr("3 channels",
                read(riff(fmtChunk(1, 3, SR, 16), chunk("data", new byte[64])), 64), "channel count 3");
        expectErr("sample rate 0",
                read(riff(fmtChunk(1, 1, 0, 16), chunk("data", new byte[64])), 64), "sample rate 0");
        expectErr("sample rate 4 MHz",
                read(riff(fmtChunk(1, 1, 4000000, 16), chunk("data", new byte[64])), 64), "sample rate");
        expectErr("empty data chunk",
                read(riff(fmtChunk(1, 1, SR, 16), chunk("data", new byte[0])), 64), "empty");
        expectErr("null stream", WavReader.read(null, 1024), "no input stream");

        // stream that fails mid read
        InputStream boom = new InputStream() {
            int n = 0;
            public int read() throws IOException {
                if (++n > 4) throw new IOException("simulated io failure");
                return 'R';
            }
            public int read(byte[] b, int off, int len) throws IOException {
                b[off] = (byte) read();
                return 1;
            }
        };
        WavReader.AudioData db = WavReader.read(boom, 1024);
        expectErr("IOException from the stream is reported", db, null);
        check("IOException message mentions the failure",
                db.error != null && db.error.indexOf("simulated io failure") >= 0, "error=" + db.error);

        // random junk
        Random rnd = new Random(20261006L);
        int crashes = 0, okJunk = 0;
        for (int i = 0; i < 400; i++) {
            byte[] junk = new byte[16 + rnd.nextInt(500)];
            rnd.nextBytes(junk);
            try {
                if (read(junk, 4096).ok()) okJunk++;
            } catch (Throwable t) {
                crashes++;
            }
        }
        check("400 random blobs never throw", crashes == 0, "crashes=" + crashes);
        System.out.println("  [data] random blobs accepted as audio: " + okJunk + " / 400");

        // every truncation of a valid file
        byte[] full = riff(fmtChunk(1, 2, 48000, 16),
                chunk("data", pcm16(interleave(tone(3000, 440.0, 48000, 0.7),
                        tone(3000, 660.0, 48000, 0.5)))));
        int bad = 0, okTrunc = 0, errTrunc = 0, noFlag = 0;
        for (int len = 0; len <= full.length; len++) {
            try {
                WavReader.AudioData d = read(Arrays.copyOf(full, len), 100000);
                if (d.ok()) {
                    okTrunc++;
                    if (len < full.length && !d.truncated) noFlag++;
                } else {
                    errTrunc++;
                }
            } catch (Throwable t) {
                bad++;
            }
        }
        check("every truncation (0.." + full.length + " bytes) is handled", bad == 0, "crashes=" + bad);
        check("truncated files are flagged or rejected", noFlag == 0, "unflagged=" + noFlag);
        System.out.println("  [data] truncations: " + okTrunc + " decoded (partial), "
                + errTrunc + " rejected, " + bad + " crashes");

        // file path
        File tmp = new File(System.getProperty("java.io.tmpdir", "."),
                "v4tune_wav_selftest_" + Long.toHexString(System.nanoTime()));
        if (!tmp.mkdirs() && !tmp.isDirectory()) {
            check("temp dir created", false, tmp.getAbsolutePath());
        } else {
            File f = new File(tmp, "probe.wav");
            writeFile(f, riff(fmtChunk(1, 1, SR, 16), chunk("data", pcm16(tone(1000, 440.0, SR, 0.6)))));
            WavReader.AudioData df = WavReader.readFile(f, 1 << 20);
            expectOk("readFile decodes a real file", df);
            eqI("readFile frames", 1000, df.frames);
            eqD("readFile first sample", dec16(q16(tone(1000, 440.0, SR, 0.6)[0])), df.left[0], 0.0);

            // fmt after data, through the file path
            File f2 = new File(tmp, "late_fmt.wav");
            writeFile(f2, riff(chunk("data", pcm16(tone(500, 300.0, SR, 0.5))), fmtChunk(1, 1, SR, 16)));
            WavReader.AudioData df2 = WavReader.readFile(f2, 1 << 20);
            expectOk("readFile handles fmt after data", df2);
            eqI("readFile fmt-after-data frames", 500, df2.frames);

            // lying data size, through the file path
            File f3 = new File(tmp, "short.wav");
            writeFile(f3, riff(fmtChunk(1, 1, SR, 16), chunkWithSize("data", 100000L, pcm16(tone(50, 300.0, SR, 0.5)))));
            WavReader.AudioData df3 = WavReader.readFile(f3, 1 << 20);
            expectOk("readFile survives a data chunk longer than the file", df3);
            eqI("readFile lying size -> 50 frames", 50, df3.frames);
            check("readFile lying size flagged truncated", df3.truncated);

            expectErr("readFile on a missing file", WavReader.readFile(new File(tmp, "nope.wav"), 1024), "cannot read");
            expectErr("readFile on a directory", WavReader.readFile(tmp, 1024), "cannot read");
            expectErr("readFile(null)", WavReader.readFile(null, 1024), "no file");
            deleteTree(tmp);
        }
    }

    // ================================================================== 6. AudioClip

    static void g6_clip() {
        group("6. AudioClip timeline placement and mixing");

        float[] s = tone(44100, 440.0, SR, 0.8);
        WavReader.AudioData d = read(riff(fmtChunk(1, 1, SR, 16), chunk("data", pcm16(s))), 1 << 20);
        AudioClip c = AudioClip.fromWav(d, 1920, "take1");
        check("fromWav builds a clip", c != null);
        if (c == null) return;
        eqI("clip startTick", 1920, c.startTick);
        eqS("clip name", "take1", c.name);
        eqD("clip default gain", 1.0, c.gain, 0.0);
        check("clip default not muted", !c.mute);
        check("clip shares the decoded data", c.data == d);
        eqI("lengthTicks 120 bpm (12000)", 960, c.lengthTicks(12000));
        eqI("lengthTicks 90 bpm (9000)", 720, c.lengthTicks(9000));
        eqI("lengthTicks 60 bpm (6000)", 480, c.lengthTicks(6000));
        eqI("endTick 120 bpm", 1920 + 960, c.endTick(12000));
        eqI("lengthTicks bpm 0", 0, c.lengthTicks(0));
        eqI("lengthTicks bpm -100", 0, c.lengthTicks(-100));

        WavReader.AudioData d48 = new WavReader.AudioData(new float[48000], null, 48000, 48000, null);
        AudioClip c48 = AudioClip.fromWav(d48, 0, null);
        check("fromWav with a null name still builds a clip", c48 != null);
        if (c48 != null) {
            eqS("null name becomes AudioClip", "AudioClip", c48.name);
            eqI("48000 frames at 48000 Hz = 1 s = 960 ticks", 960, c48.lengthTicks(12000));
            eqI("48000 frames at 96000 bpm100", 1920, c48.lengthTicks(24000));
        }
        eqI("lengthTicks of 1000 frames", 22, AudioClip.fromWav(
                new WavReader.AudioData(new float[1000], null, SR, 1000, null), 0, "x")
                .lengthTicks(12000));

        check("fromWav(null) == null", AudioClip.fromWav(null, 0, "x") == null);
        check("fromWav(failed data) == null",
                AudioClip.fromWav(WavReader.read(new ByteArrayInputStream(new byte[]{1, 2, 3}), 16), 0, "x") == null);
        check("fromWav of failed data keeps the error readable",
                WavReader.read(new ByteArrayInputStream(new byte[]{1, 2, 3}), 16).error != null);

        // startFrame follows the tempo map (empty map => 120 bpm)
        Song song = new Song();
        AudioClip atTick = AudioClip.fromWav(d, 960, "beat2");
        eqI("startFrame(tick 960 @120bpm)", 44100, atTick.startFrame(song));
        eqI("startFrame(tick 0)", 0, AudioClip.fromWav(d, 0, "zero").startFrame(song));
        eqI("startFrame(null song)", 0, c.startFrame(null));

        // mixing: 1 s mono clip, gain 0.5, placed at frame 100
        float[] outL = new float[2000];
        float[] outR = new float[2000];
        AudioClip m = AudioClip.fromWav(d, 0, "m");
        m.gain = 0.5f;
        int mixed = m.mixInto(outL, outR, 100);
        eqI("mixInto returns the cropped frame count", 1900, mixed);
        eqD("mixInto left sample", d.left[0] * 0.5, outL[100], 1e-7);
        eqD("mixInto right gets the mono channel", d.left[0] * 0.5, outR[100], 1e-7);
        eqD("mixInto last frame", d.left[1899] * 0.5, outL[1999], 1e-7);
        eqD("mixInto keeps samples before the clip silent", 0.0, outL[99], 0.0);

        m.mute = true;
        eqI("muted clip mixes nothing", 0, m.mixInto(outL, outR, 0));
        m.mute = false;
        m.gain = 0.0f;
        eqI("gain 0 mixes nothing", 0, m.mixInto(outL, outR, 0));
        m.gain = 0.5f;
        eqI("null destination mixes nothing", 0, m.mixInto(null, null, 0));
        eqI("clip fully after the buffer mixes nothing", 0, m.mixInto(outL, outR, 2000));

        float[] headL = new float[100];
        int head = m.mixInto(headL, null, -50);
        eqI("negative start crops the head", 100, head);
        eqD("negative start keeps the right source offset", d.left[50] * 0.5, headL[0], 1e-7);

        // stereo source mixes into stereo
        float[] sl = tone(300, 200.0, SR, 0.5);
        float[] sr = tone(300, 900.0, SR, 0.25);
        WavReader.AudioData dst = read(
                riff(fmtChunk(1, 2, SR, 16), chunk("data", pcm24(interleave(sl, sr)))), 4096);
        AudioClip cs = AudioClip.fromWav(dst, 0, "stereo");
        float[] ml = new float[300], mr = new float[300];
        eqI("stereo clip mixInto", 300, cs.mixInto(ml, mr, 0));
        eqD("stereo clip left", dst.left[10], ml[10], 1e-7);
        eqD("stereo clip right stays on the right", dst.right[10], mr[10], 1e-7);
        check("stereo clip channels differ", Math.abs(ml[10] - mr[10]) > 0.05,
                "L=" + ml[10] + " R=" + mr[10]);

        // copy() is independent for placement, shares the samples
        AudioClip cp = c.copy();
        cp.gain = 0.25f;
        cp.startTick = 0;
        eqD("copy keeps the original gain", 1.0, c.gain, 0.0);
        eqI("copy keeps the original startTick", 1920, c.startTick);
        check("copy shares the samples", cp.data == c.data);
        eqS("copy keeps the name", "take1", cp.name);
    }

    // ================================================================== WAV builders

    static byte[] riff(byte[]... chunks) {
        byte[] body = concat(chunks);
        return concat(ascii("RIFF"), le32(4 + body.length), ascii("WAVE"), body);
    }

    static byte[] riffBadMagic(String magic) {
        byte[] body = concat(fmtChunk(1, 1, SR, 16), chunk("data", new byte[64]));
        return concat(ascii(magic), le32(4 + body.length), ascii("WAVE"), body);
    }

    static byte[] chunk(String id, byte[] payload) {
        return chunkWithSize(id, payload.length, payload);
    }

    /** A chunk whose declared size may differ from the bytes that follow. */
    static byte[] chunkWithSize(String id, long size, byte[] payload) {
        byte[] pad = ((size & 1L) != 0) ? new byte[1] : new byte[0];
        return concat(ascii(id), le32(size), payload, pad);
    }

    static byte[] fmtChunk(int tag, int ch, int rate, int bits) {
        int bytes = bits / 8;
        return chunk("fmt ", concat(le16(tag), le16(ch), le32(rate),
                le32((long) rate * ch * bytes), le16(ch * bytes), le16(bits)));
    }

    /** WAVE_FORMAT_EXTENSIBLE fmt chunk with a 22 byte extension and a GUID sub-format. */
    static byte[] fmtExtChunk(int subTag, int ch, int rate, int bits, int validBits) {
        int bytes = bits / 8;
        byte[] guid = new byte[16];
        guid[0] = (byte) (subTag & 0xFF);
        guid[1] = (byte) ((subTag >> 8) & 0xFF);
        return chunk("fmt ", concat(le16(0xFFFE), le16(ch), le32(rate),
                le32((long) rate * ch * bytes), le16(ch * bytes), le16(bits),
                le16(22), le16(validBits), le32(ch == 2 ? 3 : 4), guid));
    }

    /** A 18 byte extensible fmt chunk: too short to carry the sub-format GUID. */
    static byte[] shortExtFmtChunk() {
        return chunk("fmt ", concat(le16(0xFFFE), le16(1), le32(SR), le32(SR * 2), le16(2), le16(16)));
    }

    // ================================================================== sample codecs

    static byte[] pcm8(float[] s) {
        byte[] b = new byte[s.length];
        for (int i = 0; i < s.length; i++) b[i] = (byte) (q8(s[i]) + 128);
        return b;
    }

    static byte[] pcm16(float[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            int q = q16(s[i]);
            b[i * 2] = (byte) (q & 0xFF);
            b[i * 2 + 1] = (byte) ((q >> 8) & 0xFF);
        }
        return b;
    }

    static byte[] pcm24(float[] s) {
        byte[] b = new byte[s.length * 3];
        for (int i = 0; i < s.length; i++) {
            int q = q24(s[i]);
            b[i * 3] = (byte) (q & 0xFF);
            b[i * 3 + 1] = (byte) ((q >> 8) & 0xFF);
            b[i * 3 + 2] = (byte) ((q >> 16) & 0xFF);
        }
        return b;
    }

    static byte[] pcm32(float[] s) {
        byte[] b = new byte[s.length * 4];
        for (int i = 0; i < s.length; i++) {
            int q = q32(s[i]);
            for (int k = 0; k < 4; k++) b[i * 4 + k] = (byte) ((q >>> (8 * k)) & 0xFF);
        }
        return b;
    }

    static byte[] f32(float[] s) {
        byte[] b = new byte[s.length * 4];
        for (int i = 0; i < s.length; i++) {
            int q = Float.floatToIntBits(s[i]);
            for (int k = 0; k < 4; k++) b[i * 4 + k] = (byte) ((q >>> (8 * k)) & 0xFF);
        }
        return b;
    }

    static int q8(double v) {
        return (int) clamp(Math.round(v * 127.0), -128, 127);
    }

    static int q16(double v) {
        return (int) clamp(Math.round(v * 32767.0), -32768, 32767);
    }

    static int q24(double v) {
        return (int) clamp(Math.round(v * 8388607.0), -8388608, 8388607);
    }

    static int q32(double v) {
        return (int) clamp(Math.round(v * 2147483647.0), -2147483648L, 2147483647L);
    }

    static float dec8(int q) {
        return q / 128.0f;
    }

    static float dec16(int q) {
        return q / 32768.0f;
    }

    static float dec24(int q) {
        return q / 8388608.0f;
    }

    static float dec32(int q) {
        return (float) (q / 2147483648.0);
    }

    static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static float[] tone(int frames, double freq, int rate, double amp) {
        float[] s = new float[frames];
        for (int i = 0; i < frames; i++) s[i] = (float) (amp * Math.sin(2 * Math.PI * freq * i / rate));
        return s;
    }

    static float[] interleave(float[] l, float[] r) {
        int n = Math.min(l.length, r.length);
        float[] s = new float[n * 2];
        for (int i = 0; i < n; i++) {
            s[i * 2] = l[i];
            s[i * 2 + 1] = r[i];
        }
        return s;
    }

    /** Output length WavReader must produce for a source of {@code n} frames at {@code rate}. */
    static long outLen(long n, int rate) {
        if (rate == SR) return n;
        return Math.max(1L, Math.round(n * (double) SR / rate));
    }

    // ================================================================== helpers

    static WavReader.AudioData read(byte[] wav, int maxFrames) {
        return WavReader.read(new ByteArrayInputStream(wav), maxFrames);
    }

    static void writeFile(File f, byte[] bytes) throws IOException {
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(bytes);
        } finally {
            os.close();
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (int i = 0; i < kids.length; i++) deleteTree(kids[i]);
        f.delete();
    }

    static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    static byte[] le16(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF)};
    }

    static byte[] le32(long v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)};
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (int i = 0; i < parts.length; i++) n += parts[i].length;
        byte[] out = new byte[n];
        int p = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, p, parts[i].length);
            p += parts[i].length;
        }
        return out;
    }

    // ================================================================== assertions

    static void group(String name) {
        System.out.println();
        System.out.println("== " + name);
    }

    static void check(String what, boolean cond) {
        check(what, cond, null);
    }

    static void check(String what, boolean cond, String detail) {
        checks++;
        if (cond) {
            System.out.println("  PASS  " + what);
        } else {
            fails++;
            System.out.println("  FAIL  " + what + (detail == null ? "" : "   <" + detail + ">"));
        }
    }

    static void eqI(String what, long expected, long actual) {
        check(what, expected == actual, "expected " + expected + ", got " + actual);
    }

    static void eqD(String what, double expected, double actual, double tol) {
        double diff = Math.abs(expected - actual);
        check(what, diff <= tol, "expected " + expected + ", got " + actual + ", |diff|=" + diff);
    }

    static void eqS(String what, String expected, String actual) {
        check(what, expected == null ? actual == null : expected.equals(actual),
                "expected " + expected + ", got " + actual);
    }

    static void expectOk(String what, WavReader.AudioData d) {
        check(what, d != null && d.ok(), d == null ? "null result" : "error=" + d.error);
    }

    static void expectErr(String what, WavReader.AudioData d, String needle) {
        boolean bad = d == null || d.ok() || d.error == null || d.error.length() == 0;
        if (!bad && needle != null && d.error.indexOf(needle) < 0) bad = true;
        check(what, !bad, d == null ? "null result"
                : "ok=" + d.ok() + ", error=" + d.error + ", wanted substring " + needle);
    }
}
