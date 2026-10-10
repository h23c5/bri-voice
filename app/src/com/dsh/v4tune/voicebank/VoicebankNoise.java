package com.dsh.v4tune.voicebank;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Stage C-3: the noise section. Ported from tools/voicebank_pack.py scan_snd_offsets(709) and
 * welch_psd(741); constants are the measured values from that file.
 *
 * <p>The .ddb is a gap free [4B tag][u32 total_len][payload] stream, so a byte search is exact
 * once every hit is validated against the 18 byte SND header. Chunks overlap by 18 bytes so a
 * header cannot be lost at a boundary.
 *
 * <p>Golden numbers for the scan are in the Python docstring: 7749 records on Luotianyi_CHN_Meng
 * and 5195 on MIKU_V4_Chinese - the self test checks exactly those.
 */
public final class VoicebankNoise {

    public static final byte[] SND_MAGIC = new byte[]{'S', 'N', 'D', ' '};
    public static final int SND_RATE = 44100;
    public static final int SND_HEADER = 18;
    public static final int NOISE_SEG = 2048;
    public static final int NOISE_HOP = 1024;
    public static final int NOISE_CLASSES = 8;
    public static final int NOISE_MIN_CLASS_MEMBERS = 5;
    public static final double NOISE_FLAT_MIN = 0.02;
    public static final int NOISE_SMOOTH = 3;
    public static final double NOISE_BROADBAND = 0.5;
    public static final double NOISE_ASP_MARGIN_DB = 6.0;

    private VoicebankNoise() { }

    /** Result of the scan: the record offsets, and how many bytes were actually read. */
    public static final class Scan {
        public final long[] offsets;
        public final long bytesRead;
        Scan(long[] o, long b) { offsets = o; bytesRead = b; }
    }

    /** scan_snd_offsets: budgetBytes 0 means the whole file. */
    public static Scan scanSndOffsets(File ddb, long ddbSize, long budgetBytes) throws Exception {
        return scanSndOffsets(ddb, ddbSize, budgetBytes, 8 << 20);
    }

    public static Scan scanSndOffsets(File ddb, long ddbSize, long budgetBytes, int chunkBytes) throws Exception {
        long limit = budgetBytes <= 0 ? ddbSize : Math.min(ddbSize, budgetBytes);
        ArrayList<Long> hits = new ArrayList<Long>();
        long read = 0;
        RandomAccessFile f = new RandomAccessFile(ddb, "r");
        try {
            long pos = 0;
            byte[] buf = new byte[chunkBytes + SND_HEADER];
            while (pos < limit) {
                f.seek(pos);
                int want = (int) Math.min(chunkBytes, limit - pos);
                f.readFully(buf, 0, want);
                if (want <= 0) break;
                read += want;
                int i = VoicebankFrames.indexOf(buf, SND_MAGIC, 0);
                while (i >= 0) {
                    if (i + SND_HEADER <= want) {
                        // struct.unpack_from("<IIHHH", buf, i + 4): tl(u32) sr(u32) ch n resv
                        long tl = VoicebankFrames.u32(buf, i + 4);
                        long sr = VoicebankFrames.u32(buf, i + 8);
                        int ch = u16(buf, i + 12);
                        int n = u16(buf, i + 14);
                        int resv = u16(buf, i + 16);
                        if (tl == 18L + 2L * n && sr == SND_RATE && ch == 1 && resv == 0 && n >= 256) {
                            hits.add(pos + i);
                        }
                    }
                    i = VoicebankFrames.indexOf(buf, SND_MAGIC, i + 4);
                }
                pos += Math.max(1, want - SND_HEADER);
            }
        } finally {
            f.close();
        }
        long[] out = new long[hits.size()];
        for (int i = 0; i < out.length; i++) out[i] = hits.get(i).longValue();
        Arrays.sort(out);
        int k = 0;
        for (int i = 0; i < out.length; i++) if (i == 0 || out[i] != out[i - 1]) out[k++] = out[i];
        return new Scan(Arrays.copyOf(out, k), read);
    }

    // (helpers below)
    static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    /** One SND record: the 16 bit mono samples that follow the 18 byte header. */
    public static short[] readPcm(RandomAccessFile f, long offset) throws Exception {
        byte[] h = new byte[SND_HEADER];
        f.seek(offset);
        f.readFully(h);
        int n = u16(h, 14);   // sample count sits at header offset 14, not 12
        byte[] pcm = new byte[2 * n];
        f.readFully(pcm);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = (short) u16(pcm, 2 * i);
        return out;
    }

