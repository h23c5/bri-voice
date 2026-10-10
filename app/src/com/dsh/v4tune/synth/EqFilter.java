package com.dsh.v4tune.synth;

/**
 * Voice-pack equalizer: a cascade of RBJ peaking biquads, one per (frequency, gain) point of a
 * pack's {@code shelf} curve (points ascending, 0..{@value #MAX_POINTS} points, Q = 0.9, gain
 * clamped to +/-12 dB, frequency clamped to 20 Hz..20 kHz).
 *
 * <p>Peaking filters overlap, so a cascade built with the requested gains one by one misses its own
 * curve where the points are close together (a 0 dB point between two -6 dB points would come out
 * about 3 dB low). The design therefore first fits the per-stage gains with a damped fixed-point
 * iteration, so the cascade response at every requested frequency equals the requested gain.
 * {@link #responseDb(double[], double[], double, double)} reports the response of that fitted
 * cascade, i.e. exactly what is heard.</p>
 *
 * <p>The target coefficients are designed only when the curve or the sample rate changes. Between
 * those events {@link #filter(float)} merely adds a precomputed per-sample increment, i.e. a 5 ms
 * linear glide from the current filter to the new one. That keeps a curve change click free (the
 * output and its slope stay continuous) and bounds the per-sample work to five additions per stage.
 * A straight path between two stable biquads is itself stable - the stability region in the
 * (a1, a2) plane is a convex triangle - so a glide can never destabilise the cascade.</p>
 *
 * <p>{@link #filter(float)} is allocation free and safe on an audio thread. State is double
 * precision direct form 1; NaN/Inf input, state or output resets the filter instead of poisoning
 * the signal path.</p>
 */
public class EqFilter {

    /** Maximum number of (freq, gain) points; extra points are ignored. */
    public static final int MAX_POINTS = 12;

    /** Peaking-filter quality factor. */
    private static final double Q = 0.9;
    /** Gain clamp in dB, for the requested and for the fitted stage gains. */
    private static final double MAX_GAIN_DB = 12.0;
    /** Frequency clamp in Hz. */
    private static final double MIN_HZ = 20.0, MAX_HZ = 20000.0;
    /** Length of the transition when the curve or the sample rate changes. */
    private static final double GLIDE_SECONDS = 0.005;
    /** Damped fixed-point fitting of the per-stage gains. */
    private static final int FIT_ITERATIONS = 20;
    private static final double FIT_DAMP = 0.8, FIT_EPSILON_DB = 1.0e-4;

    private static final int FALLBACK_SR = 44100;

    /** One coefficient set: b0,b1,b2,a1,a2 per stage (a0 normalised away). */
    private static final class Coef {
        final double[] b0 = new double[MAX_POINTS];
        final double[] b1 = new double[MAX_POINTS];
        final double[] b2 = new double[MAX_POINTS];
        final double[] a1 = new double[MAX_POINTS];
        final double[] a2 = new double[MAX_POINTS];

        /** Pass-through: y = x. */
        void identity() {
            java.util.Arrays.fill(b0, 1.0);
            java.util.Arrays.fill(b1, 0.0);
            java.util.Arrays.fill(b2, 0.0);
            java.util.Arrays.fill(a1, 0.0);
            java.util.Arrays.fill(a2, 0.0);
        }

        void copyFrom(Coef o) {
            System.arraycopy(o.b0, 0, b0, 0, MAX_POINTS);
            System.arraycopy(o.b1, 0, b1, 0, MAX_POINTS);
            System.arraycopy(o.b2, 0, b2, 0, MAX_POINTS);
            System.arraycopy(o.a1, 0, a1, 0, MAX_POINTS);
            System.arraycopy(o.a2, 0, a2, 0, MAX_POINTS);
        }
    }

    private double sampleRate = FALLBACK_SR;

    private final Coef cur = new Coef(), tgt = new Coef(), stp = new Coef();
    private final double[] state = new double[MAX_POINTS * 4];      // x1,x2,y1,y2 per stage
    private int stages = 0, targetStages = 0, glideLeft = 0, glideLen = 1;

    private final double[] srcFreqs = new double[MAX_POINTS];
    private final double[] srcGains = new double[MAX_POINTS];
    private final double[] fitFreqs = new double[MAX_POINTS];
    private final double[] fitTargets = new double[MAX_POINTS];
    private final double[] fitGains = new double[MAX_POINTS];
    private int srcCount = 0;
    private boolean bypassed = true;

