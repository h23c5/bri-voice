package com.dsh.v4tune.voicebank;

import com.dsh.v4tune.phoneme.ChineseG2P;
import com.dsh.v4tune.phoneme.PhonemeTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * BriVoice 11.0 custom voicebank builder.
 *
 * <p>Turns the user's own WAV takes into a measured {@code .v4voice} pack. A take is named after
 * the syllable it sings ({@code la.wav}, {@code ma.wav}, {@code a.wav}, {@code tian.wav} ...); the
 * file name selects the phoneme, so a handful of takes already gives the renderer real vowel
 * spectra instead of the reference table. Every take contributes:</p>
 * <ul>
 *   <li>a measured harmonic band per pitch cluster - the real timbre of the take, and</li>
 *   <li>a short frame sequence per band, so the note keeps the swell/dynamics of the recording,</li>
 *   <li>a noise spectrum for the leading consonant, when the syllable has one.</li>
 * </ul>
 *
 * <p>Pure Java + the same helpers the desktop converter uses, so it can be tested off-device.
 * The neural link model is trained by the caller ({@link com.dsh.v4tune.synth.NeuralSpectrum});
 * this class only measures.</p>
 */
public final class CustomBankBuilder {

    /** one uploaded take */
    public static final class Sample {
        public final String fileName;
        public final float[] mono;
        public final int sampleRate;
        /** original WAV bytes, kept inside the pack so the user can re-train later */
        public final byte[] wavBytes;

        public Sample(String fileName, float[] mono, int sampleRate, byte[] wavBytes) {
            this.fileName = fileName == null ? "" : fileName;
            this.mono = mono;
            this.sampleRate = sampleRate;
            this.wavBytes = wavBytes;
        }
    }

    /** a measured symbol, reported to the UI */
    public static final class Report {
        public int takes;
        public int symbols;
        public int bands;
        public double f0Min = -1, f0Max = -1;
        public final StringBuilder skipped = new StringBuilder();
        /** sampled neural model trained from the raw takes (v11.1), null when not requested */
        public com.dsh.v4tune.synth.SampleNet.Model sampleNet;
        public byte[] sampleNetBytes;
        public int sampleNetFrames;
        public int sampleNetParams;
        public String sampleNetError;
    }

    private static final String[] VOWELS = {"a", "i", "u", "e", "o"};
    private static final double F0_MIN = 60.0, F0_MAX = 1000.0;
    private static final int MAX_SAMPLES = 64;

    private CustomBankBuilder() { }

    /**
     * @param name display name chosen by the user (may be empty)
     * @param lang 0 = JPN, 1 = ENG, 2 = CHN
     */
    public static VoicebankWriter build(List<Sample> samples, String name, int lang, Report report)
            throws Exception {
        return build(samples, name, lang, report, null);
    }

