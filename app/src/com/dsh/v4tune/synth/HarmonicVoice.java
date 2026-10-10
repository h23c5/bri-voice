package com.dsh.v4tune.synth;

import java.util.Arrays;

/**
 * Real-voice harmonic source: a wavetable built from a measured harmonic envelope.
 *
 * <p>{@link VoiceSource} is purely parametric (a glottal pulse shaped by a formant cascade), so it
 * can only approximate a singer. A {@code .v4voice} pack measured from a real voicebank carries the
 * harmonic magnitudes a phoneme actually has; this class plays them back. One period of the
 * waveform is synthesised from the envelope with a small radix-2 inverse FFT and then read back
 * with a phase accumulator, so one sample costs a table lookup and a phase add - never a sum of
 * sines (with 80 harmonics that would be far too slow on a phone).</p>
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li>{@link #setShape} is a control-rate call (every 1-5 ms). It rebuilds the table only when the
 *       requested sound materially changed (f0 by more than 0.5 %, any envelope value by more than
 *       0.25 dB), so a steady note does no FFT work at all.</li>
 *   <li>A rebuilt table is cross-faded with the one that is playing over a few milliseconds, so
 *       vowel and pitch changes are click free - even when the shape changes again mid fade.</li>
 *   <li>Every table is centred (zero mean) and normalised to a fixed RMS, so a timbre change never
 *       changes the level: the envelope only shapes the spectrum.</li>
 *   <li>{@link #sample()} allocates nothing, never returns NaN and tolerates a missing, stale or
 *       hostile shape.</li>
 * </ul>
 *
 * <p>The table is band limited to {@value #MAX_HARMONICS} harmonics, to {@value #MAX_HZ} Hz and to
 * 45 % of the sample rate, so the source can never alias. Reading the 1024 point table linearly
 * costs at most about 0.2 dB of droop on the top harmonic of a low note.</p>
 *
 * <p>Not thread safe: use one instance per rendered voice.</p>
 */
public class HarmonicVoice {

    /** Samples in one period of the wavetable (power of two, so the IFFT is a plain radix-2). */
    public static final int TABLE = 1024;

    /** Hard cap on the number of harmonics. */
    private static final int MAX_HARMONICS = 256;
    /** Highest harmonic kept, and the fraction of the sample rate that has to stay clear. */
    private static final double MAX_HZ = 16000.0, NYQUIST_GUARD = 0.45;
    /** RMS every table is normalised to: changing vowel must not change the level. */
    private static final double TARGET_RMS = 0.24;
    /** Peak ceiling of the table (only degenerate, very spiky envelopes ever reach it). */
    private static final double PEAK_LIMIT = 1.2;
    /** Accepted envelope range in dB. */
    private static final double MAX_DB = 24.0, MIN_DB = -120.0;
    /** Rebuild thresholds: f0 by 0.5 %, any envelope value by 0.25 dB. */
    private static final double F0_TOL = 0.005, DB_TOL = 0.25;
    /** Length of the old/new table cross-fade in milliseconds. */
    private static final double XFADE_MS = 3.0;
    /** Breath noise: level at b = 1, the noise high-pass corner and its smoothing time. */
    private static final double BREATH_LEVEL = 0.12, BREATH_HZ = 3500.0, BREATH_SMOOTH_SEC = 0.005;
    /** Sample rates accepted by {@link #setSampleRate}. */
    private static final double MIN_SR = 4000.0, MAX_SR = 384000.0;
    /** Largest envelope handed to the table builder; points beyond this are ignored. */
    private static final int MAX_ENV_POINTS = 1024;
    /** Fixed seed: the same call sequence always renders the same samples. */
    private static final long NOISE_SEED = 0x5EEDBEEFL;

    // ------------------------------------------------------------ table state

    private final double[] table = new double[TABLE];
    private final double[] prevTable = new double[TABLE];
    private final double[] re = new double[TABLE];
    private final double[] im = new double[TABLE];
    private final double[] cosT = new double[TABLE / 2];
    private final double[] sinT = new double[TABLE / 2];
    private final int[] brev = new int[TABLE];
    /** scratch: harmonic amplitudes of the current table, index = harmonic number */
    private final double[] amp = new double[MAX_HARMONICS + 1];
    /** scratch: sanitised copy of the caller's envelope (strictly ascending, finite) */
    private final double[] gridF = new double[MAX_ENV_POINTS];
    private final double[] gridD = new double[MAX_ENV_POINTS];

