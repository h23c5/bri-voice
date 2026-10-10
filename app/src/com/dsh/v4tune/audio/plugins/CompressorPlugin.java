package com.dsh.v4tune.audio.plugins;

import com.dsh.v4tune.audio.Plugin;

/**
 * Feed-forward peak compressor with a stereo-linked detector.
 *
 * <p>Parameters: 0 = threshold in dBFS, 1 = ratio (n:1), 2 = attack in ms, 3 = release in ms,
 * 4 = makeup gain in dB. The detector takes max(|L|, |R|), converts it to dB, applies the static
 * curve (gain reduction = overshoot * (1 - 1/ratio)) and smooths the reduction in the dB domain
 * with one-pole attack / release coefficients, so the timing is independent of the signal level
 * and both channels always get exactly the same gain (the stereo image never moves).</p>
 *
 * <p>Plain Java, no allocation inside process().</p>
 */
public class CompressorPlugin implements Plugin {

    public static final int P_THRESHOLD_DB = 0;
    public static final int P_RATIO = 1;
    public static final int P_ATTACK_MS = 2;
    public static final int P_RELEASE_MS = 3;
    public static final int P_MAKEUP_DB = 4;

    private static final double MIN_LEVEL = 1e-12;   // -240 dBFS floor for log()

    private final double[] params = { -18.0, 4.0, 10.0, 120.0, 0.0 };

    private int sampleRate = 44100;
    private double attackCoef = 1.0;
    private double releaseCoef = 1.0;
    private double gainReductionDb = 0.0;
    private boolean dirty = true;

    public CompressorPlugin() {
        updateCoefficients();
    }

    public String id() { return "builtin.compressor"; }

    public String name() { return "Compressor"; }

    public int paramCount() { return 5; }

    public String paramName(int i) {
        switch (i) {
            case P_THRESHOLD_DB: return "Threshold";
            case P_RATIO: return "Ratio";
            case P_ATTACK_MS: return "Attack";
            case P_RELEASE_MS: return "Release";
            case P_MAKEUP_DB: return "Makeup";
            default: return "?";
        }
    }

    public double paramMin(int i) {
        switch (i) {
            case P_THRESHOLD_DB: return -60.0;
            case P_RATIO: return 1.0;
            case P_ATTACK_MS: return 0.1;
            case P_RELEASE_MS: return 5.0;
            case P_MAKEUP_DB: return 0.0;
            default: return 0.0;
        }
    }

    public double paramMax(int i) {
        switch (i) {
            case P_THRESHOLD_DB: return 0.0;
            case P_RATIO: return 20.0;
            case P_ATTACK_MS: return 200.0;
            case P_RELEASE_MS: return 2000.0;
            case P_MAKEUP_DB: return 24.0;
            default: return 0.0;
        }
    }

    public double paramDefault(int i) {
        switch (i) {
            case P_THRESHOLD_DB: return -18.0;
            case P_RATIO: return 4.0;
            case P_ATTACK_MS: return 10.0;
            case P_RELEASE_MS: return 120.0;
            case P_MAKEUP_DB: return 0.0;
            default: return 0.0;
        }
    }

    public void setParam(int i, double v) {
        if (i < 0 || i >= params.length) return;
        double lo = paramMin(i), hi = paramMax(i);
        if (v != v) v = lo;
        if (v < lo) v = lo;
        if (v > hi) v = hi;
        if (params[i] != v) {
            params[i] = v;
            if (i == P_ATTACK_MS || i == P_RELEASE_MS) dirty = true;
        }
    }

    public double getParam(int i) {
        return (i >= 0 && i < params.length) ? params[i] : 0.0;
    }

    public void prepare(int sampleRate, int maxFrames) {
        if (sampleRate < 8000) sampleRate = 8000;
        this.sampleRate = sampleRate;
        dirty = true;
        updateCoefficients();
    }

    public void reset() {
        gainReductionDb = 0.0;
    }

    /** Current gain reduction in dB (>= 0). Useful for a meter. */
    public double currentGainReductionDb() {
        return gainReductionDb;
    }

    public void process(float[] l, float[] r, int frames) {
        if (l == null || r == null || frames <= 0) return;
        int n = frames;
        if (n > l.length) n = l.length;
        if (n > r.length) n = r.length;
        if (n <= 0) return;
        if (dirty) updateCoefficients();

        double threshold = params[P_THRESHOLD_DB];
        double slope = 1.0 - 1.0 / params[P_RATIO];
        double makeup = params[P_MAKEUP_DB];
        double gr = gainReductionDb;

        for (int i = 0; i < n; i++) {
            double al = l[i] < 0 ? -l[i] : l[i];
            double ar = r[i] < 0 ? -r[i] : r[i];
            double peak = al > ar ? al : ar;
            double levelDb = 20.0 * Math.log10(peak + MIN_LEVEL);
            double over = levelDb - threshold;
            double target = over > 0.0 ? over * slope : 0.0;
            if (target > gr) gr += (target - gr) * attackCoef;
            else gr += (target - gr) * releaseCoef;
            if (gr < 0.0) gr = 0.0;
            float g = (float) Math.pow(10.0, (makeup - gr) / 20.0);
            l[i] *= g;
            r[i] *= g;
        }
        gainReductionDb = gr;
    }

    private void updateCoefficients() {
        double sr = sampleRate;
        double atk = Math.max(0.01, params[P_ATTACK_MS]) * 0.001 * sr;
        double rel = Math.max(0.1, params[P_RELEASE_MS]) * 0.001 * sr;
        attackCoef = 1.0 - Math.exp(-1.0 / atk);
        releaseCoef = 1.0 - Math.exp(-1.0 / rel);
        dirty = false;
    }
}
