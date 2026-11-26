package com.dsh.v4tune.ai;

import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BriTune-Net (v10_26H1): the tuning assistant that runs entirely on the phone.
 *
 * <p>It is a small dense network distilled from a rule teacher that encodes common Chinese
 * vocal-tuning practice, so no request ever leaves the device. v3 is 88 -> 256 -> 128 -> 13
 * (about 57k weights / 225 KB): four extra beat/context features per slot and two extra outputs
 * (note-internal DYN shape, delayed vibrato onset). The header carries the real shape and the
 * number of features is derived from it, so v2 files (68 -> 192 -> 96 -> 11) still load and keep
 * their old behaviour. The structured part JSON is the input; the tuning strategy that the cloud
 * prompt describes in words is baked into the model as five style profiles plus an intensity
 * axis. The output is the same JSON the cloud assistant returns, so
 * {@link AiTuner#parseTuning} and the normal apply path handle it unchanged.
 *
 * <p>Weights live in a separate file pulled from the release page after the app itself updates
 * (see {@link LocalModelStore}), which keeps the APK small.
 */
public final class LocalTuner {

    public static final String[] STYLES = {"pop", "ballad", "rock", "electronic", "guofeng"};
    public static final String[] STYLE_NAMES = {"流行", "抒情", "摇滚", "电子", "国风"};

    /** which parameter each network output drives */
    public static final String[] OUT_PARAM = {"DYN", "BRE", "BRI", "CLE", "OPE", "POR", "VIB", "PIT", "PIT", "VEL", "GEN"};

    /** parameter defaults, same table the piano-roll parameter lane uses */
    static final double[] PARAM_DEF = {64, 64, 64, 64, 64, 64, 64, 0, 2, 64, 64, 64};

    private static final int N_FEAT = 12;          // v2 layout, kept for old weight files
    private static final int N_FEAT_V3 = 16;       // + beat position / strong / off / repeated pitch
    private static final int IN_DIM_V3 = (2 * 2 + 1) * N_FEAT_V3 + 8;
    private static final int CTX = 2;
    private static final int GLOBAL = 8;

    /** progress callback: the UI redraws a bar with it, so it must stay cheap */
    public interface Progress {
        /** @param done notes finished, @param total notes to do, @param stage short label */
        void onProgress(int done, int total, String stage);
    }

    /** one loaded model; immutable after {@link #load(File)} */
    public static final class Model {
        public int inDim, h1, h2, outDim;
        public float[] mu, sd, w1, b1, w2, b2, w3, b3, outScale;

        public int paramCount() {
            return w1.length + b1.length + w2.length + b2.length + w3.length + b3.length;
        }

        public String describe() {
            return "BriTune-Net · " + inDim + "-" + h1 + "-" + h2 + "-" + outDim + " · 参数 " + paramCount();
        }
    }

    /** the model file itself is little-endian float32; this is the only place that knows the order */
    public static Model load(File f) throws IOException {
        byte[] all = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
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
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        if (all.length < 24) throw new IOException("模型文件不完整");
        byte[] magic = new byte[4];
        bb.get(magic);
        if (magic[0] != 'B' || magic[1] != 'R' || magic[2] != 'T' || magic[3] != 'N')
            throw new IOException("不是 BriTune 模型文件");
        Model m = new Model();
        bb.getInt();                       // version
        m.inDim = bb.getInt();
        m.h1 = bb.getInt();
        m.h2 = bb.getInt();
        m.outDim = bb.getInt();
        if (m.inDim <= 0 || m.h1 <= 0 || m.h2 <= 0 || m.outDim <= 0 || m.inDim > 4096)
            throw new IOException("模型头无效");
        int need = 24 + 4 * (m.inDim * 2 + m.inDim * m.h1 + m.h1 + m.h1 * m.h2 + m.h2
                + m.h2 * m.outDim + m.outDim + m.outDim);
        if (all.length < need) throw new IOException("模型文件长度不足");
        m.mu = floats(bb, m.inDim);
        m.sd = floats(bb, m.inDim);
        m.w1 = floats(bb, m.inDim * m.h1);
        m.b1 = floats(bb, m.h1);
        m.w2 = floats(bb, m.h1 * m.h2);
        m.b2 = floats(bb, m.h2);
        m.w3 = floats(bb, m.h2 * m.outDim);
        m.b3 = floats(bb, m.outDim);
        m.outScale = floats(bb, m.outDim);
        return m;
    }

    private static float[] floats(ByteBuffer bb, int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = bb.getFloat();
        return a;
    }

    /** writes the exact same layout {@link #load(File)} reads; used by self-learning */
    public static void save(Model m, File f) throws IOException {
        int bytes = 24 + 4 * (m.inDim * 2 + m.inDim * m.h1 + m.h1 + m.h1 * m.h2 + m.h2
                + m.h2 * m.outDim + m.outDim + m.outDim);
        ByteBuffer bb = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[]{'B', 'R', 'T', 'N'});
        bb.putInt(1);
        bb.putInt(m.inDim);
        bb.putInt(m.h1);
        bb.putInt(m.h2);
        bb.putInt(m.outDim);
        put(bb, m.mu); put(bb, m.sd);
        put(bb, m.w1); put(bb, m.b1);
        put(bb, m.w2); put(bb, m.b2);
        put(bb, m.w3); put(bb, m.b3);
        put(bb, m.outScale);
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(bb.array());
        } finally {
            try { out.close(); } catch (Throwable ignored) { }
        }
        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) {
            FileOutputStream direct = new FileOutputStream(f);
            try { direct.write(bb.array()); } finally {
                try { direct.close(); } catch (Throwable ignored) { }
            }
            tmp.delete();
        }
    }

    private static void put(ByteBuffer bb, float[] a) {
        for (int i = 0; i < a.length; i++) bb.putFloat(a[i]);
    }

    /**
     * v12.2: weighted average of two BriTune-Net models with the same shape ("model soup"). The
     * result keeps the architecture of {@code a} and is a real model: it goes through the same
     * {@link #save(Model, File)} / {@link #load(File)} path before it may replace anything.
     *
     * @param wA 0..1 weight of {@code a} (the installed model when merging an import)
     */
    public static Model blend(Model a, Model b, double wA) throws IOException {
        if (a == null || b == null) throw new IOException("缺少要合并的模型");
        if (a.inDim != b.inDim || a.h1 != b.h1 || a.h2 != b.h2 || a.outDim != b.outDim) {
            throw new IOException("两个 BriTune-Net 结构不同（" + a.inDim + "-" + a.h1 + "-" + a.h2 + "-" + a.outDim
                    + " vs " + b.inDim + "-" + b.h1 + "-" + b.h2 + "-" + b.outDim + "），不能合并");
        }
        Model m = new Model();
        m.inDim = a.inDim;
        m.h1 = a.h1;
        m.h2 = a.h2;
        m.outDim = a.outDim;
        double wB = 1.0 - wA;
        m.mu = mix(a.mu, b.mu, wA, wB);
        m.sd = mix(a.sd, b.sd, wA, wB);
        m.w1 = mix(a.w1, b.w1, wA, wB);
        m.b1 = mix(a.b1, b.b1, wA, wB);
        m.w2 = mix(a.w2, b.w2, wA, wB);
        m.b2 = mix(a.b2, b.b2, wA, wB);
        m.w3 = mix(a.w3, b.w3, wA, wB);
        m.b3 = mix(a.b3, b.b3, wA, wB);
        m.outScale = mix(a.outScale, b.outScale, wA, wB);
        return m;
    }

    private static float[] mix(float[] x, float[] y, double wx, double wy) {
        float[] out = new float[x.length];
        for (int i = 0; i < out.length; i++) out[i] = (float) (x[i] * wx + y[i] * wy);
        return out;
    }

    /** "流行" etc -> index into {@link #STYLES}; unknown text falls back to pop */
    public static int styleIndex(String s) {
        if (s != null) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(t)) return i;
            for (int i = 0; i < STYLE_NAMES.length; i++) if (STYLE_NAMES[i].equals(s.trim())) return i;
            if (t.contains("抒情") || t.contains("慢") || t.contains("ballad")) return 1;
            if (t.contains("摇滚") || t.contains("rock")) return 2;
            if (t.contains("电子") || t.contains("electronic")) return 3;
            if (t.contains("古") || t.contains("国风") || t.contains("guofeng")) return 4;
        }
        return 0;
    }

    /** true when the loaded file was trained with the v3 layout; features must match it exactly */
    static boolean isV3(Model m) { return m != null && m.inDim == IN_DIM_V3; }

    /** one note's 12 features; kept identical to the training script, do not reorder */
    private static void noteFeatures(List<Note> ns, int i, float[] out, int at) {
        Note n = ns.get(i);
        int cnt = ns.size();
        boolean first = i == 0, last = i == cnt - 1;
        double pos = cnt <= 1 ? 0d : (double) i / (double) (cnt - 1);
        long restBefore = 0;
        long restAfter = 240;
        int prevKey = n.key, nextKey = n.key;
        if (i > 0) {
            Note pv = ns.get(i - 1);
            restBefore = Math.max(0, n.pos - (pv.pos + pv.dur));
            prevKey = pv.key;
        }
        if (!last) {
            Note nx = ns.get(i + 1);
            restAfter = Math.max(0, nx.pos - (n.pos + n.dur));
            nextKey = nx.key;
        }
        double leap = prevKey == 0 ? 0 : (n.key - prevKey) / 12.0;
        double nleap = (nextKey - n.key) / 12.0;
        int lyricLen = n.lyric == null ? 1 : Math.max(1, n.lyric.trim().length());
        out[at] = (float) ((n.key - 60) / 24.0);
        out[at + 1] = (float) (Math.log(Math.max(30, n.dur) / 120.0) / Math.log(2.0) / 3.0);
        out[at + 2] = n.vel / 127f;
        out[at + 3] = first ? 1f : 0f;
        out[at + 4] = last ? 1f : 0f;
        out[at + 5] = (float) pos;
        out[at + 6] = (float) Math.min(1.0, restBefore / 480.0);
        out[at + 7] = (float) Math.min(1.0, restAfter / 480.0);
        out[at + 8] = (float) Math.max(-1.0, Math.min(1.0, leap));
        out[at + 9] = (float) Math.max(-1.0, Math.min(1.0, nleap));
        out[at + 10] = n.dur >= 480 ? 1f : 0f;
        out[at + 11] = (float) Math.min(1.0, lyricLen / 8.0);
    }

    /**
     * v3 per-note features: the same 12 as v2 plus the metric grid the teacher actually accents
     * (bar position, strong beat, off beat) and a repeated-pitch flag. The app is 4/4, so a bar is
     * 1920 ticks and a beat 480; that matches the teacher. Kept identical to the training script.
     */
    private static void noteFeaturesV3(List<Note> ns, int i, float[] out, int at) {
        noteFeatures(ns, i, out, at);
        Note n = ns.get(i);
        double beatInBar = (n.pos % 1920) / 480.0;
        boolean onBeat = Math.abs(beatInBar - Math.round(beatInBar)) < 0.06;
        boolean strongBeat = onBeat && ((int) Math.round(beatInBar)) % 2 == 0;
        boolean offBeat = Math.abs(beatInBar - (Math.floor(beatInBar) + 0.5)) < 0.08;
        int prevKey = i > 0 ? ns.get(i - 1).key : n.key;
        out[at + 12] = (float) (beatInBar / 4.0);
        out[at + 13] = strongBeat ? 1f : 0f;
        out[at + 14] = offBeat ? 1f : 0f;
        out[at + 15] = n.key == prevKey ? 1f : 0f;
    }

    /** the full input for note {@code i}: 12 (v2) or 16 (v3) features x 5 context slots */
    static float[] features(List<Note> ns, int i, Model m) {
        boolean v3 = isV3(m);
        int nf = v3 ? N_FEAT_V3 : N_FEAT;
        float[] x = new float[v3 ? IN_DIM_V3 : 5 * N_FEAT + GLOBAL];
        int ctxSpan = CTX * 2 + 1;
        for (int c = 0; c < ctxSpan; c++) {
            int j = i + c - CTX;
            if (j < 0) j = 0;
            if (j > ns.size() - 1) j = ns.size() - 1;
            if (v3) noteFeaturesV3(ns, j, x, c * nf);
            else noteFeatures(ns, j, x, c * nf);
        }
        return x;
    }

    /** input vector after per-feature mean/std normalisation, with the globals written in */
    static float[] normInput(Model m, float[] x, double tempo, int style, double intensity, int noteCount) {
        int in = m.inDim;
        float[] buf = new float[in];
        for (int i = 0; i < in; i++) {
            // a feature the dataset never varied is constant; training normalised it to exactly 0,
            // so keep it neutral here instead of amplifying a live value by 1/sd
            if (m.sd[i] < 1e-3f) { buf[i] = 0f; continue; }
            buf[i] = (x[i] - m.mu[i]) / m.sd[i];
        }
        // globals ride at the tail of the vector
        int g = in - GLOBAL;
        buf[g] = (float) (tempo / 120.0 - 1.0);
        for (int s = 0; s < 5; s++) buf[g + 1 + s] = (style == s) ? 1f : 0f;
        buf[g + 6] = (float) intensity;
        buf[g + 7] = noteCount / 64f;

        return buf;
    }

    /**
     * Forward pass on an already normalised input. Returns the raw network output, i.e. the value
     * before the per-parameter output scale; self-learning trains against exactly this.
     */
    static float[] forwardRaw(Model m, float[] buf) {
        int in = m.inDim;
        // weights are stored row-major as written by numpy: W1[in][h1], W2[h1][h2], W3[h2][out]
        float[] h1 = new float[m.h1];
        for (int i = 0; i < in; i++) {
            float xv = buf[i];
            if (xv == 0f) continue;
            int row = i * m.h1;
            for (int j = 0; j < m.h1; j++) h1[j] += xv * m.w1[row + j];
        }
        for (int j = 0; j < m.h1; j++) h1[j] = Math.max(0f, h1[j] + m.b1[j]);
        float[] h2 = new float[m.h2];
        for (int j = 0; j < m.h1; j++) {
            float hv = h1[j];
            if (hv == 0f) continue;
            int row = j * m.h2;
            for (int k = 0; k < m.h2; k++) h2[k] += hv * m.w2[row + k];
        }
        for (int k = 0; k < m.h2; k++) h2[k] = Math.max(0f, h2[k] + m.b2[k]);
        float[] out = new float[m.outDim];
        for (int k = 0; k < m.h2; k++) {
            float hv = h2[k];
            if (hv == 0f) continue;
            int row = k * m.outDim;
            for (int o = 0; o < m.outDim; o++) out[o] += hv * m.w3[row + o];
        }
        for (int o = 0; o < m.outDim; o++) out[o] += m.b3[o];
        return out;
    }

    /** forward pass for a single note; returns the 11 physical deltas (already un-scaled) */
    static float[] infer(Model m, float[] x, double tempo, int style, double intensity, int noteCount) {
        float[] out = forwardRaw(m, normInput(m, x, tempo, style, intensity, noteCount));
        for (int o = 0; o < out.length; o++)
            out[o] = out[o] * (o < m.outScale.length ? m.outScale[o] : 1f);
        return out;
    }

    /** parameter value already on the curve at this tick, or the parameter default */
    private static double baseAt(Part p, int idx, long tick) {
        ControlCurve c = p.ctrlOrNull(Params.IDS[idx]);
        if (c == null || c.pts.isEmpty()) return PARAM_DEF[idx];
        return Params.clamp(idx, c.valueAt(tick));
    }

    /**
     * Tunes one part on-device and returns the same {@link Tuning} shape the cloud assistant uses.
     *
     * @param style     one of {@link #STYLES} or a {@link #STYLE_NAMES} entry
     * @param intensity 0.35..1.0, how strongly the profile is applied
     * @param strength  0..1 multiplier on every delta, for a quick "lighter / stronger" dial
     */
    public static Tuning tune(Part p, Song song, int trackIndex, Model m,
                              String style, float intensity, float strength, Progress cb) {
        Tuning t = new Tuning();
        if (p == null || m == null || p.notes.isEmpty()) return t;
        List<Note> ns = new ArrayList<Note>(p.notes);
        java.util.Collections.sort(ns, new java.util.Comparator<Note>() {
            public int compare(Note a, Note b) { return Long.compare(a.pos, b.pos); }
        });
        int total = ns.size();
        double tempo = 120;
        if (song != null && song.tempos != null && !song.tempos.isEmpty())
            tempo = song.tempos.get(0)[1] / 100.0;
        int st = styleIndex(style);
        float itn = Math.max(0.2f, Math.min(1.2f, intensity));
        float mul = Math.max(0f, Math.min(1.5f, strength));
        boolean v3 = isV3(m);

        Map<String, List<double[]>> curves = new LinkedHashMap<String, List<double[]>>();
        for (int i = 0; i < CURVE_IDS.length; i++)
            curves.put(CURVE_IDS[i], new ArrayList<double[]>());

        for (int i = 0; i < total; i++) {
            Note n = ns.get(i);
            float[] x = features(ns, i, m);
            float[] d = infer(m, x, tempo, st, itn, total);
            long start = n.pos, end = n.pos + n.dur;

            double dynDelta = d[0] * mul;
            addPoint(curves.get("DYN"), p, 0, start, dynDelta);
            addPoint(curves.get("BRE"), p, 1, start, d[1] * mul);
            addPoint(curves.get("BRI"), p, 2, start, d[2] * mul);
            addPoint(curves.get("CLE"), p, 3, start, d[3] * mul);
            addPoint(curves.get("OPE"), p, 4, start, d[4] * mul);
            addPoint(curves.get("POR"), p, 6, start, d[5] * mul);
            if (v3) {
                // note-internal dynamics: a second point near the end, so an accent can lean in
                // and release inside the note instead of only stepping at the next note's onset
                double dynShape = d[11] * mul;
                long shapeTick = end - Math.max(12, Math.min(48, n.dur / 8));
                if (Math.abs(dynShape) >= 0.5 && shapeTick > start + 24) {
                    double startValue = Params.clamp(0, baseAt(p, 0, start) + dynDelta);
                    addRaw(curves.get("DYN"), 0, shapeTick, startValue + dynShape);
                }
                // delayed vibrato: hold VIB at zero, then jump to the predicted depth at the
                // onset. The first segment is the slow bloom a singer uses on a long note.
                double vibDelta = d[6] * mul;
                double onsetFrac = Math.max(0, Math.min(0.55, d[12]));
                long onsetT = (long) (n.dur * onsetFrac);
                double depth = Params.clamp(9, baseAt(p, 9, start) + vibDelta);
                if (onsetFrac > 0.02 && onsetT > 24 && onsetT < n.dur - 24 && depth > 0.5) {
                    addRaw(curves.get("VIB"), 9, start, 0);
                    addRaw(curves.get("VIB"), 9, start + onsetT, depth);
                } else {
                    addPoint(curves.get("VIB"), p, 9, start, vibDelta);
                }
            } else {
                addPoint(curves.get("VIB"), p, 9, start, d[6] * mul);
            }
            addPoint(curves.get("GEN"), p, 5, start, d[10] * mul);
            // pitch: a scoop into the note, back to the note's own pitch, then a release at the
            // tail. The two zero points in between matter: PIT is a continuous curve, so writing
            // only the two ends would smear one long slide across the whole note (audible drift)
            // instead of a short portamento that settles on pitch.
            double lead = d[7] * mul, tail = d[8] * mul;
            long scoopT = Math.max(24, Math.min(96, n.dur / 8));
            long relT = Math.max(24, Math.min(120, n.dur / 6));
            boolean room = n.dur > scoopT + relT + 24;
            if (Math.abs(lead) >= 40) {
                addPoint(curves.get("PIT"), p, 7, start, lead);
                if (room) addPoint(curves.get("PIT"), p, 7, start + scoopT, 0);
            }
            if (Math.abs(tail) >= 40) {
                if (room) addPoint(curves.get("PIT"), p, 7, end - relT, 0);
                addPoint(curves.get("PIT"), p, 7, end, tail);
            }

            float dv = d[9] * mul;
            if (Math.abs(dv) >= 0.5f) {
                Tuning.NoteEdit e = new Tuning.NoteEdit();
                e.pos = n.pos;
                e.vel = (int) Math.max(1, Math.min(127, Math.round(n.vel + dv)));
                t.notes.add(e);
            }
            if (cb != null && (i % 16 == 0 || i == total - 1)) cb.onProgress(i + 1, total, "推理音符");
        }

        for (Map.Entry<String, List<double[]>> e : curves.entrySet()) {
            List<double[]> pts = e.getValue();
            if (pts.isEmpty()) continue;
            t.curves.put(e.getKey(), pts.toArray(new double[pts.size()][]));
        }
        if (cb != null) cb.onProgress(total, total, "生成 JSON");
        return t;
    }

    private static final String[] CURVE_IDS = {"DYN", "BRE", "BRI", "CLE", "OPE", "POR", "VIB", "GEN", "PIT"};

    private static void addPoint(List<double[]> out, Part p, int idx, long tick, double delta) {
        if (out == null) return;
        double v = Params.clamp(idx, baseAt(p, idx, tick) + delta);
        out.add(new double[]{tick, v});
    }

    /** absolute curve value, for the second point of a shaped curve (no base offset) */
    private static void addRaw(List<double[]> out, int idx, long tick, double value) {
        if (out == null) return;
        out.add(new double[]{tick, Params.clamp(idx, value)});
    }

    /** the model's answer as printable JSON; this is what "导出 JSON" saves */
    public static String toJson(Tuning t) {
        if (t == null) return "{}";
        StringBuilder b = new StringBuilder(256);
        b.append("{\n  \"source\": \"britune-local\",\n  \"curves\": {");
        boolean firstCurve = true;
        for (Map.Entry<String, double[][]> e : t.curves.entrySet()) {
            if (!firstCurve) b.append(',');
            firstCurve = false;
            b.append("\n    \"").append(e.getKey()).append("\": [");
            double[][] pts = e.getValue();
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) b.append(',');
                b.append('[').append((long) pts[i][0]).append(',')
                        .append(Math.round(pts[i][1] * 10) / 10.0).append(']');
            }
            b.append(']');
        }
        if (!firstCurve) b.append("\n  ");
        b.append("},\n  \"notes\": [");
        for (int i = 0; i < t.notes.size(); i++) {
            Tuning.NoteEdit e = t.notes.get(i);
            if (i > 0) b.append(',');
            b.append("{\"pos\":").append(e.pos);
            if (e.vel != null) b.append(",\"vel\":").append(e.vel.intValue());
            b.append('}');
        }
        b.append("]\n}");
        return b.toString();
    }
}