    /**
     * @param base sampled neural model to fine-tune on these takes (v11.1); null keeps the
     *             measurement-only voicebank
     */
    public static VoicebankWriter build(List<Sample> samples, String name, int lang, Report report,
                                        com.dsh.v4tune.synth.SampleNet.Model base) throws Exception {
        if (samples == null || samples.isEmpty()) throw new IllegalStateException("没有选择采样");
        VoicebankWriter w = new VoicebankWriter();
        w.id = "custom-" + Long.toString(System.currentTimeMillis(), 36) + "-" + Integer.toHexString(samples.size() * 131);
        String nm = (name == null || name.trim().isEmpty()) ? "我的声库" : name.trim();
        w.name = nm;
        w.nameEn = nm;
        w.nameJa = nm;
        w.lang = lang;
        w.compID = "BriVoiceCustom";
        w.source = "BriVoice 自制声库 · 本机分析 " + samples.size() + " 个采样";
        w.notes = "Sampled voicebank built in-app from the user's own WAV takes; harmonic envelopes and "
                + "frame sequences were measured on the phone, no cloud processing.";
        w.engine = "voice";

        w.voice.put("formantScale", 1.0);
        w.voice.put("f1Mul", 1.0);
        w.voice.put("f2Mul", 1.0);
        w.voice.put("f3Mul", 1.0);
        w.voice.put("tiltBias", 0.0);
        w.voice.put("breathBias", 0.12);
        w.voice.put("oqBias", 0.0);
        w.voice.put("vibratoRate", 5.4);
        w.voice.put("gain", 1.0);
        w.voice.put("consonantGain", 1.0);
        w.voice.put("breathSoundAmount", 0.3);
        double[][] shelf = {{60, 0}, {152, 0}, {386, 0}, {980, 0}, {2486, 0}, {6307, 0}, {16000, 0}};
        for (double[] p : shelf) w.shelf.add(p);

        double[] grid = VoicebankPack.harmonicGrid(VoicebankPack.HARM_POINTS);
        w.harmFreqs = grid;

        // symbol -> all measured frames of every take that sings that symbol
        LinkedHashMap<String, ArrayList<double[]>> curvesBySym = new LinkedHashMap<String, ArrayList<double[]>>();
        LinkedHashMap<String, ArrayList<Double>> f0BySym = new LinkedHashMap<String, ArrayList<Double>>();
        LinkedHashMap<String, ArrayList<double[]>> noiseBySym = new LinkedHashMap<String, ArrayList<double[]>>();
        double fmin = 1e9, fmax = 0;
        int takes = 0;

        for (Sample s : samples) {
            if (s == null || s.mono == null || s.sampleRate <= 0) continue;
            if (takes >= MAX_SAMPLES) break;
            String stem = stemOf(s.fileName);
            List<String> ph = phonemesOf(stem);
            if (ph == null || ph.isEmpty()) {
                if (report.skipped.length() < 180) {
                    report.skipped.append(report.skipped.length() > 0 ? "、" : "").append(s.fileName);
                }
                continue;
            }
            String vowel = ph.get(ph.size() - 1);
            if (!PhonemeTable.isVowel(vowel)) vowel = lastVowel(ph);
            if (vowel == null) continue;
            List<double[]> frames = new ArrayList<double[]>();
            List<Double> f0s = new ArrayList<Double>();
            measure(s, grid, frames, f0s);
            if (frames.size() < 3) continue;
            ArrayList<double[]> all = curvesBySym.get(vowel);
            if (all == null) { all = new ArrayList<double[]>(); curvesBySym.put(vowel, all); }
            ArrayList<Double> fl = f0BySym.get(vowel);
            if (fl == null) { fl = new ArrayList<Double>(); f0BySym.put(vowel, fl); }
            for (int i = 0; i < frames.size() && all.size() < 420; i++) {
                all.add(frames.get(i));
                fl.add(f0s.get(i));
                double f = f0s.get(i).doubleValue();
                if (f < fmin) fmin = f;
                if (f > fmax) fmax = f;
            }
            // leading consonants -> noise spectrum of the first 60 ms
            for (int i = 0; i < ph.size() - 1; i++) {
                String cons = ph.get(i);
                if (cons == null || cons.isEmpty() || PhonemeTable.isVowel(cons)) continue;
                double[] nz = noiseCurve(s, grid);
                if (nz == null) continue;
                ArrayList<double[]> nl = noiseBySym.get(cons);
                if (nl == null) { nl = new ArrayList<double[]>(); noiseBySym.put(cons, nl); }
                nl.add(nz);
            }
            takes++;
        }
        if (curvesBySym.isEmpty()) {
            throw new IllegalStateException("采样里没有测到可用的元音（文件名请写成 la / ma / a 这类音节）");
        }

        int bands = 0;
        for (java.util.Map.Entry<String, ArrayList<double[]>> en : curvesBySym.entrySet()) {
            String sym = en.getKey();
            ArrayList<double[]> curves = en.getValue();
            ArrayList<Double> fl = f0BySym.get(sym);
            double[] f0a = new double[curves.size()];
            for (int i = 0; i < f0a.length; i++) f0a[i] = (fl != null && i < fl.size()) ? fl.get(i).doubleValue() : 0;
            int[] part = new int[curves.size()];
            for (int i = 0; i < part.length; i++) part[i] = i;
            // one band per pitch cluster so a high and a low take do not average into mush
            ArrayList<Integer> order = new ArrayList<Integer>();
            for (int i = 0; i < f0a.length; i++) if (f0a[i] > 0) order.add(Integer.valueOf(i));
            Collections.sort(order, new java.util.Comparator<Integer>() {
                public int compare(Integer a, Integer b) { return Double.compare(a.doubleValue(), b.doubleValue()); }
            });
            ArrayList<ArrayList<Integer>> clusters = new ArrayList<ArrayList<Integer>>();
            for (Integer idx : order) {
                double f = f0a[idx.intValue()];
                ArrayList<Integer> cur = clusters.isEmpty() ? null : clusters.get(clusters.size() - 1);
                if (cur == null || Math.abs(f - f0a[cur.get(0).intValue()]) > f * 0.09) {
                    cur = new ArrayList<Integer>();
                    clusters.add(cur);
                }
                cur.add(idx);
            }
            if (clusters.isEmpty()) continue;
            ArrayList<VoicebankPack.Band> out = new ArrayList<VoicebankPack.Band>();
            for (ArrayList<Integer> cl : clusters) {
                if (cl.size() < 3) continue;
                int[] sub = new int[cl.size()];
                for (int i = 0; i < sub.length; i++) sub[i] = cl.get(i).intValue();
                ArrayList<VoicebankPack.Band> vs = VoicebankPack.dynVariants(
                        curves.toArray(new double[0][]), f0a, sub);
                for (VoicebankPack.Band b : vs) { b.hasF0 = true; out.add(b); }
            }
            if (out.isEmpty()) {
                VoicebankPack.Band b = VoicebankPack.bandCurve(curves.toArray(new double[0][]), f0a, Double.valueOf(0.5));
                out.add(b);
            }
            // keep at most three pitch bands per symbol (lowest, middle, highest)
            while (out.size() > 3) out.remove(out.size() - 2);
            w.phonemes.put(sym, out);
            int[] fm = ReferenceFormants.get(sym);
            if (fm != null) w.formants.put(sym, fm);
            bands += out.size();
        }
        for (java.util.Map.Entry<String, ArrayList<double[]>> en : noiseBySym.entrySet()) {
            ArrayList<double[]> list = en.getValue();
            if (list.isEmpty()) continue;
            double[] mean = new double[grid.length];
            for (double[] c : list) for (int i = 0; i < mean.length; i++) mean[i] += Math.pow(10.0, c[i] / 20.0);
            double mx = -Double.MAX_VALUE;
            for (int i = 0; i < mean.length; i++) { mean[i] /= list.size(); if (mean[i] > mx) mx = mean[i]; }
            double[] db = new double[grid.length];
            for (int i = 0; i < grid.length; i++) {
                double v = 20.0 * Math.log10(Math.max(mean[i], 1e-9) / Math.max(mx, 1e-9));
                db[i] = Math.max(VoicebankPack.HARM_DB_FLOOR, Math.min(0.0, Math.rint(v / VoicebankPack.HARM_DB_STEP) * VoicebankPack.HARM_DB_STEP));
            }
            w.noise.put(en.getKey(), new double[][]{grid, db});
        }
        w.stats.put("samples", takes);
        w.stats.put("clusters", w.phonemes.size());
        w.stats.put("matchedPhonemes", w.phonemes.size());
        w.stats.put("bands", bands);
        w.stats.put("noiseNamed", w.noise.size());
        w.stats.put("f0Min", fmin == 1e9 ? 0.0 : Math.round(fmin * 10) / 10.0);
        w.stats.put("f0Max", fmax == 0.0 ? 0.0 : Math.round(fmax * 10) / 10.0);
        w.stats.put("f0Median", 0.0);
        if (report != null) {
            report.takes = takes;
            report.symbols = w.phonemes.size();
            report.bands = bands;
            report.f0Min = fmin == 1e9 ? -1 : fmin;
            report.f0Max = fmax;
            if (base != null) {
                try {
                    trainSampleNet(samples, base, report);
                } catch (Throwable t) {
                    report.sampleNetError = String.valueOf(t);
                }
            }
        }
        return w;
    }

