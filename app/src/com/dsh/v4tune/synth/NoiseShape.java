package com.dsh.v4tune.synth;

/**
 * Shapes a noise excitation with a measured spectral envelope (a bank of peaking biquads).
 *
 * <p>Unvoiced consonants used to be synthesised from a table of formants, so every fricative came
 * out of the same generic noise - /s/ and /x/ were indistinguishable even though the voicebank ships
 * their real spectra. This filter applies those measured band levels instead.</p>
 */
public final class NoiseShape {

    private static final int BANDS = 20;

    private double sampleRate = 44100.0;
    private final double[] fc = new double[BANDS];
    private final double[] b0 = new double[BANDS], b1 = new double[BANDS], b2 = new double[BANDS];
    private final double[] a1 = new double[BANDS], a2 = new double[BANDS];
    private final double[] z1 = new double[BANDS], z2 = new double[BANDS];
    private boolean active;

    public NoiseShape() {
        for (int i = 0; i < BANDS; i++) {
            fc[i] = 150.0 * Math.pow(16000.0 / 150.0, i / (double) (BANDS - 1));
        }
    }

    public void setSampleRate(double sr) {
        if (sr > 1000) sampleRate = sr;
    }

    public boolean isActive() { return active; }

    public void reset() {
        java.util.Arrays.fill(z1, 0.0);
        java.util.Arrays.fill(z2, 0.0);
        active = false;
    }

    /**
     * Configures the bank from a measured envelope on a log-spaced grid.
     *
     * @param freqsHz band centres of the measured curve (ascending)
     * @param gainsDb relative level per band, dB (0 = loudest)
     * @param rangeDb levels below {@code max - rangeDb} are left out of the bank
     */
    public void set(double[] freqsHz, double[] gainsDb, double rangeDb) {
        if (freqsHz == null || gainsDb == null || freqsHz.length < 2) { active = false; return; }
        double max = -1e9;
        for (double d : gainsDb) if (d > max) max = d;
        active = false;
        for (int i = 0; i < BANDS; i++) {
            double f = fc[i];
            double db = interp(freqsHz, gainsDb, f) - max;      // 0 dB at the loudest band
            if (db < -rangeDb) db = -rangeDb;
            double A = Math.pow(10.0, db / 40.0);
            double w0 = 2.0 * Math.PI * f / sampleRate;
            if (w0 <= 0 || w0 >= Math.PI * 0.98) { b0[i] = 1; b1[i] = 0; b2[i] = 0; a1[i] = 0; a2[i] = 0; continue; }
            double alpha = Math.sin(w0) / (2.0 * 1.4);
            double a0 = 1.0 + alpha / A;
            b0[i] = (1.0 + alpha * A) / a0;
            b1[i] = (-2.0 * Math.cos(w0)) / a0;
            b2[i] = (1.0 - alpha * A) / a0;
            a1[i] = (-2.0 * Math.cos(w0)) / a0;
            a2[i] = (1.0 - alpha / A) / a0;
            if (Math.abs(db) > 1.5) active = true;
        }
        // The filter state is deliberately NOT cleared here: set() runs at the control rate (about
        // every 5 ms) and zeroing z1/z2 on every call chopped the signal at 172 Hz, which is heard as
        // an electric buzz in front of the note. reset() clears it when the path is left.
    }

    private static double interp(double[] f, double[] v, double x) {
        int n = Math.min(f.length, v.length);
        if (x <= f[0]) return v[0];
        if (x >= f[n - 1]) return v[n - 1];
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) { int m = (lo + hi) >>> 1; if (f[m] <= x) lo = m; else hi = m; }
        double t = (x - f[lo]) / (f[hi] - f[lo]);
        return v[lo] + (v[hi] - v[lo]) * t;
    }

    /** one sample through the bank */
    public float process(float x) {
        if (!active) return x;
        double v = x;
        for (int i = 0; i < BANDS; i++) {
            if (b0[i] == 1.0 && b1[i] == 0.0 && a1[i] == 0.0) continue;
            double y = b0[i] * v + z1[i];
            z1[i] = b1[i] * v - a1[i] * y + z2[i];
            z2[i] = b2[i] * v - a2[i] * y;
            v = y;
        }
        return (float) v;
    }
}