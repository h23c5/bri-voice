package com.dsh.v4tune.synth;

import com.dsh.v4tune.phoneme.PhonemeDef;
import com.dsh.v4tune.phoneme.PhonemeTable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * On-device neural spectral voice model (v9.0).
 *
 * <p>Two-stage training, both on the phone:</p>
 * <ol>
 *   <li><b>Universal prior</b> - {@link #trainUniversal(List, Progress)} learns the common
 *       spectral-envelope patterns of every installed voicebank. Only the harmonic frames already
 *       measured into each {@code .v4voice} are read; no audio samples are decoded.</li>
 *   <li><b>Per-singer acoustic model</b> - {@link #fineTune(NeuralSpectrum, VoiceModel, Progress)}
 *       starts from that prior and adapts it to one singer, so the result keeps the singer's own
 *       colour while needing only a few hundred frames.</li>
 * </ol>
 *
 * <p>The network is deliberately small (one tanh hidden layer, 64-bin envelope). It predicts the
 * harmonic envelope used by the measured-harmonic renderer; the glottal source, formants,
 * consonants and effects still come from the existing synthesizer. This is an acoustic
 * <i>spectral</i> model, not a full XStudio/NNSVS acoustic model trained on audio.</p>
 *
 * <p>Inputs are bank-independent articulatory features (phoneme class, F1/F2/F3, voicing, noise,
 * nasal zero, pitch, DYN and note position), so a universal prior transfers to any singer without
 * needing the same phoneme inventory.</p>
 */
public class NeuralSpectrum {
    private static final int MAGIC = 0x56344E4E; // "V4NN"
    private static final int VERSION = 2;
    private static final int VERSION_LEGACY = 1;

    public static final int KIND_BANK = 0;
    public static final int KIND_UNIVERSAL = 1;
    public static final int KIND_FINE = 2;

    private static final int GRID = 64;
    private static final double GRID_LO = 60.0;
    private static final double GRID_HI = 16000.0;
    private static final int FEATURE_DIM = 27;
    private static final int MAX_SYMBOLS = 2048;
    private static final int MAX_GRID = 4096;
    private static final int MAX_SAMPLES = 36000;
    private static final int HIDDEN_BANK = 32;
    private static final int HIDDEN_UNIVERSAL = 48;

    private static final int EPOCHS_BANK = 120;
    private static final int EPOCHS_UNIVERSAL = 80;
    private static final int EPOCHS_FINE = 55;
    private static final int BATCH = 16;
    private static final double LR = 0.010;
    private static final double LR_FINE = 0.0045;
    private static final double SCALE = 12.0;

    /** fixed log-spaced analysis grid shared by every model (Hz) */
    private static final double[] GRID_HZ = buildGrid();

    public final String[] symbols;
    public final int gridLen;
    public final int hidden;
    public final int inputDim;
    public final float[] meanDb;
    public final float[][] w1;
    public final float[] b1;
    public final float[][] w2;
    public final float[] b2;
    /** {@link #KIND_BANK}, {@link #KIND_UNIVERSAL} or {@link #KIND_FINE}. */
    public final int kind;
    /** number of voicebanks that contributed training data (1 for a per-bank model). */
    public final int bankCount;
    /** number of measured harmonic frames used for training. */
    public final int sampleCount;
    public final double trainLoss;
    public final double valLoss;
    /** true for models written by the earlier 8.2.1 prototype (per-symbol one-hot inputs). */
    public final boolean legacy;

    private final LinkedHashMap<String, Integer> index = new LinkedHashMap<String, Integer>();
    private final boolean[] known;

    private NeuralSpectrum(String[] symbols, int gridLen, float[] meanDb,
                           float[][] w1, float[] b1, float[][] w2, float[] b2,
                           int kind, int bankCount, int sampleCount,
                           double trainLoss, double valLoss, boolean legacy) {
        this.symbols = symbols == null ? new String[0] : symbols;
        this.gridLen = gridLen;
        this.meanDb = meanDb;
        this.w1 = w1;
        this.b1 = b1;
        this.w2 = w2;
        this.b2 = b2;
        this.hidden = b1.length;
        this.inputDim = (w1.length > 0 && w1[0] != null) ? w1[0].length : symbols.length + 3;
        this.kind = kind;
        this.bankCount = bankCount;
        this.sampleCount = sampleCount;
        this.trainLoss = trainLoss;
        this.valLoss = valLoss;
        this.legacy = legacy;
        this.known = new boolean[this.symbols.length];
        for (int i = 0; i < this.symbols.length; i++) {
            this.symbols[i] = this.symbols[i] == null ? "" : this.symbols[i];
            this.index.put(this.symbols[i], Integer.valueOf(i));
            this.known[i] = true;
        }
    }

    // ------------------------------------------------------------------ inference

    public boolean hasSymbol(String sym) {
        if (sym == null) return false;
        if (legacy) return index.containsKey(sym);
        return index.containsKey(sym);
    }

    /** Fill out[0..gridLen-1] with predicted relative dB on the model grid. */
    public boolean predict(String sym, double f0, double dyn, double pos, double[] out) {
        Integer ix = index.get(sym);
        if (ix == null || out == null || out.length < gridLen) return false;
        float[] x = legacy ? legacyInput(ix.intValue(), f0, dyn, pos) : features(sym, f0, dyn, pos);
        float[] h = new float[hidden];
        float[] o = new float[gridLen];
        forward(x, h, o);
        for (int k = 0; k < gridLen; k++) {
            double db = meanDb[k] + SCALE * Math.tanh(o[k]);
            if (db < -90.0) db = -90.0;
            else if (db > 6.0) db = 6.0;
            out[k] = db;
        }
        return true;
    }

    /**
     * Predict on an arbitrary target frequency grid. Models store a canonical 64-bin grid, while a
     * voicebank may have its own grid, so the envelope is interpolated in log-frequency.
     */
    public boolean predictForGrid(String sym, double f0, double dyn, double pos,
                                  double[] targetFreqs, double[] out) {
        if (targetFreqs == null || targetFreqs.length < 4 || out == null
                || out.length < targetFreqs.length) {
            return predict(sym, f0, dyn, pos, out);
        }
        if (legacy) {
            if (gridLen != targetFreqs.length) return false;
            return predict(sym, f0, dyn, pos, out);
        }
        double[] mid = new double[gridLen];
        if (!predict(sym, f0, dyn, pos, mid)) return false;
        sampleToGrid(GRID_HZ, mid, targetFreqs, out);
        return true;
    }

    private float[] legacyInput(int symIndex, double f0, double dyn, double pos) {
        float[] x = new float[inputDim];
        if (symIndex >= 0 && symIndex < symbols.length) x[symIndex] = 1f;
        double lf = (f0 > 20.0) ? Math.log(f0 / 220.0) / Math.log(2.0) / 2.0 : 0.0;
        if (lf < -3) lf = -3; else if (lf > 3) lf = 3;
        x[symbols.length] = (float) lf;
        x[symbols.length + 1] = (float) ((clamp01(dyn) - 0.5) * 2.0);
        x[symbols.length + 2] = (float) ((clamp01(pos) - 0.5) * 2.0);
        return x;
    }

    private static float[] features(String sym, double f0, double dyn, double pos) {
        float[] x = new float[FEATURE_DIM];
        PhonemeDef d = PhonemeTable.getOrDefault(sym == null ? "a" : sym);
        int type = d == null ? PhonemeDef.T_VOWEL : d.type;
        if (type < 0 || type > 8) type = 0;
        x[type] = 1f;
        x[9] = (float) clamp((d == null ? 0 : d.f1 - 500) / 900.0, -2, 2);
        x[10] = (float) clamp((d == null ? 0 : d.f2 - 1500) / 1500.0, -2, 2);
        x[11] = (float) clamp((d == null ? 0 : d.f3 - 2500) / 1800.0, -2, 2);
        x[12] = (float) (d == null ? 1.0 : clamp01(d.voiced));
        x[13] = (float) (d == null ? 0.0 : clamp01(d.noise));
        x[14] = (float) (d == null ? 0.0 : clamp(d.tilt / 6.0, -2, 2));
        x[15] = (float) (d == null ? 0.0 : clamp01(d.nasalZero));
        x[16] = (float) (d == null ? 0.0 : clamp(d.noiseFc / 8000.0, 0, 2));
        x[17] = (float) (d == null ? 0.0 : clamp(d.noiseBw / 4000.0, 0, 2));
        // six cheap symbol-hash features keep some identity without forcing a shared inventory
        int h = (sym == null) ? 0 : sym.hashCode();
        for (int k = 0; k < 6; k++) {
            int v = (h >>> (k * 5)) & 31;
            x[18 + k] = (float) ((v / 31.0) * 2.0 - 1.0);
        }
        double lf = (f0 > 20.0) ? Math.log(f0 / 220.0) / Math.log(2.0) / 2.0 : 0.0;
        x[24] = (float) clamp(lf, -3, 3);
        x[25] = (float) ((clamp01(dyn) - 0.5) * 2.0);
        x[26] = (float) ((clamp01(pos) - 0.5) * 2.0);
        return x;
    }

    private void forward(float[] x, float[] h, float[] o) {
        for (int j = 0; j < hidden; j++) {
            double z = b1[j];
            float[] wj = w1[j];
            for (int i = 0; i < x.length; i++) z += wj[i] * x[i];
            h[j] = (float) Math.tanh(z);
        }
        for (int k = 0; k < gridLen; k++) {
            double z = b2[k];
            float[] wk = w2[k];
            for (int j = 0; j < hidden; j++) z += wk[j] * h[j];
            o[k] = (float) z;
        }
    }

    public String describe() {
        if (legacy) return "旧版模型 · " + sampleCount + " 帧";
        String base;
        if (kind == KIND_UNIVERSAL) base = "通用模型";
        else if (kind == KIND_FINE) base = "当前歌手模型";
        else base = "单声库模型";
        return base + " · " + Math.max(1, bankCount) + " 声库 · " + sampleCount + " 帧"
                + " · 验证 " + fmt(valLoss);
    }

    // ------------------------------------------------------------------ training

    public interface Progress {
        /** percent 0..100; message is a short human-readable state. */
        void onProgress(int percent, String message);
    }

    /** Train a model from one voicebank's measured harmonics (legacy single-bank path). */
    public static NeuralSpectrum train(VoiceModel vm, Progress cb) throws IOException {
        if (vm == null || vm.harmonicFreqs == null || vm.harmonicFreqs.length < 4
                || vm.harmonics.isEmpty()) {
            throw new IOException("当前声库没有实测谐波数据，无法训练神经模型");
        }
        if (cb != null) cb.onProgress(2, "读取声库频谱…");
        Data data = collect(java.util.Collections.singletonList(vm), false, cb);
        if (data.count < 8) throw new IOException("实测帧太少，无法训练");
        return fit(data, KIND_BANK, 1, HIDDEN_BANK, EPOCHS_BANK, LR, null, cb);
    }

    /**
     * Learn the shared spectral rules of every installed voicebank. This is the universal prior
     * that per-singer fine-tuning starts from.
     */
    public static NeuralSpectrum trainUniversal(List<VoiceModel> banks, Progress cb)
            throws IOException {
        List<VoiceModel> usable = usableBanks(banks);
        if (usable.isEmpty()) throw new IOException("没有可用的实测声库（需要含谐波数据的 .v4voice）");
        if (cb != null) cb.onProgress(2, "读取 " + usable.size() + " 个声库的频谱…");
        Data data = collect(usable, true, cb);
        if (data.count < 8) throw new IOException("实测帧太少，无法训练通用模型");
        return fit(data, KIND_UNIVERSAL, usable.size(), HIDDEN_UNIVERSAL, EPOCHS_UNIVERSAL, LR,
                null, cb);
    }

    /**
     * Adapt a universal prior to one singer. Falls back to {@link #train(VoiceModel, Progress)}
     * when the prior is missing or was written by the old prototype.
     */
    public static NeuralSpectrum fineTune(NeuralSpectrum prior, VoiceModel vm, Progress cb)
            throws IOException {
        if (vm == null || vm.harmonicFreqs == null || vm.harmonicFreqs.length < 4
                || vm.harmonics.isEmpty()) {
            throw new IOException("当前声库没有实测谐波数据，无法微调");
        }
        if (prior == null || prior.legacy || prior.gridLen != GRID) {
            return train(vm, cb);
        }
        if (cb != null) cb.onProgress(2, "读取 " + vm.name + " 的频谱…");
        Data data = collect(java.util.Collections.singletonList(vm), false, cb);
        if (data.count < 8) throw new IOException("当前声库实测帧太少，无法微调");
        return fit(data, KIND_FINE, 1, prior.hidden, EPOCHS_FINE, LR_FINE, prior, cb);
    }

    private static List<VoiceModel> usableBanks(List<VoiceModel> banks) {
        ArrayList<VoiceModel> out = new ArrayList<VoiceModel>();
        if (banks == null) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (VoiceModel vm : banks) {
            if (vm == null || vm.harmonicFreqs == null || vm.harmonicFreqs.length < 4
                    || vm.harmonics == null || vm.harmonics.isEmpty()) continue;
            String key = (vm.id == null || vm.id.isEmpty()) ? vm.name : vm.id;
            if (key == null || !seen.add(key)) continue;
            out.add(vm);
        }
        return out;
    }

    /** input features + canonical-grid targets collected from measured bands */
    private static final class Data {
        int count;
        float[][] x;
        float[][] y;
        String[] known;
    }

    private static Data collect(List<VoiceModel> banks, boolean universal, Progress cb) {
        final int nb = Math.max(1, banks.size());
        final int perBank = universal ? Math.max(96, MAX_SAMPLES / nb) : MAX_SAMPLES;
        final Random rnd = new Random(20261121L);
        ArrayList<float[]> xs = new ArrayList<float[]>();
        ArrayList<float[]> ys = new ArrayList<float[]>();
        LinkedHashSet<String> known = new LinkedHashSet<String>();
        int totalSeen = 0;
        int bankIndex = 0;
        for (VoiceModel vm : banks) {
            bankIndex++;
            if (cb != null) {
                cb.onProgress(2 + (int) Math.round(bankIndex * 10.0 / nb),
                        "读取声库 " + bankIndex + "/" + nb + "：" + (vm.name == null ? vm.id : vm.name));
            }
            ArrayList<float[]> bx = new ArrayList<float[]>();
            ArrayList<float[]> by = new ArrayList<float[]>();
            int seen = 0;
            for (java.util.Map.Entry<String, VoiceModel.HarmonicBand[]> e : vm.harmonics.entrySet()) {
                String sym = e.getKey();
                VoiceModel.HarmonicBand[] bands = e.getValue();
                if (sym == null || bands == null) continue;
                known.add(sym);
                for (VoiceModel.HarmonicBand b : bands) {
                    if (b == null) continue;
                    double[][] frames = (b.seq != null && b.seq.length > 0)
                            ? b.seq : new double[][]{b.db};
                    for (int fi = 0; fi < frames.length; fi++) {
                        double pos = frames.length > 1 ? fi / (double) (frames.length - 1) : 0.5;
                        float[] x = features(sym, b.f0, b.dyn, pos);
                        float[] y = canonicalTarget(vm.harmonicFreqs, frames[fi]);
                        if (y == null) continue;
                        if (bx.size() < perBank) {
                            bx.add(x);
                            by.add(y);
                        } else {
                            int j = rnd.nextInt(seen + 1);
                            if (j < perBank) { bx.set(j, x); by.set(j, y); }
                        }
                        seen++;
                    }
                }
            }
            totalSeen += bx.size();
            for (int i = 0; i < bx.size(); i++) {
                if (xs.size() < MAX_SAMPLES) {
                    xs.add(bx.get(i));
                    ys.add(by.get(i));
                } else {
                    int j = rnd.nextInt(totalSeen + 1);
                    if (j < MAX_SAMPLES) { xs.set(j, bx.get(i)); ys.set(j, by.get(i)); }
                }
            }
        }
        Data d = new Data();
        d.count = xs.size();
        d.x = xs.toArray(new float[0][]);
        d.y = ys.toArray(new float[0][]);
        d.known = known.toArray(new String[0]);
        if (cb != null) cb.onProgress(14, "已读取 " + d.count + " 帧实测频谱，开始训练…");
        return d;
    }

    private static float[] canonicalTarget(double[] freqs, double[] db) {
        if (freqs == null || freqs.length < 4 || db == null || db.length == 0) return null;
        float[] y = new float[GRID];
        double[] tmp = new double[GRID];
        sampleToGrid(freqs, db, GRID_HZ, tmp);
        for (int k = 0; k < GRID; k++) {
            double v = tmp[k];
            if (Double.isNaN(v)) v = -60.0;
            if (v < -60.0) v = -60.0; else if (v > 6.0) v = 6.0;
            y[k] = (float) v;
        }
        return y;
    }

    private static NeuralSpectrum fit(Data d, int kind, int bankCount, int hidden, int epochs,
                                      double lr, NeuralSpectrum init, Progress cb)
            throws IOException {
        if (d == null || d.count < 8) throw new IOException("训练样本太少");
        final int n = d.count;
        final int grid = GRID;
        final int in = FEATURE_DIM;

        // centre targets on the mean spectrum of this dataset
        float[] mean = new float[grid];
        for (int q = 0; q < n; q++) for (int k = 0; k < grid; k++) mean[k] += d.y[q][k];
        for (int k = 0; k < grid; k++) mean[k] /= n;
        for (int q = 0; q < n; q++) for (int k = 0; k < grid; k++) {
            double t = (d.y[q][k] - mean[k]) / SCALE;
            if (t < -1) t = -1; else if (t > 1) t = 1;
            d.y[q][k] = (float) t;
        }

        final Random rnd = new Random(20261122L);
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        shuffle(order, rnd);
        int valN = Math.max(2, n / 5);
        if (valN >= n) valN = n / 2;
        int trainN = n - valN;
        if (trainN < 4) throw new IOException("训练样本太少");
        int[] trainOrder = new int[trainN];
        for (int i = 0; i < trainN; i++) trainOrder[i] = order[i];

        float[][] w1;
        float[] b1;
        float[][] w2;
        float[] b2;
        if (init != null && init.w1.length == hidden && init.w1[0].length == in
                && init.w2.length == grid && init.w2[0].length == hidden) {
            w1 = copy(init.w1);
            b1 = init.b1.clone();
            w2 = copy(init.w2);
            b2 = init.b2.clone();
        } else {
            w1 = new float[hidden][in];
            b1 = new float[hidden];
            w2 = new float[grid][hidden];
            b2 = new float[grid];
            for (int j = 0; j < hidden; j++) {
                double s = Math.sqrt(6.0 / (in + hidden));
                for (int i = 0; i < in; i++) w1[j][i] = (float) ((rnd.nextDouble() * 2 - 1) * s);
            }
            for (int k = 0; k < grid; k++) {
                double s = Math.sqrt(6.0 / (hidden + grid));
                for (int j = 0; j < hidden; j++) w2[k][j] = (float) ((rnd.nextDouble() * 2 - 1) * s);
            }
        }

        float[][] mw1 = new float[hidden][in], vw1 = new float[hidden][in];
        float[] mb1 = new float[hidden], vb1 = new float[hidden];
        float[][] mw2 = new float[grid][hidden], vw2 = new float[grid][hidden];
        float[] mb2 = new float[grid], vb2 = new float[grid];
        float[][] gw1 = new float[hidden][in];
        float[] gb1 = new float[hidden];
        float[][] gw2 = new float[grid][hidden];
        float[] gb2 = new float[grid];
        int step = 0;

        for (int ep = 1; ep <= epochs; ep++) {
            shuffle(trainOrder, rnd);
            for (int base = 0; base < trainN; base += BATCH) {
                int end = Math.min(trainN, base + BATCH);
                zero(gw1); zero(gb1); zero(gw2); zero(gb2);
                for (int q = base; q < end; q++) {
                    int idx = trainOrder[q];
                    float[] x = d.x[idx];
                    float[] y = d.y[idx];
                    float[] h = new float[hidden];
                    float[] o = new float[grid];
                    for (int j = 0; j < hidden; j++) {
                        double z = b1[j];
                        for (int i = 0; i < in; i++) z += w1[j][i] * x[i];
                        h[j] = (float) Math.tanh(z);
                    }
                    for (int k = 0; k < grid; k++) {
                        double z = b2[k];
                        for (int j = 0; j < hidden; j++) z += w2[k][j] * h[j];
                        o[k] = (float) z;
                    }
                    for (int k = 0; k < grid; k++) {
                        double p = Math.tanh(o[k]);
                        double dO = (p - y[k]) * (1.0 - p * p);
                        gb2[k] += (float) dO;
                        for (int j = 0; j < hidden; j++) gw2[k][j] += (float) (dO * h[j]);
                    }
                    float[] dH = new float[hidden];
                    for (int k = 0; k < grid; k++) {
                        double p = Math.tanh(o[k]);
                        double dO = (p - y[k]) * (1.0 - p * p);
                        for (int j = 0; j < hidden; j++) dH[j] += (float) (dO * w2[k][j]);
                    }
                    for (int j = 0; j < hidden; j++) {
                        double dd = dH[j] * (1.0 - h[j] * (double) h[j]);
                        gb1[j] += (float) dd;
                        for (int i = 0; i < in; i++) gw1[j][i] += (float) (dd * x[i]);
                    }
                }
                int bn = end - base;
                step++;
                double bc1 = 1.0 - Math.pow(0.9, step);
                double bc2 = 1.0 - Math.pow(0.999, step);
                for (int j = 0; j < hidden; j++) {
                    for (int i = 0; i < in; i++) {
                        mw1[j][i] = (float) (0.9 * mw1[j][i] + 0.1 * gw1[j][i] / bn);
                        vw1[j][i] = (float) (0.999 * vw1[j][i] + 0.001 * (gw1[j][i] / bn) * (gw1[j][i] / bn));
                        w1[j][i] -= (float) (lr * (mw1[j][i] / bc1) / (Math.sqrt(vw1[j][i] / bc2) + 1e-8));
                    }
                    mb1[j] = (float) (0.9 * mb1[j] + 0.1 * gb1[j] / bn);
                    vb1[j] = (float) (0.999 * vb1[j] + 0.001 * (gb1[j] / bn) * (gb1[j] / bn));
                    b1[j] -= (float) (lr * (mb1[j] / bc1) / (Math.sqrt(vb1[j] / bc2) + 1e-8));
                }
                for (int k = 0; k < grid; k++) {
                    for (int j = 0; j < hidden; j++) {
                        mw2[k][j] = (float) (0.9 * mw2[k][j] + 0.1 * gw2[k][j] / bn);
                        vw2[k][j] = (float) (0.999 * vw2[k][j] + 0.001 * (gw2[k][j] / bn) * (gw2[k][j] / bn));
                        w2[k][j] -= (float) (lr * (mw2[k][j] / bc1) / (Math.sqrt(vw2[k][j] / bc2) + 1e-8));
                    }
                    mb2[k] = (float) (0.9 * mb2[k] + 0.1 * gb2[k] / bn);
                    vb2[k] = (float) (0.999 * vb2[k] + 0.001 * (gb2[k] / bn) * (gb2[k] / bn));
                    b2[k] -= (float) (lr * (mb2[k] / bc1) / (Math.sqrt(vb2[k] / bc2) + 1e-8));
                }
            }
            if (cb != null && (ep == 1 || ep % 5 == 0 || ep == epochs)) {
                double tr = 0;
                for (int q = 0; q < trainN; q++) tr += sampleLoss(w1, b1, w2, b2, d.x[trainOrder[q]], d.y[trainOrder[q]], grid, hidden, in);
                tr /= trainN;
                double val = 0;
                int vn = 0;
                for (int q = trainN; q < n; q++) {
                    int idx = order[q];
                    val += sampleLoss(w1, b1, w2, b2, d.x[idx], d.y[idx], grid, hidden, in);
                    vn++;
                }
                if (vn > 0) val /= vn;
                cb.onProgress(15 + (int) Math.round(ep * 83.0 / epochs),
                        "第 " + ep + "/" + epochs + " 轮 · 训练损失 " + fmt(tr)
                                + " · 验证损失 " + fmt(val));
            }
        }

        double tr = 0;
        for (int q = 0; q < trainN; q++) tr += sampleLoss(w1, b1, w2, b2, d.x[trainOrder[q]], d.y[trainOrder[q]], grid, hidden, in);
        tr /= trainN;
        double val = 0;
        int vn = 0;
        for (int q = trainN; q < n; q++) {
            int idx = order[q];
            val += sampleLoss(w1, b1, w2, b2, d.x[idx], d.y[idx], grid, hidden, in);
            vn++;
        }
        if (vn > 0) val /= vn;
        if (cb != null) cb.onProgress(99, "已保存模型");
        return new NeuralSpectrum(d.known, grid, mean, w1, b1, w2, b2,
                kind, bankCount, n, tr, val, false);
    }

    private static double sampleLoss(float[][] w1, float[] b1, float[][] w2, float[] b2,
                                     float[] x, float[] y, int grid, int hid, int in) {
        float[] h = new float[hid];
        for (int j = 0; j < hid; j++) {
            double z = b1[j];
            for (int i = 0; i < in; i++) z += w1[j][i] * x[i];
            h[j] = (float) Math.tanh(z);
        }
        double sum = 0;
        for (int k = 0; k < grid; k++) {
            double z = b2[k];
            for (int j = 0; j < hid; j++) z += w2[k][j] * h[j];
            double dd = Math.tanh(z) - y[k];
            sum += 0.5 * dd * dd;
        }
        return sum / grid;
    }

    // ------------------------------------------------------------------ persistence

    public void save(File f) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)));
        try {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(kind);
            out.writeInt(bankCount);
            out.writeInt(sampleCount);
            out.writeFloat((float) trainLoss);
            out.writeFloat((float) valLoss);
            out.writeInt(symbols.length);
            for (int s = 0; s < symbols.length; s++) out.writeUTF(symbols[s]);
            out.writeInt(hidden);
            out.writeInt(gridLen);
            out.writeFloat((float) SCALE);
            for (int k = 0; k < gridLen; k++) out.writeFloat(meanDb[k]);
            for (int j = 0; j < hidden; j++) for (int i = 0; i < inputDim; i++) out.writeFloat(w1[j][i]);
            for (int j = 0; j < hidden; j++) out.writeFloat(b1[j]);
            for (int k = 0; k < gridLen; k++) for (int j = 0; j < hidden; j++) out.writeFloat(w2[k][j]);
            for (int k = 0; k < gridLen; k++) out.writeFloat(b2[k]);
        } finally {
            out.close();
        }
    }

    public static NeuralSpectrum load(File f) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
        try {
            if (in.readInt() != MAGIC) throw new IOException("不是神经声库文件");
            int version = in.readInt();
            if (version == VERSION_LEGACY) return loadLegacy(in);
            if (version != VERSION) throw new IOException("神经声库版本不支持");
            int kind = in.readInt();
            int bankCount = in.readInt();
            int sampleCount = in.readInt();
            float trainLoss = in.readFloat();
            float valLoss = in.readFloat();
            int ns = in.readInt();
            if (ns < 0 || ns > MAX_SYMBOLS) throw new IOException("神经声库尺寸无效");
            String[] syms = new String[ns];
            for (int s = 0; s < ns; s++) syms[s] = in.readUTF();
            int hid = in.readInt();
            int grid = in.readInt();
            float scale = in.readFloat();
            if (hid <= 0 || hid > 256 || grid <= 0 || grid > MAX_GRID || Math.abs(scale - SCALE) > 1e-3) {
                throw new IOException("神经声库尺寸无效");
            }
            float[] mean = new float[grid];
            for (int k = 0; k < grid; k++) mean[k] = in.readFloat();
            float[][] w1 = new float[hid][FEATURE_DIM];
            for (int j = 0; j < hid; j++) for (int i = 0; i < FEATURE_DIM; i++) w1[j][i] = in.readFloat();
            float[] b1 = new float[hid];
            for (int j = 0; j < hid; j++) b1[j] = in.readFloat();
            float[][] w2 = new float[grid][hid];
            for (int k = 0; k < grid; k++) for (int j = 0; j < hid; j++) w2[k][j] = in.readFloat();
            float[] b2 = new float[grid];
            for (int k = 0; k < grid; k++) b2[k] = in.readFloat();
            return new NeuralSpectrum(syms, grid, mean, w1, b1, w2, b2,
                    kind, bankCount, sampleCount, trainLoss, valLoss, false);
        } finally {
            in.close();
        }
    }

    private static NeuralSpectrum loadLegacy(DataInputStream in) throws IOException {
        int ns = in.readInt();
        int inputDim = in.readInt();
        int hid = in.readInt();
        int grid = in.readInt();
        float scale = in.readFloat();
        if (ns <= 0 || ns > MAX_SYMBOLS || inputDim <= 0 || inputDim > MAX_SYMBOLS + 16
                || hid <= 0 || hid > 256 || grid <= 0 || grid > MAX_GRID
                || Math.abs(scale - SCALE) > 1e-3) throw new IOException("神经声库尺寸无效");
        String[] syms = new String[ns];
        for (int s = 0; s < ns; s++) syms[s] = in.readUTF();
        float[] mean = new float[grid];
        for (int k = 0; k < grid; k++) mean[k] = in.readFloat();
        float[][] w1 = new float[hid][inputDim];
        for (int j = 0; j < hid; j++) for (int i = 0; i < inputDim; i++) w1[j][i] = in.readFloat();
        float[] b1 = new float[hid];
        for (int j = 0; j < hid; j++) b1[j] = in.readFloat();
        float[][] w2 = new float[grid][hid];
        for (int k = 0; k < grid; k++) for (int j = 0; j < hid; j++) w2[k][j] = in.readFloat();
        float[] b2 = new float[grid];
        for (int k = 0; k < grid; k++) b2[k] = in.readFloat();
        return new NeuralSpectrum(syms, grid, mean, w1, b1, w2, b2,
                KIND_BANK, 1, 0, 0, 0, true);
    }

    // ------------------------------------------------------------------ helpers

    private static double[] buildGrid() {
        double[] f = new double[GRID];
        double ratio = Math.log(GRID_HI / GRID_LO);
        for (int k = 0; k < GRID; k++) f[k] = GRID_LO * Math.exp(ratio * k / (GRID - 1.0));
        return f;
    }

    /** Log-frequency linear interpolation; -90 dB outside the source range. */
    private static void sampleToGrid(double[] srcFreqs, double[] srcVals, double[] dstFreqs, double[] out) {
        int n = Math.min(srcFreqs.length, srcVals.length);
        for (int k = 0; k < out.length; k++) {
            double f = dstFreqs[k];
            if (n == 0 || f <= 0) { out[k] = -90; continue; }
            if (f <= srcFreqs[0]) { out[k] = srcVals[0]; continue; }
            if (f >= srcFreqs[n - 1]) { out[k] = srcVals[n - 1]; continue; }
            int lo = 0, hi = n - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (srcFreqs[mid] <= f) lo = mid; else hi = mid;
            }
            double f0 = srcFreqs[lo], f1 = srcFreqs[hi];
            double t = (f1 > f0) ? Math.log(f / f0) / Math.log(f1 / f0) : 0;
            out[k] = srcVals[lo] + (srcVals[hi] - srcVals[lo]) * t;
        }
    }

    private static float[][] copy(float[][] a) {
        float[][] b = new float[a.length][];
        for (int i = 0; i < a.length; i++) b[i] = a[i].clone();
        return b;
    }

    private static void zero(float[][] a) { for (float[] r : a) Arrays.fill(r, 0f); }
    private static void zero(float[] a) { Arrays.fill(a, 0f); }
    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
    private static void shuffle(int[] a, Random r) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = a[i]; a[i] = a[j]; a[j] = t;
        }
    }
}
