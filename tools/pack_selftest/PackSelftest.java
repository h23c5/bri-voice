import com.dsh.v4tune.io.PackStore;
import com.dsh.v4tune.synth.EqFilter;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Desktop self test for the singer-pack layer (.v4voice / VoicePack / EqFilter / PackStore).
 *
 * <p>Groups:</p>
 * <ol>
 *   <li>load every .v4voice given on the command line, or every pack in
 *       {@code build/voicepacks} - a synthetic pack built in the temp dir is used when that
 *       directory is empty, so the test is self contained;</li>
 *   <li>EqFilter: analytic response vs. the configured gain, bypass, a 1 s noise burst,
 *       click-free curve changes, NaN/Inf robustness, sample-rate change;</li>
 *   <li>PackStore import -> loadAll -> delete -> size round trip in a temp directory, including
 *       hostile file names / ids;</li>
 *   <li>VoicePack.write -> load, asserting identity, shelf (sorted) and formant overrides survive,
 *       plus a byte-identical re-export;</li>
 *   <li>rejection of malformed packs and clamping of out-of-range values.</li>
 * </ol>
 *
 * <p>Usage: {@code java PackSelftest [pack.v4voice ...]} - exits non-zero when a check fails.</p>
 */
public class PackSelftest {

    static final String DEFAULT_PACK_DIR =
            "C:\\Users\\H23_C5\\Documents\\deepseek-harness\\default-workspace\\vocaloid-android\\build\\voicepacks";
    static final int SR = 44100;

    static int checks = 0, fails = 0, groupFails = 0;
    static String groupName = null;

