package com.dsh.v4tune.synth;

/**
 * Glottal pulse + aspiration noise generator (Klatt-style source).
 *
 * <p>The pulse shape is a Rosenberg flow derivative (a bipolar pulse with a sharp
 * closure discontinuity, zero mean), evaluated from a 4096-entry table at 8x
 * oversampling and decimated with an 8-tap box average so the closure edge does not
 * alias badly. A two-pole lowpass whose cutoff follows the {@code tl} parameter supplies
 * the spectral tilt. Per-period jitter (FM) and shimmer (AM) come from a seeded
 * {@link Noise} generator, so a given call sequence always produces the same signal.</p>
 *
 * <p>All inputs are guarded: NaN, infinities and out-of-range values are clamped and the
 * output is always finite.</p>
 */
public class VoiceSource {

    /** Pulse-shape oversampling factor. */
    private static final int OS = 8;
    /** Pulse table resolution (entries per period, excluding the duplicated endpoint). */
    private static final int TABLE = 4096;
    /** Fixed seed: reset() always restarts the same deterministic stream. */
    private static final long SEED = 0x5DEECE66DBL;

    /** Peak relative frequency jitter (0.4%). */
    private static final double JITTER = 0.004;
    /** Peak relative amplitude shimmer (3%). */
    private static final double SHIMMER = 0.03;

    /** Overall pulse amplitude. */
    private static final double PULSE_GAIN = 1.0;
    /** Aspiration level at breath = 1 (relative to the pulse amplitude). */
    private static final double ASP_GAIN = 0.40;
    /** Corner of the one-pole highpass used to make the aspiration hissy. */
    private static final double ASP_HP_HZ = 1500.0;
    /** Spectral-tilt lowpass corners mapped from tl = 0 and tl = 1. */
    private static final double TILT_BRIGHT_HZ = 9000.0;
    private static final double TILT_DARK_HZ = 1500.0;

    private static final double MIN_F0 = 1.0;
    private static final double MAX_F0 = 4000.0;

    private double sr = 44100.0;
    private double invSr = 1.0 / 44100.0;

    private final Noise noise = new Noise(SEED);

    private final double[] pulse = new double[TABLE + 1];
    private double tableOq = -1.0;

    /** Phase accumulator in [0,1). */
    private double phase = 0.0;
    private boolean wrapped = false;

    private double jitter = 0.0;
    private double shimmer = 0.0;

    /** Two cascaded one-pole sections = the tilt lowpass. */
    private double tilt1 = 0.0;
    private double tilt2 = 0.0;
    private double tiltP = 0.0;
    private double tiltCut = -1.0;

    /** One-pole highpass state for the aspiration noise. */
    private double aspLp = 0.0;
    private double aspP = 0.0;
    private double aspCut = -1.0;

    public VoiceSource() {
        reset();
    }

    /** Sets the sample rate (usually called once, before the first sample()). */
    public void setSampleRate(double sr) {
        if (Double.isNaN(sr) || sr < 4000.0) sr = 44100.0;
        if (sr > 384000.0) sr = 384000.0;
        this.sr = sr;
        this.invSr = 1.0 / sr;
        this.tiltCut = -1.0;   // force coefficient refresh
        this.aspCut = -1.0;
    }

    /** Clears phase and filter state and restarts the deterministic noise stream. */
    public void reset() {
        phase = 0.0;
        wrapped = false;
        jitter = 0.0;
        shimmer = 0.0;
        tilt1 = 0.0;
        tilt2 = 0.0;
        aspLp = 0.0;
        noise.reset(SEED);
    }

    /**
     * Advances one sample.
     *
     * @param f0    fundamental in Hz (values &lt;= 0, NaN or infinity produce silence
     *              for the voiced part instead of NaNs)
     * @param oq    open quotient, 0.30..0.95 (0.55 = normal), clamped
     * @param tl    spectral tilt, 0 = bright .. 1 = very dark, clamped
     * @param breath aspiration amount 0..1, clamped
     * @return the excitation sample (voiced pulse + aspiration noise)
     */
    public float sample(double f0, double oq, double tl, double breath) {
        oq = clamp(oq, 0.55, 0.20, 0.98);
        tl = clamp(tl, 0.0, 0.0, 1.0);
        breath = clamp(breath, 0.0, 0.0, 1.0);

        if (tableOq < 0.0 || Math.abs(oq - tableOq) > 0.002) buildTable(oq);
        updateTilt(tl);
        updateAsp();

        wrapped = false;
        double pulseOut = 0.0;

        if (!Double.isNaN(f0) && !Double.isInfinite(f0) && f0 > 0.0) {
            double f = clamp(f0, 220.0, MIN_F0, MAX_F0);
            double inc = f * (1.0 + jitter) * invSr;
            if (inc > 0.5) inc = 0.5;

            phase += inc;
            if (phase >= 1.0) {
                phase -= Math.floor(phase);
                wrapped = true;
                jitter = JITTER * noise.nextGauss();
                shimmer = SHIMMER * noise.nextGauss();
            }

            // 8x oversampled evaluation of the pulse shape across the elapsed sample.
            double p0 = phase - inc;
            double acc = 0.0;
            for (int k = 0; k < OS; k++) {
                double p = p0 + inc * (k + 0.5) / OS;
                p -= Math.floor(p);
                acc += lookup(p);
            }
            pulseOut = (acc / OS) * (1.0 + shimmer) * PULSE_GAIN;
        }

        // Spectral tilt: two cascaded one-pole lowpasses (12 dB/oct).
        tilt1 += (1.0 - tiltP) * (pulseOut - tilt1);
        tilt2 += (1.0 - tiltP) * (tilt1 - tilt2);
        double out = tilt2;

        if (breath > 0.0) {
            double n = noise.next();
            aspLp += (1.0 - aspP) * (n - aspLp);
            out += (n - aspLp) * (ASP_GAIN * breath);
        }

        if (!(out > -1.0e9 && out < 1.0e9)) {   // catches NaN and infinities
            out = 0.0;
            tilt1 = 0.0;
            tilt2 = 0.0;
            aspLp = 0.0;
        }
        return (float) out;
    }

