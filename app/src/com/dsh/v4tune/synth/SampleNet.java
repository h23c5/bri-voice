package com.dsh.v4tune.synth;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * BriSampleNet (v11.1) - 直接用采样训练的帧级神经声学模型。
 *
 * 与旧的 NeuralSpectrum 不同：它不先"测量"每个音素的谐波再拟合，而是把采样 WAV
 * 逐帧切开（40ms 窗 / 10ms 移），在每一帧上按该帧的 f0 取 40 个谐波幅度 + 噪声比，
 * 然后把「音素 + 音高 + 音符内位置 + 力度」作为条件训练一个全连接网络，
 * 直接预测每一帧的谐波包络。
 *
 * 训练方式是「基座 + 微调」：基座用 UTAU 免费声库的原始采样在学校/服务器/桌面上训练好，
 * 随包内置；用户录自己的采样时只做几十轮小幅微调（学习率约基座 1/8），
 * 这样几个元音也能得到时间上会走动的音色，而不是一条平均曲线。
 */
public final class SampleNet {

    public static final String MAGIC = "BRSN";
    public static final int VERSION = 1;

    /** analysis */
    public static final int SR = 16000;
    public static final int HOP = 160;          // 10 ms
    public static final int WIN = 640;          // 40 ms
    public static final int HARM = 40;
    public static final int PHONE_MAX = 48;     // phoneme table capacity
    public static final int CTX = 4;            // +-4 frames of context in the conditioning
    public static final int IN_DIM = PHONE_MAX + 8;
    public static final int OUT_DIM = HARM + 1; // harmonics + noise

    private SampleNet() { }

    // ------------------------------------------------------------------ model

    public static final class Model {
        public int inDim = IN_DIM, h1 = 128, h2 = 96, outDim = OUT_DIM;
        public String[] phones = new String[PHONE_MAX];
        public int phoneCount = 0;
        public float[] w1, b1, w2, b2, w3, b3;
        /** per-output normalisation of the regression targets */
        public float[] outMean = new float[OUT_DIM], outStd = new float[OUT_DIM];
        public String tag = "";

        public int paramCount() {
            return inDim * h1 + h1 + h1 * h2 + h2 + h2 * outDim + outDim;
        }

        public int indexOf(String sym) {
            if (sym == null) return -1;
            for (int i = 0; i < phoneCount; i++) if (sym.equals(phones[i])) return i;
            return -1;
        }

        public String describe() {
            return "采样声学模型 · " + phoneCount + " 音素 · " + inDim + "-" + h1 + "-" + h2 + "-" + outDim
                    + " · 参数 " + paramCount();
        }
    }

    // ------------------------------------------------------------------ analysis

    /** one analysed frame of a sample */
    public static final class Frame {
        public float f0;
        public float[] harm = new float[HARM]; // dB relative to frame energy
        public float noise;                    // residual ratio 0..1
        public float relPos;                   // 0..1 position inside the sample
        public float rms;
    }

    /** convert to 16 kHz mono by simple box averaging */
    public static float[] to16k(float[] mono, int sr) {
        if (sr == SR) return mono;
        double ratio = sr / (double) SR;
        int n = (int) (mono.length / ratio);
        float[] out = new float[Math.max(1, n)];
        for (int i = 0; i < out.length; i++) {
            int a = (int) Math.floor(i * ratio);
            int b = Math.min(mono.length, (int) Math.floor((i + 1) * ratio));
            if (b <= a) b = Math.min(mono.length, a + 1);
            double s = 0;
            for (int k = a; k < b; k++) s += mono[k];
            out[i] = (float) (s / Math.max(1, b - a));
        }
        return out;
    }

