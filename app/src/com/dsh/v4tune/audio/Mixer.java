package com.dsh.v4tune.audio;

/**
 * Stereo mixer for BriVoice: per-track gain (0..2, default 1), equal-power pan (-1..1,
 * default 0), mute / solo, plus a master gain / pan and a soft limiter.
 *
 * <p><b>Level policy.</b> The per-track stage applies gain and pan <i>without clipping</i>,
 * so the summing bus is free to exceed +/-1; only {@link #processMaster(float[], float[], int)}
 * limits. The limiter is exactly linear (unity slope) below {@link #LIMIT_KNEE}, continuous
 * in value and slope at the knee, and asymptotically approaches 1.0 above it, so a finished
 * master can never hold a sample outside [-1, 1].</p>
 *
 * <p><b>Pan law.</b> Track pan is textbook equal power: -1 -> (1, 0), 0 -> (0.7071, 0.7071),
 * +1 -> (0, 1). Master pan is a balance control on the same curve, normalised to unity at the
 * centre, so the default master (gain 1, pan 0) is bit transparent.</p>
 *
 * <p><b>Solo priority.</b> When any track in [0, {@link #trackCount()}) is soloed, only soloed
 * tracks are audible. Mute is applied after solo, so a track that is both muted and soloed stays
 * silent (usual DAW behaviour).</p>
 *
 * <p>Indices outside [0, trackCount) read as a default track (gain 1, centre pan, unmuted), so a
 * forgotten {@link #setTrackCount(int)} cannot accidentally silence the whole song.</p>
 *
 * <p>Plain Java only - no android.* - so this class is desktop testable.</p>
 */
public class Mixer {

    /** Upper bound accepted by the gain setters (track and master). */
    public static final float MAX_GAIN = 2.0f;
    /** Hard cap on the number of tracks the mixer allocates for. */
    public static final int MAX_TRACKS = 512;
    /** Below this magnitude the master limiter is exactly linear. */
    public static final float LIMIT_KNEE = 0.7f;

    private static final double SQRT2 = 1.4142135623730951;

    private int trackCount;
    private float[] gain = new float[0];
    private float[] pan = new float[0];
    private boolean[] mute = new boolean[0];
    private boolean[] solo = new boolean[0];

    private float masterGain = 1.0f;
    private float masterPan = 0.0f;

    public Mixer() {
        this(1);
    }

    public Mixer(int tracks) {
        setTrackCount(tracks);
    }

    // ------------------------------------------------------------------ layout

    /** Set the number of tracks. Existing per-track settings survive a resize. */
    public void setTrackCount(int n) {
        if (n < 0) n = 0;
        if (n > MAX_TRACKS) n = MAX_TRACKS;
        ensure(n);
        trackCount = n;
    }

    public int trackCount() {
        return trackCount;
    }

    // ------------------------------------------------------------------ track parameters

    /** Set a track gain, clamped to [0, {@link #MAX_GAIN}]. */
    public void setGain(int i, float g) {
        if (i < 0) return;
        ensure(i + 1);
        gain[i] = clampGain(g);
    }

    public float getGain(int i) {
        return (i >= 0 && i < gain.length) ? gain[i] : 1.0f;
    }

    /** Set a track pan, clamped to [-1, 1]. -1 is hard left, +1 hard right. */
    public void setPan(int i, float p) {
        if (i < 0) return;
        ensure(i + 1);
        pan[i] = clampPan(p);
    }

    public float getPan(int i) {
        return (i >= 0 && i < pan.length) ? pan[i] : 0.0f;
    }

    public void setMute(int i, boolean m) {
        if (i < 0) return;
        ensure(i + 1);
        mute[i] = m;
    }

    public boolean isMuted(int i) {
        return i >= 0 && i < mute.length && mute[i];
    }

    public void setSolo(int i, boolean s) {
        if (i < 0) return;
        ensure(i + 1);
        solo[i] = s;
    }

    public boolean isSolo(int i) {
        return i >= 0 && i < solo.length && solo[i];
    }

    /** True when at least one track inside [0, trackCount) is soloed. */
    public boolean anySolo() {
        int n = Math.min(trackCount, solo.length);
        for (int i = 0; i < n; i++) if (solo[i]) return true;
        return false;
    }

    /** Solo takes priority over everything except mute. */
    public boolean isAudible(int i) {
        if (i < 0) return false;
        if (isMuted(i)) return false;
        if (anySolo()) return isSolo(i);
        return true;
    }

    public void clearSolo() {
        for (int i = 0; i < solo.length; i++) solo[i] = false;
    }

    // ------------------------------------------------------------------ master

    public void setMasterGain(float g) {
        masterGain = clampGain(g);
    }

    public float getMasterGain() {
        return masterGain;
    }

    public void setMasterPan(float p) {
        masterPan = clampPan(p);
    }

    public float getMasterPan() {
        return masterPan;
    }

    // ------------------------------------------------------------------ processing

    /**
     * Apply track gain, equal-power pan and mute/solo to one track's stereo buffer, in place.
     * Nothing is clipped here: the caller sums the tracks and lets the master limiter do the
     * level management. A silent track has both channels zeroed.
     *
     * @param l          left channel of this track
     * @param r          right channel of this track
     * @param frames     number of frames to touch
     * @param trackIndex track whose gain / pan / mute / solo is used
     */
    public void process(float[] l, float[] r, int frames, int trackIndex) {
        int n = frames(l, r, frames);
        if (n <= 0) return;
        if (!isAudible(trackIndex)) {
            for (int i = 0; i < n; i++) { l[i] = 0.0f; r[i] = 0.0f; }
            return;
        }
        double p = getPan(trackIndex);
        double g = getGain(trackIndex);
        float gl = (float) (g * panLeftGain(p));
        float gr = (float) (g * panRightGain(p));
        for (int i = 0; i < n; i++) { l[i] *= gl; r[i] *= gr; }
    }