    /**
     * Unvoiced (fricative/aspiration) excitation only: highpass-shaped white noise with
     * full-scale amplitude, scaled by {@code breath}. The phase accumulator is not
     * advanced and {@link #lastWrapped()} is left untouched.
     */
    public float noiseOnly(double breath) {
        breath = clamp(breath, 0.0, 0.0, 1.0);
        updateAsp();
        if (breath <= 0.0) return 0.0f;
        double n = noise.next();
        aspLp += (1.0 - aspP) * (n - aspLp);
        double out = (n - aspLp) * breath;
        if (!(out > -8.0 && out < 8.0)) out = 0.0;
        return (float) out;
    }

    /** True when the phase wrapped on the most recent {@link #sample} call. */
    public boolean lastWrapped() {
        return wrapped;
    }

    // ---------------------------------------------------------------- internals

    private static double clamp(double v, double dflt, double lo, double hi) {
        if (Double.isNaN(v)) return dflt;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /**
     * Builds the glottal flow-derivative table for the given open quotient.
     *
     * <p>Rosenberg opening phase (a half sine of area +1) followed by an LF-style
     * exponential return phase (area -1, exactly zero at the end of the open phase).
     * Both lobes carry the same area, so the pulse has no DC. The exponential return
     * matters: with two equal half-sine lobes the derivative spectrum cancels into a
     * -20 dB comb at multiples of ~3*f0, which would stamp notches into every vowel.
     * With the exponential return the source spectrum is smooth and falls at roughly
     * -6 dB/oct, leaving the formant structure cleanly visible. The table is scaled so
     * that the closure spike peaks at 1.</p>
     */
    private void buildTable(double oq) {
        double t1 = 0.65 * oq;         // opening phase, in periods
        double t2 = 0.35 * oq;         // closing (return) phase
        double tau = 0.35 * t2;        // return time constant
        double decay = Math.exp(-t2 / tau);
        double area = tau * (1.0 - decay) - t2 * decay;
        if (!(area > 1.0e-9)) area = 1.0e-9;
        double neg = 1.0 / area;                 // makes the return lobe area exactly -1
        double pos = Math.PI / (2.0 * t1);       // makes the opening lobe area exactly +1
        double peak = Math.max(pos, neg * (1.0 - decay));
        double scale = 1.0 / peak;

        for (int i = 0; i <= TABLE; i++) {
            double p = (double) i / TABLE;
            double v;
            if (p < t1) {
                v = pos * Math.sin(Math.PI * p / t1);
            } else if (p < t1 + t2) {
                v = -neg * (Math.exp(-(p - t1) / tau) - decay);
            } else {
                v = 0.0;
            }
            pulse[i] = v * scale;
        }
        tableOq = oq;
    }

    /** Linear-interpolated table read; {@code p} must be in [0,1). */
    private double lookup(double p) {
        double x = p * TABLE;
        int i = (int) x;
        if (i < 0) i = 0; else if (i >= TABLE) i = TABLE - 1;
        double fr = x - i;
        return pulse[i] + fr * (pulse[i + 1] - pulse[i]);
    }

    /** Maps tl 0..1 onto a 9 kHz..1.5 kHz two-pole lowpass (exponential in tl). */
    private void updateTilt(double tl) {
        double fc = TILT_BRIGHT_HZ * Math.pow(TILT_DARK_HZ / TILT_BRIGHT_HZ, tl);
        if (tiltCut > 0.0 && Math.abs(fc - tiltCut) < 0.01 * tiltCut) return;
        tiltCut = fc;
        tiltP = Math.exp(-2.0 * Math.PI * fc * invSr);
        if (tiltP > 0.9999) tiltP = 0.9999;
        if (tiltP < 0.0) tiltP = 0.0;
    }

    private void updateAsp() {
        double fc = ASP_HP_HZ;
        if (aspCut > 0.0) return;
        aspCut = fc;
        aspP = Math.exp(-2.0 * Math.PI * fc * invSr);
        if (aspP > 0.9999) aspP = 0.9999;
    }
}