    /** envelope the current table was built from (kept for the "did it change?" test) */
    private double[] shapeFreqs = new double[0];
    private double[] shapeDb = new double[0];
    private double shapeF0 = 0.0;

    private double sampleRate = 44100.0;
    private double f0 = 0.0;
    private boolean haveShape = false;
    private int harmonicCount = 0;
    private long rebuilds = 0;

    private double phase = 0.0;
    private int xfadeLeft = 0;
    private int xfadeTotal = 1;

    // ------------------------------------------------------------ noise state

    private final Noise noise = new Noise(NOISE_SEED);
    private double breath = 0.0, breathCur = 0.0;
    private double breathCoef = 0.01, lpCoef = 0.4, lpState = 0.0;

    public HarmonicVoice() {
        // twiddles + bit reversal are built once so that setShape() and sample() allocate nothing
        for (int i = 0; i < TABLE / 2; i++) {
            double a = -2.0 * Math.PI * i / TABLE;
            cosT[i] = Math.cos(a);
            sinT[i] = Math.sin(a);
        }
        for (int i = 0; i < TABLE; i++) {
            int r = 0;
            for (int b = 0; b < 10; b++) {
                if ((i & (1 << b)) != 0) r |= 1 << (9 - b);
            }
            brev[i] = r;
        }
        recomputeCoefs();
    }

    // ------------------------------------------------------------ public API

    /** Sets the output sample rate; a change clears the table and the phase. Invalid values are ignored. */
    public void setSampleRate(double sr) {
        if (Double.isNaN(sr) || sr <= 0.0 || Double.isInfinite(sr)) return;
        if (sr < MIN_SR) sr = MIN_SR;
        if (sr > MAX_SR) sr = MAX_SR;
        if (sr == sampleRate) return;
        sampleRate = sr;
        recomputeCoefs();
        reset();
    }

    public double getSampleRate() {
        return sampleRate;
    }

    /** Forgets the shape, the table, the phase and the noise state - as if freshly constructed. */
    public void reset() {
        phase = 0.0;
        f0 = 0.0;
        haveShape = false;
        harmonicCount = 0;
        xfadeLeft = 0;
        Arrays.fill(table, 0.0);
        Arrays.fill(prevTable, 0.0);
        lpState = 0.0;
        breathCur = breath;
        noise.reset(NOISE_SEED);
    }

    /** Breath noise amount, 0..1 (values outside the range and NaN are clamped). */
    public void setBreath(double b) {
        if (Double.isNaN(b)) b = 0.0;
        breath = b < 0.0 ? 0.0 : (b > 1.0 ? 1.0 : b);
    }

    /**
     * Sets the current spectrum. Call at control rate (every 1-5 ms).
     *
     * @param freqsHz  envelope frequencies in Hz, ascending (a null/short/hostile array is ignored)
     * @param gainsDb  relative dB at {@code freqsHz}, same length, strongest harmonic around 0 dB
     * @param f0       the note's fundamental in Hz; {@code <= 0}, NaN or Inf switches the voice off
     */
    public void setShape(double[] freqsHz, double[] gainsDb, double f0) {
        if (freqsHz == null || gainsDb == null) {
            clear();
            return;
        }
        int n = freqsHz.length < gainsDb.length ? freqsHz.length : gainsDb.length;
        if (n < 1) {
            clear();
            return;
        }
        double f = f0;
        if (Double.isNaN(f) || Double.isInfinite(f) || f <= 0.0) {
            clear();
            return;
        }
        double fMax = sampleRate * 0.49;
        if (f > fMax) f = fMax;

        if (!materiallyDifferent(freqsHz, gainsDb, n, f)) {
            this.f0 = f;                       // same timbre, only a hair of pitch drift
            return;
        }
        if (haveShape && harmonicCount > 0) {
            // keep the wave continuous across the swap: freeze whatever is playing right now
            if (xfadeLeft > 0) {
                double w = (xfadeTotal - xfadeLeft) / (double) xfadeTotal;
                for (int i = 0; i < TABLE; i++) prevTable[i] += (table[i] - prevTable[i]) * w;
            } else {
                System.arraycopy(table, 0, prevTable, 0, TABLE);
            }
            xfadeLeft = xfadeTotal;
        } else {
            xfadeLeft = 0;
        }
        buildTable(freqsHz, gainsDb, n, f);
        shapeFreqs = Arrays.copyOf(freqsHz, n);
        shapeDb = Arrays.copyOf(gainsDb, n);
        shapeF0 = f;
        this.f0 = f;
        haveShape = true;
        rebuilds++;
    }

