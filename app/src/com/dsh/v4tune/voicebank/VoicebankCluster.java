package com.dsh.v4tune.voicebank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Stage B: clustering, formant picking and phoneme matching.
 *
 * <p>Ported from tools/voicebank_pack.py: kmeans(420), pick_formants(440), match_phonemes(474),
 * assign_clusters(544), frame_envelope(396), moving_average(412). Constants are the measured
 * values from that file.
 *
 * <p>ONE DELIBERATE DEVIATION, agreed with the user: the Python seeds its centroids with
 * np.random.default_rng(20261006).choice(n, k, replace=False). Replicating PCG64 bit for bit in
 * Java is a project of its own, so this port uses deterministic k-means++ with a fixed Java seed
 * instead. Cluster labels may therefore differ from the PC build; the acceptance test is that the
 * same bank produces the same result on every run, and that the pack it writes synthesises.
 */
public final class VoicebankCluster {

    public static final int ENV_POINTS = 96;
    public static final double ENV_F_LO = 60.0;
    public static final double ENV_F_HI = 16000.0;
    public static final double TILT_HZ = 400.0;
    public static final double TILT_DB_PER_OCT = 12.0;
    public static final double[][] FORMANT_BANDS = {{200.0, 1100.0}, {800.0, 3000.0}, {2300.0, 4000.0}};
    public static final double BAND_GATE_DB = -25.0;
    public static final double F2_OVER_F1 = 1.12;
    public static final double F3_OVER_F2 = 1.03;
    public static final double F1_F0_FACTOR = 1.15;
    public static final double MATCH_MAX_OCT = 0.45;
    public static final double MATCH_FREE_SLACK = 1.12;
    public static final int KMEANS_SEED = 20261006;

    /** log-spaced analysis grid, ENV_F = geomspace(60, 16000, 96) */
    public static final double[] ENV_F = geomspace(ENV_F_LO, ENV_F_HI, ENV_POINTS);
    /** ENV_TILT = where(ENV_F > 400, 12 * log2(ENV_F / 400), 0) */
    public static final double[] ENV_TILT = tilt();

    private VoicebankCluster() { }

    // ------------------------------------------------------------------ helpers

    public static double[] geomspace(double lo, double hi, int n) {
        double[] out = new double[n];
        double a = Math.log(lo), b = Math.log(hi);
        for (int i = 0; i < n; i++) out[i] = Math.exp(a + (b - a) * i / (n - 1.0));
        return out;
    }

    static double[] tilt() {
        double[] t = new double[ENV_F.length];
        for (int i = 0; i < t.length; i++) {
            t[i] = ENV_F[i] > TILT_HZ ? TILT_DB_PER_OCT * (Math.log(ENV_F[i] / TILT_HZ) / Math.log(2)) : 0.0;
        }
        return t;
    }

