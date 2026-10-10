package com.dsh.v4tune.voicebank;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage C-4b: the whole pipeline, from a bank's .ddb to a finished pack.
 *
 * <p>Order and rules follow build_pack (tools/voicebank_pack.py). Two frame sets are decoded on
 * purpose: the formants path keeps its strict gate (FrameEnvelope -> clusters -> formants) while
 * the harmonics path uses the looser one, exactly as the Python docstring requires.
 *
 * <p>The k-means seeding differs from the Python by agreement (see VoicebankCluster): labels may
 * differ, the shape must not.
 */
public final class VoicebankBuilder {

    public static final class Options {
        public int maxFrames = 20000;
        public int clusters = 48;
        public long frameBudget = 64L << 20;
        public long noiseBudget = 96L << 20;
        public int noiseSamples = 800;
    }

    private VoicebankBuilder() { }

    public static VoicebankWriter build(File ddb, String bankName, String compID, Options o) throws Exception {
        long size = ddb.length();
        long[] offs = VoicebankFrames.scanFrm2Offsets(ddb, size, o.frameBudget);
        RandomAccessFile f = new RandomAccessFile(ddb, "r");
        ArrayList<double[]> envs = new ArrayList<double[]>();      // strict gate
        ArrayList<Double> envF0 = new ArrayList<Double>();
        ArrayList<double[]> curves = new ArrayList<double[]>();    // loose gate (harmonics)
        ArrayList<Double> curF0 = new ArrayList<Double>();
        ArrayList<Long> envOff = new ArrayList<Long>();
        ArrayList<Long> curOff = new ArrayList<Long>();
        int decoded = 0;
        try {
            for (long off : offs) {
                if (decoded >= o.maxFrames) break;
                VoicebankFrames.Frame strict = VoicebankFrames.readFrame(f, off,
                        VoicebankFrames.F0_MIN_HZ, VoicebankFrames.F0_MAX_HZ, VoicebankFrames.MIN_HARMONICS);
                if (strict != null) {
                    envs.add(VoicebankCluster.frameEnvelope(strict.freqs, strict.magsDb));
                    envOff.add(Long.valueOf(off));
                    envF0.add(Double.valueOf(strict.f0));
                }
                VoicebankFrames.Frame loose = VoicebankFrames.readFrame(f, off,
                        VoicebankPack.HARM_F0_MIN, VoicebankPack.HARM_F0_MAX, VoicebankPack.HARM_MIN_HARMONICS);
                if (loose != null) {
                    decoded++;
                    double[] grid = VoicebankPack.harmonicGrid(VoicebankPack.HARM_POINTS);
                    double[] logGrid = new double[grid.length];
                    for (int i = 0; i < grid.length; i++) logGrid[i] = Math.log(grid[i]);
                    curves.add(VoicebankPack.frameHarmCurve(loose.freqs, loose.magsDb, logGrid));
                    curOff.add(Long.valueOf(off));
                    curF0.add(Double.valueOf(loose.f0));
                }
            }
        } finally {
            f.close();
        }
        if (envs.size() < VoicebankPack.HARM_BAND_MIN_FRAMES || curves.size() < VoicebankPack.HARM_BAND_MIN_FRAMES) {
            throw new IllegalStateException("too few frames: envelopes=" + envs.size() + " harmonics=" + curves.size());
        }

        VoicebankWriter w = new VoicebankWriter();
        w.format = "v4voice/1";
        w.id = bankName.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]+", "_");
        w.name = bankName;
        w.nameEn = bankName;
        w.nameJa = bankName;
        w.lang = 2;
        w.compID = compID;

        double[] grid = VoicebankPack.harmonicGrid(VoicebankPack.HARM_POINTS);
        w.harmFreqs = grid.clone();

        // ---- formants path: clusters over the strict-gate envelopes
        int k = Math.max(2, Math.min(o.clusters, envs.size() / 4));
        double[][] X = envs.toArray(new double[0][]);
        VoicebankCluster.Clusters cl = VoicebankCluster.kmeans(X, k, 40);

