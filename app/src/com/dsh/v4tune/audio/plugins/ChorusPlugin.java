package com.dsh.v4tune.audio.plugins;

import com.dsh.v4tune.audio.Plugin;

/**
 * Chorus: a single modulated delay tap per channel, the left and right LFOs running 90 degrees
 * apart so a mono source widens into stereo.
 *
 * <p>Parameters: 0 = depth in ms (peak deviation of the delay), 1 = rate in Hz, 2 = dry/wet mix
 * (0 = dry only, 1 = wet only). The delay centre is 20 ms and the depth is clamped to 15 ms, so
 * the tap always stays in [5, 35] ms - inside the classic chorus range and never negative.</p>
 *
 * <p>Fractional delays use linear interpolation. Plain Java, no allocation inside process().</p>
 */
public class ChorusPlugin implements Plugin {

    public static final int P_DEPTH_MS = 0;
    public static final int P_RATE_HZ = 1;
    public static final int P_MIX = 2;

    /** Centre delay in ms; the LFO swings +/- depth around it. */
    public static final double BASE_DELAY_MS = 20.0;
    /** Hard cap on the modulation depth in ms (keeps the tap at 5..35 ms). */
    public static final double MAX_DEPTH_MS = 15.0;

    private final double[] params = { 4.0, 0.8, 0.5 };

    private int sampleRate = 44100;
    private float[] bufL = new float[1];
    private float[] bufR = new float[1];
    private int writeL;
    private int writeR;
    private long timeSamples;

    public ChorusPlugin() {
        prepare(44100, 0);
    }

    public String id() { return "builtin.chorus"; }

    public String name() { return "Chorus"; }

    public int paramCount() { return 3; }

    public String paramName(int i) {
        switch (i) {
            case P_DEPTH_MS: return "Depth";
            case P_RATE_HZ: return "Rate";
            case P_MIX: return "Dry/Wet";
            default: return "?";
        }
    }

    public double paramMin(int i) {
        switch (i) {
            case P_DEPTH_MS: return 0.0;
            case P_RATE_HZ: return 0.01;
            case P_MIX: return 0.0;
            default: return 0.0;
        }
    }

    public double paramMax(int i) {
        switch (i) {
            case P_DEPTH_MS: return MAX_DEPTH_MS;
            case P_RATE_HZ: return 10.0;
            case P_MIX: return 1.0;
            default: return 0.0;
        }
    }

    public double paramDefault(int i) {
        switch (i) {
            case P_DEPTH_MS: return 4.0;
            case P_RATE_HZ: return 0.8;
            case P_MIX: return 0.5;
            default: return 0.0;
        }
    }

    public void setParam(int i, double v) {
        if (i < 0 || i >= params.length) return;
        double lo = paramMin(i), hi = paramMax(i);
        if (v != v) v = lo;
        if (v < lo) v = lo;
        if (v > hi) v = hi;
        params[i] = v;
    }

    public double getParam(int i) {
        return (i >= 0 && i < params.length) ? params[i] : 0.0;
    }

    public void prepare(int sampleRate, int maxFrames) {
        if (sampleRate < 8000) sampleRate = 8000;
        this.sampleRate = sampleRate;
        int len = (int) Math.ceil((BASE_DELAY_MS + MAX_DEPTH_MS) * 0.001 * sampleRate) + 4;
        bufL = new float[len];
        bufR = new float[len];
        writeL = 0;
        writeR = 0;
        timeSamples = 0L;
    }

    public void reset() {
        java.util.Arrays.fill(bufL, 0.0f);
        java.util.Arrays.fill(bufR, 0.0f);
        writeL = 0;
        writeR = 0;
        timeSamples = 0L;
    }

    public void process(float[] l, float[] r, int frames) {
        if (l == null || r == null || frames <= 0) return;
        int n = frames;
        if (n > l.length) n = l.length;
        if (n > r.length) n = r.length;
        if (n <= 0 || bufL.length < 4) return;

        double rate = params[P_RATE_HZ];
        double depthMs = params[P_DEPTH_MS];
        float mix = (float) params[P_MIX];
        float dry = 1.0f - mix;
        double sr = sampleRate;
        double baseSamples = BASE_DELAY_MS * 0.001 * sr;
        double depthSamples = depthMs * 0.001 * sr;
        double w = 2.0 * Math.PI * rate / sr;

        for (int i = 0; i < n; i++) {
            double phase = w * (timeSamples + i);
            double dL = baseSamples + depthSamples * Math.sin(phase);
            double dR = baseSamples + depthSamples * Math.sin(phase + Math.PI * 0.5);
            float xl = l[i];
            float xr = r[i];
            float wl = readFrac(bufL, writeL, dL);
            float wr = readFrac(bufR, writeR, dR);
            bufL[writeL] = xl;
            bufR[writeR] = xr;
            if (++writeL >= bufL.length) writeL = 0;
            if (++writeR >= bufR.length) writeR = 0;
            l[i] = xl * dry + wl * mix;
            r[i] = xr * dry + wr * mix;
        }
        timeSamples += n;
    }

    /** Current delay of the left tap in samples (for metering / tests). */
    public double currentDelaySamplesL() {
        double phase = 2.0 * Math.PI * params[P_RATE_HZ] * timeSamples / sampleRate;
        return BASE_DELAY_MS * 0.001 * sampleRate + params[P_DEPTH_MS] * 0.001 * sampleRate * Math.sin(phase);
    }

    /** Current delay of the right tap in samples (90 degrees from the left tap). */
    public double currentDelaySamplesR() {
        double phase = 2.0 * Math.PI * params[P_RATE_HZ] * timeSamples / sampleRate;
        return BASE_DELAY_MS * 0.001 * sampleRate
                + params[P_DEPTH_MS] * 0.001 * sampleRate * Math.sin(phase + Math.PI * 0.5);
    }

    // ------------------------------------------------------------------ internals

    /** Read the delay line at a fractional delay behind the newest written sample. */
    private static float readFrac(float[] buf, int writeIdx, double delaySamples) {
        int len = buf.length;
        double d = delaySamples;
        if (d < 1.0) d = 1.0;
        if (d > len - 2) d = len - 2;
        double pos = writeIdx - d;
        int i0 = (int) Math.floor(pos);
        double frac = pos - i0;
        int a = ((i0 % len) + len) % len;
        int b = (a + 1) % len;
        return (float) (buf[a] * (1.0 - frac) + buf[b] * frac);
    }
}