    public static java.util.List<Frame> analyze(float[] mono, int sr) {
        float[] x = to16k(mono, sr);
        java.util.ArrayList<Frame> out = new java.util.ArrayList<Frame>();
        int n = x.length;
        if (n < WIN + HOP) return out;
        for (int pos = 0; pos + WIN <= n; pos += HOP) {
            double f0 = f0Of(x, pos, WIN);
            if (f0 <= 0) continue;
            Frame f = new Frame();
            f.f0 = (float) f0;
            f.relPos = pos / (float) Math.max(1, n - WIN);
            // hann window
            double[] win = new double[WIN];
            double energy = 0;
            for (int i = 0; i < WIN; i++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WIN - 1));
                win[i] = w * x[pos + i];
                energy += win[i] * win[i];
            }
            double rms = Math.sqrt(energy / WIN);
            f.rms = (float) rms;
            if (rms < 1e-4) continue;
            double harmSum = 0;
            for (int k = 1; k <= HARM; k++) {
                double freq = f0 * k;
                if (freq > SR * 0.47) { f.harm[k - 1] = -90; continue; }
                double re = 0, im = 0;
                double w0 = 2 * Math.PI * freq / SR;
                for (int i = 0; i < WIN; i++) {
                    re += win[i] * Math.cos(w0 * i);
                    im += win[i] * Math.sin(w0 * i);
                }
                double mag = Math.sqrt(re * re + im * im) / (WIN * 0.5);
                double db = 20.0 * Math.log10(Math.max(1e-6, mag));
                f.harm[k - 1] = (float) db;
                harmSum += mag * mag;
            }
            // residual = everything the harmonic comb did not explain
            double total = 0;
            for (int i = 0; i < WIN; i++) total += win[i] * win[i];
            double harmonicEnergy = harmSum * WIN * 0.25;
            double noise = total > 1e-9 ? 1.0 - harmonicEnergy / total : 0.0;
            f.noise = (float) clamp(noise, 0, 1);
            out.add(f);
        }
        return out;
    }

    private static double f0Of(float[] x, int off, int w) {
        int lo = (int) (SR / 900.0), hi = (int) (SR / 60.0);
        if (hi >= w) hi = w - 2;
        double best = 0;
        int bestLag = -1;
        double[] score = new double[hi + 2];
        double s0 = 0;
        for (int i = 0; i < w; i++) s0 += x[off + i] * x[off + i];
        if (s0 < 1e-8) return 0;
        for (int lag = lo; lag <= hi; lag++) {
            double s = 0;
            for (int i = 0; i + lag < w; i++) s += x[off + i] * x[off + i + lag];
            double norm = s / s0;
            score[lag] = norm;
            if (norm > best) { best = norm; bestLag = lag; }
        }
        if (bestLag < 0 || best < 0.30) return 0;
        // prefer the first strong local peak: stops the classic half-octave error
        for (int lag = lo + 1; lag < bestLag; lag++) {
            if (score[lag] >= 0.86 * best && score[lag] >= score[lag - 1] && score[lag] >= score[lag + 1]) {
                bestLag = lag;
                break;
            }
        }
        double a = score[Math.max(lo, bestLag - 1)], b = score[bestLag], c = score[Math.min(hi, bestLag + 1)];
        double den = a - 2 * b + c;
        double delta = Math.abs(den) > 1e-9 ? 0.5 * (a - c) / den : 0;
        return SR / (bestLag + clamp(delta, -0.5, 0.5));
    }

    // ------------------------------------------------------------------ dataset

    public static final class Dataset {
        public float[][] x;
        public float[][] y;
        public int count;
        public int frameTotal;
    }

    /** build a training set from samples: symbol -> mono */
    public static Dataset build(java.util.LinkedHashMap<String, java.util.List<float[]>> samples, Model phones) {
        java.util.ArrayList<float[]> xs = new java.util.ArrayList<float[]>();
        java.util.ArrayList<float[]> ys = new java.util.ArrayList<float[]>();
        for (java.util.Map.Entry<String, java.util.List<float[]>> e : samples.entrySet()) {
            int idx = ensurePhone(phones, e.getKey());
            for (float[] mono : e.getValue()) {
                java.util.List<Frame> frames = analyze(mono, SR);
                int total = frames.size();
                for (int i = 0; i < total; i++) {
                    Frame f = frames.get(i);
                    float prevF0 = (i > 0) ? frames.get(i - 1).f0 : f.f0;
                    float dF0 = (float) (Math.log(f.f0 / prevF0) / Math.log(2));
                    float[] x = features(idx, f.f0, f.relPos, 0.8f, dF0);
                    float[] y = new float[OUT_DIM];
                    for (int k = 0; k < HARM; k++) y[k] = f.harm[k];
                    y[HARM] = f.noise;
                    xs.add(x);
                    ys.add(y);
                }
            }
        }
        Dataset d = new Dataset();
        d.count = xs.size();
        d.frameTotal = xs.size();
        d.x = xs.toArray(new float[0][]);
        d.y = ys.toArray(new float[0][]);
        return d;
    }

    public static int ensurePhone(Model m, String sym) {
        int idx = m.indexOf(sym);
        if (idx >= 0) return idx;
        if (m.phoneCount >= PHONE_MAX) return 0;
        idx = m.phoneCount;
        m.phones[idx] = sym;
        m.phoneCount++;
        return idx;
    }

    /** conditioning vector for one frame */
    public static float[] features(int phoneIdx, double f0, double relPos, double dyn, double dF0) {
        float[] x = new float[IN_DIM];
        if (phoneIdx >= 0 && phoneIdx < PHONE_MAX) x[phoneIdx] = 1f;
        int o = PHONE_MAX;
        x[o] = (float) clamp(Math.log(f0 / 220.0) / Math.log(2) / 2.0, -2, 2);
        x[o + 1] = (float) clamp((dyn - 0.6) * 2.0, -1.5, 1.5);
        x[o + 2] = (float) (relPos * 2 - 1);
        x[o + 3] = (float) clamp(dF0 * 4.0, -1.5, 1.5);
        x[o + 4] = (float) clamp(Math.log(f0 / 220.0) / Math.log(2) * (relPos * 2 - 1), -2, 2);
        x[o + 5] = (float) Math.sin(Math.PI * relPos);
        x[o + 6] = (float) (1 - relPos);
        x[o + 7] = 1f;
        return x;
    }

    // ------------------------------------------------------------------ init / train

    public static Model emptyModel(String tag) {
        Model m = new Model();
        m.tag = tag;
        java.util.Random rnd = new java.util.Random(7);
        m.w1 = rand(rnd, m.inDim * m.h1, Math.sqrt(2.0 / m.inDim));
        m.b1 = new float[m.h1];
        m.w2 = rand(rnd, m.h1 * m.h2, Math.sqrt(2.0 / m.h1));
        m.b2 = new float[m.h2];
        m.w3 = rand(rnd, m.h2 * m.outDim, Math.sqrt(2.0 / m.h2));
        m.b3 = new float[m.outDim];
        for (int i = 0; i < m.outDim; i++) { m.outMean[i] = -30f; m.outStd[i] = 12f; }
        m.outMean[HARM] = 0.3f; m.outStd[HARM] = 0.25f;
        return m;
    }

    private static float[] rand(java.util.Random r, int n, double scale) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = (float) (r.nextGaussian() * scale);
        return a;
    }

    /** normalise the regression targets of an existing model (mean/std over the training set) */
    public static void fitTargetStats(Model m, Dataset d) {
        double[] mean = new double[m.outDim], var = new double[m.outDim];
        for (int i = 0; i < d.count; i++)
            for (int k = 0; k < m.outDim; k++) mean[k] += d.y[i][k];
        for (int k = 0; k < m.outDim; k++) mean[k] /= Math.max(1, d.count);
        for (int i = 0; i < d.count; i++)
            for (int k = 0; k < m.outDim; k++) { double dv = d.y[i][k] - mean[k]; var[k] += dv * dv; }
        for (int k = 0; k < m.outDim; k++) {
            m.outMean[k] = (float) mean[k];
            m.outStd[k] = (float) Math.max(k == HARM ? 0.08 : 3.0, Math.sqrt(var[k] / Math.max(1, d.count)));
        }
    }

    public interface Progress { void onProgress(int epoch, int epochs, double loss); }

    public static final class FitOptions {
        public int epochs = 60;
        public int batch = 128;
        public double lr = 0.002;
        public double l2 = 1e-5;
        public long seed = 11;
    }

    /** Adam training; y is normalised with m.outMean/outStd */
    public static double train(Model m, Dataset d, FitOptions o, Progress cb) {
        if (d.count == 0) return 0;
        int[] perm = new int[d.count];
        for (int i = 0; i < perm.length; i++) perm[i] = i;
        java.util.Random rnd = new java.util.Random(o.seed);
        float[] w1 = m.w1, b1 = m.b1, w2 = m.w2, b2 = m.b2, w3 = m.w3, b3 = m.b3;
        float[] mw1 = new float[w1.length], vw1 = new float[w1.length];
        float[] mb1 = new float[b1.length], vb1 = new float[b1.length];
        float[] mw2 = new float[w2.length], vw2 = new float[w2.length];
        float[] mb2 = new float[b2.length], vb2 = new float[b2.length];
        float[] mw3 = new float[w3.length], vw3 = new float[w3.length];
        float[] mb3 = new float[b3.length], vb3 = new float[b3.length];
        double beta1 = 0.9, beta2 = 0.999, eps = 1e-8;
        int step = 0;
        double last = 0;
        float[] h1v = new float[m.h1], h2v = new float[m.h2], outv = new float[m.outDim];
        float[] d1 = new float[m.h1], d2 = new float[m.h2], dO = new float[m.outDim];
        for (int ep = 0; ep < o.epochs; ep++) {
            // shuffle
            for (int i = perm.length - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = perm[i]; perm[i] = perm[j]; perm[j] = t;
            }
            double lossSum = 0;
            int seen = 0;
            for (int start = 0; start < d.count; start += o.batch) {
                int end = Math.min(d.count, start + o.batch);
                int bs = end - start;
                float[] gw1 = new float[w1.length], gb1 = new float[b1.length];
                float[] gw2 = new float[w2.length], gb2 = new float[b2.length];
                float[] gw3 = new float[w3.length], gb3 = new float[b3.length];
                for (int bi = start; bi < end; bi++) {
                    int idx = perm[bi];
                    float[] x = d.x[idx], y = d.y[idx];
                    // forward
                    for (int i = 0; i < m.h1; i++) {
                        double s = b1[i];
                        int off = i * m.inDim;
                        for (int k = 0; k < m.inDim; k++) s += w1[off + k] * x[k];
                        h1v[i] = (float) Math.tanh(s);
                    }
                    for (int i = 0; i < m.h2; i++) {
                        double s = b2[i];
                        int off = i * m.h1;
                        for (int k = 0; k < m.h1; k++) s += w2[off + k] * h1v[k];
                        h2v[i] = (float) Math.tanh(s);
                    }
                    for (int i = 0; i < m.outDim; i++) {
                        double s = b3[i];
                        int off = i * m.h2;
                        for (int k = 0; k < m.h2; k++) s += w3[off + k] * h2v[k];
                        outv[i] = (float) s;
                    }
                    // backward (MSE on normalised targets)
                    for (int i = 0; i < m.outDim; i++) {
                        double t = (y[i] - m.outMean[i]) / m.outStd[i];
                        double e = outv[i] - t;
                        lossSum += e * e;
                        dO[i] = (float) (2 * e / bs);
                    }
                    seen++;
                    for (int i = 0; i < m.h2; i++) {
                        double s = 0;
                        for (int k = 0; k < m.outDim; k++) s += w3[k * m.h2 + i] * dO[k];
                        d2[i] = (float) (s * (1 - h2v[i] * h2v[i]));
                    }
                    for (int i = 0; i < m.h1; i++) {
                        double s = 0;
                        for (int k = 0; k < m.h2; k++) s += w2[k * m.h1 + i] * d2[k];
                        d1[i] = (float) (s * (1 - h1v[i] * h1v[i]));
                    }
                    for (int i = 0; i < m.outDim; i++)
                        for (int k = 0; k < m.h2; k++) gw3[i * m.h2 + k] += dO[i] * h2v[k];
                    for (int i = 0; i < m.outDim; i++) gb3[i] += dO[i];
                    for (int i = 0; i < m.h2; i++)
                        for (int k = 0; k < m.h1; k++) gw2[i * m.h1 + k] += d2[i] * h1v[k];
                    for (int i = 0; i < m.h2; i++) gb2[i] += d2[i];
                    for (int i = 0; i < m.h1; i++)
                        for (int k = 0; k < m.inDim; k++) gw1[i * m.inDim + k] += d1[i] * x[k];
                    for (int i = 0; i < m.h1; i++) gb1[i] += d1[i];
                }
                step++;
                adam(w1, gw1, mw1, vw1, o.lr, beta1, beta2, eps, step, o.l2);
                adam(b1, gb1, mb1, vb1, o.lr, beta1, beta2, eps, step, 0);
                adam(w2, gw2, mw2, vw2, o.lr, beta1, beta2, eps, step, o.l2);
                adam(b2, gb2, mb2, vb2, o.lr, beta1, beta2, eps, step, 0);
                adam(w3, gw3, mw3, vw3, o.lr, beta1, beta2, eps, step, o.l2);
                adam(b3, gb3, mb3, vb3, o.lr, beta1, beta2, eps, step, 0);
            }
            last = seen > 0 ? lossSum / Math.max(1, seen * m.outDim) : 0;
            if (cb != null && (ep % 5 == 0 || ep == o.epochs - 1)) cb.onProgress(ep + 1, o.epochs, last);
        }
        return last;
    }

    private static void adam(float[] w, float[] g, float[] m, float[] v, double lr,
                             double beta1, double beta2, double eps, int step, double l2) {
        double bc1 = 1 - Math.pow(beta1, step), bc2 = 1 - Math.pow(beta2, step);
        for (int i = 0; i < w.length; i++) {
            double gi = g[i] + (l2 > 0 ? l2 * w[i] : 0);
            m[i] = (float) (beta1 * m[i] + (1 - beta1) * gi);
            v[i] = (float) (beta2 * v[i] + (1 - beta2) * gi * gi);
            double mh = m[i] / bc1, vh = v[i] / bc2;
            w[i] -= (float) (lr * mh / (Math.sqrt(vh) + eps));
        }
    }

    // ------------------------------------------------------------------ inference

    /** predict the raw (un-normalised) output for one frame */
    public static void forward(Model m, float[] x, float[] out) {
        float[] h1v = new float[m.h1], h2v = new float[m.h2];
        for (int i = 0; i < m.h1; i++) {
            double s = m.b1[i];
            int off = i * m.inDim;
            for (int k = 0; k < m.inDim; k++) s += m.w1[off + k] * x[k];
            h1v[i] = (float) Math.tanh(s);
        }
        for (int i = 0; i < m.h2; i++) {
            double s = m.b2[i];
            int off = i * m.h1;
            for (int k = 0; k < m.h1; k++) s += m.w2[off + k] * h1v[k];
            h2v[i] = (float) Math.tanh(s);
        }
        for (int i = 0; i < m.outDim; i++) {
            double s = m.b3[i];
            int off = i * m.h2;
            for (int k = 0; k < m.h2; k++) s += m.w3[off + k] * h2v[k];
            out[i] = (float) (s * m.outStd[i] + m.outMean[i]);
        }
    }

    /**
     * Envelope for one rendered frame: writes dB per harmonic (index 0 = f0) into out.
     * Returns false when the symbol is not in this model.
     */
    public static boolean envelope(Model m, String sym, double f0, double pos, double dyn,
                                  double prevF0, float[] out) {
        int idx = m.indexOf(sym);
        if (idx < 0) return false;
        float dF0 = (float) (prevF0 > 0 ? Math.log(f0 / prevF0) / Math.log(2) : 0);
        float[] x = features(idx, f0, pos, dyn, dF0 * 0.25);
        float[] y = new float[OUT_DIM];
        forward(m, x, y);
        for (int k = 0; k < HARM; k++) out[k] = y[k];
        return true;
    }

    /** project harmonic dB (harmonic k at k*f0) onto the renderer's fixed frequency grid */
    public static void fillGrid(Model m, String sym, double f0, double pos, double dyn, double prevF0,
                                double[] gridHz, double[] dbOut) {
        float[] env = new float[HARM];
        if (!envelope(m, sym, f0, pos, dyn, prevF0, env)) {
            for (int i = 0; i < dbOut.length; i++) dbOut[i] = -90;
            return;
        }
        for (int i = 0; i < gridHz.length; i++) {
            double f = gridHz[i];
            double h = f / f0;                       // fractional harmonic index
            if (h < 1) { dbOut[i] = -90; continue; }
            int a = (int) Math.floor(h), b = a + 1;
            if (a >= HARM) { dbOut[i] = env[HARM - 1]; continue; }
            double frac = h - a;
            double va = env[a - 1];
            double vb = (b <= HARM) ? env[b - 1] : env[HARM - 1];
            dbOut[i] = va + (vb - va) * frac;
        }
    }

    // ------------------------------------------------------------------ io

    public static void save(Model m, File f) throws IOException {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        try { write(m, fo); } finally { fo.close(); }
        if (f.exists() && !f.delete()) throw new IOException("cannot replace " + f);
        if (!tmp.renameTo(f)) throw new IOException("cannot rename " + tmp);
    }

    public static void write(Model m, OutputStream os) throws IOException {
        java.io.DataOutputStream d = new java.io.DataOutputStream(new java.io.BufferedOutputStream(os));
        d.writeBytes(MAGIC);
        d.writeInt(VERSION);
        d.writeInt(m.inDim); d.writeInt(m.h1); d.writeInt(m.h2); d.writeInt(m.outDim);
        d.writeInt(m.phoneCount);
        for (int i = 0; i < m.phoneCount; i++) {
            byte[] b = m.phones[i].getBytes("UTF-8");
            d.writeShort(b.length);
            d.write(b);
        }
        writeFloats(d, m.w1); writeFloats(d, m.b1);
        writeFloats(d, m.w2); writeFloats(d, m.b2);
        writeFloats(d, m.w3); writeFloats(d, m.b3);
        writeFloats(d, m.outMean); writeFloats(d, m.outStd);
        d.flush();
    }

    private static void writeFloats(java.io.DataOutputStream d, float[] a) throws IOException {
        d.writeInt(a.length);
        for (float v : a) d.writeFloat(v);
    }

    public static Model load(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try { return read(in); } finally { in.close(); }
    }

    public static Model read(InputStream is) throws IOException {
        java.io.DataInputStream d = new java.io.DataInputStream(new java.io.BufferedInputStream(is));
        byte[] magic = new byte[4];
        d.readFully(magic);
        if (!MAGIC.equals(new String(magic, "US-ASCII"))) throw new IOException("not a BriSampleNet model");
        int ver = d.readInt();
        if (ver != VERSION) throw new IOException("unsupported model version " + ver);
        Model m = new Model();
        m.inDim = d.readInt(); m.h1 = d.readInt(); m.h2 = d.readInt(); m.outDim = d.readInt();
        m.phoneCount = d.readInt();
        m.phones = new String[PHONE_MAX];
        for (int i = 0; i < m.phoneCount; i++) {
            int len = d.readUnsignedShort();
            byte[] b = new byte[len];
            d.readFully(b);
            m.phones[i] = new String(b, "UTF-8");
        }
        m.w1 = readFloats(d); m.b1 = readFloats(d);
        m.w2 = readFloats(d); m.b2 = readFloats(d);
        m.w3 = readFloats(d); m.b3 = readFloats(d);
        m.outMean = readFloats(d); m.outStd = readFloats(d);
        return m;
    }

    private static float[] readFloats(java.io.DataInputStream d) throws IOException {
        int n = d.readInt();
        if (n < 0 || n > 8_000_000) throw new IOException("bad array length " + n);
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = d.readFloat();
        return a;
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
