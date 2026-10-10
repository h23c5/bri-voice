package com.dsh.v4tune.synth;

/**
 * Klatt-style cascade of five resonators plus two anti-resonators, with a parallel
 * fricative-noise branch.
 *
 * <p>Every resonator uses the classic Klatt coefficients
 * {@code a = 1-b-c, b = 2*exp(-pi*bw/sr)*cos(2*pi*f/sr), c = -exp(-2*pi*bw/sr)} in
 * transposed direct form 2. That normalisation puts the gain at exactly 1 at DC; the gain
 * at the resonance itself is higher and grows with the formant frequency and with how
 * narrow the bandwidth is (a 300 Hz formant with a 60 Hz bandwidth peaks near 5, a 2.5 kHz
 * one near 21), so the cascade output is scaled by a smoothed normalisation gain that
 * brings the cascade peak response to 1. Anti-resonators are the matching inverse (FIR)
 * sections, normalised to unity DC gain. Resonator and bandpass states are clamped, so
 * fast formant movement (a setFormant() call every 5 ms) cannot blow the filter up.</p>
 *
 * <p>filter() allocates nothing and is safe against NaN input and NaN parameters.</p>
 */
public class FormantFilter {

    private static final int NF = 5;      // resonators
    private static final int NZ = 2;      // anti-resonators
    private static final int NB = 3;      // parallel fricative bands
    /** State clamp; the filters normally run far below this. */
    private static final double LIMIT = 16.0;
    /**
     * Output trim. The cascade is normalised to unity peak gain, which with a unit-peak
     * glottal pulse leaves a typical vowel peaking near 0.3; this brings it to roughly
     * half scale while keeping even a high-pitched sweep inside +-1.
     */
    private static final double OUTPUT_GAIN = 1.5;
    /** Split frequency of the extra first-order tilt. */
    private static final double TILT_HZ = 700.0;

    private double sr = 44100.0;

    // --- cascade resonators -------------------------------------------------
    private final double[] fFreq = new double[NF];
    private final double[] fBw = new double[NF];
    private final double[] fA = new double[NF];
    private final double[] fB = new double[NF];
    private final double[] fC = new double[NF];
    private final double[] fS1 = new double[NF];
    private final double[] fS2 = new double[NF];

    // --- anti-resonators (nasal zeros) --------------------------------------
    private final double[] zFreq = new double[NZ];
    private final double[] zBw = new double[NZ];
    private final double[] zN0 = new double[NZ];
    private final double[] zN1 = new double[NZ];
    private final double[] zN2 = new double[NZ];
    private final double[] zS1 = new double[NZ];
    private final double[] zS2 = new double[NZ];

    // --- parallel fricative bandpasses --------------------------------------
    private final double[] nFreq = new double[NB];
    private final double[] nBw = new double[NB];
    private final double[] nGain = new double[NB];
    private final double[] nN0 = new double[NB];
    private final double[] nN1 = new double[NB];
    private final double[] nN2 = new double[NB];
    private final double[] nD1 = new double[NB];
    private final double[] nD2 = new double[NB];
    private final double[] nS1 = new double[NB];
    private final double[] nS2 = new double[NB];
    private double fricGain = 0.0;
    private boolean anyFricBand = false;

    // --- extra tilt ---------------------------------------------------------
    private double tilt = 0.0;
    private double tiltP = 0.0;
    private double tiltLp = 0.0;

    // --- output normalisation ----------------------------------------------
    /** Probe frequencies used to find the peak of the cascade response. */
    private double[] probe = new double[0];
    /** Gain currently applied (glides towards normTarget, so edits never click). */
    private double normGain = 1.0;
    private double normTarget = 1.0;
    private double normCoef = 1.0;
    /** Response parameters the current normTarget was computed from. */
    private final double[] normFreq = new double[NF];
    private final double[] normBw = new double[NF];
    private final double[] normZf = new double[NZ];
    private final double[] normZb = new double[NZ];

    public FormantFilter() {
        setSampleRate(44100.0);
        // Klatt's neutral defaults
        setFormant(0, 500.0, 60.0);
        setFormant(1, 1500.0, 90.0);
        setFormant(2, 2500.0, 120.0);
        setFormant(3, 3500.0, 150.0);
        setFormant(4, 4500.0, 200.0);
        setZero(0, 0.0, 0.0);
        setZero(1, 0.0, 0.0);
        setFricBand(0, 0.0, 0.0, 0.0);
        setFricBand(1, 0.0, 0.0, 0.0);
        setFricBand(2, 0.0, 0.0, 0.0);
        reset();
    }

