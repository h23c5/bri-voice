package com.dsh.v4tune.audio;

/**
 * A real-time stereo audio plugin: a small, allocation-free processing block that is driven
 * between {@link #prepare(int, int)} and an arbitrary number of {@link #process} calls.
 *
 * <p>A host (see {@link PluginChain}) prepares every plugin with the sample rate and the largest
 * block it will ever be handed, then calls process() with buffers that are at least that long.
 * Implementations must not allocate inside process() and must be plain Java - no android.* - so
 * the whole chain can be rendered and tested on the desktop.</p>
 *
 * <p>Parameters are addressed by index in [0, {@link #paramCount()}); values are clamped to
 * [paramMin(i), paramMax(i)] by {@link #setParam(int, double)}.</p>
 */
public interface Plugin {

    /** Stable identifier, e.g. "builtin.reverb". Used for project persistence. */
    String id();

    /** Human readable name for the plugin picker. */
    String name();

    /** Number of automatable parameters. */
    int paramCount();

    /** Display name of parameter i. */
    String paramName(int i);

    double paramMin(int i);

    double paramMax(int i);

    double paramDefault(int i);

    /** Set parameter i, clamped to its declared range. */
    void setParam(int i, double v);

    double getParam(int i);

    /** Allocate delay lines / coefficients. Safe to call again with a new rate. */
    void prepare(int sampleRate, int maxFrames);

    /** Clear all internal state (delay lines, envelopes) without touching parameters. */
    void reset();

    /** Process frames stereo frames in place. */
    void process(float[] l, float[] r, int frames);
}
