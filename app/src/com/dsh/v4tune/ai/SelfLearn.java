package com.dsh.v4tune.ai;

import android.content.Context;

import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Self-learning for BriTune-Net (v10.1.1).
 *
 * <p>Right after a WAV export the app asks whether the take sounds right. If the user says yes, the
 * tuning that is currently on the project's curves becomes a training sample: the exact input
 * vector the model sees for every note, plus the parameter offsets the user actually kept.
 * Everything stays on the phone - no audio, no project and no sample ever leaves the device.
 *
 * <p>Samples accumulate in a small binary file ({@code inDim + outDim} floats per note). The file
 * name carries the model generation, so upgrading to a wider base model cannot silently read v2
 * rows as v3 rows; old samples stay on disk untouched. Every "yes" then retrains the network from
 * the shipped weights with Adam for a few hundred epochs, pulling the weights back towards the
 * shipped ones so a handful of samples cannot erase what the model already knows. The result is
 * written next to the shipped file and {@link LocalModelStore#load(Context)} prefers it.
 */
public final class SelfLearn {

    /** output index -> index into {@link Params#IDS} used for that output's label; -1 = not learnt */
    private static final int[] LABEL_PARAM = {0, 1, 2, 3, 4, 6, 9, 7, 7, -1, 5, 0, -1};

    /** PIT release is read at the note end, everything else at its start */
    private static final int PIT_END_OUT = 8;

    /** note-internal DYN shape is the difference between the end and the start value */
    private static final int DYN_SHAPE_OUT = 11;

    /** one retrain never looks at more than this many samples, so it stays interactive */
    private static final int MAX_FIT = 200;

    /** the dataset file is small but it still should not grow forever */
    private static final int MAX_ROWS = 4000;

    private static final float LR = 0.0018f;
    private static final int BASE_ITERS = 20000;
    private static final double L2 = 0.25;

    /** a label below this is noise from a curve the user barely touched */
    private static final float MIN_LABEL = 0.02f;

    private SelfLearn() { }

    /** legacy v2 dataset; new models write their own generation file, see {@link #dataFile(Context, LocalTuner.Model)} */
    public static File dataFile(Context ctx) {
        return new File(LocalModelStore.dir(ctx), "britune-self.dat");
    }

    /** dataset that belongs to the loaded model's feature/label layout */
    public static File dataFile(Context ctx, LocalTuner.Model m) {
        if (m == null || !LocalTuner.isV3(m)) return dataFile(ctx);
        return new File(LocalModelStore.dir(ctx), "britune-self-v3.dat");
    }

    public static File userModelFile(Context ctx) {
        return new File(LocalModelStore.dir(ctx), "britune-v1.user.bin");
    }

    /** how many notes have been learnt so far for this model layout */
    public static int count(File data, LocalTuner.Model m) {
        if (data == null || !data.exists() || m == null) return 0;
        int rec = 4 * (m.inDim + m.outDim);
        if (rec <= 0) return 0;
        return (int) (data.length() / rec);
    }

    /** keeps the newest {@code keep} samples, dropping the oldest ones first */
    private static void trim(File data, int keep, int recBytes) {
        try {
            int total = count(data, null) == 0 ? (int) (data.length() / recBytes) : 0;
            total = (int) (data.length() / recBytes);
            if (total <= keep) return;
            byte[] all = new byte[(int) data.length()];
            FileInputStream in = new FileInputStream(data);
            try {
                int off = 0;
                while (off < all.length) {
                    int n = in.read(all, off, all.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            int drop = total - keep;
            FileOutputStream out = new FileOutputStream(data);
            try {
                out.write(all, drop * recBytes, all.length - drop * recBytes);
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    public static void clear(File data) {
        if (data != null && data.exists()) data.delete();
    }

    /** removes both the legacy and the current-generation dataset */
    public static void clearAll(Context ctx) {
        clear(dataFile(ctx));
        File v3 = new File(LocalModelStore.dir(ctx), "britune-self-v3.dat");
        if (v3.exists()) v3.delete();
    }

    /** offsets currently on the curves, in the same normalised space the model was trained in */
    static float[] label(Part p, Note nt, LocalTuner.Model m) {
        int out = m.outDim;
        float[] y = new float[out];
        long start = nt.pos, end = nt.pos + nt.dur;
        for (int o = 0; o < out; o++) {
            int idx = o < LABEL_PARAM.length ? LABEL_PARAM[o] : -1;
            if (idx < 0) continue;
            double v, d;
            if (o == DYN_SHAPE_OUT) {
                v = valueAt(p, idx, end) - valueAt(p, idx, start);
                d = v;
            } else {
                v = valueAt(p, idx, o == PIT_END_OUT ? end : start);
                d = v - LocalTuner.PARAM_DEF[idx];
            }
            float sc = o < m.outScale.length ? m.outScale[o] : 1f;
            if (sc <= 0) continue;
            float ny = (float) (d / sc);
            y[o] = Math.max(-1f, Math.min(1f, ny));
        }
        return y;
    }

    private static double valueAt(Part p, int idx, long tick) {
        ControlCurve c = p.ctrlOrNull(Params.IDS[idx]);
        if (c == null || c.pts.isEmpty()) return LocalTuner.PARAM_DEF[idx];
        return Params.clamp(idx, c.valueAt(tick));
    }

    /**
     * Turns the current project into samples and appends them to the dataset.
     *
     * @return notes added (0 when nothing on the curves was changed, so there is nothing to learn)
     */
    public static int record(Context ctx, Song song, LocalTuner.Model m, int style, int intensityPct)
            throws IOException {
        if (song == null || m == null) return 0;
        List<float[]> xs = new ArrayList<float[]>();
        List<float[]> ys = new ArrayList<float[]>();
        collect(song, m, style, intensityPct / 100f, xs, ys);
        if (xs.isEmpty()) return 0;
        int recBytes = 4 * (m.inDim + m.outDim);
        DataOutputStream out = new DataOutputStream(new FileOutputStream(dataFile(ctx, m), true));
        try {
            for (int i = 0; i < xs.size(); i++) {
                float[] x = xs.get(i);
                for (int k = 0; k < x.length; k++) out.writeFloat(x[k]);
                float[] y = ys.get(i);
                for (int k = 0; k < m.outDim; k++) out.writeFloat(y[k]);
            }
        } finally {
            try { out.close(); } catch (Throwable ignored) { }
        }
        File f = dataFile(ctx, m);
        if ((int) (f.length() / recBytes) > MAX_ROWS) trim(f, MAX_ROWS * 3 / 4, recBytes);
        return xs.size();
    }

    /** every note of every part whose curves say something; all parts share one global feature set */
    static void collect(Song song, LocalTuner.Model m, int style, float intensity,
                        List<float[]> xs, List<float[]> ys) {
        if (song == null || m == null) return;
        double tempo = song.tempoAt(0);
        for (Track t : song.tracks) {
            for (Part p : t.parts) {
                if (p.notes.isEmpty()) continue;
                List<Note> ns = new ArrayList<Note>(p.notes);
                Collections.sort(ns, new Comparator<Note>() {
                    public int compare(Note a, Note b) { return Long.compare(a.pos, b.pos); }
                });
                for (int i = 0; i < ns.size(); i++) {
                    float[] y = label(p, ns.get(i), m);
                    boolean any = false;
                    for (int o = 0; o < m.outDim; o++) {
                        if (Math.abs(y[o]) >= MIN_LABEL) { any = true; break; }
                    }
                    if (!any) continue;
                    float[] x = LocalTuner.features(ns, i, m);
                    xs.add(LocalTuner.normInput(m, x, tempo, style, intensity, ns.size()));
                    ys.add(y);
                }
            }
        }
    }

    /** all stored samples for this model layout, ready for {@link #fit} */
    public static void load(File data, LocalTuner.Model m, List<float[]> xs, List<float[]> ys)
            throws IOException {
        if (data == null || !data.exists() || m == null) return;
        int inDim = m.inDim, outDim = m.outDim;
        DataInputStream in = new DataInputStream(new FileInputStream(data));
        try {
            while (true) {
                float[] x = new float[inDim];
                float[] y = new float[outDim];
                try {
                    for (int k = 0; k < inDim; k++) x[k] = in.readFloat();
                } catch (java.io.EOFException eof) {
                    break;
                }
                for (int k = 0; k < outDim; k++) y[k] = in.readFloat();
                xs.add(x);
                ys.add(y);
            }
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * Retrains from the shipped weights on the stored samples.
     *
     * <p>Only the parameters the user actually moved are supervised (a sample that left GEN alone
     * teaches nothing about GEN), and the weights are pulled towards {@code base} while training.
     * The returned model is a copy; {@code base} is never modified.
     */
    public static LocalTuner.Model fit(LocalTuner.Model base, List<float[]> xs, List<float[]> ys,
                                       LocalTuner.Progress cb) {
        int n = xs.size();
        if (n == 0) return base;
        int stride = Math.max(1, (n + MAX_FIT - 1) / MAX_FIT);
        List<float[]> X = new ArrayList<float[]>();
        List<float[]> Y = new ArrayList<float[]>();
        for (int i = 0; i < n; i += stride) {
            X.add(xs.get(i));
            Y.add(ys.get(i));
        }
        int ns = X.size();
        int iters = Math.max(16, Math.min(200, BASE_ITERS / ns));

        int in = base.inDim, h1 = base.h1, h2 = base.h2, out = base.outDim;
        double[] w1 = dbl(base.w1), b1 = dbl(base.b1), w2 = dbl(base.w2), b2 = dbl(base.b2);
        double[] w3 = dbl(base.w3), b3 = dbl(base.b3);
        double[] B1 = w1.clone(), B2 = b1.clone(), B3 = w2.clone(), B4 = b2.clone();
        double[] B5 = w3.clone(), B6 = b3.clone();
        double[] g1 = new double[w1.length], g2 = new double[b1.length];
        double[] g3 = new double[w2.length], g4 = new double[b2.length];
        double[] g5 = new double[w3.length], g6 = new double[b3.length];
        double[] m1 = new double[w1.length], m2 = new double[b1.length];
        double[] m3 = new double[w2.length], m4 = new double[b2.length];
        double[] m5 = new double[w3.length], m6 = new double[b3.length];
        double[] v1 = new double[w1.length], v2 = new double[b1.length];
        double[] v3 = new double[w2.length], v4 = new double[b2.length];
        double[] v5 = new double[w3.length], v6 = new double[b3.length];
        double[] pre1 = new double[h1], act1 = new double[h1];
        double[] pre2 = new double[h2], act2 = new double[h2];
        double[] o = new double[out], err = new double[out];
        double[] d1 = new double[h1], d2 = new double[h2];
        double lambda = L2 * (100.0 / (100.0 + ns));

        for (int ep = 0; ep < iters; ep++) {
            java.util.Arrays.fill(g1, 0d); java.util.Arrays.fill(g2, 0d);
            java.util.Arrays.fill(g3, 0d); java.util.Arrays.fill(g4, 0d);
            java.util.Arrays.fill(g5, 0d); java.util.Arrays.fill(g6, 0d);
            for (int s = 0; s < ns; s++) {
                float[] x = X.get(s);
                float[] y = Y.get(s);
                java.util.Arrays.fill(pre1, 0d);
                for (int i = 0; i < in; i++) {
                    double xv = x[i];
                    if (xv == 0d) continue;
                    int row = i * h1;
                    for (int j = 0; j < h1; j++) pre1[j] += xv * w1[row + j];
                }
                for (int j = 0; j < h1; j++) {
                    double a = pre1[j] + b1[j];
                    act1[j] = a > 0 ? a : 0;
                }
                java.util.Arrays.fill(pre2, 0d);
                for (int j = 0; j < h1; j++) {
                    double av = act1[j];
                    if (av == 0d) continue;
                    int row = j * h2;
                    for (int k = 0; k < h2; k++) pre2[k] += av * w2[row + k];
                }
                for (int k = 0; k < h2; k++) {
                    double a = pre2[k] + b2[k];
                    act2[k] = a > 0 ? a : 0;
                }
                java.util.Arrays.fill(o, 0d);
                for (int k = 0; k < h2; k++) {
                    double av = act2[k];
                    if (av == 0d) continue;
                    int row = k * out;
                    for (int q = 0; q < out; q++) o[q] += av * w3[row + q];
                }
                for (int q = 0; q < out; q++) {
                    o[q] += b3[q];
                    double t = y[q];
                    err[q] = Math.abs(t) >= MIN_LABEL ? (o[q] - t) : 0d;
                }
                java.util.Arrays.fill(d2, 0d);
                for (int k = 0; k < h2; k++) {
                    double av = act2[k];
                    int row = k * out;
                    for (int q = 0; q < out; q++) {
                        g5[row + q] += av * err[q];
                        d2[k] += w3[row + q] * err[q];
                    }
                }
                for (int q = 0; q < out; q++) g6[q] += err[q];
                for (int k = 0; k < h2; k++) d2[k] = pre2[k] + b2[k] > 0 ? d2[k] : 0d;
                java.util.Arrays.fill(d1, 0d);
                for (int j = 0; j < h1; j++) {
                    double av = act1[j];
                    int row = j * h2;
                    for (int k = 0; k < h2; k++) {
                        g3[row + k] += av * d2[k];
                        d1[j] += w2[row + k] * d2[k];
                    }
                }
                for (int k = 0; k < h2; k++) g4[k] += d2[k];
                for (int j = 0; j < h1; j++) d1[j] = pre1[j] + b1[j] > 0 ? d1[j] : 0d;
                for (int i = 0; i < in; i++) {
                    double xv = x[i];
                    if (xv == 0d) continue;
                    int row = i * h1;
                    for (int j = 0; j < h1; j++) g1[row + j] += xv * d1[j];
                }
                for (int j = 0; j < h1; j++) g2[j] += d1[j];
            }
            double inv = 1.0 / ns;
            for (int i = 0; i < g1.length; i++) g1[i] *= inv;
            for (int i = 0; i < g2.length; i++) g2[i] *= inv;
            for (int i = 0; i < g3.length; i++) g3[i] *= inv;
            for (int i = 0; i < g4.length; i++) g4[i] *= inv;
            for (int i = 0; i < g5.length; i++) g5[i] *= inv;
            for (int i = 0; i < g6.length; i++) g6[i] *= inv;
            int step = ep + 1;
            float lr = LR * (ep < iters / 2 ? 1f : 0.35f);
            adam(w1, g1, m1, v1, B1, lr, lambda, step);
            adam(b1, g2, m2, v2, B2, lr, lambda, step);
            adam(w2, g3, m3, v3, B3, lr, lambda, step);
            adam(b2, g4, m4, v4, B4, lr, lambda, step);
            adam(w3, g5, m5, v5, B5, lr, lambda, step);
            adam(b3, g6, m6, v6, B6, lr, lambda, step);
            if (cb != null && (ep % 5 == 0 || ep == iters - 1))
                cb.onProgress(ep + 1, iters, "自学习微调");
        }

        double alpha = Math.max(0.17, Math.min(0.45, 0.15 + ns / 500.0));
        blend(w1, B1, alpha); blend(b1, B2, alpha);
        blend(w2, B3, alpha); blend(b2, B4, alpha);
        blend(w3, B5, alpha); blend(b3, B6, alpha);

        LocalTuner.Model outM = new LocalTuner.Model();
        outM.inDim = base.inDim;
        outM.h1 = base.h1;
        outM.h2 = base.h2;
        outM.outDim = base.outDim;
        outM.mu = base.mu.clone();
        outM.sd = base.sd.clone();
        outM.outScale = base.outScale.clone();
        outM.w1 = flt(w1); outM.b1 = flt(b1);
        outM.w2 = flt(w2); outM.b2 = flt(b2);
        outM.w3 = flt(w3); outM.b3 = flt(b3);
        return outM;
    }

    private static void blend(double[] p, double[] base, double alpha) {
        for (int i = 0; i < p.length; i++) p[i] = base[i] + alpha * (p[i] - base[i]);
    }

    private static void adam(double[] p, double[] g, double[] mm, double[] vv, double[] base,
                             float lr, double lambda, int step) {
        double c1 = 1 - Math.pow(0.9, step), c2 = 1 - Math.pow(0.999, step);
        for (int i = 0; i < p.length; i++) {
            double gr = g[i] + lambda * (p[i] - base[i]);
            mm[i] = 0.9 * mm[i] + 0.1 * gr;
            vv[i] = 0.999 * vv[i] + 0.001 * gr * gr;
            p[i] -= lr * (mm[i] / c1) / (Math.sqrt(vv[i] / c2) + 1e-8);
        }
    }

    private static double[] dbl(float[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = a[i];
        return d;
    }

    private static float[] flt(double[] a) {
        float[] f = new float[a.length];
        for (int i = 0; i < a.length; i++) f[i] = (float) a[i];
        return f;
    }
}
