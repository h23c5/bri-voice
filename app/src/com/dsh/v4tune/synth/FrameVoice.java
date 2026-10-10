package com.dsh.v4tune.synth;

/**
 * Frame-sequence synthesis: every hop the real measured frame of the voicebank sets the amplitude of
 * each harmonic, and a bank of exact-frequency wavetable oscillators turns that into sound.
 *
 * <p>The older path collapsed a frame into one pitch period and looped that table, which throws the
 * frame's own evolution away. Here the stored sequence is walked across the note (the renderer feeds a
 * new frame every control step), the amplitudes ramp over the hop so nothing steps, and every harmonic
 * keeps its own running phase so the joins stay seamless.</p>
 *
 * <p>Notes from building this: placing the harmonics on FFT bins detuned them by up to half a bin
 * (a quarter tone low down) and matched the measured envelope at only r=0.60; a recursive oscillator
 * is marginally stable and baked the gain into its state, which made the level quadratic. A shared sine
 * table with unit-amplitude phase accumulators is exact on both counts (r=0.92, linear gain).</p>
 */
public final class FrameVoice {

    private static final int MAX_HARMONICS = 160;
    /** the measured curves are relative dB, so each frame is brought to this RMS (see HarmonicVoice) */
    private static final double TARGET_RMS = 0.24;
    private static final int TABLE = 4096;
    private static final double[] SIN = new double[TABLE + 1];
    static {
        for (int i = 0; i <= TABLE; i++) SIN[i] = Math.sin(2.0 * Math.PI * i / TABLE);
    }

    private double sampleRate = 44100.0;
    private int hop = 256;

    private double[] bandsHz = new double[0];
    private double f0 = 0.0;
    private boolean voiced = true;
    private boolean haveFrame;

    private int harmonics;
    private double[] amp = new double[0];        // target amplitude per harmonic
    private double[] ampFrom = new double[0];    // amplitude at the start of the hop
    private double[] dphi = new double[0];       // target phase advance per sample, in turns
    private double[] dphiCur = new double[0];    // smoothed advance: the control rate would otherwise
                                                 // staircase the vibrato into FM sidebands
    private double freqCoef = 1.0;
    private static final double FREQ_SEC = 0.004;
    private double[] phase = new double[0];      // running phase per harmonic, in turns
    private double[] smoothed = new double[0];   // one-pole amplitude trajectory
    private double smoothCoef = 1.0;             // set from SMOOTH_SEC
    private static final double SMOOTH_SEC = 0.020;
    private final java.util.Random rnd = new java.util.Random(20240507L);

    public void setSampleRate(double sr) {
        if (sr > 1000 && Math.abs(sr - sampleRate) > 0.5) {
            sampleRate = sr;
            reset();
        }
    }

    public int hopSize() { return hop; }

    public void reset() {
        haveFrame = false;
        harmonics = 0;
        java.util.Arrays.fill(phase, 0.0);
        java.util.Arrays.fill(smoothed, 0.0);
        smoothCoef = 1.0 - Math.exp(-1.0 / (SMOOTH_SEC * sampleRate));
        freqCoef = 1.0 - Math.exp(-1.0 / (FREQ_SEC * sampleRate));
    }

    /** no measured data for the current phoneme: fall back to the formant path */
    public void clear() { haveFrame = false; }

    public boolean isActive() { return haveFrame && f0 > 20.0 && harmonics > 0; }

