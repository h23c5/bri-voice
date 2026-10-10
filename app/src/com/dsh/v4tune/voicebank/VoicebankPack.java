package com.dsh.v4tune.voicebank;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Stage C: the harmonics section of a pack.
 *
 * <p>Ported from tools/voicebank_pack.py: harmonic_grid(525), frame_harm_curve(530),
 * band_curve(566), frame_sequence(596), dyn_variants(640), pitch_bands(680). Constants are the
 * measured values from that file.
 *
 * <p>Two details that are easy to get wrong, both taken from the Python:
 * <ul>
 *   <li>the harmonics path uses a LOOSER frame gate than the formants path (8 harmonics, f0
 *       inside 80..1200) - pass those to VoicebankFrames.readFrame;</li>
 *   <li>numpy rounds halves to even (np.rint / np.round), so Math.rint is used here; Math.round
 *       would shift values by one quantisation step.</li>
 * </ul>
 */
public final class VoicebankPack {

    public static final double HARM_F_LO = 60.0;
    public static final double HARM_F_HI = 16000.0;
    public static final int HARM_POINTS = 64;
    public static final int HARM_MIN_HARMONICS = 8;
    public static final double HARM_F0_MIN = 80.0;
    public static final double HARM_F0_MAX = 1200.0;
    public static final int HARM_BAND_MIN_FRAMES = 40;
    public static final int HARM_MAX_BANDS = 3;
    public static final int HARM_DYN_VARIANTS = 3;
    public static final int HARM_SEQ_FRAMES = 12;
    public static final double SEQ_Q = 0.5;
    public static final double HARM_DB_STEP = 0.5;
    public static final double HARM_DB_FLOOR = -96.0;

    private VoicebankPack() { }

    // ------------------------------------------------------------------ grid and curve

    public static double[] harmonicGrid(int nPoints) {
        return VoicebankCluster.geomspace(HARM_F_LO, HARM_F_HI, nPoints);
    }

    public static double[] frameHarmCurve(double[] freqs, double[] mags, double[] logGrid) {
        double[] logF = new double[freqs.length];
        for (int i = 0; i < freqs.length; i++) logF[i] = Math.log(freqs[i]);
        double[] y = VoicebankCluster.interp(logGrid, logF, mags);
        double max = -Double.MAX_VALUE;
        for (double v : y) if (v > max) max = v;
        for (int i = 0; i < y.length; i++) y[i] -= max;
        return y;
    }

    // ------------------------------------------------------------------ one pitch band

    /** One exported band: median f0, the quantised mean curve, an optional dyn tag, a sequence. */
    public static final class Band {
        public double f0;
        public boolean hasF0;   // set by bandCurve, like the "f0" key in the Python dict
        public double[] db;          // dropped when a sequence with seqN >= 2 exists
        public Double dyn;           // 0 = softest, 1 = loudest, null when the caller omits it
        public String seqB64;
        public int seqN;
        public double seqQ;
        public double[] lvl;
    }

    /**
     * band_curve: average the member frames IN THE LINEAR AMPLITUDE DOMAIN (averaging dB would be
     * wrong), normalise to exactly 0 dB, quantise by HARM_DB_STEP and clamp at HARM_DB_FLOOR.
     */
    public static Band bandCurve(double[][] rowsDb, double[] f0Sel, Double dyn) {
        int n = rowsDb.length, m = rowsDb[0].length;
        double[] mean = new double[m];
        for (double[] row : rowsDb) {
            for (int i = 0; i < m; i++) mean[i] += Math.pow(10.0, row[i] / 20.0);
        }
        for (int i = 0; i < m; i++) mean[i] /= n;
        double[] db = new double[m];
        for (int i = 0; i < m; i++) db[i] = 20.0 * Math.log10(Math.max(mean[i], 1e-9));
        double max = -Double.MAX_VALUE;
        for (double v : db) if (v > max) max = v;
        for (int i = 0; i < m; i++) {
            double v = Math.rint((db[i] - max) / HARM_DB_STEP) * HARM_DB_STEP;
            if (v < HARM_DB_FLOOR) v = HARM_DB_FLOOR;
            if (v > 0.0) v = 0.0;
            if (v == 0.0) v = 0.0;
            db[i] = v;
        }
        Band b = new Band();
        b.f0 = rint1(median(f0Sel));
        b.hasF0 = true;
        b.db = db;
        b.dyn = dyn;
        if (dyn != null) b.dyn = rint3(dyn);
        return b;
    }