    public static void main(String[] args) throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir", "."),
                "v4tune_pack_selftest_" + Long.toHexString(System.nanoTime()));
        if (!tmp.mkdirs() && !tmp.isDirectory()) {
            System.out.println("FAIL: cannot create temp dir " + tmp.getAbsolutePath());
            System.exit(2);
        }
        System.out.println("PackSelftest - temp " + tmp.getAbsolutePath());

        // ---- synthetic pack, always available -------------------------------
        VoiceModel synthModel = syntheticModel();
        File synthetic = new File(tmp, "synthetic.v4voice");
        VoicePack.write(synthetic, synthModel, "self-test synthetic pack - generated, not for distribution");

        // ---- group 1: load packs -------------------------------------------
        group("1. load .v4voice packs");
        ArrayList<File> packs = new ArrayList<File>();
        boolean explicit = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null && args[i].trim().length() > 0) {
                packs.add(new File(args[i].trim()));
                explicit = true;
            }
        }
        if (!explicit) {
            File def = new File(DEFAULT_PACK_DIR);
            if (!def.isDirectory()) def = new File("build/voicepacks");
            File[] found = v4voiceFiles(def);
            if (found.length > 0) {
                info("pack dir " + def.getAbsolutePath() + " (" + found.length + " file(s))");
                for (int i = 0; i < found.length; i++) packs.add(found[i]);
            }
        }
        if (packs.isEmpty()) {
            info("no .v4voice in " + DEFAULT_PACK_DIR + " yet - using the synthetic pack");
            packs.add(synthetic);
        }

        ArrayList<VoiceModel> models = new ArrayList<VoiceModel>();
        for (int i = 0; i < packs.size(); i++) {
            File f = packs.get(i);
            VoicePack.Result r = VoicePack.load(f);
            if (!r.ok()) {
                if (explicit) check("parse " + f.getName(), false, r.error);
                else warn("skipped " + f.getName() + ": " + r.error);
                continue;
            }
            VoiceModel m = r.model;
            models.add(m);
            System.out.println("  ....  " + f.getName() + ": id=" + m.id + " name=" + m.name
                    + " lang=" + m.lang + " compID=" + (m.compID.isEmpty() ? "-" : m.compID)
                    + " overrides=" + m.overrides.size() + " shelf=" + m.shelfFreqs.length);
            check("id/name present (" + f.getName() + ")",
                    m.id.length() > 0 && m.name.length() > 0, m.id + " / " + m.name);
            check("shelf arrays aligned + ascending (" + f.getName() + ")",
                    m.shelfFreqs.length == m.shelfGains.length && ascending(m.shelfFreqs),
                    m.shelfFreqs.length + " points " + Arrays.toString(m.shelfFreqs));
            check("lang in 0..2 (" + f.getName() + ")", m.lang >= 0 && m.lang <= 2, "lang=" + m.lang);
        }
        check("at least one pack loaded", models.size() > 0, models.size() + " of " + packs.size());

        // ---- group 2: EqFilter ---------------------------------------------
        group("2. EqFilter");
        double[] docF = {60, 200, 800, 3000, 6000, 12000};
        double[] docG = {-3.0, 0.0, 1.5, 2.0, -1.5, -4.0};

        EqFilter empty = new EqFilter();
        empty.setSampleRate(SR);
        empty.setCurve(null, null);
        check("empty curve is bypassed", empty.isBypassed(), null);
        check("responseDb of an empty curve is 0 dB",
                Math.abs(EqFilter.responseDb(new double[0], new double[0], 1000, SR)) < 1.0e-9, null);
        Random r0 = new Random(7);
        boolean identity = true;
        for (int i = 0; i < 2000; i++) {
            float x = (float) (r0.nextDouble() * 2.0 - 1.0);
            if (empty.filter(x) != x) identity = false;
        }
        check("bypass passes samples through unchanged", identity, null);

        EqFilter one = new EqFilter();
        one.setSampleRate(SR);
        one.setCurve(new double[]{1000}, new double[]{6.0});
        check("single point is not bypassed", !one.isBypassed(), null);
        check("6 dB point responds +6 dB at 1 kHz",
                Math.abs(EqFilter.responseDb(new double[]{1000}, new double[]{6.0}, 1000, SR) - 6.0) < 0.01,
                fmt(EqFilter.responseDb(new double[]{1000}, new double[]{6.0}, 1000, SR)) + " dB");

        ArrayList<double[][]> curves = new ArrayList<double[][]>();
        curves.add(new double[][]{docF, docG});
        for (int i = 0; i < models.size(); i++) {
            VoiceModel m = models.get(i);
            if (m.shelfFreqs.length > 0) curves.add(new double[][]{m.shelfFreqs, m.shelfGains});
        }
        double worst = 0;
        String worstAt = "-";
        boolean gainOk = true;
        for (int c = 0; c < curves.size(); c++) {
            double[] f = curves.get(c)[0], g = curves.get(c)[1];
            for (int i = 0; i < f.length; i++) {
                double got = EqFilter.responseDb(f, g, f[i], SR);
                double dev = Math.abs(got - g[i]);
                if (dev > worst) {
                    worst = dev;
                    worstAt = fmt(f[i]) + " Hz: " + fmt(got) + " vs " + fmt(g[i]) + " dB";
                }
                if (dev > 1.5) gainOk = false;
            }
        }
        check("responseDb matches the configured gain at each shelf point (<= 1.5 dB)", gainOk,
                curves.size() + " curve(s), worst " + fmt(worst) + " dB @ " + worstAt);

        EqFilter sine = new EqFilter();
        sine.setSampleRate(SR);
        sine.setCurve(new double[]{1000}, new double[]{6.0});
        double sumIn = 0, sumOut = 0;
        for (int i = 0; i < SR; i++) {
            double x = 0.25 * Math.sin(2.0 * Math.PI * 1000.0 * i / SR);
            double y = sine.filter((float) x);
            if (i > SR / 2) {
                sumIn += x * x;
                sumOut += y * y;
            }
        }
        double measured = 20.0 * Math.log10(Math.sqrt(sumOut / Math.max(sumIn, 1e-30)));
        check("realized gain of a +6 dB point is +6 dB (1 kHz sine)", Math.abs(measured - 6.0) < 0.6,
                "measured " + fmt(measured) + " dB");

        EqFilter noise = new EqFilter();
        noise.setSampleRate(SR);
        noise.setCurve(docF, docG);
        Random rnd = new Random(42);
        int nonFinite = 0;
        double peak = 0;
        for (int i = 0; i < SR; i++) {
            if (i == SR / 3) noise.setSampleRate(48000);
            float y = noise.filter((float) (rnd.nextDouble() * 2.0 - 1.0));
            if (!finite(y)) nonFinite++;
            peak = Math.max(peak, Math.abs(y));
        }
        check("1 s noise burst / sample-rate change: no NaN or Inf", nonFinite == 0, nonFinite + " bad samples");
        check("noise burst output stays bounded", peak < 12.0, "peak " + fmt(peak));

        EqFilter nanf = new EqFilter();
        nanf.setSampleRate(SR);
        nanf.setCurve(docF, docG);
        float a1 = nanf.filter(Float.NaN), a2 = nanf.filter(Float.POSITIVE_INFINITY), a3 = nanf.filter(Float.NEGATIVE_INFINITY);
        int nanAfter = 0;
        for (int i = 0; i < 1000; i++) if (!finite(nanf.filter(0.3f))) nanAfter++;
        check("NaN/Inf input does not poison the filter",
                finite(a1) && finite(a2) && finite(a3) && nanAfter == 0, "a1=" + a1 + " a2=" + a2 + " a3=" + a3);

        EqFilter click = new EqFilter();
        click.setSampleRate(SR);
        click.setCurve(new double[]{200}, new double[]{0.0});
        double prev = 0, maxStep = 0, sineStep = 0.5 * 2.0 * Math.PI * 50.0 / SR;
        boolean firstSample = true;
        for (int i = 0; i < SR; i++) {
            if (i == SR / 2) click.setCurve(new double[]{1000, 4000}, new double[]{12.0, -12.0});
            double y = click.filter((float) (0.5 * Math.sin(2.0 * Math.PI * 50.0 * i / SR)));
            if (!firstSample) maxStep = Math.max(maxStep, Math.abs(y - prev));
            prev = y;
            firstSample = false;
        }
        check("curve change is click free", maxStep < 0.05,
                "max sample step " + fmt(maxStep) + " (50 Hz sine step " + fmt(sineStep) + ")");

        // ---- group 3: PackStore --------------------------------------------
        group("3. PackStore round trip");
        File storeDir = new File(tmp, "singers");
        PackStore store = new PackStore(storeDir);
        byte[] packBytes = readFile(synthetic);
        check("fresh store is empty", store.size() == 0, null);
        VoicePack.Result imp = store.importStream(new ByteArrayInputStream(packBytes),
                "..\\..\\weird name!.v4voice", true);
        check("importStream succeeds", imp.ok(), imp.ok() ? null : imp.error);
        check("imported id comes from the model", imp.ok() && synthModel.id.equals(imp.model.id),
                imp.ok() ? imp.model.id : "-");
        List<VoicePack.Result> all = store.loadAll();
        check("loadAll finds the imported pack", all.size() == 1, all.size() + " pack(s)");
        check("loadAll keeps the id", all.size() == 1 && synthModel.id.equals(all.get(0).model.id),
                all.isEmpty() ? "-" : all.get(0).model.id);
        check("size() == 1", store.size() == 1, "size=" + store.size());
        File stored = store.fileFor(synthModel.id);
        check("fileFor(id) is the imported file", stored.isFile(), stored.getAbsolutePath());
        check("stored file name is sanitized",
                stored.getName().matches("[A-Za-z0-9._-]+\\.v4voice"), stored.getName());
        check("existing pack is kept without overwrite",
                !store.importStream(new ByteArrayInputStream(packBytes), "again.v4voice", false).ok(), null);
        check("overwrite=true replaces it",
                store.importStream(new ByteArrayInputStream(packBytes), "again.v4voice", true).ok(), null);
        check("size() still 1 after overwrite", store.size() == 1, "size=" + store.size());

        File brokenFile = new File(storeDir, "broken.v4voice");
        writeFile(brokenFile, "this is not a zip at all".getBytes(StandardCharsets.UTF_8));
        check("broken file is skipped by loadAll", store.loadAll().size() == 1,
                store.loadAll().size() + " pack(s)");
        check("size() counts pack files", store.size() == 2, "size=" + store.size());
        brokenFile.delete();

        VoiceModel evil = syntheticModel();
        evil.id = "../../escape";
        evil.name = "escape test";
        File evilPack = new File(tmp, "evil.v4voice");
        VoicePack.write(evilPack, evil, null);
        VoicePack.Result ev = store.importStream(new FileInputStream(evilPack), "evil.v4voice", true);
        File evFile = store.fileFor(ev.ok() ? ev.model.id : "");
        check("hostile id cannot escape the store directory",
                ev.ok() && evFile.getParentFile().getCanonicalFile().equals(storeDir.getCanonicalFile()),
                ev.ok() ? evFile.getName() : ev.error);
        check("hostile file name has no path separator",
                evFile.getName().indexOf('/') < 0 && evFile.getName().indexOf('\\') < 0, evFile.getName());
        if (ev.ok()) store.delete(ev.model.id);

        check("delete(id) removes the pack", store.delete(synthModel.id), null);
        check("size() == 0 after delete", store.size() == 0, "size=" + store.size());
        check("loadAll is empty after delete", store.loadAll().isEmpty(), null);
        check("delete of an unknown id is false", !store.delete(synthModel.id), null);
        check("delete of an unusable id is false", !store.delete("///"), null);

        // ---- group 4: write -> load round trip ------------------------------
        group("4. VoicePack.write -> load");
        VoiceModel m = new VoiceModel();
        m.id = "selftest_roundtrip";
        m.name = "自检 · 往返测试";
        m.nameEn = "Self Test (round trip)";
        m.lang = 1;
        m.compID = "TESTCOMP01";
        m.formantScale = 1.037;
        m.f1Mul = 1.11; m.f2Mul = 0.93; m.f3Mul = 1.02; m.f4Mul = 1.0; m.f5Mul = 1.0;
        m.tiltBias = -0.25; m.breathBias = 0.42; m.oqBias = 0.11;
        m.vibratoRate = 6.25; m.vibratoDepthCents = 35; m.vibratoDelayMs = 180;
        m.gain = 0.93; m.consonantGain = 1.21; m.breathSoundAmount = 0.55;
        m.description = "round trip 描述 with \"quotes\" and \\ backslash";
        m.shelfFreqs = new double[]{3000, 60, 800};          // unsorted on purpose
        m.shelfGains = new double[]{2.0, -3.0, 1.5};
        m.overrides.put("a", new double[]{850, 1200, 2500});
        m.overrides.put("iE_n", new double[]{330.5, 2100, 2900});
        m.overrides.put("ts\\_h", new double[]{400, 1800, 2600});

        File out1 = new File(tmp, "roundtrip.v4voice");
        VoicePack.write(out1, m, "license line 1\nlicense line 2");
        VoicePack.Result rl = VoicePack.load(out1);
        check("written pack reloads", rl.ok(), rl.ok() ? null : rl.error);
        if (rl.ok()) {
            VoiceModel b = rl.model;
            check("identity survives", m.id.equals(b.id) && m.name.equals(b.name) && m.nameEn.equals(b.nameEn)
                    && m.lang == b.lang && m.compID.equals(b.compID), b.id + " / " + b.name + " / lang " + b.lang);
            check("description survives", m.description.equals(b.description), b.description);
            check("voice numbers survive", near(m.formantScale, b.formantScale) && near(m.f1Mul, b.f1Mul)
                    && near(m.f2Mul, b.f2Mul) && near(m.f3Mul, b.f3Mul) && near(m.tiltBias, b.tiltBias)
                    && near(m.breathBias, b.breathBias) && near(m.oqBias, b.oqBias)
                    && near(m.vibratoRate, b.vibratoRate) && near(m.vibratoDepthCents, b.vibratoDepthCents)
                    && near(m.vibratoDelayMs, b.vibratoDelayMs) && near(m.gain, b.gain)
                    && near(m.consonantGain, b.consonantGain) && near(m.breathSoundAmount, b.breathSoundAmount),
                    "scale " + b.formantScale + " gain " + b.gain);
            check("shelf points survive sorted ascending",
                    b.shelfFreqs.length == 3 && near(b.shelfFreqs[0], 60) && near(b.shelfFreqs[1], 800)
                            && near(b.shelfFreqs[2], 3000) && near(b.shelfGains[0], -3)
                            && near(b.shelfGains[1], 1.5) && near(b.shelfGains[2], 2),
                    Arrays.toString(b.shelfFreqs) + " " + Arrays.toString(b.shelfGains));
            check("formant overrides survive",
                    b.overrides.size() == 3 && eq3(b.overrides.get("a"), 850, 1200, 2500)
                            && eq3(b.overrides.get("iE_n"), 330.5, 2100, 2900)
                            && eq3(b.overrides.get("ts\\_h"), 400, 1800, 2600),
                    keys(b.overrides));
            File out2 = new File(tmp, "roundtrip2.v4voice");
            VoicePack.write(out2, b, "license line 1\nlicense line 2");
            check("re-export is byte identical", Arrays.equals(readFile(out1), readFile(out2)),
                    readFile(out1).length + " bytes");
            check("LICENSE.txt is stored", "license line 1\nlicense line 2".equals(zipText(out1, "LICENSE.txt")),
                    zipText(out1, "LICENSE.txt"));
            check("pack has no LICENSE.txt when none is given", zipText(evilPack, "LICENSE.txt") == null, null);
        }

        // ---- group 5: rejection and clamping --------------------------------
        group("5. rejection and clamping");
        check("wrong format is rejected",
                !VoicePack.load(packStream("{\"format\":\"v4voice/2\",\"id\":\"x\",\"name\":\"y\"}"), "x.v4voice").ok(), null);
        check("missing format is rejected",
                !VoicePack.load(packStream("{\"id\":\"x\",\"name\":\"y\"}"), "x.v4voice").ok(), null);
        check("missing id is rejected",
                !VoicePack.load(packStream("{\"format\":\"v4voice/1\",\"name\":\"y\"}"), "x.v4voice").ok(), null);
        check("empty name is rejected",
                !VoicePack.load(packStream("{\"format\":\"v4voice/1\",\"id\":\"x\",\"name\":\"  \"}"), "x.v4voice").ok(), null);
        check("bare json (not a zip) is rejected",
                !VoicePack.load(stream("{\"format\":\"v4voice/1\",\"id\":\"x\",\"name\":\"y\"}"), "bare.v4voice").ok(), null);
        check("garbage bytes are rejected without throwing",
                !VoicePack.load(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), "junk.v4voice").ok(), null);
        check("empty stream is rejected",
                !VoicePack.load(new ByteArrayInputStream(new byte[0]), "empty.v4voice").ok(), null);
        check("null stream is rejected", !VoicePack.load((InputStream) null, "null.v4voice").ok(), null);
        check("truncated json is rejected",
                !VoicePack.load(packStream("{\"format\":\"v4voice/1\","), "t.v4voice").ok(), null);

        String clampJson = "{\"format\":\"v4voice/1\",\"id\":\"clamp\",\"name\":\"Clamp\",\"lang\":9,"
                + "\"voice\":{\"gain\":1e9,\"formantScale\":-5,\"breathBias\":7,\"vibratoRate\":999},"
                + "\"shelf\":[[10,99],[\"x\",1],[3000,-40],[3000,3]],"
                + "\"formants\":{\"a\":[0,100,200],\"b\":[500,1500],\"c\":[520,1180,2600]}}";
        VoicePack.Result cr = VoicePack.load(packStream(clampJson), "clamp.v4voice");
        check("out-of-range pack still loads", cr.ok(), cr.ok() ? null : cr.error);
        if (cr.ok()) {
            VoiceModel c = cr.model;
            check("gain clamped to 4", near(c.gain, 4.0), "gain=" + c.gain);
            check("formantScale clamped to 0.5", near(c.formantScale, 0.5), "scale=" + c.formantScale);
            check("breathBias clamped to 1", near(c.breathBias, 1.0), "breath=" + c.breathBias);
            check("vibratoRate clamped to 12", near(c.vibratoRate, 12.0), "rate=" + c.vibratoRate);
            check("lang clamped to 2", c.lang == 2, "lang=" + c.lang);
            check("shelf clamped + sorted + deduped",
                    c.shelfFreqs.length == 2 && near(c.shelfFreqs[0], 20.0) && near(c.shelfFreqs[1], 3000.0)
                            && near(c.shelfGains[0], 12.0) && near(c.shelfGains[1], 3.0),
                    Arrays.toString(c.shelfFreqs) + " " + Arrays.toString(c.shelfGains));
            check("bad formant entries dropped, good one kept",
                    c.overrides.size() == 1 && eq3(c.overrides.get("c"), 520, 1180, 2600), keys(c.overrides));
        }
        VoicePack.Result noName = VoicePack.load(packStream("{\"format\":\"v4voice/1\",\"id\":\"x\",\"name\":\"N\"}"),
                "min.v4voice");
        check("minimal pack uses the built-in defaults",
                noName.ok() && near(noName.model.formantScale, 1.0) && near(noName.model.vibratoRate, 5.5)
                        && noName.model.shelfFreqs.length == 0 && noName.model.overrides.isEmpty(),
                noName.ok() ? "scale " + noName.model.formantScale + " vib " + noName.model.vibratoRate : noName.error);

        groupEnd();
        System.out.println();
        System.out.println(checks + " checks, " + fails + " failure(s)");
        System.out.println(fails == 0 ? "RESULT: ALL GROUPS PASS" : "RESULT: FAIL");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- helpers

    static VoiceModel syntheticModel() {
        VoiceModel m = new VoiceModel();
        m.id = "selftest_synthetic";
        m.name = "自检合成声库";
        m.nameEn = "Self-test synthetic bank";
        m.lang = 2;
        m.compID = "SELFTEST00000001";
        m.formantScale = 1.05;
        m.f1Mul = 1.02; m.f2Mul = 1.08; m.f3Mul = 1.03;
        m.tiltBias = -0.1; m.breathBias = 0.2; m.oqBias = 0.03;
        m.vibratoRate = 5.6; m.gain = 0.97; m.consonantGain = 1.05; m.breathSoundAmount = 0.4;
        m.description = "self-test synthetic pack (no voicebank data)";
        m.shelfFreqs = new double[]{60, 200, 800, 3000, 6000, 12000};
        m.shelfGains = new double[]{-3.0, 0.0, 1.5, 2.0, -1.5, -4.0};
        m.overrides.put("a", new double[]{850, 1200, 2500});
        m.overrides.put("i", new double[]{300, 2300, 3000});
        m.overrides.put("uo", new double[]{400, 800, 2400});
        return m;
    }

    static void group(String name) {
        groupEnd();
        groupName = name;
        groupFails = 0;
        System.out.println("\n== " + name + " ==");
    }

    static void groupEnd() {
        if (groupName != null) {
            System.out.println("  ==> " + groupName + ": " + (groupFails == 0 ? "PASS" : "FAIL"));
        }
    }

    static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            fails++;
            groupFails++;
        }
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what
                + (detail == null || detail.isEmpty() ? "" : "   [" + detail + "]"));
    }

    static void warn(String what) {
        System.out.println("  WARN  " + what);
    }

    static void info(String what) {
        System.out.println("  ....  " + what);
    }

    static File[] v4voiceFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        ArrayList<File> out = new ArrayList<File>();
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".v4voice")) out.add(f);
        }
        java.util.Collections.sort(out);
        return out.toArray(new File[out.size()]);
    }

    static boolean ascending(double[] a) {
        for (int i = 1; i < a.length; i++) if (!(a[i] > a[i - 1])) return false;
        return true;
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-6;
    }

    static boolean eq3(double[] v, double a, double b, double c) {
        return v != null && v.length == 3 && near(v[0], a) && near(v[1], b) && near(v[2], c);
    }

    static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    static String keys(Map<String, double[]> map) {
        StringBuilder sb = new StringBuilder();
        for (String k : map.keySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(k);
        }
        return sb.toString();
    }

    static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Wraps an inline voice.json in the real .v4voice zip container. */
    static ByteArrayInputStream packStream(String json) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos);
        java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("voice.json");
        zos.putNextEntry(e);
        zos.write(json.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.close();
        return new ByteArrayInputStream(bos.toByteArray());
    }

    static byte[] readFile(File f) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        } finally {
            in.close();
        }
        return bos.toByteArray();
    }

    static void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    static String zipText(File zipFile, String entryName) throws Exception {
        ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!entryName.equals(e.getName())) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = zip.read(buf)) > 0) bos.write(buf, 0, r);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
            return null;
        } finally {
            zip.close();
        }
    }
}