    // ------------------------------------------------------------------ FFT and Welch

    /** iterative radix-2 FFT, numpy's unnormalised convention (no scaling on the forward pass) */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1.0, ci = 0.0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
    }

    /** welch_psd: Hann window (denominator n-1, like np.hanning), 50 % overlap, power averaged */
    public static double[][] welchPsd(double[] x) {
        int n = x.length, seg = NOISE_SEG;
        if (n < seg) seg = 1 << Math.max(5, (int) (Math.log(Math.max(1, n)) / Math.log(2)));
        double[] y = x;
        if (n < seg) { y = Arrays.copyOf(x, seg); n = seg; }
        int hop = seg >= NOISE_SEG ? NOISE_HOP : Math.max(1, seg / 2);
        int bins = seg / 2 + 1;
        double[] acc = new double[bins];
        int frames = 0;
        for (int start = 0; start + seg <= n; start += hop) {
            double[] re = new double[seg], im = new double[seg];
            for (int i = 0; i < seg; i++) re[i] = y[start + i] * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (seg - 1)));
            fft(re, im);
            for (int k = 0; k < bins; k++) acc[k] += re[k] * re[k] + im[k] * im[k];
            frames++;
        }
        if (frames == 0) {   // shorter than one segment: pad and use a single frame
            double[] re = new double[seg], im = new double[seg];
            for (int i = 0; i < seg; i++) re[i] = (i < y.length ? y[i] : 0.0) * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (seg - 1)));
            fft(re, im);
            for (int k = 0; k < bins; k++) acc[k] += re[k] * re[k] + im[k] * im[k];
            frames = 1;
        }
        double[] freqs = new double[bins];
        for (int k = 0; k < bins; k++) { acc[k] /= frames; freqs[k] = (double) k * SND_RATE / seg; }
        return new double[][]{freqs, acc};
    }

    /** One measured noise record: the curve on the log grid plus the naming features. */
    public static final class Noise {
        public double[] curve;
        public double centroid, bandwidth, flatness, level;
        public int samples;
    }

    /** snd_analysis: curve on the shared log grid, or null when the record is unusable. */
    public static Noise sndAnalysis(short[] pcm, double[] logGrid, int smooth) {
        double[] x = new double[pcm.length];
        double mean = 0;
        for (int i = 0; i < pcm.length; i++) { x[i] = pcm[i] / 32768.0; mean += x[i]; }
        mean /= Math.max(1, pcm.length);
        double e = 0;
        for (int i = 0; i < x.length; i++) { x[i] -= mean; e += x[i] * x[i]; }
        double rms = x.length > 0 ? Math.sqrt(e / x.length) : 0.0;
        if (!VoicebankFrames.isFinite(rms) || rms < 1e-6) return null;
        double[][] psd = welchPsd(x);
        double[] fr = psd[0], P = psd[1];
        int lo = 0, hi = -1;
        for (int i = 0; i < fr.length; i++) { if (fr[i] >= VoicebankPack.HARM_F_LO) { lo = i; break; } }
        for (int i = fr.length - 1; i >= 0; i--) { if (fr[i] <= VoicebankPack.HARM_F_HI) { hi = i; break; } }
        int cnt = hi - lo + 1;
        if (cnt < 4) return null;
        double[] f2 = new double[cnt], P2 = new double[cnt];
        double tot = 0;
        for (int i = 0; i < cnt; i++) { f2[i] = fr[lo + i]; P2[i] = Math.max(P[lo + i], 1e-30); tot += P2[i]; }
        if (!VoicebankFrames.isFinite(tot) || tot <= 0.0) return null;
        double fc = 0, bw = 0, logSum = 0;
        for (int i = 0; i < cnt; i++) { fc += f2[i] * P2[i]; logSum += Math.log(P2[i]); }
        fc /= tot;
        for (int i = 0; i < cnt; i++) { double d = f2[i] - fc; bw += d * d * P2[i]; }
        bw = Math.sqrt(bw / tot);
        double flat = Math.exp(logSum / cnt) / (tot / cnt);
        double[] logF = new double[cnt], db = new double[cnt];
        for (int i = 0; i < cnt; i++) { logF[i] = Math.log(f2[i]); db[i] = 10.0 * Math.log10(P2[i]); }
        double[] curve = VoicebankCluster.interp(logGrid, logF, db);
        if (smooth > 1) curve = VoicebankCluster.movingAverage(curve, smooth);
        double mx = -Double.MAX_VALUE;
        for (double v : curve) if (v > mx) mx = v;
        for (int i = 0; i < curve.length; i++) curve[i] -= mx;
        Noise n = new Noise();
        n.curve = curve;
        n.centroid = fc; n.bandwidth = bw; n.flatness = flat;
        n.level = 20.0 * Math.log10(rms);
        n.samples = x.length;
        return n;
    }

    /** NOISE_TARGETS: symbol, centroid window, and whether the class must be broadband. */
    /** One clustered SND class: what the naming decision looks at. */
    public static final class ClassStat {
        public int n;
        public double centroid, bandwidth, flatness, level;
        public ClassStat(int n, double centroid, double bandwidth, double flatness, double level) {
            this.n = n; this.centroid = centroid; this.bandwidth = bandwidth; this.flatness = flatness; this.level = level;
        }
    }

    /** classify_noise_classes: greedy one-to-one naming, closest to the window centre first. */
    public static java.util.Map<String, Integer> classifyNoiseClasses(java.util.List<ClassStat> classes, double[] lvlMedOut) {
        java.util.ArrayList<Double> levels = new java.util.ArrayList<Double>();
        for (ClassStat c : classes) if (VoicebankFrames.isFinite(c.level)) levels.add(Double.valueOf(c.level));
        double lvlMed = 0.0;
        if (!levels.isEmpty()) {
            double[] a = new double[levels.size()];
            for (int i = 0; i < a.length; i++) a[i] = levels.get(i).doubleValue();
            lvlMed = VoicebankPack.median(a);
        }
        if (lvlMedOut != null && lvlMedOut.length > 0) lvlMedOut[0] = lvlMed;
        java.util.ArrayList<Object[]> cand = new java.util.ArrayList<Object[]>();
        for (int j = 0; j < classes.size(); j++) {
            ClassStat c = classes.get(j);
            if (c.n < NOISE_MIN_CLASS_MEMBERS || c.flatness < NOISE_FLAT_MIN) continue;
            double ratio = c.bandwidth / Math.max(1.0, c.centroid);
            for (Object[] t : NOISE_TARGETS) {
                String sym = (String) t[0];
                double lo = ((Double) t[1]).doubleValue(), hi = ((Double) t[2]).doubleValue();
                boolean broad = ((Boolean) t[3]).booleanValue();
                if (!(lo <= c.centroid && c.centroid <= hi)) continue;
                if (broad && ratio < NOISE_BROADBAND) continue;
                if (sym.equals("Asp") && c.level > lvlMed - NOISE_ASP_MARGIN_DB) continue;
                double score = Math.abs(Math.log(c.centroid / Math.sqrt(lo * hi)));
                cand.add(new Object[]{Double.valueOf(score), Integer.valueOf(j), sym});
            }
        }
        java.util.Collections.sort(cand, new java.util.Comparator<Object[]>() {
            public int compare(Object[] x, Object[] y) {
                int r = Double.compare(((Double) x[0]).doubleValue(), ((Double) y[0]).doubleValue());
                if (r != 0) return r;
                r = Integer.compare(((Integer) x[1]).intValue(), ((Integer) y[1]).intValue());
                if (r != 0) return r;
                return ((String) x[2]).compareTo((String) y[2]);
            }
        });
        java.util.LinkedHashMap<String, Integer> named = new java.util.LinkedHashMap<String, Integer>();
        java.util.HashSet<Integer> used = new java.util.HashSet<Integer>();
        for (Object[] c : cand) {
            Integer j = (Integer) c[1];
            String sym = (String) c[2];
            if (used.contains(j) || named.containsKey(sym)) continue;
            used.add(j);
            named.put(sym, j);
        }
        return named;
    }

    public static final Object[][] NOISE_TARGETS = {
        {"s", 4500.0, 9000.0, Boolean.FALSE},
        {"s\\", 2800.0, 5500.0, Boolean.FALSE},
        {"x", 2800.0, 5500.0, Boolean.FALSE},
        {"s`", 2200.0, 3800.0, Boolean.FALSE},
        {"f", 1500.0, 6500.0, Boolean.TRUE},
        {"h", 900.0, 3000.0, Boolean.FALSE},
        {"Asp", 500.0, 6000.0, Boolean.TRUE},
    };
}