    /** Sets the sample rate and recomputes every coefficient. */
    public void setSampleRate(double sr) {
        if (Double.isNaN(sr) || sr < 4000.0) sr = 44100.0;
        if (sr > 384000.0) sr = 384000.0;
        this.sr = sr;
        tiltP = Math.exp(-2.0 * Math.PI * TILT_HZ / sr);
        normCoef = 1.0 - Math.exp(-1.0 / (0.005 * sr));      // 5 ms gain glide
        buildProbe();
        for (int i = 0; i < NF; i++) {
            if (fFreq[i] <= 0.0 && fA[i] == 1.0) continue;   // disabled
            setFormant(i, fFreq[i], fBw[i]);
        }
        for (int i = 0; i < NZ; i++) setZero(i, zFreq[i], zBw[i]);
        for (int i = 0; i < NB; i++) setFricBand(i, nFreq[i], nBw[i], nGain[i]);
        updateNormNow();
        normGain = normTarget;
    }

    /** Logarithmic probe grid, used to locate the cascade peak gain. */
    private void buildProbe() {
        int n = 48;
        probe = new double[n];
        double lo = 60.0;
        double hi = Math.min(0.45 * sr, 12000.0);
        for (int i = 0; i < n; i++) {
            probe[i] = lo * Math.pow(hi / lo, (double) i / (n - 1));
        }
    }

    /**
     * Magnitude of the cascade (resonators and anti-resonators) at f, in linear units.
     * Coefficients are the ones in force right now.
     */
    private double cascadeGainAt(double f) {
        double w = 2.0 * Math.PI * f / sr;
        double cw = Math.cos(w), sw = Math.sin(w);
        double c2 = 2.0 * cw * cw - 1.0, s2 = 2.0 * sw * cw;
        double mag = 1.0;
        for (int i = 0; i < NF; i++) {
            if (fB[i] == 0.0 && fC[i] == 0.0) continue;      // disabled section
            double re = 1.0 - fB[i] * cw - fC[i] * c2;
            double im = fB[i] * sw + fC[i] * s2;
            double d = Math.sqrt(re * re + im * im);
            if (!(d > 1.0e-12)) d = 1.0e-12;
            mag *= fA[i] / d;
        }
        for (int i = 0; i < NZ; i++) {
            if (zN0[i] == 1.0 && zN1[i] == 0.0 && zN2[i] == 0.0) continue;
            double re = zN0[i] + zN1[i] * cw + zN2[i] * c2;
            double im = -(zN1[i] * sw + zN2[i] * s2);
            mag *= Math.sqrt(re * re + im * im);
        }
        return mag;
    }

    /**
     * Recomputes the normalisation gain: the cascade peak gain is brought to 1 so the
     * output level is bounded and independent of how sharp the current formants are.
     */
    private void updateNormNow() {
        for (int i = 0; i < NF; i++) { normFreq[i] = fFreq[i]; normBw[i] = fBw[i]; }
        for (int i = 0; i < NZ; i++) { normZf[i] = zFreq[i]; normZb[i] = zBw[i]; }

        double max = 1.0;                                     // DC gain is 1 by design
        for (int i = 0; i < probe.length; i++) {
            double g = cascadeGainAt(probe[i]);
            if (g > max) max = g;
        }
        for (int i = 0; i < NF; i++) {
            if (fFreq[i] > 0.0 && cascadeGainAt(fFreq[i]) > max) max = cascadeGainAt(fFreq[i]);
        }
        if (!(max > 1.0e-6) || max > 1.0e6) max = 1.0;
        normTarget = 1.0 / max;
    }

    /**
     * Recomputes the normalisation only when the response actually changed. Renderers call
     * setFormant() at control rate, often with unchanged values; skipping those keeps the
     * control path cheap without letting the gain depend on call history.
     */
    private void updateNorm() {
        for (int i = 0; i < NF; i++) {
            if (fFreq[i] != normFreq[i] || fBw[i] != normBw[i]) { updateNormNow(); return; }
        }
        for (int i = 0; i < NZ; i++) {
            if (zFreq[i] != normZf[i] || zBw[i] != normZb[i]) { updateNormNow(); return; }
        }
    }

