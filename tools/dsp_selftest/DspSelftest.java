import com.dsh.v4tune.synth.FormantFilter;
import com.dsh.v4tune.synth.Noise;
import com.dsh.v4tune.synth.VoiceSource;
import com.dsh.v4tune.synth.WavWriter;

import java.io.File;

/**
 * DSP self-test for the V4Tune singing-synthesis primitives (VoiceSource, FormantFilter,
 * Noise, WavWriter). Renders steady vowels plus an f0 sweep, writes them as WAV, and
 * analyses the spectra with a hand-written DFT. No external libraries.
 *
 * <p><b>How the formant peaks are measured.</b> The excitation is a harmonic pulse train,
 * so at f0 = 220 Hz the raw DFT only samples the vocal-tract response every 220 Hz and
 * cannot place a 300 Hz formant (its nearest harmonics are 220 and 440 Hz). The check
 * therefore reads the peaks off the smooth spectral envelope of the same 4096-point DFT
 * frame: a real cepstrum low-pass liftered below the pitch quefrency, plus the usual
 * +6 dB/octave analysis pre-emphasis that cancels the glottal source roll-off. The three
 * largest envelope peaks below 3.4 kHz (F4 sits at 3.5 kHz) are compared with the
 * targets. The raw harmonic peaks are printed as well for reference.</p>
 *
 * <p>Usage: {@code java DspSelftest [outputDir]} - exits non-zero when a check fails.</p>
 */
public class DspSelftest {

    // ---- render / analysis configuration -----------------------------------
    static final int SR = 44100;
    static final int DFT_N = 4096;
    static final int DFT_OFF = (int) (0.30 * SR);   // analysis frame, 0.30 s in
    static final double PEAK_MIN_HZ = 120.0;        // ignore the DC/tilt region
    static final double PEAK_MAX_HZ = 3400.0;       // "below 3.5 kHz"; F4 sits at 3.5 kHz
    static final double TOL = 0.15;                 // 15% formant tolerance
    static final double MIN_PROM_DB = 2.5;          // peak prominence needed to count
    static final int ANA_N = 3281;                  // envelope grid, 1 Hz from 120..3400
    static final int CEP_LIFTER = 150;              // quefrency cutoff (< the 200 sample pitch period)
    static final double ANA_PREEMPH_DB_OCT = 6.0;   // +6 dB/oct analysis pre-emphasis

    static final double F0 = 220.0;
    static final double OQ = 0.55;
    static final double TL = 0.2;
    static final double BREATH = 0.0;