    /**
     * frame_sequence: a run of REAL consecutive frames around the one whose f0 is closest to the
     * band's median. Curves are int8 quantised at SEQ_Q dB and base64 packed, with each frame's
     * level (dB, relative to the median energy) stored alongside.
     */
    public static Band frameSequence(double[][] curves, double[] f0a, int[] part, int maxFrames) {
        int n = part.length;
        if (n == 0) return null;
        int[] order = stableArgsort(part);
        double[] f0s = new double[n];
        double[][] rows = new double[n][];
        for (int i = 0; i < n; i++) { f0s[i] = f0a[order[i]]; rows[i] = curves[order[i]]; }
        int take = Math.min(maxFrames, n);
        if (take < 3) return null;
        double med = median(f0s);
        int c = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) { double d = Math.abs(f0s[i] - med); if (d < best) { best = d; c = i; } }
        int a = Math.max(0, Math.min(n - take, c - take / 2));
        int m = rows[0].length;
        double[][] sel = new double[take][m];
        for (int i = 0; i < take; i++) sel[i] = rows[a + i];
        if (sel.length < 3) return null;

        double[] energy = new double[take];
        for (int i = 0; i < take; i++) {
            double s = 0;
            for (int j = 0; j < m; j++) s += Math.pow(10.0, sel[i][j] / 20.0);
            energy[i] = s;
        }
        double ref = median(energy);
        double[] lvl = new double[take];
        for (int i = 0; i < take; i++) {
            double v = 20.0 * Math.log10(Math.max(energy[i], 1e-12) / Math.max(ref, 1e-12));
            if (v < -24.0) v = -24.0;
            if (v > 12.0) v = 12.0;
            lvl[i] = rint1(v);
        }