    /** Clears every filter state (keeps the coefficients). */
    public void reset() {
        for (int i = 0; i < NF; i++) { fS1[i] = 0.0; fS2[i] = 0.0; }
        for (int i = 0; i < NZ; i++) { zS1[i] = 0.0; zS2[i] = 0.0; }
        for (int i = 0; i < NB; i++) { nS1[i] = 0.0; nS2[i] = 0.0; }
        tiltLp = 0.0;
    }

    /**
     * Sets cascade formant i (0..4 -> F1..F5). A frequency &lt;= 0 bypasses the section,
     * which lets a caller mute a formant without disturbing the others.
     */
    public void setFormant(int i, double freq, double bw) {
        if (i < 0 || i >= NF) return;
        if (Double.isNaN(freq)) freq = 500.0 * (i + 1);
        if (Double.isNaN(bw) || bw < 1.0) bw = 60.0;
        if (freq <= 0.0) {                      // disabled: pass-through
            fFreq[i] = 0.0;
            fBw[i] = bw;
            fA[i] = 1.0; fB[i] = 0.0; fC[i] = 0.0;
            fS1[i] = 0.0; fS2[i] = 0.0;
            updateNorm();
            return;
        }
        double maxF = sr * 0.45;
        if (freq > maxF) freq = maxF;
        if (freq < 20.0) freq = 20.0;
        if (bw > maxF) bw = maxF;
        fFreq[i] = freq;
        fBw[i] = bw;

        double r = Math.exp(-Math.PI * bw / sr);
        double w = 2.0 * Math.PI * freq / sr;
        double b = 2.0 * r * Math.cos(w);
        double c = -r * r;
        fA[i] = 1.0 - b - c;
        fB[i] = b;
        fC[i] = c;
        updateNorm();
    }

    /**
     * Sets nasal anti-resonator i (0..1). A frequency &lt;= 0 disables it. The section is
     * the exact inverse of the matching resonator, normalised to unity DC gain; note
     * that a deep zero therefore raises the overall level between the formants.
     */
    public void setZero(int i, double freq, double bw) {
        if (i < 0 || i >= NZ) return;
        if (Double.isNaN(freq) || freq <= 0.0) {
            zFreq[i] = 0.0;
            zBw[i] = 0.0;
            zN0[i] = 1.0; zN1[i] = 0.0; zN2[i] = 0.0;
            zS1[i] = 0.0; zS2[i] = 0.0;
            updateNorm();
            return;
        }
        if (Double.isNaN(bw) || bw < 1.0) bw = 60.0;
        double maxF = sr * 0.45;
        if (freq > maxF) freq = maxF;
        if (freq < 20.0) freq = 20.0;
        if (bw > maxF) bw = maxF;
        zFreq[i] = freq;
        zBw[i] = bw;

        double r = Math.exp(-Math.PI * bw / sr);
        double w = 2.0 * Math.PI * freq / sr;
        double b = 2.0 * r * Math.cos(w);
        double c = -r * r;
        double a0 = 1.0 - b - c;                 // = |denominator| at the resonance
        if (!(a0 > 1.0e-12)) a0 = 1.0e-12;
        double g = 1.0 / a0;
        zN0[i] = g;
        zN1[i] = -b * g;
        zN2[i] = -c * g;
        zS1[i] = 0.0;
        zS2[i] = 0.0;
        updateNorm();
    }

    /** Extra first-order tilt, -1..1; negative is darker, 0 is flat. */
    public void setTilt(double tilt) {
        if (Double.isNaN(tilt)) tilt = 0.0;
        if (tilt < -1.0) tilt = -1.0;
        if (tilt > 1.0) tilt = 1.0;
        this.tilt = tilt;
    }

    /** Gain of the parallel noise branch, 0..1. */
    public void setFricGain(double g) {
        if (Double.isNaN(g) || g < 0.0) g = 0.0;
        if (g > 1.0) g = 1.0;
        fricGain = g;
    }