    /** One excitation sample, roughly -1..1 and free of DC. Cheap and allocation free. */
    public float sample() {
        if (!haveShape) return 0.0f;

        double p = phase + f0 / sampleRate;
        if (p >= 1.0) p -= 1.0;
        else if (p < 0.0) p = 0.0;
        phase = p;

        double pos = p * TABLE;
        int i0 = (int) pos;
        if (i0 >= TABLE) i0 = TABLE - 1;
        int i1 = i0 + 1;
        if (i1 >= TABLE) i1 = 0;
        double frac = pos - i0;
        double v = table[i0] + (table[i1] - table[i0]) * frac;

        if (xfadeLeft > 0) {
            double pv = prevTable[i0] + (prevTable[i1] - prevTable[i0]) * frac;
            double w = (xfadeTotal - xfadeLeft) / (double) xfadeTotal;
            v = pv + (v - pv) * w;
            xfadeLeft--;
        }

        if (breath != breathCur) {
            breathCur += (breath - breathCur) * breathCoef;
            if (Math.abs(breath - breathCur) < 1e-4) breathCur = breath;
        }
        if (breathCur > 1e-5) {
            double w = noise.nextGauss();
            lpState += (w - lpState) * lpCoef;
            v += (w - lpState) * (BREATH_LEVEL * breathCur);
        }
        return (float) v;
    }

    /** True while the voice produces sound (a usable shape was set and f0 > 0). */
    public boolean isActive() {
        return haveShape && f0 > 0.0 && harmonicCount > 0;
    }

    /** Number of harmonics in the current table (0 while inactive). Informational. */
    public int harmonicCount() {
        return harmonicCount;
    }

    /** How many times the wavetable was rebuilt. Informational (used by the self test). */
    public long rebuildCount() {
        return rebuilds;
    }

    // ------------------------------------------------------------ internals

    private void clear() {
        haveShape = false;
        f0 = 0.0;
        harmonicCount = 0;
        xfadeLeft = 0;
    }

    private void recomputeCoefs() {
        xfadeTotal = (int) Math.round(XFADE_MS * 0.001 * sampleRate);
        if (xfadeTotal < 1) xfadeTotal = 1;
        breathCoef = 1.0 - Math.exp(-1.0 / (BREATH_SMOOTH_SEC * sampleRate));
        double a = 1.0 - Math.exp(-2.0 * Math.PI * BREATH_HZ / sampleRate);
        lpCoef = a < 0.0 ? 0.0 : (a > 1.0 ? 1.0 : a);
    }

    /** True when the requested spectrum differs enough from the built one to be worth an FFT. */
    private boolean materiallyDifferent(double[] freqs, double[] db, int n, double f) {
        if (!haveShape || shapeFreqs.length != n) return true;
        if (shapeF0 <= 0.0) return true;
        double r = f / shapeF0;
        if (r > 1.0 + F0_TOL || r < 1.0 - F0_TOL) return true;
        for (int i = 0; i < n; i++) {
            double a = freqs[i], b = shapeFreqs[i];
            if (Double.isNaN(a) || Double.isNaN(b)) return true;
            if (Math.abs(a - b) > 1e-4 * (Math.abs(b) > 1.0 ? Math.abs(b) : 1.0)) return true;
        }
        for (int i = 0; i < n; i++) {
            double d = db[i] - shapeDb[i];
            if (Double.isNaN(d) || d > DB_TOL || d < -DB_TOL) return true;
        }
        return false;
    }

    private int harmonicLimit(double f) {
        double top = MAX_HZ < NYQUIST_GUARD * sampleRate ? MAX_HZ : NYQUIST_GUARD * sampleRate;
        int k = (int) Math.floor(top / f);
        if (k > MAX_HARMONICS) k = MAX_HARMONICS;
        return k < 0 ? 0 : k;
    }