    /** Sets the sample rate in Hz (clamped to 8000..192000) and glides to the redesigned curve. */
    public void setSampleRate(double sr) {
        if (!finite(sr)) return;
        double v = clamp(sr, 8000.0, 192000.0);
        if (v == sampleRate) return;
        sampleRate = v;
        if (srcCount > 0 || stages > 0) applyDesign();
    }

    /** Clears the filter state and snaps to the configured curve (call on seek/stream restart). */
    public void reset() {
        java.util.Arrays.fill(state, 0.0);
        cur.copyFrom(tgt);
        stages = targetStages;
        glideLeft = 0;
    }

    /**
     * Configures the cascade from (freqHz, gainDb) points. The arrays may be null, empty or of
     * different lengths; an empty curve means bypass. At most {@link #MAX_POINTS} points are used,
     * non-finite entries are dropped, values are clamped and the points are sorted ascending
     * (duplicate frequencies keep the last gain). The change is applied through a short glide, so
     * it is click free.
     */
    public void setCurve(double[] freqs, double[] gainsDb) {
        int n = 0;
        if (freqs != null && gainsDb != null) {
            int m = Math.min(freqs.length, gainsDb.length);
            double fmax = Math.min(MAX_HZ, sampleRate * 0.45);
            for (int i = 0; i < m && n < MAX_POINTS; i++) {
                double f = freqs[i], g = gainsDb[i];
                if (!finite(f) || !finite(g) || f <= 0.0) continue;
                srcFreqs[n] = clamp(f, MIN_HZ, fmax);
                srcGains[n] = clamp(g, -MAX_GAIN_DB, MAX_GAIN_DB);
                n++;
            }
        }
        // stable insertion sort by frequency
        for (int i = 1; i < n; i++) {
            double f = srcFreqs[i], g = srcGains[i];
            int j = i - 1;
            while (j >= 0 && srcFreqs[j] > f) {
                srcFreqs[j + 1] = srcFreqs[j];
                srcGains[j + 1] = srcGains[j];
                j--;
            }
            srcFreqs[j + 1] = f;
            srcGains[j + 1] = g;
        }
        // drop duplicate frequencies, last one wins
        int u = 0;
        for (int i = 0; i < n; i++) {
            if (u > 0 && srcFreqs[i] == srcFreqs[u - 1]) {
                srcGains[u - 1] = srcGains[i];
                continue;
            }
            srcFreqs[u] = srcFreqs[i];
            srcGains[u] = srcGains[i];
            u++;
        }
        srcCount = u;
        bypassed = (u == 0);
        applyDesign();
    }

    /** True when the configured curve is empty (the filter passes the signal through). */
    public boolean isBypassed() {
        return bypassed;
    }

    /** Processes one sample. Allocation free; click free across curve changes. */
    public float filter(float x) {
        double in = x;
        if (!finite(in)) in = 0.0;
        if (stages == 0 && glideLeft <= 0) return (float) in;      // bypassed and settled

        if (glideLeft > 0) stepGlide();

        double[] cb0 = cur.b0, cb1 = cur.b1, cb2 = cur.b2, ca1 = cur.a1, ca2 = cur.a2;
        double[] s = state;
        double y = in;
        for (int i = 0, q = 0; i < stages; i++, q += 4) {
            double x1 = s[q], x2 = s[q + 1], y1 = s[q + 2], y2 = s[q + 3];
            double o = cb0[i] * y + cb1[i] * x1 + cb2[i] * x2 - ca1[i] * y1 - ca2[i] * y2;
            s[q] = y;
            s[q + 1] = x1;
            s[q + 2] = o;
            s[q + 3] = y1;
            y = o;
        }
        if (!finite(y)) {
            reset();
            y = in;
        }
        return (float) y;
    }

