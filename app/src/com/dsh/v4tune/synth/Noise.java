package com.dsh.v4tune.synth;

/**
 * Deterministic white-noise generator based on a 64-bit xorshift.
 *
 * <p>No allocation, no lookup tables, no libraries: only long/double arithmetic so the
 * stream is bit-identical on every JVM and on Android. Cheap enough to be called once
 * per sample at 44.1 kHz.</p>
 */
public class Noise {

    /** 2^-53, used to turn 53 random bits into a double in [0,1). */
    private static final double INV_53 = 1.0 / 9007199254740992.0;

    /** Any non-zero constant, used when someone seeds with 0. */
    private static final long FALLBACK_SEED = 0x9E3779B97F4A7C15L;

    private long state;

    public Noise(long seed) {
        reset(seed);
    }

    /** Re-seeds the generator; the same seed always yields the same sequence. */
    public void reset(long seed) {
        state = (seed == 0L) ? FALLBACK_SEED : seed;
    }

    /** xorshift64 step. */
    private long nextLong() {
        long x = state;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        state = x;
        return x;
    }

    /** White noise, uniformly distributed in [-1, 1). */
    public float next() {
        long v = nextLong() >>> 11;            // keep the high 53 bits
        double u = (double) v * INV_53;        // [0,1)
        return (float) (u * 2.0 - 1.0);        // [-1,1)
    }

    /**
     * Approximately gaussian noise: the sum of three uniforms, which has zero mean and
     * unit variance. About 68% of the values fall in [-1,1] and virtually all of them in
     * [-3,3], so callers can read {@code 1 + 0.004*gauss} as "0.4% standard deviation".
     */
    public float nextGauss() {
        return next() + next() + next();
    }
}