    /** Accumulate one already-panned-by-the-mixer track into a summing bus (no clipping). */
    public void accumulate(float[] tl, float[] tr, float[] l, float[] r, int frames, int trackIndex) {
        int n = frames(l, r, frames);
        if (n <= 0 || tl == null || tr == null) return;
        if (n > tl.length) n = tl.length;
        if (n > tr.length) n = tr.length;
        if (n <= 0 || !isAudible(trackIndex)) return;
        double p = getPan(trackIndex);
        double g = getGain(trackIndex);
        float gl = (float) (g * panLeftGain(p));
        float gr = (float) (g * panRightGain(p));
        for (int i = 0; i < n; i++) { l[i] += tl[i] * gl; r[i] += tr[i] * gr; }
    }

    /**
     * Accumulate a mono track (what {@code Renderer.renderPart} returns) into a stereo bus with
     * equal-power pan and gain applied. This is the usual wiring path for offline rendering.
     */
    public void accumulateMono(float[] mono, float[] l, float[] r, int frames, int trackIndex) {
        int n = frames(l, r, frames);
        if (n <= 0 || mono == null) return;
        if (n > mono.length) n = mono.length;
        if (n <= 0 || !isAudible(trackIndex)) return;
        double p = getPan(trackIndex);
        double g = getGain(trackIndex);
        float gl = (float) (g * panLeftGain(p));
        float gr = (float) (g * panRightGain(p));
        for (int i = 0; i < n; i++) { float v = mono[i]; l[i] += v * gl; r[i] += v * gr; }
    }

    /** Master gain + master pan + soft limiter, in place. Always call this last. */
    public void processMaster(float[] l, float[] r, int frames) {
        int n = frames(l, r, frames);
        if (n <= 0) return;
        float gl = (float) (masterGain * masterLeftGain(masterPan));
        float gr = (float) (masterGain * masterRightGain(masterPan));
        for (int i = 0; i < n; i++) {
            l[i] = softLimit(l[i] * gl);
            r[i] = softLimit(r[i] * gr);
        }
    }

    // ------------------------------------------------------------------ laws

    /** Equal-power left gain of a track pan: -1 -> 1.0, 0 -> 0.7071, +1 -> 0.0. */
    public static double panLeftGain(double pan) {
        return Math.cos((clampPan(pan) + 1.0) * Math.PI * 0.25);
    }

    /** Equal-power right gain of a track pan: -1 -> 0.0, 0 -> 0.7071, +1 -> 1.0. */
    public static double panRightGain(double pan) {
        return Math.sin((clampPan(pan) + 1.0) * Math.PI * 0.25);
    }

    /** Master balance left gain: same curve, unity at the centre. */
    public static double masterLeftGain(double pan) {
        return Math.cos((clampPan(pan) + 1.0) * Math.PI * 0.25) * SQRT2;
    }

    /** Master balance right gain: same curve, unity at the centre. */
    public static double masterRightGain(double pan) {
        return Math.sin((clampPan(pan) + 1.0) * Math.PI * 0.25) * SQRT2;
    }

    /**
     * Soft limiter: y = x for |x| &lt;= knee, then a tanh knee that reaches 1.0 asymptotically.
     * Value and slope (1.0) are continuous at the knee, so quiet material is untouched.
     */
    public static float softLimit(float x) {
        if (x != x) return 0.0f;                       // NaN -> silence
        float a = x < 0 ? -x : x;
        if (a <= LIMIT_KNEE) return x;
        double y = LIMIT_KNEE + (1.0 - LIMIT_KNEE) * Math.tanh((a - LIMIT_KNEE) / (1.0 - LIMIT_KNEE));
        return (float) (x < 0 ? -y : y);
    }

    public static float clampGain(double g) {
        if (g != g) return 0.0f;
        if (g < 0.0f) return 0.0f;
        if (g > MAX_GAIN) return MAX_GAIN;
        return (float) g;
    }

    public static float clampPan(double p) {
        if (p != p) return 0.0f;
        if (p < -1.0f) return -1.0f;
        if (p > 1.0f) return 1.0f;
        return (float) p;
    }

    // ------------------------------------------------------------------ internals

    private static int frames(float[] l, float[] r, int frames) {
        if (l == null || r == null || frames <= 0) return 0;
        int n = frames;
        if (n > l.length) n = l.length;
        if (n > r.length) n = r.length;
        return n;
    }

    private void ensure(int n) {
        if (n <= gain.length) return;
        int cap = Math.max(n, Math.max(4, gain.length * 2));
        if (cap > MAX_TRACKS) cap = MAX_TRACKS;
        if (cap < n) cap = n;
        float[] ng = new float[cap];
        float[] np = new float[cap];
        boolean[] nm = new boolean[cap];
        boolean[] ns = new boolean[cap];
        for (int i = 0; i < cap; i++) {
            ng[i] = i < gain.length ? gain[i] : 1.0f;
            np[i] = i < pan.length ? pan[i] : 0.0f;
            nm[i] = i < mute.length && mute[i];
            ns[i] = i < solo.length && solo[i];
        }
        gain = ng; pan = np; mute = nm; solo = ns;
    }
}