    /**
     * Analytic magnitude response of the configured cascade at {@code testHz}, in dB
     * (0 dB for an empty curve). The stage gains are fitted exactly like {@link #setCurve} fits
     * them, so this is what the filter actually does. Used by tests and by the EQ editor UI.
     */
    public static double responseDb(double[] freqs, double[] gainsDb, double testHz, double sampleRate) {
        if (freqs == null || gainsDb == null || !finite(testHz)) return 0.0;
        int m = Math.min(freqs.length, gainsDb.length);
        if (m > MAX_POINTS) m = MAX_POINTS;
        if (m == 0) return 0.0;
        double fs = finite(sampleRate) ? clamp(sampleRate, 8000.0, 192000.0) : FALLBACK_SR;
        double fmax = Math.min(MAX_HZ, fs * 0.45);
        double[] f = new double[m];
        double[] g = new double[m];
        int n = 0;
        for (int i = 0; i < m; i++) {
            double fv = freqs[i], gv = gainsDb[i];
            if (!finite(fv) || !finite(gv) || fv <= 0.0) continue;
            f[n] = clamp(fv, MIN_HZ, fmax);
            g[n] = clamp(gv, -MAX_GAIN_DB, MAX_GAIN_DB);
            n++;
        }
        if (n == 0) return 0.0;
        double[] eff = new double[n];
        fitStageGains(f, g, n, fs, eff);
        if (maxErrorDb(f, g, g, n, fs) <= maxErrorDb(f, g, eff, n, fs)) {
            System.arraycopy(g, 0, eff, 0, n);                      // fitting made it worse: keep raw
        }
        double total = 0.0;
        for (int j = 0; j < n; j++) total += stageDb(f[j], eff[j], testHz, fs);
        return finite(total) ? total : 0.0;
    }

    // ---------------------------------------------------------------- internals

    /** Designs the stored curve as the new target and starts a glide towards it. */
    private void applyDesign() {
        design(tgt, srcCount);
        targetStages = srcCount;
        glideLen = Math.max(8, (int) Math.round(sampleRate * GLIDE_SECONDS));
        double move = 0.0;
        for (int i = 0; i < MAX_POINTS; i++) {
            double d0 = (tgt.b0[i] - cur.b0[i]) / glideLen;
            double d1 = (tgt.b1[i] - cur.b1[i]) / glideLen;
            double d2 = (tgt.b2[i] - cur.b2[i]) / glideLen;
            double d3 = (tgt.a1[i] - cur.a1[i]) / glideLen;
            double d4 = (tgt.a2[i] - cur.a2[i]) / glideLen;
            stp.b0[i] = d0;
            stp.b1[i] = d1;
            stp.b2[i] = d2;
            stp.a1[i] = d3;
            stp.a2[i] = d4;
            move += Math.abs(d0) + Math.abs(d1) + Math.abs(d2) + Math.abs(d3) + Math.abs(d4);
        }
        // stages that are being faded out must still be processed while they move
        if (srcCount > stages) stages = srcCount;
        if (move == 0.0) {                                         // nothing to do
            cur.copyFrom(tgt);
            stages = targetStages;
            glideLeft = 0;
            return;
        }
        glideLeft = glideLen;
    }

    /** Computes the target coefficients; stages >= n are pass-through. */
    private void design(Coef c, int n) {
        c.identity();
        if (n > MAX_POINTS) n = MAX_POINTS;
        double fs = sampleRate > 0 ? sampleRate : FALLBACK_SR;
        double fmax = Math.min(MAX_HZ, fs * 0.45);
        for (int i = 0; i < n; i++) {
            fitFreqs[i] = clamp(srcFreqs[i], MIN_HZ, fmax);
            fitTargets[i] = clamp(srcGains[i], -MAX_GAIN_DB, MAX_GAIN_DB);
        }
        fitStageGains(fitFreqs, fitTargets, n, fs, fitGains);
        if (maxErrorDb(fitFreqs, fitTargets, fitTargets, n, fs) <= maxErrorDb(fitFreqs, fitTargets, fitGains, n, fs)) {
            System.arraycopy(fitTargets, 0, fitGains, 0, n);        // fitting made it worse: keep raw
        }
        double[] co = new double[5];
        for (int i = 0; i < n; i++) {
            peaking(fitFreqs[i], fitGains[i], fs, co);
            c.b0[i] = co[0];
            c.b1[i] = co[1];
            c.b2[i] = co[2];
            c.a1[i] = co[3];
            c.a2[i] = co[4];
        }
    }

    private void stepGlide() {
        double[] cb0 = cur.b0, cb1 = cur.b1, cb2 = cur.b2, ca1 = cur.a1, ca2 = cur.a2;
        double[] sb0 = stp.b0, sb1 = stp.b1, sb2 = stp.b2, sa1 = stp.a1, sa2 = stp.a2;
        for (int i = 0; i < stages; i++) {
            cb0[i] += sb0[i];
            cb1[i] += sb1[i];
            cb2[i] += sb2[i];
            ca1[i] += sa1[i];
            ca2[i] += sa2[i];
        }
        if (--glideLeft <= 0) {
            cur.copyFrom(tgt);
            stages = targetStages;
            glideLeft = 0;
        }
    }