        byte[] q = new byte[take * m];
        for (int i = 0; i < take; i++) {
            double rowMax = -Double.MAX_VALUE;
            for (int j = 0; j < m; j++) if (sel[i][j] > rowMax) rowMax = sel[i][j];
            for (int j = 0; j < m; j++) {
                double v = sel[i][j] - rowMax;
                if (v < HARM_DB_FLOOR) v = HARM_DB_FLOOR;
                if (v > 0.0) v = 0.0;
                long k = Math.round(Math.rint(v / SEQ_Q));
                if (k < -128) k = -128;
                if (k > 0) k = 0;
                q[i * m + j] = (byte) k;
            }
        }
        Band b = new Band();
        b.seqB64 = base64(q);
        b.seqN = take;
        b.seqQ = SEQ_Q;
        b.lvl = lvl;
        return b;
    }

    /** dyn_variants: split a band's frames by total energy into 2..3 loudness variants. */
    public static ArrayList<Band> dynVariants(double[][] curves, double[] f0a, int[] part) {
        ArrayList<Band> out = new ArrayList<Band>();
        int n = part.length;
        if (n < HARM_BAND_MIN_FRAMES * 2) {
            out.add(bandCurve(sub(curves, part), sub(f0a, part), Double.valueOf(0.5)));
            return out;
        }
        double[] energy = new double[n];
        int m = curves[0].length;
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < m; j++) s += Math.pow(10.0, curves[part[i]][j] / 20.0);
            energy[i] = s;
        }
        int[] order = stableArgsort(energy);
        int nv = n >= HARM_BAND_MIN_FRAMES * HARM_DYN_VARIANTS ? HARM_DYN_VARIANTS : 2;
        for (int v = 0; v < nv; v++) {
            int lo = (int) Math.round(n * v / (double) nv);
            int hi = (int) Math.round(n * (v + 1) / (double) nv);
            if (hi - lo < 1) continue;
            int[] sub = new int[hi - lo];
            for (int i = 0; i < sub.length; i++) sub[i] = part[order[lo + i]];
            double dyn = nv == 1 ? 0.0 : v / (double) (nv - 1);
            Band band = bandCurve(sub(curves, sub), sub(f0a, sub), Double.valueOf(dyn));
            Band seq = frameSequence(curves, f0a, sub, HARM_SEQ_FRAMES);
            if (seq != null) {
                band.seqB64 = seq.seqB64;
                band.seqN = seq.seqN;
                band.seqQ = seq.seqQ;
                band.lvl = seq.lvl;
                if (seq.seqN >= 2) band.db = null;   // the mean curve is only a fallback
            }
            out.add(band);
        }
        return out;
    }

    /** pitch_bands: split a phoneme's frames into up to three f0 tertiles; [] when too few. */
    public static ArrayList<Band> pitchBands(double[][] curves, double[] f0s) {
        ArrayList<Band> out = new ArrayList<Band>();
        int n = f0s.length;
        if (n < HARM_BAND_MIN_FRAMES) return out;
        int[] order = stableArgsort(f0s);
        for (int nb = HARM_MAX_BANDS; nb >= 1; nb--) {
            if (n < HARM_BAND_MIN_FRAMES * nb) continue;
            int[][] parts = new int[nb][];
            boolean ok = true;
            for (int i = 0; i < nb; i++) {
                int lo = (int) Math.round(n * i / (double) nb);
                int hi = (int) Math.round(n * (i + 1) / (double) nb);
                parts[i] = new int[hi - lo];
                for (int k = 0; k < parts[i].length; k++) parts[i][k] = order[lo + k];
                if (parts[i].length < HARM_BAND_MIN_FRAMES) ok = false;
            }
            if (!ok) continue;
            for (int i = 0; i < nb; i++) out.addAll(dynVariants(curves, f0s, parts[i]));
            return out;
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    static double[][] sub(double[][] a, int[] idx) {
        double[][] out = new double[idx.length][];
        for (int i = 0; i < idx.length; i++) out[i] = a[idx[i]];
        return out;
    }

    static double[] sub(double[] a, int[] idx) {
        double[] out = new double[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = a[idx[i]];
        return out;
    }

    /** np.median: middle value, mean of the two middles for an even count. */
    static double median(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0;
    }

    /** argsort(kind="stable"): indices ordered by value, ties keep their original order. */
    static int[] stableArgsort(final double[] a) {
        Integer[] idx = new Integer[a.length];
        for (int i = 0; i < a.length; i++) idx[i] = Integer.valueOf(i);
        Arrays.sort(idx, new java.util.Comparator<Integer>() {
            public int compare(Integer x, Integer y) { return Double.compare(a[x.intValue()], a[y.intValue()]); }
        });
        int[] out = new int[a.length];
        for (int i = 0; i < out.length; i++) out[i] = idx[i].intValue();
        return out;
    }

    /** the same for an int array: numpy sorts whatever it is given */
    static int[] stableArgsort(final int[] a) {
        Integer[] idx = new Integer[a.length];
        for (int i = 0; i < a.length; i++) idx[i] = Integer.valueOf(i);
        Arrays.sort(idx, new java.util.Comparator<Integer>() {
            public int compare(Integer x, Integer y) { return Integer.compare(a[x.intValue()], a[y.intValue()]); }
        });
        int[] out = new int[a.length];
        for (int i = 0; i < out.length; i++) out[i] = idx[i].intValue();
        return out;
    }

    static double rint1(double v) { return Math.rint(v * 10.0) / 10.0; }
    static double rint3(double v) { return Math.rint(v * 1000.0) / 1000.0; }

    /** standard base64, written out so the class runs on the desktop JVM as well as on Android. */
    static String base64(byte[] in) {
        final char[] A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xFF;
            int b1 = i + 1 < in.length ? in[i + 1] & 0xFF : -1;
            int b2 = i + 2 < in.length ? in[i + 2] & 0xFF : -1;
            sb.append(A[b0 >> 2]);
            sb.append(A[((b0 & 0x03) << 4) | (b1 < 0 ? 0 : b1 >> 4)]);
            sb.append(b1 < 0 ? '=' : A[((b1 & 0x0F) << 2) | (b2 < 0 ? 0 : b2 >> 6)]);
            sb.append(b2 < 0 ? '=' : A[b2 & 0x3F]);
        }
        return sb.toString();
    }
}