    /**
     * Sets parallel fricative band i (0..2): a bandpass at {@code freq} with the given
     * bandwidth and a peak gain of {@code gain}. A frequency &lt;= 0 disables the band.
     */
    public void setFricBand(int i, double freq, double bw, double gain) {
        if (i < 0 || i >= NB) return;
        if (Double.isNaN(freq) || freq <= 0.0) {
            nFreq[i] = 0.0; nBw[i] = 0.0; nGain[i] = 0.0;
            nN0[i] = 0.0; nN1[i] = 0.0; nN2[i] = 0.0;
            nD1[i] = 0.0; nD2[i] = 0.0;
            nS1[i] = 0.0; nS2[i] = 0.0;
            anyFricBand = nN0[0] != 0.0 || nN0[1] != 0.0 || nN0[2] != 0.0;
            return;
        }
        if (Double.isNaN(bw) || bw < 1.0) bw = 100.0;
        if (Double.isNaN(gain)) gain = 0.0;
        double maxF = sr * 0.45;
        if (freq > maxF) freq = maxF;
        if (freq < 20.0) freq = 20.0;
        if (bw > maxF) bw = maxF;
        nFreq[i] = freq;
        nBw[i] = bw;
        nGain[i] = gain;

        double r = Math.exp(-Math.PI * bw / sr);
        double w = 2.0 * Math.PI * freq / sr;
        double sinw = Math.sin(w);
        double den = 1.0 - 2.0 * r * Math.cos(w) + r * r;
        if (!(den > 1.0e-12)) den = 1.0e-12;
        double peak = 2.0 * Math.abs(sinw) / den;     // gain of (1 - z^-2)/den at w
        if (!(peak > 1.0e-9)) peak = 1.0;
        double g0 = gain / peak;
        nN0[i] = g0;
        nN1[i] = 0.0;
        nN2[i] = -g0;
        nD1[i] = 2.0 * r * Math.cos(w);
        nD2[i] = -r * r;
        nS1[i] = 0.0;
        nS2[i] = 0.0;
        anyFricBand = nN0[0] != 0.0 || nN0[1] != 0.0 || nN0[2] != 0.0;
    }

    /**
     * One sample through the filter.
     *
     * @param x       voiced excitation
     * @param noiseIn fricative noise excitation for the parallel branch
     * @return the filtered sample (always finite)
     */
    public float filter(float x, float noiseIn) {
        double v = x;
        if (!(v > -1.0e9 && v < 1.0e9)) v = 0.0;

        // extra first-order tilt (state always tracks, so enabling it never clicks)
        tiltLp += (1.0 - tiltP) * (v - tiltLp);
        if (tilt != 0.0) v = tiltLp + (1.0 + tilt) * (v - tiltLp);

        // cascade of resonators, transposed direct form 2
        for (int i = 0; i < NF; i++) {
            double s1 = fS1[i];
            double s2 = fS2[i];
            double y = fA[i] * v + s1;
            s1 = fB[i] * y + s2;
            s2 = fC[i] * y;
            if (s1 > LIMIT) s1 = LIMIT; else if (s1 < -LIMIT) s1 = -LIMIT;
            if (s2 > LIMIT) s2 = LIMIT; else if (s2 < -LIMIT) s2 = -LIMIT;
            fS1[i] = s1;
            fS2[i] = s2;
            v = y;
        }

        // anti-resonators (FIR inverse sections)
        for (int i = 0; i < NZ; i++) {
            double s1 = zS1[i];
            double s2 = zS2[i];
            double y = zN0[i] * v + s1;
            s1 = zN1[i] * v + s2;
            s2 = zN2[i] * v;
            zS1[i] = s1;
            zS2[i] = s2;
            v = y;
        }

        // level normalisation (smoothed, so formant edits do not step the gain)
        normGain += (normTarget - normGain) * normCoef;
        v *= normGain * OUTPUT_GAIN;

        // parallel fricative branch
        if (anyFricBand && fricGain > 0.0) {
            double n = noiseIn;
            if (!(n > -1.0e9 && n < 1.0e9)) n = 0.0;
            double f = 0.0;
            for (int i = 0; i < NB; i++) {
                if (nN0[i] == 0.0 && nN2[i] == 0.0) continue;
                double s1 = nS1[i];
                double s2 = nS2[i];
                double y = nN0[i] * n + s1;
                s1 = nN1[i] * n + nD1[i] * y + s2;
                s2 = nN2[i] * n + nD2[i] * y;
                if (s1 > LIMIT) s1 = LIMIT; else if (s1 < -LIMIT) s1 = -LIMIT;
                if (s2 > LIMIT) s2 = LIMIT; else if (s2 < -LIMIT) s2 = -LIMIT;
                nS1[i] = s1;
                nS2[i] = s2;
                f += y;
            }
            v += f * fricGain;
        }

        if (!(v > -1.0e6 && v < 1.0e6)) {     // NaN/Inf escape hatch
            reset();
            return 0.0f;
        }
        return (float) v;
    }
}