    // ------------------------------------------------------------------ sampled neural model (v11.1)

    /**
     * Fine-tunes the shared sampled model (trained on a free UTAU bank) on the user's own takes.
     * The frames come straight from the WAV: no harmonic table, no measurement shortcut, so the
     * model learns the singer's own spectrum, noise and time evolution.
     */
    private static void trainSampleNet(List<Sample> samples, com.dsh.v4tune.synth.SampleNet.Model base,
                                       Report report) throws Exception {
        // work on a private copy: the asset model must stay untouched for the next voicebank
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(160 * 1024);
        com.dsh.v4tune.synth.SampleNet.write(base, bos);
        com.dsh.v4tune.synth.SampleNet.Model m =
                com.dsh.v4tune.synth.SampleNet.read(new java.io.ByteArrayInputStream(bos.toByteArray()));
        LinkedHashMap<String, ArrayList<float[]>> takes = new LinkedHashMap<String, ArrayList<float[]>>();
        for (Sample s : samples) {
            if (s == null || s.mono == null || s.sampleRate <= 0) continue;
            List<String> ph = phonemesOf(stemOf(s.fileName));
            if (ph == null || ph.isEmpty()) continue;
            String vowel = ph.get(ph.size() - 1);
            if (!PhonemeTable.isVowel(vowel)) vowel = lastVowel(ph);
            if (vowel == null) continue;
            ArrayList<float[]> l = takes.get(vowel);
            if (l == null) { l = new ArrayList<float[]>(); takes.put(vowel, l); }
            if (l.size() < 8) l.add(s.mono);
        }
        if (takes.isEmpty()) { report.sampleNetError = "没有可用的元音采样"; return; }
        LinkedHashMap<String, java.util.List<float[]>> ds = new LinkedHashMap<String, java.util.List<float[]>>();
        for (java.util.Map.Entry<String, ArrayList<float[]>> en : takes.entrySet()) {
            ds.put(en.getKey(), new ArrayList<float[]>(en.getValue()));
        }
        com.dsh.v4tune.synth.SampleNet.Dataset data = com.dsh.v4tune.synth.SampleNet.build(ds, m);
        if (data == null || data.x == null || data.x.length < 120) {
            report.sampleNetError = "采样帧数太少（" + (data == null || data.x == null ? 0 : data.x.length) + " 帧）";
            return;
        }
        com.dsh.v4tune.synth.SampleNet.fitTargetStats(m, data);
        com.dsh.v4tune.synth.SampleNet.FitOptions o = new com.dsh.v4tune.synth.SampleNet.FitOptions();
        o.epochs = 24;
        o.batch = 64;
        o.lr = 8e-4;
        com.dsh.v4tune.synth.SampleNet.train(m, data, o, null);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(160 * 1024);
        com.dsh.v4tune.synth.SampleNet.write(m, out);
        report.sampleNet = m;
        report.sampleNetBytes = out.toByteArray();
        report.sampleNetFrames = data.x.length;
        report.sampleNetParams = m.paramCount();
    }