    /**
     * The frame the next hop is sung from.
     *
     * @param f0      fundamental of the note
     * @param freqsHz band centres of the measured curve
     * @param envDb   measured band levels, dB (relative, peak at 0)
     * @param voiced  true for vowels/nasals, false for fricatives (noise phases)
     * @param level   gain of this frame
     */
    public void setFrame(double f0, double[] freqsHz, double[] envDb, boolean voiced, double level) {
        if (freqsHz == null || envDb == null || freqsHz.length < 2 || f0 < 20.0) { haveFrame = false; return; }
        int nb = Math.min(freqsHz.length, envDb.length);
        if (bandsHz.length != nb) bandsHz = new double[nb];
        System.arraycopy(freqsHz, 0, bandsHz, 0, nb);
        double[] env = java.util.Arrays.copyOf(envDb, nb);

        // Only a real jump (a new note, not vibrato) may restart the phases. The first version
        // tested a 0.05 Hz change, which the default vibrato crosses on every control step, so all
        // harmonic phases were reset every 5 ms - heard as heavy electronic noise.
        boolean restart = harmonics == 0 || Math.abs(f0 - this.f0) > 0.25 * this.f0;
        this.f0 = f0;
        this.voiced = voiced;

        int maxHarm = (int) Math.min(MAX_HARMONICS, sampleRate * 0.45 / f0);
        if (amp.length < maxHarm + 2) {
            amp = new double[maxHarm + 2];
            ampFrom = new double[maxHarm + 2];
            dphi = new double[maxHarm + 2];
            dphiCur = new double[maxHarm + 2];
            smoothed = new double[maxHarm + 2];
            phase = new double[maxHarm + 2];
        }
        double power = 0.0;
        double[] raw = new double[maxHarm + 2];
        for (int k = 1; k <= maxHarm; k++) {
            double hz = k * f0;
            if (hz >= sampleRate * 0.45) break;
            raw[k] = Math.pow(10.0, interp(bandsHz, env, hz) / 20.0);
            power += raw[k] * raw[k] * 0.5;
        }
        double norm = power > 1e-12 ? TARGET_RMS / Math.sqrt(power) : 0.0;
        for (int k = 1; k <= maxHarm; k++) {
            // The frequency step belongs to every frame: keeping it inside the restart branch meant the
            // oscillators held the frequency of the first frame for the whole note, so vibrato and pitch
            // bends detuned every harmonic against the frame data - heard as electronic noise, and the
            // reason smoothing the amplitudes changed nothing. Only the phase may restart.
            dphi[k] = k * f0 / sampleRate;                   // turns per sample
            if (restart) dphiCur[k] = dphi[k];
            if (restart) phase[k] = voiced ? 0.0 : rnd.nextDouble();
            amp[k] = norm * raw[k] * level;
            if (restart) smoothed[k] = 0.0;
        }
        // no amplitude reset: zeroing ampFrom on every pitch change gated the output at the
        // control rate (another source of buzz)
        harmonics = maxHarm;
        haveFrame = true;
    }

    /** one output sample from the oscillator bank */
    public float sample() {
        if (!haveFrame || harmonics == 0) return 0.0f;
        double v = 0.0;
        for (int k = 1; k <= harmonics; k++) {
            dphiCur[k] += (dphi[k] - dphiCur[k]) * freqCoef;
            double ph = phase[k] + dphiCur[k];
            if (ph >= 1.0) ph -= 1.0;
            phase[k] = ph;
            double x = ph * TABLE;
            int i0 = (int) x;
            if (i0 >= TABLE) i0 = TABLE - 1;
            double fr = x - i0;
            double y = SIN[i0] + (SIN[i0 + 1] - SIN[i0]) * fr;
            // One-pole trajectory instead of a linear ramp per hop. Ramping the amplitudes at the
            // 172 Hz hop rate modulated every harmonic and put sidebands between them: the
            // inter-harmonic energy measured +5.4 dB against -39.7 dB for the old engine. A 20 ms
            // one-pole keeps the frame evolution (a few Hz) and removes the hop-rate sidebands.
            smoothed[k] += (amp[k] - smoothed[k]) * smoothCoef;
            v += smoothed[k] * y;
        }
        return (float) v;
    }

    private static double interp(double[] f, double[] v, double x) {
        int n = Math.min(f.length, v.length);
        if (n == 0) return -90.0;
        if (x <= f[0]) return v[0];
        if (x >= f[n - 1]) return v[n - 1];
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) { int m = (lo + hi) >>> 1; if (f[m] <= x) lo = m; else hi = m; }
        double t = Math.log(x / f[lo]) / Math.log(f[hi] / f[lo]);
        return v[lo] + (v[hi] - v[lo]) * t;
    }
}