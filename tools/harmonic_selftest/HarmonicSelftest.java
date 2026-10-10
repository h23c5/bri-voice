import com.dsh.v4tune.synth.HarmonicVoice;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Desktop self test for the harmonic (additive) synthesis core and the pack data that feeds it.
 *
 * <p>Groups:</p>
 * <ol>
 *   <li>a synthetic envelope (three formants, 1/12 octave grid) is rendered for 1 s at 220 Hz and
 *       every harmonic is measured with a Hann windowed DFT and compared with the request;</li>
 *   <li>pitch: autocorrelation and the strongest spectral peak;</li>
 *   <li>level stability across three very different envelopes;</li>
 *   <li>robustness: null/NaN/Inf/negative f0, DC, click free mid stream shape change, rebuild
 *       thresholds;</li>
 *   <li>packs: a synthetic {@code harmonics} section (unsorted grid, duplicates, out of range
 *       points, malformed bands) plus every real pack in {@code build/voicepacks};</li>
 *   <li>realtime factor of 30 s of audio.</li>
 * </ol>
 *
 * <p>Usage: {@code java HarmonicSelftest [pack.v4voice ...]} - exits non-zero when a check fails.</p>
 */
public class HarmonicSelftest {

    static final int SR = 44100;
    static final String PACK_DIR =
            "C:\\Users\\H23_C5\\Documents\\deepseek-harness\\default-workspace\\vocaloid-android\\build\\voicepacks";

    static int checks = 0, fails = 0, groupFails = 0;
    static String groupName = null;

    static double[] grid;          // shared 1/12 octave test envelope grid
    static double[] envFormant;    // the reference envelope on that grid
    static double[] sig;           // 1 s of group 1 audio at 220 Hz

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        System.out.println("=== HarmonicSelftest === " + SR + " Hz, table "
                + HarmonicVoice.TABLE + " samples");
        double[] g = logGrid(40.0, 16000.0, 12);
        grid = g;
        envFormant = sampleEnv(g, ENV_FORMANT);

        group("1. measured envelope -> harmonic amplitudes (f0 = 220 Hz)");
        group1();
        group("2. pitch");
        group2();
        group("3. level stability across very different envelopes");
        group3();
        group("4. robustness: hostile input, DC, click free shape change");
        group4();
        group("5. harmonic data in .v4voice packs");
        group5(args);
        group("6. realtime factor");
        group6();
        endGroup();

