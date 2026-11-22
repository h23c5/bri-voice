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
 * On-device neural spectral voice model (v9.1, BriNeting base).
 *
 * <p>Two-stage training, both on the phone:</p>
 * <ol>
 *   <li><b>BriNeting base</b> - {@link #trainUniversal(List, Progress)} learns the common
 *       spectral-envelope patterns of every installed voicebank. Only the harmonic frames already
 *       measured into each {@code .v4voice} are read; no audio samples are decoded.</li>
 *   <li><b>Per-singer acoustic model</b> - {@link #fineTune(NeuralSpectrum, VoiceModel, Progress)}
 *       starts from that prior and adapts it to one singer, so the result keeps the singer's own
 *       colour while needing only a few hundred frames.</li>
 * </ol>
 *
 * <p>v9.4 adds {@link #trainFusion(List, Progress)}: several installed voicebanks are fused into
 * one wider network (112x72 hidden, 2x the parameters of the base) so the result blends their
 * measured spectra instead of sounding like any single one of them.</p>
 *
 * <p>The v9.3.2 network is a two-layer tanh MLP (64x48 hidden) predicting a 64-bin envelope.
 * Compared with the first version it adds articulation features (F1/F2/F3 spacing, voiced-noise
 * interaction, consonant place), a perceptual band weighting, early stopping on a held-out split
 * and an L2-SP pull back to the universal prior during fine-tuning. The glottal source, formants,
 * consonants and effects still come from the existing synthesizer; this remains a spectral-envelope
 * model, not a full XStudio/NNSVS acoustic model trained on audio.</p>
 *
 * <p>Inputs are bank-independent articulatory features (phoneme class, F1/F2/F3, voicing, noise,
 * nasal zero, pitch, DYN and note position), so a universal prior transfers to any singer without
 * needing the same phoneme inventory.</p>
 */
public class NeuralSpectrum {
    private static final int MAGIC = 0x56344E4E; // "V4NN"
    private static final int VERSION = 3;
    private static final int VERSION_V2 = 2;
    private static final int VERSION_LEGACY = 1;

    public static final int KIND_BANK = 0;
    public static final int KIND_UNIVERSAL = 1;
    public static final int KIND_FINE = 2;
    /** 2..n voicebanks fused into one wider network */
    public static final int KIND_FUSION = 3;

    private static final int GRID = 64;
    private static final double GRID_LO = 60.0;
    private static final double GRID_HI = 16000.0;
    private static final int FEATURE_DIM = 33;
    private static final int MAX_SYMBOLS = 2048;
    private static final int MAX_GRID = 4096;
    private static final int MAX_SAMPLES = 36000;
    private static final int HID1_BANK = 40;
    private static final int HID1_UNIVERSAL = 64;
    private static final int HID1_FINE = 64;
    private static final int HID2_BANK = 24;
    private static final int HID2_UNIVERSAL = 48;
    private static final int HID2_FINE = 48;
    /** fused model is deliberately wider: ~2x the parameters of the universal base */
    private static final int HID1_FUSION = 112;
    private static final int HID2_FUSION = 72;

    private static final int EPOCHS_BANK = 160;
    private static final int EPOCHS_UNIVERSAL = 130;
    private static final int EPOCHS_FINE = 100;
    private static final int EPOCHS_FUSION = 170;
    /** fusion frames are capped lower than the base so training stays under a couple of minutes */
    private static final int MAX_FUSION_SAMPLES = 26000;
    private static final int BATCH = 16;
    private static final double LR = 0.008;
    private static final double LR_FINE = 0.0035;
    private static final double LR_FUSION = 0.006;
    private static final double SCALE = 12.0;
    /** L2-SP pull towards the universal weights during per-singer fine-tuning. */
    private static final double SP_LAMBDA = 0.0025;
    /** perceptual loss weighting: low/mid bands count more than the top octave */
    private static final double[] BAND_W = buildBandWeights();
    private static final double BAND_W_MEAN = meanOf(BAND_W);

    /** fixed log-spaced analysis grid shared by every model (Hz) */
    private static final double[] GRID_HZ = buildGrid();

    public final String[] symbols;
    public final int gridLen;
    /** width of the first hidden layer */
    public final int hidden;
    /** width of the second hidden layer (0 for a v1/v2 single-layer model) */
    public final int hidden2;
    public final int inputDim;
    public final float[] meanDb;
    public final float[][] w1;
    public final float[] b1;
    public final float[][] w2;
    public final float[] b2;
    public final float[][] w3;
    public final float[] b3;
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
                           float[][] w3, float[] b3,
                           int kind, int bankCount, int sampleCount,
                           double trainLoss, double valLoss, boolean legacy) {
        this.symbols = symbols == null ? new String[0] : symbols;
        this.gridLen = gridLen;
        this.meanDb = meanDb;
        this.w1 = w1;
        this.b1 = b1;
        this.w2 = w2;
        this.b2 = b2;
        this.w3 = w3;
        this.b3 = b3;
        this.hidden = b1.length;
        this.hidden2 = (b2 == null) ? 0 : b2.length;
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
        float[] h1 = new float[hidden];
        float[] h2 = new float[hidden2];
        float[] o = new float[gridLen];
        forward(x, h1, h2, o);
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
        // v9.3.2 articulation features: formant spacing/rounding and consonant place
        double f1 = d == null ? 500 : d.f1;
        double f2 = d == null ? 1500 : d.f2;
        double f3 = d == null ? 2500 : d.f3;
        x[27] = (float) clamp((f1 - 300) / 700.0, -1.5, 2.0);       // openness
        x[28] = (float) clamp((f2 - 1000) / 1500.0, -1.5, 2.0);     // frontness
        x[29] = (float) clamp((f2 - f1) / 1200.0, -2.0, 2.0);       // rounding / backness
        x[30] = (float) clamp((f3 - f2) / 1200.0, -2.0, 2.0);       // front-cavity spacing
        double vv = d == null ? 1.0 : clamp01(d.voiced);
        double nn = d == null ? 0.0 : clamp01(d.noise);
        x[31] = (float) (vv * (1.0 - nn));                          // voiced frication margin
        x[32] = (float) (nn * clamp((d == null ? 0 : d.noiseFc) / 8000.0, 0, 2)); // consonant place
        return x;
    }

    private void forward(float[] x, float[] h1, float[] h2, float[] o) {
        for (int j = 0; j < hidden; j++) {
            double z = b1[j];
            float[] wj = w1[j];
            for (int i = 0; i < x.length; i++) z += wj[i] * x[i];
            h1[j] = (float) Math.tanh(z);
        }
        for (int j = 0; j < hidden2; j++) {
            double z = b2[j];
            float[] wj = w2[j];
            for (int i = 0; i < hidden; i++) z += wj[i] * h1[i];
            h2[j] = (float) Math.tanh(z);
        }
        for (int k = 0; k < gridLen; k++) {
            double z = b3[k];
            float[] wk = w3[k];
            for (int j = 0; j < hidden2; j++) z += wk[j] * h2[j];
            o[k] = (float) z;
        }
    }

    /** the canonical 64-bin log grid shared by every model (Hz, copy) */
    public static double[] canonicalGrid() {
        return GRID_HZ.clone();
    }

    public String describe() {
        if (legacy) return "旧版模型 · " + sampleCount + " 帧";
        String base;
        if (kind == KIND_UNIVERSAL) base = "BriNeting 基座";
        else if (kind == KIND_FINE) base = "当前歌手模型";
        else if (kind == KIND_FUSION) base = "融合模型";
        else base = "单声库模型";
        return base + " · " + Math.max(1, bankCount) + " 声库 · " + sampleCount + " 帧"
                + " · 验证 " + fmt(valLoss) + " · 参数 " + paramCount();
    }

    /** number of trainable weights and biases in this network */
    public int paramCount() {
        return hidden * inputDim + hidden
                + hidden2 * hidden + hidden2
                + gridLen * hidden2 + gridLen;
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
        return fit(data, KIND_BANK, 1, HID1_BANK, HID2_BANK, EPOCHS_BANK, LR, null, cb);
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
        if (data.count < 8) throw new IOException("实测帧太少，无法训练 BriNeting 基座");
        return fit(data, KIND_UNIVERSAL, usable.size(), HID1_UNIVERSAL, HID2_UNIVERSAL,
                EPOCHS_UNIVERSAL, LR, null, cb);
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
        if (prior == null || prior.legacy || prior.gridLen != GRID || prior.hidden2 <= 0) {
            return train(vm, cb);
        }
        if (cb != null) cb.onProgress(2, "读取 " + vm.name + " 的频谱…");
        Data data = collect(java.util.Collections.singletonList(vm), false, cb);
        if (data.count < 8) throw new IOException("当前声库实测帧太少，无法微调");
        return fit(data, KIND_FINE, 1, prior.hidden, prior.hidden2, EPOCHS_FINE, LR_FINE,
                prior, cb);
    }

    /**
     * Fuse 2..n voicebanks into one wider neural acoustic model. Every selected bank contributes
     * an equal share of frames, so a bank with a huge measured set cannot drown the others; the
     * result predicts a blended spectral envelope and is installed as a {@code .v4neural} singer.
     */
    public static NeuralSpectrum trainFusion(List<VoiceModel> banks, Progress cb)
            throws IOException {
        List<VoiceModel> usable = usableBanks(banks);
        if (usable.size() < 2) throw new IOException("融合训练至少需要 2 个含实测谐波的声库");
        if (cb != null) cb.onProgress(2, "读取 " + usable.size() + " 个声库的频谱…");
        Data data = collect(usable, true, MAX_FUSION_SAMPLES, cb);
        if (data.count < 8) throw new IOException("实测帧太少，无法融合训练");
        return fit(data, KIND_FUSION, usable.size(), HID1_FUSION, HID2_FUSION,
                EPOCHS_FUSION, LR_FUSION, null, cb);
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
        return collect(banks, universal, MAX_SAMPLES, cb);
    }

    private static Data collect(List<VoiceModel> banks, boolean universal, int maxSamples,
                                Progress cb) {
        final int nb = Math.max(1, banks.size());
        final int cap = Math.max(8, maxSamples);
        final int perBank = universal ? Math.max(96, cap / nb) : cap;
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
                if (xs.size() < cap) {
                    xs.add(bx.get(i));
                    ys.add(by.get(i));
                } else {
                    int j = rnd.nextInt(totalSeen + 1);
                    if (j < cap) { xs.set(j, bx.get(i)); ys.set(j, by.get(i)); }
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

    private static NeuralSpectrum fit(Data d, int kind, int bankCount, int hid1, int hid2,
                                      int epochs, double lr, NeuralSpectrum init, Progress cb)
            throws IOException {
        if (d == null || d.count < 8) throw new IOException("训练样本太少");
        if (hid2 <= 0) hid2 = 1;
        final int n = d.count;
        final int grid = GRID;
        final int in = FEATURE_DIM;
        final int h1n = hid1, h2n = hid2;

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

        float[][] w1, w2, w3;
        float[] b1, b2, b3;
        boolean reuse = init != null && init.w1.length == h1n && init.w1[0].length == in
                && init.w2.length == h2n && init.w2[0].length == h1n
                && init.w3.length == grid && init.w3[0].length == h2n;
        if (reuse) {
            w1 = copy(init.w1); b1 = init.b1.clone();
            w2 = copy(init.w2); b2 = init.b2.clone();
            w3 = copy(init.w3); b3 = init.b3.clone();
        } else {
            w1 = new float[h1n][in]; b1 = new float[h1n];
            w2 = new float[h2n][h1n]; b2 = new float[h2n];
            w3 = new float[grid][h2n]; b3 = new float[grid];
            for (int j = 0; j < h1n; j++) {
                double s = Math.sqrt(6.0 / (in + h1n));
                for (int i = 0; i < in; i++) w1[j][i] = (float) ((rnd.nextDouble() * 2 - 1) * s);
            }
            for (int j = 0; j < h2n; j++) {
                double s = Math.sqrt(6.0 / (h1n + h2n));
                for (int i = 0; i < h1n; i++) w2[j][i] = (float) ((rnd.nextDouble() * 2 - 1) * s);
            }
            for (int k = 0; k < grid; k++) {
                double s = Math.sqrt(6.0 / (h2n + grid));
                for (int j = 0; j < h2n; j++) w3[k][j] = (float) ((rnd.nextDouble() * 2 - 1) * s);
            }
        }
        // L2-SP: keep the fine-tuned model close to the universal prior
        float[][] p1 = null, p2 = null, p3 = null;
        float[] pb1 = null, pb2 = null, pb3 = null;
        if (init != null && kind == KIND_FINE) {
            p1 = copy(init.w1); pb1 = init.b1.clone();
            p2 = copy(init.w2); pb2 = init.b2.clone();
            p3 = copy(init.w3); pb3 = init.b3.clone();
        }

        float[][] mw1 = new float[h1n][in], vw1 = new float[h1n][in];
        float[] mb1 = new float[h1n], vb1 = new float[h1n];
        float[][] mw2 = new float[h2n][h1n], vw2 = new float[h2n][h1n];
        float[] mb2 = new float[h2n], vb2 = new float[h2n];
        float[][] mw3 = new float[grid][h2n], vw3 = new float[grid][h2n];
        float[] mb3 = new float[grid], vb3 = new float[grid];
        float[][] gw1 = new float[h1n][in]; float[] gb1 = new float[h1n];
        float[][] gw2 = new float[h2n][h1n]; float[] gb2 = new float[h2n];
        float[][] gw3 = new float[grid][h2n]; float[] gb3 = new float[grid];
        int step = 0;

        float[][] bw1 = null, bw2 = null, bw3 = null;
        float[] bb1 = null, bb2 = null, bb3 = null;
        double bestVal = Double.MAX_VALUE;
        int bestEp = 0;

        for (int ep = 1; ep <= epochs; ep++) {
            shuffle(trainOrder, rnd);
            for (int base = 0; base < trainN; base += BATCH) {
                int end = Math.min(trainN, base + BATCH);
                zero(gw1); zero(gb1); zero(gw2); zero(gb2); zero(gw3); zero(gb3);
                for (int q = base; q < end; q++) {
                    int idx = trainOrder[q];
                    float[] x = d.x[idx];
                    float[] y = d.y[idx];
                    float[] h1 = new float[h1n];
                    float[] h2 = new float[h2n];
                    float[] o = new float[grid];
                    for (int j = 0; j < h1n; j++) {
                        double z = b1[j];
                        for (int i = 0; i < in; i++) z += w1[j][i] * x[i];
                        h1[j] = (float) Math.tanh(z);
                    }
                    for (int j = 0; j < h2n; j++) {
                        double z = b2[j];
                        for (int i = 0; i < h1n; i++) z += w2[j][i] * h1[i];
                        h2[j] = (float) Math.tanh(z);
                    }
                    for (int k = 0; k < grid; k++) {
                        double z = b3[k];
                        for (int j = 0; j < h2n; j++) z += w3[k][j] * h2[j];
                        o[k] = (float) z;
                    }
                    float[] dH2 = new float[h2n];
                    for (int k = 0; k < grid; k++) {
                        double p = Math.tanh(o[k]);
                        double dO = (p - y[k]) * (1.0 - p * p) * (BAND_W[k] / BAND_W_MEAN);
                        gb3[k] += (float) dO;
                        for (int j = 0; j < h2n; j++) {
                            gw3[k][j] += (float) (dO * h2[j]);
                            dH2[j] += (float) (dO * w3[k][j]);
                        }
                    }
                    float[] dH1 = new float[h1n];
                    for (int j = 0; j < h2n; j++) {
                        double dd = dH2[j] * (1.0 - h2[j] * (double) h2[j]);
                        gb2[j] += (float) dd;
                        for (int i = 0; i < h1n; i++) {
                            gw2[j][i] += (float) (dd * h1[i]);
                            dH1[i] += (float) (dd * w2[j][i]);
                        }
                    }
                    for (int j = 0; j < h1n; j++) {
                        double dd = dH1[j] * (1.0 - h1[j] * (double) h1[j]);
                        gb1[j] += (float) dd;
                        for (int i = 0; i < in; i++) gw1[j][i] += (float) (dd * x[i]);
                    }
                }
                int bn = end - base;
                step++;
                double bc1 = 1.0 - Math.pow(0.9, step);
                double bc2 = 1.0 - Math.pow(0.999, step);
                for (int j = 0; j < h1n; j++) {
                    adamRow(w1[j], gw1[j], mw1[j], vw1[j], lr, bc1, bc2, bn,
                            p1 == null ? null : p1[j], SP_LAMBDA);
                    adamRow(b1, gb1, mb1, vb1, j, lr, bc1, bc2, bn,
                            p1 == null ? null : pb1, SP_LAMBDA);
                }
                for (int j = 0; j < h2n; j++) {
                    adamRow(w2[j], gw2[j], mw2[j], vw2[j], lr, bc1, bc2, bn,
                            p2 == null ? null : p2[j], SP_LAMBDA);
                    adamRow(b2, gb2, mb2, vb2, j, lr, bc1, bc2, bn,
                            p2 == null ? null : pb2, SP_LAMBDA);
                }
                for (int k = 0; k < grid; k++) {
                    adamRow(w3[k], gw3[k], mw3[k], vw3[k], lr, bc1, bc2, bn,
                            p3 == null ? null : p3[k], SP_LAMBDA);
                    adamRow(b3, gb3, mb3, vb3, k, lr, bc1, bc2, bn,
                            p3 == null ? null : pb3, SP_LAMBDA);
                }
            }
            if (cb != null && (ep == 1 || ep % 5 == 0 || ep == epochs)) {
                double tr = evaluate(w1, b1, w2, b2, w3, b3, d, trainOrder, 0, trainN);
                double val = evaluate(w1, b1, w2, b2, w3, b3, d, order, trainN, n);
                if (val < bestVal - 1e-7) {
                    bestVal = val;
                    bestEp = ep;
                    bw1 = copy(w1); bb1 = b1.clone();
                    bw2 = copy(w2); bb2 = b2.clone();
                    bw3 = copy(w3); bb3 = b3.clone();
                }
                cb.onProgress(15 + (int) Math.round(ep * 83.0 / epochs),
                        "第 " + ep + "/" + epochs + " 轮 · 训练损失 " + fmt(tr)
                                + " · 验证损失 " + fmt(val));
                if (ep - bestEp >= 35) break;
            }
        }
        if (bw1 != null) { w1 = bw1; b1 = bb1; w2 = bw2; b2 = bb2; w3 = bw3; b3 = bb3; }

        double tr = evaluate(w1, b1, w2, b2, w3, b3, d, trainOrder, 0, trainN);
        double val = evaluate(w1, b1, w2, b2, w3, b3, d, order, trainN, n);
        if (cb != null) cb.onProgress(99, "已保存模型");
        return new NeuralSpectrum(d.known, grid, mean, w1, b1, w2, b2, w3, b3,
                kind, bankCount, n, tr, val, false);
    }

    private static double evaluate(float[][] w1, float[] b1, float[][] w2, float[] b2,
                                   float[][] w3, float[] b3, Data d, int[] idx, int from, int to) {
        if (to <= from) return 0;
        double sum = 0;
        for (int q = from; q < to; q++) {
            int i = idx[q];
            sum += sampleLoss(w1, b1, w2, b2, w3, b3, d.x[i], d.y[i]);
        }
        return sum / (to - from);
    }

    private static double sampleLoss(float[][] w1, float[] b1, float[][] w2, float[] b2,
                                     float[][] w3, float[] b3, float[] x, float[] y) {
        float[] h1 = new float[w1.length];
        for (int j = 0; j < h1.length; j++) {
            double z = b1[j];
            for (int i = 0; i < x.length; i++) z += w1[j][i] * x[i];
            h1[j] = (float) Math.tanh(z);
        }
        float[] h2 = new float[w2.length];
        for (int j = 0; j < h2.length; j++) {
            double z = b2[j];
            for (int i = 0; i < h1.length; i++) z += w2[j][i] * h1[i];
            h2[j] = (float) Math.tanh(z);
        }
        double sum = 0, wsum = 0;
        for (int k = 0; k < w3.length; k++) {
            double z = b3[k];
            for (int j = 0; j < h2.length; j++) z += w3[k][j] * h2[j];
            double dd = Math.tanh(z) - y[k];
            sum += BAND_W[k] * dd * dd;
            wsum += BAND_W[k];
        }
        return 0.5 * sum / wsum;
    }

    /** Adam update of one row (or one bias vector) with optional L2-SP pull. */
    private static void adamRow(float[] w, float[] g, float[] m, float[] v,
                                double lr, double bc1, double bc2, int bn,
                                float[] prior, double sp) {
        for (int i = 0; i < w.length; i++) adamRow(w, g, m, v, i, lr, bc1, bc2, bn, prior, sp);
    }

    private static void adamRow(float[] w, float[] g, float[] m, float[] v, int i,
                                double lr, double bc1, double bc2, int bn,
                                float[] prior, double sp) {
        double gg = g[i] / bn;
        if (prior != null) gg += sp * (w[i] - prior[i]);
        m[i] = (float) (0.9 * m[i] + 0.1 * gg);
        v[i] = (float) (0.999 * v[i] + 0.001 * gg * gg);
        w[i] -= (float) (lr * (m[i] / bc1) / (Math.sqrt(v[i] / bc2) + 1e-8));
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
            out.writeInt(hidden2);
            out.writeInt(gridLen);
            out.writeFloat((float) SCALE);
            for (int k = 0; k < gridLen; k++) out.writeFloat(meanDb[k]);
            for (int j = 0; j < hidden; j++) for (int i = 0; i < inputDim; i++) out.writeFloat(w1[j][i]);
            for (int j = 0; j < hidden; j++) out.writeFloat(b1[j]);
            for (int j = 0; j < hidden2; j++) for (int i = 0; i < hidden; i++) out.writeFloat(w2[j][i]);
            for (int j = 0; j < hidden2; j++) out.writeFloat(b2[j]);
            for (int k = 0; k < gridLen; k++) for (int j = 0; j < hidden2; j++) out.writeFloat(w3[k][j]);
            for (int k = 0; k < gridLen; k++) out.writeFloat(b3[k]);
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
            if (version == VERSION_V2) return loadV2(in);
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
            int hid2 = in.readInt();
            int grid = in.readInt();
            float scale = in.readFloat();
            if (hid <= 0 || hid > 256 || hid2 < 0 || hid2 > 256
                    || grid <= 0 || grid > MAX_GRID || Math.abs(scale - SCALE) > 1e-3) {
                throw new IOException("神经声库尺寸无效");
            }
            float[] mean = new float[grid];
            for (int k = 0; k < grid; k++) mean[k] = in.readFloat();
            float[][] w1 = new float[hid][FEATURE_DIM];
            for (int j = 0; j < hid; j++) for (int i = 0; i < FEATURE_DIM; i++) w1[j][i] = in.readFloat();
            float[] b1 = new float[hid];
            for (int j = 0; j < hid; j++) b1[j] = in.readFloat();
            float[][] w2 = new float[hid2][hid];
            for (int j = 0; j < hid2; j++) for (int i = 0; i < hid; i++) w2[j][i] = in.readFloat();
            float[] b2 = new float[hid2];
            for (int j = 0; j < hid2; j++) b2[j] = in.readFloat();
            float[][] w3 = new float[grid][hid2];
            for (int k = 0; k < grid; k++) for (int j = 0; j < hid2; j++) w3[k][j] = in.readFloat();
            float[] b3 = new float[grid];
            for (int k = 0; k < grid; k++) b3[k] = in.readFloat();
            return new NeuralSpectrum(syms, grid, mean, w1, b1, w2, b2, w3, b3,
                    kind, bankCount, sampleCount, trainLoss, valLoss, false);
        } finally {
            in.close();
        }
    }

    /** v9.1/v9.2 single-hidden-layer file (27 input features, 64-bin grid). */
    private static NeuralSpectrum loadV2(DataInputStream in) throws IOException {
        final int v2Features = 27;
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
        // widen the old 27-feature input to the new 33-feature layout with zero weights,
        // which reproduces the old predictions exactly
        float[][] w1 = new float[hid][FEATURE_DIM];
        for (int j = 0; j < hid; j++) for (int i = 0; i < v2Features; i++) w1[j][i] = in.readFloat();
        float[] b1 = new float[hid];
        for (int j = 0; j < hid; j++) b1[j] = in.readFloat();
        float[][] w3 = new float[grid][hid];
        for (int k = 0; k < grid; k++) for (int j = 0; j < hid; j++) w3[k][j] = in.readFloat();
        float[] b3 = new float[grid];
        for (int k = 0; k < grid; k++) b3[k] = in.readFloat();
        return new NeuralSpectrum(syms, grid, mean, w1, b1, new float[0][], new float[0],
                w3, b3, kind, bankCount, sampleCount, trainLoss, valLoss, false);
    }

    /** v8.2.1 prototype file: per-symbol one-hot inputs. */
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
        float[][] w3 = new float[grid][hid];
        for (int k = 0; k < grid; k++) for (int j = 0; j < hid; j++) w3[k][j] = in.readFloat();
        float[] b3 = new float[grid];
        for (int k = 0; k < grid; k++) b3[k] = in.readFloat();
        return new NeuralSpectrum(syms, grid, mean, w1, b1, new float[0][], new float[0],
                w3, b3, KIND_BANK, 1, 0, 0, 0, true);
    }

    // ------------------------------------------------------------------ helpers

    private static double[] buildGrid() {
        double[] f = new double[GRID];
        double ratio = Math.log(GRID_HI / GRID_LO);
        for (int k = 0; k < GRID; k++) f[k] = GRID_LO * Math.exp(ratio * k / (GRID - 1.0));
        return f;
    }

    /** Energy above ~10 kHz carries little vowel identity, so the loss emphasises 80 Hz-5 kHz. */
    private static double[] buildBandWeights() {
        double[] w = new double[GRID];
        double ratio = Math.log(GRID_HI / GRID_LO);
        for (int k = 0; k < GRID; k++) {
            double f = GRID_LO * Math.exp(ratio * k / (GRID - 1.0));
            if (f < 80) w[k] = 1.0;
            else if (f <= 5000) w[k] = 1.6;
            else if (f <= 10000) w[k] = 1.15;
            else w[k] = 0.7;
        }
        return w;
    }

    private static double meanOf(double[] a) {
        double sum = 0;
        for (double v : a) sum += v;
        return a.length == 0 ? 1 : sum / a.length;
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