    /** np.interp: linear interpolation, edge values held outside the measured range. */
    public static double[] interp(double[] x, double[] xp, double[] fp) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double v = x[i];
            if (v <= xp[0]) { out[i] = fp[0]; continue; }
            if (v >= xp[xp.length - 1]) { out[i] = fp[fp.length - 1]; continue; }
            int lo = 0, hi = xp.length - 1;
            while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (xp[mid] <= v) lo = mid; else hi = mid; }
            double t = (v - xp[lo]) / (xp[hi] - xp[lo]);
            out[i] = fp[lo] + t * (fp[hi] - fp[lo]);
        }
        return out;
    }

    /** moving_average: centred mean with edge replication; w <= 1 returns the input. */
    public static double[] movingAverage(double[] y, int w) {
        if (w <= 1) return y.clone();
        int n = y.length, pad = w / 2;
        double[] ext = new double[n + 2 * pad];
        for (int i = 0; i < ext.length; i++) {
            int j = i - pad;
            ext[i] = y[j < 0 ? 0 : (j >= n ? n - 1 : j)];
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int k = 0; k < w; k++) s += ext[i + k];
            out[i] = s / w;
        }
        return out;
    }

    /** frame_envelope: interpolate on the log grid, remove the mean, add the source tilt back. */
    public static double[] frameEnvelope(double[] freqs, double[] mags) {
        double[] env = interp(ENV_F, freqs, mags);
        double mean = 0;
        for (double v : env) mean += v;
        mean /= env.length;
        for (int i = 0; i < env.length; i++) env[i] = env[i] - mean + ENV_TILT[i];
        return env;
    }

    // ------------------------------------------------------------------ k-means

    public static final class Clusters {
        public final double[][] centroids;
        public final int[] labels;
        Clusters(double[][] c, int[] l) { centroids = c; labels = l; }
    }

    /**
     * Lloyd iterations with deterministic k-means++ seeding (see the class comment for why this
     * differs from the Python). Squared euclidean distance, same metric as assign_clusters.
     */
    public static Clusters kmeans(double[][] X, int k, int iters) {
        int n = X.length, dim = X[0].length;
        Random rnd = new Random(KMEANS_SEED);
        double[][] C = new double[k][dim];
        C[0] = X[rnd.nextInt(n)].clone();
        double[] best = new double[n];
        Arrays.fill(best, Double.MAX_VALUE);
        for (int c = 1; c < k; c++) {
            double total = 0;
            for (int i = 0; i < n; i++) {
                double d = sqdist(X[i], C[c - 1]);
                if (d < best[i]) best[i] = d;
                total += best[i];
            }
            double target = rnd.nextDouble() * total, acc = 0;
            int pick = n - 1;
            for (int i = 0; i < n; i++) { acc += best[i]; if (acc >= target) { pick = i; break; } }
            C[c] = X[pick].clone();
        }
        int[] lab = new int[n];
        Arrays.fill(lab, -1);
        for (int it = 0; it < iters; it++) {
            int[] next = assign(X, C);
            if (Arrays.equals(next, lab)) break;
            lab = next;
            double[][] sum = new double[k][dim];
            int[] cnt = new int[k];
            for (int i = 0; i < n; i++) {
                int j = lab[i];
                cnt[j]++;
                for (int d = 0; d < dim; d++) sum[j][d] += X[i][d];
            }
            for (int j = 0; j < k; j++) {
                if (cnt[j] == 0) continue;
                for (int d = 0; d < dim; d++) C[j][d] = sum[j][d] / cnt[j];
            }
        }
        return new Clusters(C, lab);
    }

    /** nearest centroid for every row (squared euclidean) */
    public static int[] assign(double[][] X, double[][] C) {
        int[] out = new int[X.length];
        for (int i = 0; i < X.length; i++) {
            int bestJ = 0;
            double bestD = Double.MAX_VALUE;
            for (int j = 0; j < C.length; j++) {
                double d = sqdist(X[i], C[j]);
                if (d < bestD) { bestD = d; bestJ = j; }
            }
            out[i] = bestJ;
        }
        return out;
    }

    static double sqdist(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) { double d = a[i] - b[i]; s += d * d; }
        return s;
    }

    // ------------------------------------------------------------------ formants

    /** pick_formants: returns F1,F2,F3 or null when a band peak falls below the gate. */
    public static double[] pickFormants(double[] centroid, double[] shelf, double f0Med) {
        double[] s = movingAverage(centroid, 5);
        double[] diff = new double[centroid.length];
        for (int i = 0; i < centroid.length; i++) diff[i] = centroid[i] - shelf[i];
        double[] res = movingAverage(diff, 5);
        double cmax = max(s);
        double lo0 = Math.max(FORMANT_BANDS[0][0], F1_F0_FACTOR * f0Med);
        double[][] bands = {{lo0, FORMANT_BANDS[0][1]}, FORMANT_BANDS[1], FORMANT_BANDS[2]};
        double[] out = new double[3];
        for (int b = 0; b < 3; b++) {
            double lo = bands[b][0], hi = bands[b][1];
            if (b == 1) lo = Math.max(lo, F2_OVER_F1 * out[0]);
            if (b == 2) lo = Math.max(lo, F3_OVER_F2 * out[1]);
            int argIdx = -1;
            double bestRes = -Double.MAX_VALUE;
            for (int i = 0; i < ENV_F.length; i++) {
                if (ENV_F[i] < lo || ENV_F[i] > hi) continue;
                if (res[i] > bestRes) { bestRes = res[i]; argIdx = i; }
            }
            if (argIdx < 0) return null;
            if (s[argIdx] - cmax < BAND_GATE_DB) return null;
            out[b] = ENV_F[argIdx];
        }
        return out;
    }

    // ------------------------------------------------------------------ phonemes

    /** match_phonemes: symbol -> rounded (F1,F2,F3); one cluster serves at most one symbol. */
    public static Map<String, int[]> matchPhonemes(double[][] clusters, java.util.List<String> symbols) {
        ArrayList<double[]> pairs = new ArrayList<double[]>();   // {dist, symIndex, clusterIndex}
        ArrayList<String> syms = new ArrayList<String>();
        for (String sym : symbols) {
            int[] ref = ReferenceFormants.get(sym);
            if (ref == null) continue;
            syms.add(sym);
            double[] t = {Math.log(ref[0]) / Math.log(2), Math.log(ref[1]) / Math.log(2), Math.log(ref[2]) / Math.log(2)};
            for (int ci = 0; ci < clusters.length; ci++) {
                double[] c = {Math.log(clusters[ci][0]) / Math.log(2), Math.log(clusters[ci][1]) / Math.log(2), Math.log(clusters[ci][2]) / Math.log(2)};
                double s = 0;
                for (int d = 0; d < 3; d++) { double e = c[d] - t[d]; s += e * e; }
                pairs.add(new double[]{Math.sqrt(s / 3.0), syms.size() - 1, ci});
            }
        }
        pairs.sort(new Comparator<double[]>() {
            public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
        });
        Map<String, int[]> formants = new HashMap<String, int[]>();
        Set<Integer> taken = new HashSet<Integer>();
        for (double[] p : pairs) {
            String sym = syms.get((int) p[1]);
            int ci = (int) p[2];
            if (formants.containsKey(sym) || taken.contains(ci) || p[0] > MATCH_MAX_OCT) continue;
            double own = Double.MAX_VALUE;
            for (double[] q : pairs) if ((int) q[1] == (int) p[1] && q[0] < own) own = q[0];
            if (p[0] > own * MATCH_FREE_SLACK) continue;
            formants.put(sym, round3(clusters[ci]));
            taken.add(ci);
        }
        for (double[] p : pairs) {
            String sym = syms.get((int) p[1]);
            int ci = (int) p[2];
            if (formants.containsKey(sym) || p[0] > MATCH_MAX_OCT) continue;
            formants.put(sym, round3(clusters[ci]));
        }
        return formants;
    }

    static int[] round3(double[] c) {
        return new int[]{(int) Math.round(c[0]), (int) Math.round(c[1]), (int) Math.round(c[2])};
    }

    static double max(double[] a) {
        double m = -Double.MAX_VALUE;
        for (double v : a) if (v > m) m = v;
        return m;
    }
}