    // ------------------------------------------------------------------ measurement

    private static void measure(Sample s, double[] grid, List<double[]> curves, List<Double> f0s) {
        float[] x = s.mono;
        int sr = s.sampleRate;
        int win = Math.min(1024, x.length);
        if (win < 512) return;
        int hop = Math.max(160, (int) (0.012 * sr));
        int start = Math.max(0, x.length / 10);
        int end = Math.max(start, x.length - Math.max(win, x.length / 10));
        double[] hann = new double[win];
        for (int i = 0; i < win; i++) hann[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (win - 1));
        for (int off = start; off + win <= end; off += hop) {
            double[] seg = new double[win];
            double rms = 0;
            for (int i = 0; i < win; i++) { seg[i] = x[off + i]; rms += seg[i] * seg[i]; }
            rms = Math.sqrt(rms / win);
            if (rms < 0.01) continue;
            double f0 = f0Of(seg, sr);
            if (f0 < F0_MIN || f0 > F0_MAX) continue;
            double[] spec = spectrum(seg, hann);
            double[] curve = harmonicEnvelope(spec, sr, f0, grid);
            if (curve == null) continue;
            curves.add(curve);
            f0s.add(Double.valueOf(f0));
            if (curves.size() >= 200) break;
        }
    }

    private static double[] noiseCurve(Sample s, double[] grid) {
        int sr = s.sampleRate;
        int n = Math.min((int) (0.06 * sr), s.mono.length);
        if (n < 256) return null;
        double[] seg = new double[n];
        for (int i = 0; i < n; i++) seg[i] = s.mono[i];
        double[] hann = new double[n];
        for (int i = 0; i < n; i++) hann[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
        double[] spec = spectrum(seg, hann);
        int bins = spec.length;
        double[] freqs = new double[bins];
        for (int i = 1; i < bins; i++) freqs[i] = i * (sr / 2.0) / (bins - 1);
        double[] db = new double[bins];
        for (int i = 1; i < bins; i++) db[i] = 20.0 * Math.log10(Math.max(spec[i], 1e-9));
        freqs[0] = 1.0;
        return VoicebankPack.frameHarmCurve(freqs, db, grid);
    }

    /** harmonic envelope: sample the FFT at k*f0 (peak ±f0/2) then interpolate onto the grid */
    private static double[] harmonicEnvelope(double[] mag, int sr, double f0, double[] grid) {
        int bins = mag.length;
        int maxK = (int) Math.min(120, (sr / 2.0) / f0);
        if (maxK < 4) return null;
        double[] hf = new double[maxK];
        double[] hm = new double[maxK];
        for (int k = 1; k <= maxK; k++) {
            double f = k * f0;
            int c = (int) Math.round(f * (bins - 1) / (sr / 2.0));
            int lo = Math.max(1, c - 1), hi = Math.min(bins - 1, c + 1);
            double best = 0;
            for (int b = lo; b <= hi; b++) if (mag[b] > best) best = mag[b];
            hf[k - 1] = f;
            hm[k - 1] = 20.0 * Math.log10(Math.max(best, 1e-9));
        }
        return VoicebankPack.frameHarmCurve(hf, hm, grid);
    }

    /** Hann-windowed magnitude spectrum via an in-place radix-2 FFT (bins = n/2+1) */
    private static double[] spectrum(double[] seg, double[] win) {
        int n = 1;
        while (n * 2 <= seg.length) n *= 2;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) re[i] = seg[i] * win[i];
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1.0, ci = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j, b = a + len / 2;
                    double vr = re[b] * cr - im[b] * ci;
                    double vi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - vr; im[b] = im[a] - vi;
                    re[a] += vr; im[a] += vi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
        double[] mag = new double[n / 2 + 1];
        for (int k = 0; k < mag.length; k++) mag[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
        return mag;
    }

    /** normalised autocorrelation pitch detector; 0 when unvoiced */
    private static double f0Of(double[] x, int sr) {
        int n = Math.min(1024, x.length);
        int lo = Math.max(2, (int) (sr / F0_MAX));
        int hi = Math.min(n - 2, (int) (sr / F0_MIN));
        if (hi <= lo) return 0;
        double[] score = new double[hi + 2];
        double best = 0;
        int bestLag = 0;
        for (int lag = lo; lag <= hi; lag++) {
            double v = corr(x, n, lag);
            score[lag] = v;
            if (v > best) { best = v; bestLag = lag; }
        }
        if (best < 0.30 || bestLag <= 0) return 0;
        // Prefer the SMALLEST period whose correlation is close to the global maximum: plain
        // argmax locks onto 2x/3x the period (a 220 Hz take was measured as 110 Hz).
        int chosen = bestLag;
        for (int lag = Math.max(lo + 1, (int) (sr / F0_MAX)); lag < hi; lag++) {
            if (score[lag] >= 0.88 * best && score[lag] >= score[lag - 1] && score[lag] >= score[lag + 1]) {
                chosen = lag;
                break;
            }
        }
        best = score[chosen];
        bestLag = chosen;
        double d = 0;
        if (bestLag > lo && bestLag < hi) {
            double a = score[bestLag - 1], b = best, c = score[bestLag + 1];
            double den = a - 2 * b + c;
            if (Math.abs(den) > 1e-12) d = 0.5 * (a - c) / den;
        }
        double lag = bestLag + Math.max(-0.5, Math.min(0.5, d));
        return sr / lag;
    }

    private static double corr(double[] x, int n, int lag) {
        double s = 0, e1 = 0, e2 = 0;
        for (int i = 0; i + lag < n; i++) {
            double a = x[i], b = x[i + lag];
            s += a * b; e1 += a * a; e2 += b * b;
        }
        double norm = Math.sqrt(e1 * e2);
        return norm > 1e-9 ? s / norm : 0;
    }

    // ------------------------------------------------------------------ naming

    /** la.wav / la_1.wav / LA.WAV -> "la" */
    private static String stemOf(String fileName) {
        String n = fileName == null ? "" : fileName;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.trim().toLowerCase(Locale.ROOT);
        while (n.matches(".*[_\\-\\s][0-9]+$")) n = n.replaceAll("[_\\-\\s][0-9]+$", "");
        n = n.replaceAll("[0-9]+$", "");
        return n.trim();
    }

    /** the one resolver shared with RecordGuide: file stem -> phonemes, null when unreadable */
    public static List<String> phonemesOf(String stem) {
        if (stem == null || stem.isEmpty()) return null;
        if (ChineseG2P.isSyllable(stem)) {
            List<String> p = ChineseG2P.syllableToPhonemes(stem);
            if (p != null && !p.isEmpty()) return p;
        }
        if (PhonemeTable.get(stem) != null) return Collections.singletonList(stem);
        try {
            List<String> jp = com.dsh.v4tune.phoneme.JapaneseG2P.lyricToPhonemes(stem);
            if (jp != null && !jp.isEmpty()) return jp;
        } catch (Throwable ignored) { }
        String v = lastVowelIn(stem);
        return (v == null) ? null : Collections.singletonList(v);
    }

    private static String lastVowel(List<String> ph) {
        for (int i = ph.size() - 1; i >= 0; i--) {
            if (PhonemeTable.isVowel(ph.get(i))) return ph.get(i);
        }
        return null;
    }

    private static String lastVowelIn(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            String c = String.valueOf(s.charAt(i));
            for (String v : VOWELS) if (v.equals(c)) return v;
        }
        return null;
    }
}
