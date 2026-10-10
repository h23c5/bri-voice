package com.dsh.v4tune.audio.plugins;

import com.dsh.v4tune.audio.Plugin;

/**
 * Freeverb / Schroeder style reverb: 4 parallel damped comb filters feeding 2 series allpass
 * filters per channel, with the classic 23 sample stereo spread between L and R.
 *
 * <p>Parameters: 0 = dry/wet mix (0 = bypass, 1 = fully wet), 1 = room size (comb feedback),
 * 2 = damping (low pass inside every comb). The comb delays are scaled from the 44.1 kHz
 * Freeverb tunings to the running sample rate, so the room geometry is rate independent.</p>
 *
 * <p>Plain Java, no allocation inside process().</p>
 */
public class ReverbPlugin implements Plugin {

    public static final int P_MIX = 0;
    public static final int P_ROOM_SIZE = 1;
    public static final int P_DAMPING = 2;

    /** Freeverb comb tunings in samples at 44.1 kHz (4 of the classic 8). */
    private static final int[] COMB_TUNING = { 1116, 1188, 1277, 1356 };
    /** Freeverb allpass tunings in samples at 44.1 kHz (2 of the classic 4). */
    private static final int[] ALLPASS_TUNING = { 556, 441 };
    private static final int STEREO_SPREAD = 23;
    private static final double ROOM_SCALE = 0.28;
    private static final double ROOM_OFFSET = 0.70;
    private static final double DAMP_SCALE = 0.4;
    /** Input gain into the comb bank; 2x Freeverb's 0.015 because we use 4 combs instead of 8. */
    private static final double FIXED_GAIN = 0.030;
    private static final double ALLPASS_FEEDBACK = 0.5;

    private final double[] params = { 0.30, 0.55, 0.45 };

    private int sampleRate = 44100;
    private Comb[][] comb = new Comb[2][];
    private AllPass[][] allpass = new AllPass[2][];
    private int combCount;
    private int allpassCount;

    public ReverbPlugin() {
        build(44100);
    }

    public String id() { return "builtin.reverb"; }

    public String name() { return "Reverb"; }

    public int paramCount() { return 3; }

    public String paramName(int i) {
        switch (i) {
            case P_MIX: return "Dry/Wet";
            case P_ROOM_SIZE: return "Room Size";
            case P_DAMPING: return "Damping";
            default: return "?";
        }
    }

    public double paramMin(int i) {
        switch (i) {
            case P_MIX: return 0.0;
            case P_ROOM_SIZE: return 0.0;
            case P_DAMPING: return 0.0;
            default: return 0.0;
        }
    }

    public double paramMax(int i) {
        switch (i) {
            case P_MIX: return 1.0;
            case P_ROOM_SIZE: return 1.0;
            case P_DAMPING: return 1.0;
            default: return 1.0;
        }
    }

    public double paramDefault(int i) {
        return (i >= 0 && i < params.length) ? defaultOf(i) : 0.0;
    }

    private static double defaultOf(int i) {
        switch (i) {
            case P_MIX: return 0.30;
            case P_ROOM_SIZE: return 0.55;
            case P_DAMPING: return 0.45;
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
        build(sampleRate);
    }

    public void reset() {
        for (int c = 0; c < comb.length; c++) {
            if (comb[c] == null) continue;
            for (int i = 0; i < comb[c].length; i++) comb[c][i].clear();
        }
        for (int c = 0; c < allpass.length; c++) {
            if (allpass[c] == null) continue;
            for (int i = 0; i < allpass[c].length; i++) allpass[c][i].clear();
        }
    }

    public void process(float[] l, float[] r, int frames) {
        if (l == null || r == null || frames <= 0) return;
        int n = frames;
        if (n > l.length) n = l.length;
        if (n > r.length) n = r.length;
        if (n <= 0 || combCount == 0) return;

        float mix = (float) params[P_MIX];
        float dry = 1.0f - mix;
        float feedback = (float) (ROOM_OFFSET + params[P_ROOM_SIZE] * ROOM_SCALE);
        float damp = (float) (params[P_DAMPING] * DAMP_SCALE);
        float gain = (float) FIXED_GAIN;

        for (int i = 0; i < n; i++) {
            float inL = l[i] * gain;
            float inR = r[i] * gain;

            float outL = 0.0f, outR = 0.0f;
            for (int c = 0; c < combCount; c++) {
                outL += comb[0][c].process(inL, feedback, damp);
                outR += comb[1][c].process(inR, feedback, damp);
            }
            for (int a = 0; a < allpassCount; a++) {
                outL = allpass[0][a].process(outL, (float) ALLPASS_FEEDBACK);
                outR = allpass[1][a].process(outR, (float) ALLPASS_FEEDBACK);
            }
            l[i] = l[i] * dry + outL * mix;
            r[i] = r[i] * dry + outR * mix;
        }
    }

    // ------------------------------------------------------------------ internals

    private void build(int rate) {
        double scale = rate / 44100.0;
        combCount = COMB_TUNING.length;
        allpassCount = ALLPASS_TUNING.length;
        comb = new Comb[2][];
        allpass = new AllPass[2][];
        for (int c = 0; c < 2; c++) {
            int spread = c == 0 ? 0 : STEREO_SPREAD;
            Comb[] cb = new Comb[combCount];
            for (int i = 0; i < combCount; i++) {
                int len = (int) Math.round((COMB_TUNING[i] + spread) * scale);
                cb[i] = new Comb(Math.max(2, len));
            }
            comb[c] = cb;
            AllPass[] ap = new AllPass[allpassCount];
            for (int i = 0; i < allpassCount; i++) {
                int len = (int) Math.round((ALLPASS_TUNING[i] + spread) * scale);
                ap[i] = new AllPass(Math.max(2, len));
            }
            allpass[c] = ap;
        }
    }

    /** Damped feedback comb filter (Freeverb's comb with the one-pole low pass in the loop). */
    private static final class Comb {
        private final float[] buf;
        private int idx;
        private float store;

        Comb(int len) { buf = new float[len]; }

        float process(float in, float feedback, float damp) {
            float out = buf[idx];
            store = out * (1.0f - damp) + store * damp;
            buf[idx] = in + store * feedback;
            if (++idx >= buf.length) idx = 0;
            return out;
        }

        void clear() {
            java.util.Arrays.fill(buf, 0.0f);
            store = 0.0f;
            idx = 0;
        }
    }

    /** Schroeder allpass (Freeverb's allpass, fixed 0.5 feedback). */
    private static final class AllPass {
        private final float[] buf;
        private int idx;

        AllPass(int len) { buf = new float[len]; }

        float process(float in, float feedback) {
            float bo = buf[idx];
            float out = -in + bo;
            buf[idx] = in + bo * feedback;
            if (++idx >= buf.length) idx = 0;
            return out;
        }

        void clear() {
            java.util.Arrays.fill(buf, 0.0f);
            idx = 0;
        }
    }
}