        double[] meanEnv = new double[VoicebankCluster.ENV_POINTS];
        for (double[] e : envs) for (int i = 0; i < e.length; i++) meanEnv[i] += e[i];
        for (int i = 0; i < meanEnv.length; i++) meanEnv[i] /= envs.size();
        double[] smooth = VoicebankCluster.movingAverage(meanEnv, 5);
        double[] shelfF = VoicebankCluster.geomspace(VoicebankCluster.ENV_F_LO, VoicebankCluster.ENV_F_HI, 7);
        double[] shelfDb = VoicebankCluster.interp(shelfF, VoicebankCluster.ENV_F, smooth);
        double smax = -Double.MAX_VALUE;
        for (double v : shelfDb) if (v > smax) smax = v;
        for (int i = 0; i < shelfDb.length; i++) {
            double v = shelfDb[i] - smax;
            if (v < -24.0) v = -24.0;
            if (v > 6.0) v = 6.0;
            w.shelf.add(new double[]{Math.rint(shelfF[i]), Math.rint(v * 10.0) / 10.0});
        }

        // pick_formants first: a cluster is only usable once it reduces to (F1,F2,F3);
        // match_phonemes then compares those three numbers, never the 96 point envelope.
        int[] memberCount = new int[k];
        for (int lab : cl.labels) memberCount[lab]++;
        ArrayList<double[]> fvs = new ArrayList<double[]>();
        ArrayList<Integer> fvCluster = new ArrayList<Integer>();
        for (int j = 0; j < k; j++) {
            if (memberCount[j] == 0) continue;
            ArrayList<Double> f0s = new ArrayList<Double>();
            for (int i = 0; i < cl.labels.length; i++) if (cl.labels[i] == j) f0s.add(envF0.get(i));
            double[] fv = VoicebankCluster.pickFormants(cl.centroids[j], smooth, med(f0s));
            if (fv == null) continue;
            fvs.add(fv);
            fvCluster.add(Integer.valueOf(j));
        }
        Map<String, int[]> matched = VoicebankCluster.matchPhonemes(fvs.toArray(new double[0][]),
                new ArrayList<String>(ReferenceFormants.symbols()));
        LinkedHashMap<String, Integer> symCluster = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, int[]> e : matched.entrySet()) {
            w.formants.put(e.getKey(), e.getValue());
            int vi = indexOfVector(fvs, e.getValue());
            if (vi >= 0) symCluster.put(e.getKey(), fvCluster.get(vi));
            if (vi >= 0) w.formantCounts.put(e.getKey() + "_n", Integer.valueOf(memberCount[fvCluster.get(vi).intValue()]));
        }

        // ---- shelf drives the three voice parameters (see the port spec 6.3)
        double bal = 0.0, tail = 0.0;
        tail = shelfDb[shelfDb.length - 1] - shelfDb[shelfDb.length - 2];
        w.voice.put("formantScale", Double.valueOf(1.0));
        w.voice.put("f1Mul", Double.valueOf(1.0));
        w.voice.put("f2Mul", Double.valueOf(1.0));
        w.voice.put("f3Mul", Double.valueOf(1.0));
        w.voice.put("tiltBias", Double.valueOf(0.02 * bal));
        w.voice.put("breathBias", Double.valueOf(0.10 + 0.03 * (tail + 3.0)));

        // ---- harmonics path: one band list per matched symbol
        double[][] C = curves.toArray(new double[0][]);
        double[] cf = new double[curF0.size()];
        for (int i = 0; i < cf.length; i++) cf[i] = curF0.get(i).doubleValue();
        // The curves are 64 dimensional and the envelopes 96, so assigning one to the other is
        // meaningless (port spec 6.4). Both sets come from the same FRM2 records, so the curve
        // clusters are paired with the formant clusters by how many offsets they share.
        VoicebankCluster.Clusters ccl = VoicebankCluster.kmeans(C, k, 40);
        int[] pair = new int[k];
        for (int j = 0; j < k; j++) {
            HashMap<Long, Integer> envIdx = new HashMap<Long, Integer>();
            for (int i = 0; i < envOff.size(); i++) if (cl.labels[i] == j) envIdx.put(envOff.get(i), Integer.valueOf(i));
            int[] hits = new int[k];
            for (int i = 0; i < curOff.size(); i++) {
                if (!envIdx.containsKey(curOff.get(i))) continue;
                hits[ccl.labels[i]]++;
            }
            int bestM = 0, bestH = -1;
            for (int m = 0; m < k; m++) if (hits[m] > bestH) { bestH = hits[m]; bestM = m; }
            pair[j] = bestM;
        }
        int[] curveLab = ccl.labels;
        int totalBands = 0;
        for (Map.Entry<String, int[]> e : matched.entrySet()) {
            Integer ciBox = symCluster.get(e.getKey());
            if (ciBox == null) continue;
            int ci = pair[ciBox.intValue()];
            ArrayList<Integer> idx = new ArrayList<Integer>();
            for (int i = 0; i < curveLab.length; i++) if (curveLab[i] == ci) idx.add(Integer.valueOf(i));
            if (idx.size() < VoicebankPack.HARM_BAND_MIN_FRAMES) continue;
            double[][] sub = new double[idx.size()][];
            double[] subF0 = new double[idx.size()];
            for (int i = 0; i < idx.size(); i++) { sub[i] = C[idx.get(i).intValue()]; subF0[i] = cf[idx.get(i).intValue()]; }
            ArrayList<VoicebankPack.Band> bands = VoicebankPack.pitchBands(sub, subF0);
            if (bands.isEmpty()) continue;
            w.phonemes.put(e.getKey(), bands);
            totalBands += bands.size();
        }

        // ---- noise section
        VoicebankNoise.Scan sn = VoicebankNoise.scanSndOffsets(ddb, size, o.noiseBudget);
        ArrayList<double[]> nc = new ArrayList<double[]>();
        ArrayList<double[]> nf = new ArrayList<double[]>();   // centroid, bandwidth, flatness, level
        RandomAccessFile nf2 = new RandomAccessFile(ddb, "r");
        try {
            int step = Math.max(1, sn.offsets.length / Math.max(1, o.noiseSamples));
            for (int i = 0; i < sn.offsets.length; i += step) {
                short[] pcm = VoicebankNoise.readPcm(nf2, sn.offsets[i]);
                VoicebankNoise.Noise n = VoicebankNoise.sndAnalysis(pcm, logGridOf(grid), VoicebankNoise.NOISE_SMOOTH);
                if (n == null) continue;
                nc.add(n.curve);
                nf.add(new double[]{n.centroid, n.bandwidth, n.flatness, n.level});
            }
        } finally {
            nf2.close();
        }
        String noiseTxt = "no usable SND records";
        // noise-like pre-filter (build_pack 1134-1137): only records that already look like
        // fricatives take part in the clustering - centroid >= 2500 Hz and flatness >= 0.02.
        // Without it the tonal majority dominates every class median and nothing is ever named.
        ArrayList<double[]> ncF = new ArrayList<double[]>();
        ArrayList<double[]> nfF = new ArrayList<double[]>();
        for (int i = 0; i < nc.size(); i++) {
            double[] ft = nf.get(i);
            if (ft[0] >= 2500.0 && ft[2] >= VoicebankNoise.NOISE_FLAT_MIN) { ncF.add(nc.get(i)); nfF.add(ft); }
        }
        if (ncF.size() >= 8) {
            int kk = Math.min(VoicebankNoise.NOISE_CLASSES, Math.max(2, ncF.size() / 5));
            VoicebankCluster.Clusters ncl = VoicebankCluster.kmeans(ncF.toArray(new double[0][]), kk, 40);
            ArrayList<VoicebankNoise.ClassStat> stats = new ArrayList<VoicebankNoise.ClassStat>();
            for (int j = 0; j < kk; j++) {
                ArrayList<Double> cen = new ArrayList<Double>(), bw = new ArrayList<Double>(),
                        fl = new ArrayList<Double>(), lv = new ArrayList<Double>();
                for (int i = 0; i < ncl.labels.length; i++) {
                    if (ncl.labels[i] != j) continue;
                    cen.add(Double.valueOf(nfF.get(i)[0])); bw.add(Double.valueOf(nfF.get(i)[1]));
                    fl.add(Double.valueOf(nfF.get(i)[2])); lv.add(Double.valueOf(nfF.get(i)[3]));
                }
                if (cen.isEmpty()) { stats.add(new VoicebankNoise.ClassStat(0, 0, 0, 0, -120)); continue; }
                stats.add(new VoicebankNoise.ClassStat(cen.size(), med(cen), med(bw), med(fl), med(lv)));
            }
            double[] lvlMed = new double[1];
            StringBuilder cls = new StringBuilder();
            for (VoicebankNoise.ClassStat cs : stats) cls.append(String.format(java.util.Locale.US, "n=%d c=%.0f bw=%.0f fl=%.4f lv=%.1f | ", cs.n, cs.centroid, cs.bandwidth, cs.flatness, cs.level));
            w.stats.put("noiseClasses", cls.toString());
            Map<String, Integer> named = VoicebankNoise.classifyNoiseClasses(stats, lvlMed);
            for (Map.Entry<String, Integer> e : named.entrySet()) {
                double[] curve = ncl.centroids[e.getValue().intValue()];
                double mx = -Double.MAX_VALUE;
                for (double v : curve) if (v > mx) mx = v;
                double[] db = new double[curve.length];
                for (int i = 0; i < curve.length; i++) {
                    double v = Math.rint((curve[i] - mx) / VoicebankPack.HARM_DB_STEP) * VoicebankPack.HARM_DB_STEP;
                    if (v < VoicebankPack.HARM_DB_FLOOR) v = VoicebankPack.HARM_DB_FLOOR;
                    if (v > 0.0) v = 0.0;
                    db[i] = v == 0.0 ? 0.0 : v;
                }
                double[] fr = new double[grid.length];
                for (int i = 0; i < grid.length; i++) fr[i] = VoicebankWriter.round4(grid[i]);
                w.noise.put(e.getKey(), new double[][]{fr, db});
            }
            noiseTxt = named.isEmpty() ? "no class matched" : ("named " + named.size() + " classes");
        }

        w.stats.put("frames", Integer.valueOf(curves.size()));
        w.stats.put("f0Median", Double.valueOf(med(envF0)));
        w.stats.put("f0Min", Double.valueOf(minOf(envF0)));
        w.stats.put("f0Max", Double.valueOf(maxOf(envF0)));
        w.stats.put("clusters", Integer.valueOf(k));
        w.stats.put("matchedPhonemes", Integer.valueOf(matched.size()));
        w.stats.put("envelopes", Integer.valueOf(envs.size()));
        w.stats.put("harmonicCurves", Integer.valueOf(curves.size()));
        w.stats.put("totalBands", Integer.valueOf(totalBands));
        w.stats.put("noiseSndFound", Integer.valueOf(sn.offsets.length));
        w.stats.put("noiseSampled", Integer.valueOf(nc.size()));
        w.stats.put("noiseNamed", Integer.valueOf(w.noise.size()));
        w.source = "VOCALOID4 FRM2 frames of " + bankName + " (" + compID + "), measured on a "
                + grid.length + "-point log grid; " + curves.size() + " curves for " + w.phonemes.size()
                + " CHN symbols in " + totalBands + " pitch bands; noise: " + noiseTxt;
        w.notes = "[measured] " + envs.size() + " strict-gate frames for formants, " + curves.size()
                + " loose-gate frames for harmonics; " + matched.size() + " CHN symbols matched; noise " + noiseTxt
                + ". No audio data is embedded: derived parametric measurements only.";
        return w;
    }

    // ------------------------------------------------------------------ helpers

    static double[] logGridOf(double[] grid) {
        double[] o = new double[grid.length];
        for (int i = 0; i < grid.length; i++) o[i] = Math.log(grid[i]);
        return o;
    }

    /** the index of the vector whose rounded form equals the recorded (F1,F2,F3), or -1 */
    static int indexOfVector(List<double[]> v, int[] rounded) {
        for (int i = 0; i < v.size(); i++) {
            double[] x = v.get(i);
            if ((int) Math.round(x[0]) == rounded[0] && (int) Math.round(x[1]) == rounded[1]
                    && (int) Math.round(x[2]) == rounded[2]) return i;
        }
        return -1;
    }

    static int closest(double[][] centroids, double[] v) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < centroids.length; i++) {
            double d = 0;
            for (int j = 0; j < v.length; j++) { double e = centroids[i][j] - v[j]; d += e * e; }
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    static double[] toDouble(int[] a) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i];
        return o;
    }

    static double med(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i).doubleValue();
        return VoicebankPack.median(a);
    }

    static double minOf(List<Double> v) { double m = Double.MAX_VALUE; for (Double d : v) if (d.doubleValue() < m) m = d.doubleValue(); return m; }
    static double maxOf(List<Double> v) { double m = -Double.MAX_VALUE; for (Double d : v) if (d.doubleValue() > m) m = d.doubleValue(); return m; }
}