    static final double F4 = 3500.0, BW4 = 200.0;
    static final double F5 = 4500.0, BW5 = 250.0;

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "dspout");
        if (!outDir.exists() && !outDir.mkdirs()) {
            System.out.println("FAIL: cannot create output directory " + outDir);
            System.exit(2);
        }
        System.out.println("V4Tune DSP self-test   sample rate " + SR + " Hz");
        System.out.println("output directory: " + outDir.getAbsolutePath());
        System.out.println("java: " + System.getProperty("java.version"));
        System.out.println("formant peaks are read from the cepstrally smoothed envelope of the");
        System.out.println("4096-point DFT frame (a harmonic source cannot sample a 300 Hz formant).");
        System.out.println();

        checkVowel(outDir, "a", 850.0, 1200.0, 2500.0, 60.0, 90.0, 120.0);
        checkVowel(outDir, "i", 300.0, 2300.0, 3000.0, 60.0, 90.0, 120.0);
        checkVowel(outDir, "u", 350.0, 800.0, 2400.0, 60.0, 90.0, 120.0);
        checkNoise();
        checkSweep(outDir);
        checkGlide(outDir);
        checkFricative(outDir);
        checkWavWriter(outDir);

        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: ALL CHECKS PASSED");
        } else {
            System.out.println("RESULT: " + failures + " CHECK(S) FAILED");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ vowels

    static void checkVowel(File outDir, String name,
                           double f1, double f2, double f3,
                           double b1, double b2, double b3) throws Exception {
        System.out.println("--- vowel /" + name + "/   targets F1=" + fmt(f1) + " F2=" + fmt(f2)
                + " F3=" + fmt(f3) + " Hz (BW " + fmt(b1) + "/" + fmt(b2) + "/" + fmt(b3)
                + "), f0=" + fmt(F0) + " Hz, oq=" + OQ + ", tl=" + TL + " ---");

        double[] sig = renderVowel(SR, f1, f2, f3, b1, b2, b3, F0);
        File wav = new File(outDir, "vowel_" + name + ".wav");
        WavWriter.writeMono16(wav, toFloat(sig), SR);

        double rms = rms(sig);
        double peak = peak(sig);
        int bad = countNonFinite(sig);
        System.out.println("  wrote " + wav.getName() + " (" + wav.length() + " bytes), RMS = "
                + fmt4(rms) + ", peak = " + fmt4(peak));
        check("finite samples (no NaN/Inf)", bad == 0, bad + " bad samples");
        check("peak amplitude in [0.05,0.99]", peak >= 0.05 && peak <= 0.99, "peak=" + fmt4(peak));

        double[] frame = hannFrame(sig, DFT_OFF, DFT_N);
        double[] mag = dftMagnitude(frame);
        double[][] raw = findPeaks(mag, 0.0, (double) SR / DFT_N, PEAK_MIN_HZ, PEAK_MAX_HZ, 0.0);
        sortByMagDesc(raw);
        System.out.println("  raw DFT peaks (harmonics of f0, reference only): " + peakList(raw, 3));

        double[] env = cepstralEnvelopeDb(mag, CEP_LIFTER, ANA_PREEMPH_DB_OCT,
                PEAK_MIN_HZ, PEAK_MAX_HZ, ANA_N);
        double[][] peaks = findPeaks(env, PEAK_MIN_HZ, 1.0, PEAK_MIN_HZ, PEAK_MAX_HZ, MIN_PROM_DB);
        sortByMagDesc(peaks);
        double[][] top = topN(peaks, 3);
        sortByFreq(top);

        boolean ok = (top.length == 3);
        double[] targets = { f1, f2, f3 };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(", ");
            if (i < top.length) {
                double f = top[i][0];
                double dev = (f - targets[i]) / targets[i];
                sb.append("F").append(i + 1).append("=").append(fmt(f)).append(" Hz (")
                        .append(dev >= 0 ? "+" : "").append(fmt1(dev * 100.0)).append("%)");
                if (Math.abs(dev) > TOL) ok = false;
            } else {
                sb.append("F").append(i + 1).append("=none");
                ok = false;
            }
        }
        System.out.println("  envelope peaks vs targets: " + sb);
        if (top.length != 3) {
            System.out.println("  (found " + top.length + " prominent peaks below "
                    + fmt(PEAK_MAX_HZ) + " Hz, expected 3)");
        }
        check("formant peaks within " + fmt(TOL * 100) + "% of targets", ok, sb.toString());
        System.out.println();
    }

    /** Renders n samples of a steady vowel through VoiceSource -> FormantFilter. */
    static double[] renderVowel(int n, double f1, double f2, double f3,
                                double b1, double b2, double b3, double f0) {
        VoiceSource src = new VoiceSource();
        src.setSampleRate(SR);
        src.reset();

        FormantFilter ff = new FormantFilter();
        ff.setSampleRate(SR);
        ff.reset();
        ff.setFormant(0, f1, b1);
        ff.setFormant(1, f2, b2);
        ff.setFormant(2, f3, b3);
        ff.setFormant(3, F4, BW4);
        ff.setFormant(4, F5, BW5);
        ff.setZero(0, 0.0, 0.0);
        ff.setZero(1, 0.0, 0.0);
        ff.setTilt(0.0);
        ff.setFricGain(0.0);

        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = ff.filter(src.sample(f0, OQ, TL, BREATH), 0.0f);
        }
        return out;
    }

    // ------------------------------------------------------------------ noise

    static void checkNoise() {
        System.out.println("--- Noise generator (deterministic xorshift) ---");
        Noise a = new Noise(12345L);
        Noise b = new Noise(12345L);
        boolean same = true;
        double sum = 0.0, sumsq = 0.0, maxAbs = 0.0;
        int outOfRange = 0;
        final int n = 200000;
        for (int i = 0; i < n; i++) {
            float x = a.next();
            float y = b.next();
            if (x != y) same = false;
            if (!(x >= -1.0f && x <= 1.0f)) outOfRange++;
            sum += x;
            sumsq += (double) x * x;
            if (Math.abs(x) > maxAbs) maxAbs = Math.abs(x);
        }
        double mean = sum / n;
        double rms = Math.sqrt(sumsq / n);
        double gsumsq = 0.0;
        for (int i = 0; i < n; i++) {
            double g = a.nextGauss();
            gsumsq += g * g;
        }
        double grms = Math.sqrt(gsumsq / n);
        System.out.println("  white: mean=" + fmt4(mean) + " rms=" + fmt4(rms) + " max=" + fmt4(maxAbs)
                + "   gauss rms=" + fmt4(grms));
        check("deterministic (same seed -> same stream)", same, "two generators diverged");
        check("white uniform in [-1,1]", outOfRange == 0 && rms > 0.55 && rms < 0.60,
                "out of range=" + outOfRange + " rms=" + fmt4(rms));
        check("gauss has unit variance", grms > 0.9 && grms < 1.1, "rms=" + fmt4(grms));
        System.out.println();
    }

    // ------------------------------------------------------------------ sweep

    static void checkSweep(File outDir) throws Exception {
        System.out.println("--- f0 sweep 110 -> 440 Hz over 2 s, formants fixed (/a/) ---");
        int n = 2 * SR;
        VoiceSource src = new VoiceSource();
        src.setSampleRate(SR);
        src.reset();
        FormantFilter ff = new FormantFilter();
        ff.setSampleRate(SR);
        ff.reset();
        ff.setFormant(0, 850.0, 60.0);
        ff.setFormant(1, 1200.0, 90.0);
        ff.setFormant(2, 2500.0, 120.0);
        ff.setFormant(3, F4, BW4);
        ff.setFormant(4, F5, BW5);

        double[] sig = new double[n];
        int periods = 0;
        for (int i = 0; i < n; i++) {
            double f0 = 110.0 * Math.pow(4.0, (double) i / (n - 1));   // 110 -> 440 Hz
            sig[i] = ff.filter(src.sample(f0, OQ, TL, BREATH), 0.0f);
            if (src.lastWrapped()) periods++;
        }
        WavWriter.writeMono16(new File(outDir, "sweep_f0.wav"), toFloat(sig), SR);

        double peak = peak(sig);
        double rms = rms(sig);
        int bad = countNonFinite(sig);
        double maxStep = 0.0;
        int maxAt = 0;
        double[] step = new double[n - 1];
        for (int i = 1; i < n; i++) {
            step[i - 1] = Math.abs(sig[i] - sig[i - 1]);
            if (step[i - 1] > maxStep) {
                maxStep = step[i - 1];
                maxAt = i;
            }
        }
        double[] srt = step.clone();
        java.util.Arrays.sort(srt);
        double p999 = srt[(int) (0.999 * (srt.length - 1))];

        // A signal whose highest component is ~3.5 kHz can slew at most 2*pi*3500/44100
        // = 0.50*peak per sample; allow 1.5x for the ringing right after a closure spike.
        double bound = Math.max(0.25, 0.75 * peak);
        System.out.println("  wrote sweep_f0.wav, RMS = " + fmt4(rms) + ", peak = " + fmt4(peak)
                + ", glottal periods = " + periods);
        System.out.println("  max |x[n]-x[n-1]| = " + fmt4(maxStep) + " at n=" + maxAt
                + " (99.9th pct = " + fmt4(p999) + ", ratio " + fmt2(maxStep / Math.max(p999, 1e-9)) + ")");
        check("finite samples during sweep", bad == 0, bad + " bad samples");
        check("no discontinuity (max step < " + fmt2(bound) + ")", maxStep < bound,
                "max step " + fmt4(maxStep));
        check("step is not an isolated outlier (< 10x p99.9)",
                maxStep < 10.0 * Math.max(p999, 1e-6),
                "ratio " + fmt2(maxStep / Math.max(p999, 1e-9)));
        System.out.println();
    }

    // ------------------------------------------------------------------ extra checks

    /**
     * Formants moving every 5 ms (a /a/ <-> /i/ glide): the filter must neither blow up
     * nor click. This is the "setFormant() called every 5 ms" requirement.
     */
    static void checkGlide(File outDir) throws Exception {
        System.out.println("--- formant glide /a/ <-> /i/, setFormant() every 5 ms ---");
        int n = SR;
        int ctl = 220;                                  // 5 ms at 44.1 kHz
        VoiceSource src = new VoiceSource();
        src.setSampleRate(SR);
        src.reset();
        FormantFilter ff = new FormantFilter();
        ff.setSampleRate(SR);
        ff.reset();
        ff.setFormant(3, F4, BW4);
        ff.setFormant(4, F5, BW5);

        double[] sig = new double[n];
        for (int i = 0; i < n; i++) {
            if (i % ctl == 0) {
                double m = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (double) (n - 1));
                ff.setFormant(0, 850.0 + (300.0 - 850.0) * m, 60.0 + 40.0 * m);
                ff.setFormant(1, 1200.0 + (2300.0 - 1200.0) * m, 90.0 + 40.0 * m);
                ff.setFormant(2, 2500.0 + (3000.0 - 2500.0) * m, 120.0 + 40.0 * m);
            }
            sig[i] = ff.filter(src.sample(F0, OQ, TL, BREATH), 0.0f);
        }
        WavWriter.writeMono16(new File(outDir, "glide_a_i.wav"), toFloat(sig), SR);

        double peak = peak(sig);
        double rms = rms(sig);
        int bad = countNonFinite(sig);
        double maxStep = 0.0;
        for (int i = 1; i < n; i++) {
            double d = Math.abs(sig[i] - sig[i - 1]);
            if (d > maxStep) maxStep = d;
        }
        double bound = Math.max(0.25, 0.75 * peak);
        System.out.println("  wrote glide_a_i.wav, RMS = " + fmt4(rms) + ", peak = " + fmt4(peak)
                + ", max |x[n]-x[n-1]| = " + fmt4(maxStep));
        check("finite samples while formants move", bad == 0, bad + " bad samples");
        check("no blow-up (peak < 2)", peak < 2.0, "peak=" + fmt4(peak));
        check("audible, not silent (rms > 0.01)", rms > 0.01, "rms=" + fmt4(rms));
        check("no click (max step < " + fmt2(bound) + ")", maxStep < bound, "max step " + fmt4(maxStep));
        System.out.println();
    }

    /** Fricative noise branch, nasal anti-resonance, tilt and breath: bounded and finite. */
    static void checkFricative(File outDir) throws Exception {
        System.out.println("--- fricative branch + nasal zeros + tilt + breath ---");
        int n = SR / 2;
        VoiceSource src = new VoiceSource();
        src.setSampleRate(SR);
        src.reset();
        FormantFilter ff = new FormantFilter();
        ff.setSampleRate(SR);
        ff.reset();
        ff.setFormant(0, 400.0, 70.0);
        ff.setFormant(1, 1700.0, 110.0);
        ff.setFormant(2, 2600.0, 140.0);
        ff.setFormant(3, F4, BW4);
        ff.setFormant(4, F5, BW5);
        ff.setZero(0, 900.0, 150.0);           // nasal zero
        ff.setZero(1, 2200.0, 200.0);
        ff.setTilt(-0.3);
        ff.setFricGain(0.6);
        ff.setFricBand(0, 4500.0, 900.0, 0.8);  // /s/-ish
        ff.setFricBand(1, 6800.0, 1200.0, 0.6);
        ff.setFricBand(2, 2500.0, 600.0, 0.3);

        double[] sig = new double[n];
        for (int i = 0; i < n; i++) {
            float voiced = src.sample(180.0, 0.5, 0.3, 0.5);
            float noise = src.noiseOnly(0.8);
            sig[i] = ff.filter(voiced, noise);
        }
        WavWriter.writeMono16(new File(outDir, "fricative.wav"), toFloat(sig), SR);

        double peak = peak(sig);
        double rms = rms(sig);
        int bad = countNonFinite(sig);
        System.out.println("  wrote fricative.wav, RMS = " + fmt4(rms) + ", peak = " + fmt4(peak));
        check("finite samples (fricative path)", bad == 0, bad + " bad samples");
        check("bounded, no blow-up (peak < 4)", peak > 0.0 && peak < 4.0, "peak=" + fmt4(peak));
        check("noise branch contributes (rms > 0.01)", rms > 0.01, "rms=" + fmt4(rms));
        System.out.println();
    }

    /** RIFF/WAVE header fields, chunk sizes, and soft clipping instead of wrap-around. */
    static void checkWavWriter(File outDir) throws Exception {
        System.out.println("--- WavWriter ---");
        int m = 1000;
        float[] mono = new float[m];
        for (int i = 0; i < m; i++) mono[i] = (float) Math.sin(2.0 * Math.PI * i / 50.0);
        byte[] b = WavWriter.toBytesMono16(mono, SR);
        boolean hdr = b.length == 44 + 2 * m
                && ascii(b, 0).equals("RIFF") && ascii(b, 8).equals("WAVE")
                && ascii(b, 12).equals("fmt ") && ascii(b, 36).equals("data")
                && i32(b, 4) == 36 + 2 * m && i32(b, 16) == 16 && i16(b, 20) == 1
                && i16(b, 22) == 1 && i32(b, 24) == SR && i32(b, 28) == SR * 2
                && i16(b, 32) == 2 && i16(b, 34) == 16 && i32(b, 40) == 2 * m;

        File mf = new File(outDir, "wavtest_mono.wav");
        WavWriter.writeMono16(mf, mono, SR);
        boolean sizeOk = mf.length() == 44 + 2 * m;

        float[] hot = { 0.0f, 5.0f, -5.0f, 0.5f };
        byte[] hb = WavWriter.toBytesMono16(hot, SR);
        int v1 = s16(hb, 44 + 2), v2 = s16(hb, 44 + 4), v3 = s16(hb, 44 + 6);
        boolean clip = v1 == 32767 && v2 == -32767 && Math.abs(v3) < 30000 && v3 > 10000;

        float[] l = new float[500], r = new float[500];
        for (int i = 0; i < 500; i++) { l[i] = mono[i]; r[i] = -mono[i]; }
        File sf = new File(outDir, "wavtest_stereo.wav");
        WavWriter.writeStereo16(sf, l, r, SR);
        boolean stereo = sf.length() == 44 + 4 * 500;
        byte[] sb = java.nio.file.Files.readAllBytes(sf.toPath());
        stereo = stereo && i16(sb, 22) == 2 && i16(sb, 32) == 4 && i32(sb, 28) == SR * 4;

        System.out.println("  mono " + mf.length() + " bytes, stereo " + sf.length()
                + " bytes, hot samples " + v1 + "/" + v2 + "/" + v3);
        check("RIFF/fmt/data header fields", hdr, hdr ? "ok" : "bad header");
        check("mono file size 44+2n", sizeOk, mf.length() + " bytes");
        check("stereo file 44+4n, 2 channels, 16 bit", stereo, sf.length() + " bytes");
        check("soft clipping (no wrap-around)", clip, v1 + "/" + v2 + "/" + v3);
        System.out.println();
    }

    static String ascii(byte[] b, int off) {
        return "" + (char) (b[off] & 0xFF) + (char) (b[off + 1] & 0xFF)
                + (char) (b[off + 2] & 0xFF) + (char) (b[off + 3] & 0xFF);
    }

    static int i32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    static int i16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static int s16(byte[] b, int off) {
        return (short) i16(b, off);
    }

    // ------------------------------------------------------------------ analysis

    static double[] hannFrame(double[] sig, int off, int n) {
        double[] f = new double[n];
        for (int i = 0; i < n; i++) {
            f[i] = sig[off + i] * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
        }
        return f;
    }

    /** Direct DFT, no FFT: returns |X[k]| for k = 0..n/2. */
    static double[] dftMagnitude(double[] x) {
        int n = x.length;
        double[] cos = new double[n];
        double[] sin = new double[n];
        for (int i = 0; i < n; i++) {
            double a = 2.0 * Math.PI * i / n;
            cos[i] = Math.cos(a);
            sin[i] = Math.sin(a);
        }
        double[] mag = new double[n / 2 + 1];
        for (int k = 0; k <= n / 2; k++) {
            double re = 0.0, im = 0.0;
            for (int i = 0; i < n; i++) {
                int idx = (int) (((long) k * i) % n);
                re += x[i] * cos[idx];
                im -= x[i] * sin[idx];
            }
            mag[k] = Math.sqrt(re * re + im * im);
        }
        return mag;
    }

    /**
     * Log-magnitude spectral envelope of a DFT magnitude spectrum, in dB, on a 1 Hz grid.
     *
     * <p>A real cepstrum is computed from log|X| and everything above {@code lifter}
     * quefrencies is discarded (with a raised-cosine taper), which removes the harmonic
     * fine structure while keeping the vocal-tract envelope. {@code preemphDbOct} adds a
     * linear-in-log-f slope, i.e. the usual +6 dB/octave pre-emphasis that compensates the
     * glottal source roll-off so the envelope peaks sit on the formants.</p>
     */
    static double[] cepstralEnvelopeDb(double[] mag, int lifter, double preemphDbOct,
                                       double fLo, double fHi, int npts) {
        int n = (mag.length - 1) * 2;
        double[] logm = new double[n];
        for (int k = 0; k <= n / 2; k++) {
            double m = mag[k];
            if (!(m > 1.0e-12)) m = 1.0e-12;
            double v = Math.log(m);
            logm[k] = v;
            logm[(n - k) % n] = v;
        }
        double[] cq = new double[lifter + 1];
        for (int q = 0; q <= lifter; q++) {
            double s = 0.0;
            for (int k = 0; k < n; k++) s += logm[k] * Math.cos(2.0 * Math.PI * q * k / n);
            double taper = 1.0;
            if (q > lifter * 0.8) {
                taper = 0.5 + 0.5 * Math.cos(Math.PI * (q - lifter * 0.8) / (lifter * 0.2));
            }
            cq[q] = s / n * taper;
        }
        double[] env = new double[npts];
        double dbPerNat = 20.0 / Math.log(10.0);
        for (int i = 0; i < npts; i++) {
            double f = fLo + (fHi - fLo) * i / (npts - 1);
            double w = 2.0 * Math.PI * f / SR;
            double v = cq[0];
            for (int q = 1; q <= lifter; q++) v += 2.0 * cq[q] * Math.cos(w * q);
            env[i] = dbPerNat * v + preemphDbOct * (Math.log(f / 1000.0) / Math.log(2.0));
        }
        return env;
    }

    /**
     * Local maxima of a curve on a uniform frequency grid, with parabolic refinement and
     * a prominence filter.
     *
     * @param f0Hz   frequency of index 0 (0 for a DFT magnitude array, the grid start for
     *               a spectral envelope)
     * @param stepHz grid spacing in Hz (SR/N for a DFT magnitude array)
     * @param minProm required prominence in the units of the curve (0 = keep all)
     * @return {frequency Hz, value, prominence} rows
     */
    static double[][] findPeaks(double[] y, double f0Hz, double stepHz,
                                double minF, double maxF, double minProm) {
        int n = y.length;
        int lo = Math.max(1, (int) Math.ceil((minF - f0Hz) / stepHz));
        int hi = Math.min(n - 2, (int) Math.floor((maxF - f0Hz) / stepHz));
        java.util.ArrayList<double[]> list = new java.util.ArrayList<double[]>();
        int span = Math.max(1, (int) (250.0 / stepHz));
        for (int i = lo; i <= hi; i++) {
            if (!(y[i] > y[i - 1] && y[i] >= y[i + 1])) continue;
            double left = y[i], right = y[i];
            for (int j = i - 1; j >= Math.max(0, i - span); j--) left = Math.min(left, y[j]);
            for (int j = i + 1; j <= Math.min(n - 1, i + span); j++) right = Math.min(right, y[j]);
            double prom = y[i] - Math.max(left, right);
            if (prom < minProm) continue;
            double d = y[i - 1] - 2.0 * y[i] + y[i + 1];
            double off = 0.0;
            if (Math.abs(d) > 1.0e-20) off = 0.5 * (y[i - 1] - y[i + 1]) / d;
            if (off > 0.5) off = 0.5; else if (off < -0.5) off = -0.5;
            list.add(new double[] { f0Hz + (i + off) * stepHz, y[i], prom });
        }
        return list.toArray(new double[list.size()][]);
    }

    static void sortByMagDesc(double[][] p) {
        java.util.Arrays.sort(p, new java.util.Comparator<double[]>() {
            public int compare(double[] a, double[] b) {
                return Double.compare(b[1], a[1]);
            }
        });
    }

    static void sortByFreq(double[][] p) {
        java.util.Arrays.sort(p, new java.util.Comparator<double[]>() {
            public int compare(double[] a, double[] b) {
                return Double.compare(a[0], b[0]);
            }
        });
    }

    static double[][] topN(double[][] p, int n) {
        int m = Math.min(n, p.length);
        double[][] out = new double[m][];
        for (int i = 0; i < m; i++) out[i] = p[i];
        return out;
    }

    static String peakList(double[][] p, int n) {
        StringBuilder sb = new StringBuilder();
        int m = Math.min(n, p.length);
        for (int i = 0; i < m; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fmt(p[i][0])).append(" Hz");
        }
        if (m == 0) sb.append("none");
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    static void check(String what, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println("  " + pad(what + " ", 46) + (ok ? "PASS" : "FAIL") + "  [" + detail + "]");
    }

    static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append('.');
        return sb.toString();
    }

    static float[] toFloat(double[] x) {
        float[] f = new float[x.length];
        for (int i = 0; i < x.length; i++) f[i] = (float) x[i];
        return f;
    }

    static double rms(double[] x) {
        double s = 0.0;
        for (int i = 0; i < x.length; i++) s += x[i] * x[i];
        return Math.sqrt(s / x.length);
    }

    static double peak(double[] x) {
        double p = 0.0;
        for (int i = 0; i < x.length; i++) {
            double a = Math.abs(x[i]);
            if (a > p) p = a;
        }
        return p;
    }

    static int countNonFinite(double[] x) {
        int c = 0;
        for (int i = 0; i < x.length; i++) {
            if (Double.isNaN(x[i]) || Double.isInfinite(x[i])) c++;
        }
        return c;
    }

    static String fmt(double v) {
        return String.valueOf(Math.round(v));
    }

    static String fmt1(double v) {
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    static String fmt2(double v) {
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    static String fmt4(double v) {
        return String.valueOf(Math.round(v * 10000.0) / 10000.0);
    }
}