        System.out.println();
        System.out.println("checks " + checks + ", failures " + fails);
        System.out.println(fails == 0 ? "ALL GROUPS PASSED" : "SELFTEST FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- group 1

    static void group1() {
        HarmonicVoice hv = voice();
        hv.setShape(grid, envFormant, 220.0);
        check("setShape builds a table", hv.isActive() && hv.harmonicCount() > 0,
                hv.harmonicCount() + " harmonics");

        sig = render(hv, SR);
        int kmax = 20;
        double[] dev = new double[kmax];
        double offset = 0;
        for (int k = 1; k <= kmax; k++) {
            double f = 220.0 * k;
            double mag = dftMag(sig, 0, sig.length, f);
            dev[k - 1] = 20.0 * Math.log10(mag > 1e-15 ? mag : 1e-15) - envFormantDb(f);
            offset += dev[k - 1];
        }
        offset /= kmax;
        double worst = 0;
        int worstK = 1;
        for (int k = 0; k < kmax; k++) {
            double e = Math.abs(dev[k] - offset);      // the table is RMS normalised: shape only
            if (e > worst) { worst = e; worstK = k + 1; }
        }
        check("k = 1..20 match the requested envelope within 1.5 dB", worst <= 1.5,
                "worst " + fmt(worst) + " dB at k=" + worstK + ", normalisation offset "
                        + fmt(offset) + " dB");
        System.out.println("    k=1 " + fmt(dev[0] - offset) + " dB, k=5 " + fmt(dev[4] - offset)
                + " dB, k=10 " + fmt(dev[9] - offset) + " dB, k=20 " + fmt(dev[19] - offset)
                + " dB relative to the request");
    }

    // ---------------------------------------------------------------- group 2

    static void group2() {
        int len = Math.min(sig.length, 16384);
        int from = (sig.length - len) / 2;
        double f = autocorrF0(sig, from, len, SR);
        check("autocorrelation confirms 220 Hz within 0.5 %", Math.abs(f - 220.0) / 220.0 <= 0.005,
                "f0 = " + fmt(f) + " Hz (" + fmt((f - 220.0) / 220.0 * 100.0) + " %)");
        double p = strongestPeak(sig, from, len, 120.0, 2400.0, 0.5);
        double near = Math.rint(p / 220.0) * 220.0;
        check("strongest spectral peak lies on the 220 Hz harmonic grid",
                Math.abs(p - near) / 220.0 <= 0.005,
                "peak " + fmt(p) + " Hz, nearest harmonic " + fmt(near) + " Hz");
    }

    // ---------------------------------------------------------------- group 3

    static void group3() {
        String[] names = {"dark -18 dB/oct", "formant -12 dB/oct", "bright -6 dB/oct + presence"};
        int[] kinds = {ENV_DARK, ENV_FORMANT, ENV_BRIGHT};
        double[] rms = new double[kinds.length];
        double lo = Double.MAX_VALUE, hi = 0;
        for (int i = 0; i < kinds.length; i++) {
            HarmonicVoice hv = voice();
            hv.setShape(grid, sampleEnv(grid, kinds[i]), 220.0);
            double[] x = render(hv, SR);
            rms[i] = rms(x, x.length / 2, x.length);
            if (rms[i] < lo) lo = rms[i];
            if (rms[i] > hi) hi = rms[i];
            System.out.println("    " + pad(names[i], 30) + " rms " + fmt(rms[i])
                    + "   crest (peak/rms) " + fmt(crest(x, x.length / 2, x.length))
                    + "   max step " + fmt(maxStep(x, 0, x.length)));
        }
        double spread = 20.0 * Math.log10(hi / lo);
        check("RMS of three very different envelopes stays within 3 dB", spread <= 3.0,
                "spread " + fmt(spread) + " dB");
    }

    // ---------------------------------------------------------------- group 4

    static void group4() {
        HarmonicVoice h = voice();
        check("a fresh voice is silent", !h.isActive() && h.sample() == 0.0f, null);
        h.setShape(null, null, 220.0);
        check("null shape is rejected", !h.isActive(), null);
        h.setShape(grid, envFormant, Double.NaN);
        check("NaN f0 is rejected", !h.isActive(), null);
        h.setShape(grid, envFormant, Double.POSITIVE_INFINITY);
        check("infinite f0 is rejected", !h.isActive(), null);
        h.setShape(grid, envFormant, -50.0);
        check("negative f0 is rejected", !h.isActive(), null);
        h.setShape(grid, envFormant, 0.0);
        check("f0 = 0 is rejected", !h.isActive(), null);
        h.setShape(new double[0], new double[0], 220.0);
        check("empty envelope is rejected", !h.isActive(), null);

        double[] badF = {40, Double.NaN, 300, 900, 2000, 5000, 16000};
        double[] badD = {0, -3, Double.NaN, -20, Double.NEGATIVE_INFINITY, -40, -60};
        h.setShape(badF, badD, 220.0);
        double[] bad = render(h, SR / 4);
        int nan = 0;
        for (int i = 0; i < bad.length; i++) if (Double.isNaN(bad[i]) || Double.isInfinite(bad[i])) nan++;
        check("a hostile envelope never yields NaN/Inf", nan == 0 && h.isActive(),
                nan + " bad samples, " + h.harmonicCount() + " harmonics");

        double mean = 0;
        for (int i = 0; i < sig.length; i++) mean += sig[i];
        mean /= sig.length;
        check("output has no DC offset", Math.abs(mean) < 1e-3, "mean " + fmt(mean));

        // ---- click free shape change -------------------------------------
        HarmonicVoice c = voice();
        c.setShape(grid, sampleEnv(grid, ENV_DARK), 220.0);
        double[] a = render(c, SR / 2);
        c.setShape(grid, sampleEnv(grid, ENV_BRIGHT), 247.0);   // other spectrum and other pitch
        double[] b = render(c, SR / 2);
        double stepA = maxStep(a, 0, a.length);
        double stepB = maxStep(b, 0, b.length);
        double stepAt = Math.abs(b[0] - a[a.length - 1]);
        double stepAll = Math.max(Math.max(stepA, stepB), stepAt);
        check("no sample step > 0.5 across a mid stream shape change", stepAll <= 0.5,
                "at swap " + fmt(stepAt) + ", within A " + fmt(stepA) + ", within B " + fmt(stepB));
        check("the swap is no worse than the waveform itself",
                stepAt <= Math.max(stepA, stepB) + 0.05, "step at swap " + fmt(stepAt));

        // ---- rebuild thresholds ------------------------------------------
        double[] envB = sampleEnv(grid, ENV_BRIGHT);
        HarmonicVoice r = voice();
        r.setShape(grid, envB, 247.0);
        long r0 = r.rebuildCount();
        for (int i = 0; i < 200; i++) r.setShape(grid, envB, 247.0);
        check("an unchanged shape never rebuilds the table", r.rebuildCount() == r0,
                "rebuilds " + (r.rebuildCount() - r0));
        r.setShape(grid, envB, 247.0 * 1.004);
        check("a 0.4 % pitch drift does not rebuild", r.rebuildCount() == r0,
                "rebuilds " + (r.rebuildCount() - r0));
        r.setShape(grid, envB, 247.0 * 1.02);
        check("a 2 % pitch change rebuilds", r.rebuildCount() == r0 + 1,
                "rebuilds " + (r.rebuildCount() - r0));
        double[] envB2 = envB.clone();
        envB2[10] += 0.5;
        r.setShape(grid, envB2, 247.0 * 1.02);
        check("a 0.5 dB envelope change rebuilds", r.rebuildCount() == r0 + 2,
                "rebuilds " + (r.rebuildCount() - r0));

        // ---- breath noise --------------------------------------------------
        HarmonicVoice br = voice();
        br.setShape(grid, envFormant, 220.0);
        br.setBreath(1.0);
        double[] with = render(br, SR / 4);
        br.setBreath(0.0);
        double[] without = render(br, SR / 4);
        double noiseRms = 0;
        int n = Math.min(with.length, without.length);
        for (int i = 0; i < n; i++) {
            double d = with[i] - without[i];
            noiseRms += d * d;
        }
        noiseRms = Math.sqrt(noiseRms / n);
        check("setBreath(1) adds audible but modest noise", noiseRms > 0.01 && noiseRms < 0.15,
                "added rms " + fmt(noiseRms));
    }

    // ---------------------------------------------------------------- group 5

    static void group5(String[] args) throws Exception {
        // ---- synthetic section: unsorted grid, duplicates, junk, bad bands ----
        double[] rawF = {800, 60, 16000, 400, 16000, 100, 200, 3200, 6400, 10, 30000, 12800};
        double[] rawDb = new double[rawF.length];
        for (int i = 0; i < rawDb.length; i++) rawDb[i] = -i;
        double[] wantF = {60, 100, 200, 400, 800, 3200, 6400, 12800, 16000};
        int[] wantSrc = {1, 5, 6, 3, 0, 7, 8, 11, 2};
        String json = "{\"format\":\"v4voice/1\",\"id\":\"harmtest\",\"name\":\"Harmonic parse test\","
                + "\"harmonics\":{\"freqs\":" + arr(rawF) + ",\"source\":\"synthetic\",\"phonemes\":{"
                + "\"a\":[" + band(329.0, rawDb) + "," + band(219.0, rawDb) + ",{\"f0\":219,\"db\":[1,2,3]}],"
                + "\"iE_n\":[{\"db\":" + arr(rawDb) + "}]"
                + "},\"noise\":{\"s\":{\"freqs\":" + arr(rawF) + ",\"db\":" + arr(rawDb) + "}}}}";
        VoicePack.Result sr = loadJson(json);
        check("synthetic harmonics section loads", sr.ok(), sr.ok() ? null : sr.error);
        if (sr.ok()) {
            VoiceModel m = sr.model;
            boolean asc = m.harmonicFreqs.length == wantF.length;
            for (int i = 0; asc && i < wantF.length; i++) asc = m.harmonicFreqs[i] == wantF[i];
            check("grid is sorted, de-duplicated and range checked (8..256, 20..20000 Hz)",
                    asc && m.harmonicFreqs.length >= 8 && m.harmonicFreqs.length <= 256,
                    "grid " + arr(m.harmonicFreqs));
            VoiceModel.HarmonicBand[] bands = m.harmonics.get("a");
            check("only the phonemes with a usable band are kept",
                    m.harmonics.size() == 1 && bands != null && bands.length == 2,
                    m.harmonics.size() + " symbols");
            boolean mapped = bands != null && bands.length == 2;
            if (mapped) {
                mapped = bands[0].f0 == 219.0 && bands[1].f0 == 329.0;
                for (int i = 0; mapped && i < wantSrc.length; i++) {
                    mapped = Math.abs(bands[0].db[i] - (-wantSrc[i])) < 1e-9;
                }
            }
            check("every band is re-indexed onto the sorted grid (db stays aligned)", mapped,
                    bands != null && bands.length == 2 ? "db " + arr(bands[0].db) : "-");
            check("a band with a short db array is skipped", bands != null && bands.length == 2,
                    "band count " + (bands == null ? 0 : bands.length));
            check("noise section keeps its own grid and db", m.noiseFreqs.length == wantF.length
                    && m.noiseDb.size() == 1 && m.noiseDb.get("s") != null
                    && m.noiseDb.get("s").length == wantF.length,
                    "noiseFreqs " + m.noiseFreqs.length + " points, " + m.noiseDb.size() + " symbol(s)");
        }
        VoicePack.Result shortGrid = loadJson("{\"format\":\"v4voice/1\",\"id\":\"x\",\"name\":\"y\","
                + "\"harmonics\":{\"freqs\":[60,100,200,400,800],\"phonemes\":{\"a\":[{\"f0\":220,"
                + "\"db\":[0,-1,-2,-3,-4]}]}}}");
        check("a grid with fewer than 8 points drops the section",
                shortGrid.ok() && shortGrid.model.harmonicFreqs.length == 0
                        && shortGrid.model.harmonics.isEmpty(),
                shortGrid.ok() ? "grid " + shortGrid.model.harmonicFreqs.length : shortGrid.error);
        VoicePack.Result noGrid = loadJson("{\"format\":\"v4voice/1\",\"id\":\"x\",\"name\":\"y\","
                + "\"harmonics\":{\"phonemes\":{\"a\":[{\"f0\":220,\"db\":[0,-1]}]}}}");
        check("a harmonics section without a grid is dropped",
                noGrid.ok() && noGrid.model.harmonics.isEmpty(), null);

        // ---- real packs -----------------------------------------------------
        ArrayList<File> packs = new ArrayList<File>();
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null && args[i].trim().length() > 0) packs.add(new File(args[i].trim()));
        }
        if (packs.isEmpty()) {
            File dir = new File(PACK_DIR);
            if (!dir.isDirectory()) dir = new File("build/voicepacks");
            File[] found = dir.isDirectory() ? dir.listFiles() : null;
            if (found != null) {
                for (int i = 0; i < found.length; i++) {
                    if (found[i].isFile() && found[i].getName().endsWith(VoicePack.EXT)) packs.add(found[i]);
                }
            }
            if (packs.isEmpty()) info("no packs in " + dir.getAbsolutePath() + " - nothing to load");
        }
        int withData = 0;
        for (int i = 0; i < packs.size(); i++) {
            File f = packs.get(i);
            VoicePack.Result r = VoicePack.load(f);
            check(f.getName() + " loads", r.ok(), r.ok() ? null : r.error);
            if (!r.ok()) continue;
            VoiceModel m = r.model;
            if (m.harmonicFreqs.length == 0 || m.harmonics.isEmpty()) {
                info(f.getName() + ": no harmonic data yet");
                continue;
            }
            withData++;
            int bands = 0, badLen = 0;
            for (Map.Entry<String, VoiceModel.HarmonicBand[]> e : m.harmonics.entrySet()) {
                VoiceModel.HarmonicBand[] bs = e.getValue();
                bands += bs.length;
                for (int b = 0; b < bs.length; b++) {
                    if (bs[b].db == null || bs[b].db.length != m.harmonicFreqs.length) badLen++;
                }
            }
            boolean asc = m.harmonicFreqs.length >= 8 && m.harmonicFreqs.length <= 256;
            for (int k = 1; asc && k < m.harmonicFreqs.length; k++) {
                asc = m.harmonicFreqs[k] > m.harmonicFreqs[k - 1];
            }
            check(f.getName() + ": grid valid", asc, m.harmonicFreqs.length + " points, "
                    + fmt(m.harmonicFreqs[0]) + ".." + fmt(m.harmonicFreqs[m.harmonicFreqs.length - 1]) + " Hz");
            check(f.getName() + ": every band matches the grid length", badLen == 0,
                    bands + " bands, " + badLen + " bad");
            info(m.name + ": phonemes=" + m.harmonics.size() + ", bands=" + bands
                    + ", noise=" + m.noiseDb.size());
            List<String> syms = pickSymbols(m, 3);
            for (int s = 0; s < syms.size(); s++) {
                String sym = syms.get(s);
                VoiceModel.HarmonicBand b = m.bandFor(sym, 220.0);
                if (b == null) continue;
                HarmonicVoice hv = voice();
                hv.setShape(m.harmonicFreqs, b.db, 220.0);
                double[] x = render(hv, SR / 2);
                double c = centroid(x, 0, x.length, 40.0, 12000.0, 25.0);
                double rr = rms(x, x.length / 4, x.length);
                check("  " + sym + " renders", finite(x) && rr > 0.01 && hv.isActive(),
                        "rms " + fmt(rr) + ", " + hv.harmonicCount() + " harmonics");
                System.out.println("    " + pad(sym, 10) + " band f0 " + pad(fmt(b.f0), 8)
                        + " centroid " + pad(fmt(c), 9) + " Hz   rms " + fmt(rr)
                        + "   crest " + fmt(crest(x, x.length / 4, x.length))
                        + "   max step " + fmt(maxStep(x, 0, x.length)));
            }
        }
        if (withData == 0) info("no pack carries harmonic data yet - group passes");
    }

    // ---------------------------------------------------------------- group 6

    static void group6() {
        final double seconds = 30.0;
        final int n = (int) (seconds * SR);
        final int ctl = (int) (0.001 * SR);
        HarmonicVoice hv = voice();
        for (int i = 0; i < SR / 5; i++) {                       // warm up the JIT
            if (i % ctl == 0) hv.setShape(grid, envFormant, 220.0);
            hv.sample();
        }
        double acc = 0;
        long reb0 = hv.rebuildCount();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            if (i % ctl == 0) {
                double f = 220.0 * Math.pow(2.0, 0.03 * Math.sin(2.0 * Math.PI * 5.5 * i / (double) SR));
                hv.setShape(grid, envFormant, f);                // +-50 cents vibrato, 1 ms control rate
            }
            acc += hv.sample();
        }
        long t1 = System.nanoTime();
        double el = (t1 - t0) / 1e9;
        long reb = hv.rebuildCount() - reb0;
        System.out.println("    " + fmt(seconds) + " s of audio (" + n + " samples, 1 ms control rate, "
                + reb + " table rebuilds) in " + fmt(el) + " s");
        System.out.println("    realtime factor " + fmt(el / seconds) + "  ("
                + fmt(seconds / el) + "x realtime, " + fmt(n / el / 1e6) + " Msample/s)");
        System.out.println("    3 minutes of 44.1 kHz audio would take " + fmt(el * 6.0) + " s");
        check("3 minutes of audio renders in under 10 s", el * 6.0 < 10.0,
                fmt(el * 6.0) + " s, checksum " + fmt(acc));
    }

    // ---------------------------------------------------------------- audio helpers

    static HarmonicVoice voice() {
        HarmonicVoice h = new HarmonicVoice();
        h.setSampleRate(SR);
        h.reset();
        return h;
    }

    static double[] render(HarmonicVoice h, int n) {
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = h.sample();
        return x;
    }

    static double rms(double[] x, int from, int to) {
        double s = 0;
        int n = 0;
        for (int i = from; i < to && i < x.length; i++, n++) s += x[i] * x[i];
        return n == 0 ? 0 : Math.sqrt(s / n);
    }

    /** peak / rms of a slice: a low crest factor means the fixed RMS level is really reached */
    static double crest(double[] x, int from, int to) {
        double peak = 0, s = 0;
        int n = 0;
        for (int i = from; i < to && i < x.length; i++, n++) {
            double a = Math.abs(x[i]);
            if (a > peak) peak = a;
            s += x[i] * x[i];
        }
        double r = n == 0 ? 0 : Math.sqrt(s / n);
        return r > 1e-12 ? peak / r : 0;
    }

    static double maxStep(double[] x, int from, int to) {
        double m = 0;
        for (int i = from + 1; i < to && i < x.length; i++) {
            double d = Math.abs(x[i] - x[i - 1]);
            if (d > m) m = d;
        }
        return m;
    }

    static boolean finite(double[] x) {
        for (int i = 0; i < x.length; i++) if (Double.isNaN(x[i]) || Double.isInfinite(x[i])) return false;
        return true;
    }

    /** cached Hann window and its coherent gain for the analysis length currently in use */
    static int winLen = -1;
    static double[] win;
    static double winSum = 1.0;

    static double[] hann(int n) {
        if (winLen != n) {
            double[] w = new double[n];
            double s = 0;
            for (int i = 0; i < n; i++) {
                w[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
                s += w[i];
            }
            win = w;
            winSum = s;
            winLen = n;
        }
        return win;
    }

    /**
     * Hann windowed DFT at one arbitrary frequency: returns the amplitude of a sinusoid at f.
     * Every caller analyses at least 0.36 s, so the leakage of a neighbouring harmonic is far
     * below the -60 dB floor we measure against.
     */
    static double dftMag(double[] x, int from, int len, double f) {
        double[] w = hann(len);
        double om = 2.0 * Math.PI * f / SR;
        double cw = Math.cos(om), sw = Math.sin(om);
        double c = 1.0, s = 0.0, re = 0.0, im = 0.0;
        for (int i = 0; i < len; i++) {
            if ((i & 1023) == 0) {           // re-seed the rotation: exact and trig free per sample
                double a = om * i;
                c = Math.cos(a);
                s = Math.sin(a);
            }
            double v = w[i] * x[from + i];
            re += v * c;
            im -= v * s;
            double nc = c * cw - s * sw;
            s = s * cw + c * sw;
            c = nc;
        }
        return 2.0 * Math.sqrt(re * re + im * im) / winSum;
    }

    /** normalised autocorrelation with a parabolic refinement; the earliest strong peak wins */
    static double autocorrF0(double[] x, int from, int len, double sr) {
        int lo = (int) (sr / 800.0), hi = (int) (sr / 60.0);
        if (hi > len - 2) hi = len - 2;
        double[] r = new double[hi + 2];
        double best = -2.0;
        for (int lag = lo; lag <= hi; lag++) {
            double s = 0, e1 = 0, e2 = 0;
            for (int i = 0; i + lag < len; i++) {
                double a = x[from + i], b = x[from + i + lag];
                s += a * b;
                e1 += a * a;
                e2 += b * b;
            }
            double d = Math.sqrt(e1 * e2);
            r[lag] = d > 1e-12 ? s / d : 0.0;
            if (r[lag] > best) best = r[lag];
        }
        int lag = lo;
        for (int i = lo; i <= hi; i++) {
            if (r[i] >= 0.98 * best && r[i] > r[i - 1] && r[i] >= r[i + 1]) { lag = i; break; }
        }
        double y0 = r[lag - 1], y1 = r[lag], y2 = r[lag + 1];
        double den = y0 - 2.0 * y1 + y2;
        double d = den != 0.0 ? 0.5 * (y0 - y2) / den : 0.0;
        if (d > 1.0) d = 1.0;
        if (d < -1.0) d = -1.0;
        return sr / (lag + d);
    }

    /** strongest spectral peak between lo and hi Hz, refined to a fraction of the scan step */
    static double strongestPeak(double[] x, int from, int len, double lo, double hi, double step) {
        double bestF = lo, bestM = -1;
        for (double f = lo; f <= hi + 1e-9; f += step) {
            double m = dftMag(x, from, len, f);
            if (m > bestM) { bestM = m; bestF = f; }
        }
        double m0 = dftMag(x, from, len, bestF - step);
        double m2 = dftMag(x, from, len, bestF + step);
        double den = m0 - 2.0 * bestM + m2;
        double d = den != 0.0 ? 0.5 * (m0 - m2) / den : 0.0;
        if (d > 1.0) d = 1.0;
        if (d < -1.0) d = -1.0;
        return bestF + d * step;
    }

    /** spectral centroid of a Hann windowed magnitude spectrum */
    static double centroid(double[] x, int from, int len, double lo, double hi, double step) {
        double num = 0, den = 0;
        for (double f = lo; f <= hi + 1e-9; f += step) {
            double m = dftMag(x, from, len, f);
            num += f * m;
            den += m;
        }
        return den > 1e-12 ? num / den : 0.0;
    }

    // ---------------------------------------------------------------- test signals

    static final int ENV_DARK = 0, ENV_FORMANT = 1, ENV_BRIGHT = 2;

    static double[] logGrid(double lo, double hi, int perOct) {
        int n = (int) Math.round(log2(hi / lo) * perOct) + 1;
        double[] g = new double[n];
        for (int i = 0; i < n; i++) g[i] = lo * Math.pow(2.0, i / (double) perOct);
        g[n - 1] = hi;
        return g;
    }

    static double[] sampleEnv(double[] g, int kind) {
        double[] db = new double[g.length];
        double max = -1e30;
        for (int i = 0; i < g.length; i++) {
            double v = dbOf(kind, g[i]);
            db[i] = v;
            if (v > max) max = v;
        }
        for (int i = 0; i < db.length; i++) db[i] -= max;
        return db;
    }

    static double dbOf(int kind, double f) {
        if (kind == ENV_DARK) return -18.0 * log2(f / 220.0) + peak(f, 420.0, 120.0, 10.0);
        if (kind == ENV_BRIGHT) {
            return -6.0 * log2(f / 220.0) + peak(f, 1200.0, 300.0, 8.0)
                    + peak(f, 3000.0, 700.0, 10.0) + peak(f, 5000.0, 900.0, 6.0);
        }
        return envFormantDb(f);
    }

    /** -12 dB/oct tilt plus three formants: the reference envelope of group 1 */
    static double envFormantDb(double f) {
        return -12.0 * log2(f / 220.0) + peak(f, 700.0, 110.0, 14.0)
                + peak(f, 1200.0, 160.0, 10.0) + peak(f, 2600.0, 250.0, 9.0);
    }

    static double peak(double f, double fc, double bw, double gainDb) {
        double t = (f - fc) / bw;
        return gainDb * Math.exp(-0.5 * t * t);
    }

    static double log2(double v) {
        return Math.log(v) / Math.log(2.0);
    }

    // ---------------------------------------------------------------- pack helpers

    static VoicePack.Result loadJson(String json) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        zos.putNextEntry(new ZipEntry("voice.json"));
        zos.write(json.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.close();
        return VoicePack.load(new ByteArrayInputStream(bos.toByteArray()), "synthetic.v4voice");
    }

    static String arr(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i] == Math.rint(v[i]) && Math.abs(v[i]) < 1e9
                    ? Long.toString((long) v[i]) : Double.toString(v[i]));
        }
        return sb.append(']').toString();
    }

    static String band(double f0, double[] db) {
        return "{\"f0\":" + (f0 == Math.rint(f0) ? Long.toString((long) f0) : Double.toString(f0))
                + ",\"db\":" + arr(db) + "}";
    }

    static List<String> pickSymbols(VoiceModel m, int max) {
        String[] prefer = {"a", "i", "u", "e", "o", "iE_n", "u_o"};
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < prefer.length && out.size() < max; i++) {
            if (m.harmonics.containsKey(prefer[i])) out.add(prefer[i]);
        }
        for (String s : m.harmonics.keySet()) {
            if (out.size() >= max) break;
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    // ---------------------------------------------------------------- reporting

    static void group(String name) {
        endGroup();
        groupName = name;
        groupFails = 0;
        System.out.println();
        System.out.println("--- " + name + " ---");
    }

    static void endGroup() {
        if (groupName != null) {
            System.out.println("[" + (groupFails == 0 ? "PASS" : "FAIL") + "] " + groupName);
            groupName = null;
        }
    }

    static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            fails++;
            groupFails++;
        }
        System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + what
                + (detail != null ? "  [" + detail + "]" : ""));
    }

    static void info(String what) {
        System.out.println("  --   " + what);
    }

    static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        double a = Math.abs(v);
        if (a != 0 && (a < 0.001 || a >= 1e6)) return String.format(Locale.ROOT, "%.3e", v);
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