    /**
     * Synthesises one period into {@link #table}: harmonic k gets {@code 10^(env(k*f0)/20)}, the
     * spectrum is turned into samples by the inverse FFT, then centred and normalised.
     */
    private void buildTable(double[] freqs, double[] db, int n, double f0) {
        int m = 0;
        for (int i = 0; i < n && m < MAX_ENV_POINTS; i++) {
            double f = freqs[i];
            if (Double.isNaN(f) || Double.isInfinite(f) || f <= 0.0) continue;
            if (m > 0 && f <= gridF[m - 1]) continue;          // keep the grid strictly ascending
            double d = db[i];
            if (Double.isNaN(d) || d == Double.NEGATIVE_INFINITY) d = MIN_DB;
            else if (d == Double.POSITIVE_INFINITY) d = MAX_DB;
            if (d > MAX_DB) d = MAX_DB;
            else if (d < MIN_DB) d = MIN_DB;
            gridF[m] = f;
            gridD[m] = d;
            m++;
        }

        Arrays.fill(re, 0.0);
        Arrays.fill(im, 0.0);
        int kMax = m == 0 ? 0 : harmonicLimit(f0);
        harmonicCount = kMax;
        if (kMax < 1) {
            Arrays.fill(table, 0.0);
            return;
        }

        int gi = 0;
        for (int k = 1; k <= kMax; k++) {
            double hz = k * f0;
            double g;
            if (m == 1 || hz <= gridF[0]) {
                g = gridD[0];
            } else if (hz >= gridF[m - 1]) {
                g = gridD[m - 1];
            } else {
                while (gi < m - 2 && gridF[gi + 1] <= hz) gi++;
                double fa = gridF[gi], fb = gridF[gi + 1];
                double t = fb > fa ? Math.log(hz / fa) / Math.log(fb / fa) : 0.0;
                if (t < 0.0) t = 0.0;
                else if (t > 1.0) t = 1.0;
                g = gridD[gi] + (gridD[gi + 1] - gridD[gi]) * t;
            }
            double a = Math.pow(10.0, g / 20.0);
            if (Double.isNaN(a) || Double.isInfinite(a) || a < 0.0) a = 0.0;
            amp[k] = a;
        }

        // x[n] = sum_k A[k] * cos(2*pi*k*n/N + phase[k]): the bin pair +k / N-k holds the conjugate
        // spectrum. The phases are Schroeder quadratic dispersion, which leaves the measured
        // magnitude spectrum untouched but stops the harmonics from piling up at one instant: the
        // crest factor drops by 2-3x, so the fixed RMS is actually reached (no peak limiter pulls
        // the level down) and the waveform's own sample-to-sample step stays small.
        for (int k = 1; k <= kMax; k++) {
            double a = amp[k];
            if (a <= 0.0) continue;
            double phase = -Math.PI * k * (k - 1) / (double) kMax;
            double h = 0.5 * TABLE * a;
            double cr = h * Math.cos(phase), ci = h * Math.sin(phase);
            re[k] = cr;
            im[k] = ci;
            re[TABLE - k] = cr;
            im[TABLE - k] = -ci;
        }
        inverseFft();

        double mean = 0.0;
        for (int i = 0; i < TABLE; i++) mean += re[i];
        mean /= TABLE;
        double s2 = 0.0;
        for (int i = 0; i < TABLE; i++) {
            double v = re[i] - mean;
            s2 += v * v;
        }
        double rms = Math.sqrt(s2 / TABLE);
        double g = rms > 1e-12 ? TARGET_RMS / rms : 0.0;
        double peak = 0.0;
        for (int i = 0; i < TABLE; i++) {
            double v = (re[i] - mean) * g;
            table[i] = v;
            double av = v < 0.0 ? -v : v;
            if (av > peak) peak = av;
        }
        if (peak > PEAK_LIMIT) {
            double s = PEAK_LIMIT / peak;
            for (int i = 0; i < TABLE; i++) table[i] *= s;
        }
    }

    /** In-place radix-2 inverse DFT (unnormalised here, divided by N at the end). */
    private void inverseFft() {
        for (int i = 0; i < TABLE; i++) {
            int j = brev[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= TABLE; len <<= 1) {
            int half = len >> 1, step = TABLE / len;
            for (int i = 0; i < TABLE; i += len) {
                for (int j = 0, tw = 0; j < half; j++, tw += step) {
                    double wr = cosT[tw], wi = sinT[tw];        // e^{+i*2*pi*j/len}
                    int a = i + j, b = a + half;
                    double xr = re[b] * wr - im[b] * wi;
                    double xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
        double inv = 1.0 / TABLE;
        for (int i = 0; i < TABLE; i++) {
            re[i] *= inv;
            im[i] *= inv;
        }
    }
}