    /**
     * Damped fixed-point fit of the per-stage gains: at every requested frequency the sum of the
     * dB responses of all other stages is subtracted from that stage's target gain. The requested
     * and the fitted gains are clamped to +/-12 dB, so an extreme curve may keep a small residual.
     */
    private static void fitStageGains(double[] fr, double[] tgtGains, int n, double fs, double[] out) {
        System.arraycopy(tgtGains, 0, out, 0, n);
        if (n < 2) return;                                          // no overlap to compensate
        double[] c1 = new double[n], s1 = new double[n], c2 = new double[n], s2 = new double[n];
        for (int i = 0; i < n; i++) {
            double w = 2.0 * Math.PI * fr[i] / fs;
            c1[i] = Math.cos(w);
            s1[i] = Math.sin(w);
            c2[i] = Math.cos(2.0 * w);
            s2[i] = Math.sin(2.0 * w);
        }
        double[] co = new double[n * 5];
        for (int it = 0; it < FIT_ITERATIONS; it++) {
            for (int j = 0; j < n; j++) peaking(fr[j], out[j], fs, co, j * 5);
            double move = 0.0;
            for (int i = 0; i < n; i++) {
                double sum = 0.0;
                for (int j = 0; j < n; j++) {
                    if (j != i) sum += evalStage(co, j * 5, c1[i], s1[i], c2[i], s2[i]);
                }
                double want = clamp(tgtGains[i] - sum, -MAX_GAIN_DB, MAX_GAIN_DB);
                double d = (want - out[i]) * FIT_DAMP;
                out[i] = clamp(out[i] + d, -MAX_GAIN_DB, MAX_GAIN_DB);
                if (Math.abs(d) > move) move = Math.abs(d);
            }
            if (move < FIT_EPSILON_DB) break;
        }
    }

    /** Largest |cascade response - target| over the requested frequencies, in dB. */
    private static double maxErrorDb(double[] fr, double[] tg, double[] gains, int n, double fs) {
        double[] co = new double[n * 5];
        for (int j = 0; j < n; j++) peaking(fr[j], gains[j], fs, co, j * 5);
        double worst = 0.0;
        for (int i = 0; i < n; i++) {
            double w = 2.0 * Math.PI * fr[i] / fs;
            double c1 = Math.cos(w), s1 = Math.sin(w), c2 = Math.cos(2.0 * w), s2 = Math.sin(2.0 * w);
            double sum = 0.0;
            for (int j = 0; j < n; j++) sum += evalStage(co, j * 5, c1, s1, c2, s2);
            double e = Math.abs(sum - tg[i]);
            if (e > worst) worst = e;
        }
        return worst;
    }

    /** dB magnitude of one peaking stage at {@code testHz}. */
    private static double stageDb(double f0, double gainDb, double testHz, double fs) {
        double w = 2.0 * Math.PI * testHz / fs;
        double[] co = new double[5];
        peaking(f0, gainDb, fs, co);
        return evalStage(co, 0, Math.cos(w), Math.sin(w), Math.cos(2.0 * w), Math.sin(2.0 * w));
    }

    /** RBJ peaking coefficients (b0,b1,b2,a1,a2), a0 normalised away. */
    private static void peaking(double f0, double gainDb, double fs, double[] out) {
        peaking(f0, gainDb, fs, out, 0);
    }

    private static void peaking(double f0, double gainDb, double fs, double[] out, int at) {
        double amp = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * f0 / fs;
        double alpha = Math.sin(w0) / (2.0 * Q);
        double a0 = 1.0 + alpha / amp;
        if (!finite(a0) || a0 == 0.0) a0 = 1.0;
        double cw = Math.cos(w0);
        out[at] = (1.0 + alpha * amp) / a0;
        out[at + 1] = (-2.0 * cw) / a0;
        out[at + 2] = (1.0 - alpha * amp) / a0;
        out[at + 3] = (-2.0 * cw) / a0;
        out[at + 4] = (1.0 - alpha / amp) / a0;
    }

    /** |H(e^jw)| in dB for the coefficients at {@code at}, given cos/sin of w and 2w. */
    private static double evalStage(double[] co, int at, double c1, double s1, double c2, double s2) {
        double nre = co[at] + co[at + 1] * c1 + co[at + 2] * c2;
        double nim = -(co[at + 1] * s1 + co[at + 2] * s2);
        double dre = 1.0 + co[at + 3] * c1 + co[at + 4] * c2;
        double dim = -(co[at + 3] * s1 + co[at + 4] * s2);
        double num = nre * nre + nim * nim;
        double den = dre * dre + dim * dim;
        return den <= 1.0e-30 ? 0.0 : 10.0 * Math.log10(num / den);
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
