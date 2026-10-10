import com.dsh.v4tune.audio.Mixer;
import com.dsh.v4tune.audio.Plugin;
import com.dsh.v4tune.audio.PluginChain;
import com.dsh.v4tune.audio.PluginRegistry;
import com.dsh.v4tune.audio.plugins.ChorusPlugin;
import com.dsh.v4tune.audio.plugins.CompressorPlugin;
import com.dsh.v4tune.audio.plugins.ReverbPlugin;

/**
 * Desktop self-test for the BriVoice mixer and plugin chain (Mixer, Plugin, PluginChain,
 * ReverbPlugin, ChorusPlugin, CompressorPlugin). Pure Java, no android.*, every assertion is a
 * numeric measurement printed with its value. Exits non-zero when a check fails.
 *
 * <p>Usage: {@code java MixerPluginSelftest} - no arguments, no files written.</p>
 */
public class MixerPluginSelftest {

    static final int SR = 44100;
    static int checks = 0;
    static int failures = 0;

    public static void main(String[] args) {
        System.out.println("BriVoice mixer / plugin-chain self-test   sample rate " + SR + " Hz");
        System.out.println("java: " + System.getProperty("java.version"));
        System.out.println();

        testMixerPan();
        testMixerGainMuteSolo();
        testMixerMaster();
        testReverb();
        testChorus();
        testCompressor();
        testChain();
        testRegistry();

        System.out.println();
        System.out.println("checks: " + checks + ", failures: " + failures);
        if (failures == 0) System.out.println("RESULT: ALL CHECKS PASSED");
        else System.out.println("RESULT: " + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ================================================================== mixer

    static void testMixerPan() {
        section("Mixer - equal-power pan");
        Mixer m = new Mixer(2);

        double worstPower = 0.0;
        double[] pans = { -1.0, -0.75, -0.5, 0.0, 0.5, 0.75, 1.0 };
        for (int i = 0; i < pans.length; i++) {
            double p = pans[i];
            double pl = Mixer.panLeftGain(p);
            double pr = Mixer.panRightGain(p);
            worstPower = Math.max(worstPower, Math.abs(pl * pl + pr * pr - 1.0));
        }
        check("pan law keeps constant power (gL^2+gR^2 == 1)", worstPower < 1e-12,
                "max error " + fmt(worstPower));

        check("pan 0 -> (0.70711, 0.70711)",
                near(Mixer.panLeftGain(0), Math.sqrt(0.5), 1e-9) && near(Mixer.panRightGain(0), Math.sqrt(0.5), 1e-9),
                "gL=" + fmt(Mixer.panLeftGain(0)) + " gR=" + fmt(Mixer.panRightGain(0)));

        float[] l = { 1f, 1f, 1f, 1f };
        float[] r = { 1f, 1f, 1f, 1f };
        m.setGain(0, 1.0f);
        m.setPan(0, -1.0f);
        m.process(l, r, 4, 0);
        double eL = energy(l, 0, 4);
        double eR = energy(r, 0, 4);
        check("pan -1: left unchanged", near(l[0], 1.0, 1e-6), "left[0]=" + fmt(l[0]));
        check("pan -1: right channel energy ~ 0", eR < 1e-12 && eL > 0.99,
                "E_left=" + fmt(eL) + " E_right=" + fmt(eR));

        float[] l2 = { 1f, 1f, 1f, 1f };
        float[] r2 = { 1f, 1f, 1f, 1f };
        m.setPan(0, 1.0f);
        m.process(l2, r2, 4, 0);
        check("pan +1: left channel energy ~ 0", energy(l2, 0, 4) < 1e-12 && near(r2[0], 1.0, 1e-6),
                "E_left=" + fmt(energy(l2, 0, 4)) + " right[0]=" + fmt(r2[0]));

        float[] lc = { 1f };
        float[] rc = { 1f };
        m.setPan(0, 0.0f);
        m.process(lc, rc, 1, 0);
        check("centre pan is -3.01 dB per channel", near(20 * Math.log10(lc[0]), -3.0103, 1e-4),
                "L=" + fmt4(lc[0]) + " (" + fmt(20 * Math.log10(lc[0])) + " dB)");

        check("pan clamps outside [-1,1]", Mixer.clampPan(-5f) == -1f && Mixer.clampPan(5f) == 1f,
                "clampPan(-5)=" + Mixer.clampPan(-5f) + " clampPan(5)=" + Mixer.clampPan(5f));
    }

    static void testMixerGainMuteSolo() {
        section("Mixer - gain / mute / solo");
        Mixer m = new Mixer(3);
        m.setPan(0, 0f);

        double worst = 0.0;
        double[] gains = { 0.0, 0.25, 0.5, 1.0, 1.5, 2.0 };
        for (int i = 0; i < gains.length; i++) {
            float[] l = { 0.4f };
            float[] r = { 0.4f };
            m.setGain(0, (float) gains[i]);
            m.process(l, r, 1, 0);
            double expect = 0.4 * gains[i] * Math.sqrt(0.5);
            worst = Math.max(worst, Math.abs(l[0] - expect));
        }
        check("gain is linear 0..2 (0.4 * g * 0.7071)", worst < 1e-7, "max error " + fmt4(worst));

        float[] l = { 1f, 1f };
        float[] r = { 1f, 1f };
        m.setGain(0, 2.0f);
        m.process(l, r, 2, 0);
        check("track stage does NOT clip (2.0 gain on 1.0 -> 1.414)", near(l[0], 2.0 * Math.sqrt(0.5), 1e-6),
                "sample=" + fmt4(l[0]) + " (" + fmt(20 * Math.log10(l[0])) + " dBFS)");
        check("gain clamps at 2.0", Mixer.clampGain(9f) == 2f && Mixer.clampGain(-1f) == 0f,
                "clampGain(9)=" + Mixer.clampGain(9f));

        m.setGain(0, 1.0f);
        m.setMute(0, true);
        float[] ml = { 0.5f, 0.5f };
        float[] mr = { 0.5f, 0.5f };
        m.process(ml, mr, 2, 0);
        check("mute zeroes both channels", energy(ml, 0, 2) == 0.0 && energy(mr, 0, 2) == 0.0,
                "E=" + fmt(energy(ml, 0, 2)));
        check("isAudible respects mute", !m.isAudible(0) && m.isAudible(1), "audible0=" + m.isAudible(0));

        m.setMute(0, false);
        m.setSolo(1, true);
        check("solo: soloed track audible, others not",
                m.isAudible(1) && !m.isAudible(0) && !m.isAudible(2),
                "a0=" + m.isAudible(0) + " a1=" + m.isAudible(1) + " a2=" + m.isAudible(2));
        check("anySolo true", m.anySolo(), "anySolo=" + m.anySolo());

        float[] sl = { 1f, 1f };
        float[] sr = { 1f, 1f };
        m.process(sl, sr, 2, 0);
        check("non-soloed track is silenced by process()", energy(sl, 0, 2) == 0.0, "E=" + fmt(energy(sl, 0, 2)));

        m.setMute(1, true);
        check("mute wins over solo on the same track", !m.isAudible(1), "audible1=" + m.isAudible(1));

        m.setMute(1, false);
        m.clearSolo();
        check("clearSolo restores everything", !m.anySolo() && m.isAudible(0) && m.isAudible(1) && m.isAudible(2),
                "anySolo=" + m.anySolo());

        Mixer small = new Mixer(1);
        small.setGain(3, 0.5f);
        check("indices beyond trackCount store values / read defaults",
                small.getGain(3) == 0.5f && small.getGain(7) == 1.0f && small.getPan(7) == 0.0f,
                "gain3=" + small.getGain(3) + " gain7=" + small.getGain(7));
        small.setTrackCount(0);
        check("setTrackCount(0) -> nothing soloed, still audible default",
                small.trackCount() == 0 && !small.anySolo(), "count=" + small.trackCount());

        Mixer acc = new Mixer(1);
        acc.setGain(0, 2.0f);
        acc.setPan(0, 0.0f);
        float[] mono = { 0.5f };
        float[] busL = { 0.1f };
        float[] busR = { 0.1f };
        acc.accumulateMono(mono, busL, busR, 1, 0);
        check("accumulateMono sums with gain and pan", near(busL[0], 0.1 + 0.5 * 2 * Math.sqrt(0.5), 1e-6),
                "busL=" + fmt4(busL[0]) + " (expected " + fmt4(0.1 + 0.5 * 2 * Math.sqrt(0.5)) + ")");
    }

    static void testMixerMaster() {
        section("Mixer - master gain, master pan, soft limiter");
        Mixer m = new Mixer(1);

        float[] l = { 0.5f };
        float[] r = { 0.5f };
        m.processMaster(l, r, 1);
        check("default master (gain 1, pan 0) is transparent", l[0] == 0.5f && r[0] == 0.5f,
                "L=" + fmt4(l[0]));

        l[0] = 0.5f; r[0] = 0.5f;
        m.setMasterGain(0.5f);
        m.processMaster(l, r, 1);
        check("master gain is linear below the knee", near(l[0], 0.25, 1e-7), "L=" + fmt4(l[0]));

        m.setMasterGain(1.0f);
        m.setMasterPan(-1.0f);
        l[0] = 0.25f; r[0] = 0.25f;
        m.processMaster(l, r, 1);
        check("master pan -1: right muted, left +3 dB",
                r[0] == 0.0f && near(l[0], 0.25 * Math.sqrt(2.0), 1e-6),
                "L=" + fmt4(l[0]) + " R=" + fmt4(r[0]));
        m.setMasterPan(0.0f);

        double worst = 0.0;
        double worstX = 0.0;
        for (int i = 0; i < 4000; i++) {
            float x = (float) (-12.0 + 24.0 * i / 3999.0);
            float y = Mixer.softLimit(x);
            if (Math.abs(y) > 1.0 + 1e-6) worst = Math.max(worst, Math.abs(y));
            if (Math.abs(x) <= Mixer.LIMIT_KNEE && y != x) worstX = Math.max(worstX, Math.abs(y - x));
        }
        check("soft limiter never leaves [-1,1]", worst == 0.0, "worst |out| = " + fmt4(worst));
        check("soft limiter is exactly linear below the knee",
                worstX == 0.0, "max |y-x| below knee = " + worstX);

        double c1 = Mixer.softLimit(0.7f - 1e-4f);
        double c2 = Mixer.softLimit(0.7f + 1e-4f);
        check("soft limiter is continuous at the knee", Math.abs(c2 - c1) < 1e-3,
                "y(0.6999)=" + fmt4(c1) + " y(0.7001)=" + fmt4(c2));
        check("soft limiter is odd symmetric", near(Mixer.softLimit(-1.5f), -Mixer.softLimit(1.5f), 1e-7),
                "y(1.5)=" + fmt4(Mixer.softLimit(1.5f)) + " y(-1.5)=" + fmt4(Mixer.softLimit(-1.5f)));

        float[] hot = new float[64];
        float[] hotR = new float[64];
        for (int i = 0; i < 64; i++) { hot[i] = 8.0f; hotR[i] = -8.0f; }
        m.processMaster(hot, hotR, 64);
        check("8.0 hot bus is limited to <= 1.0",
                maxAbs(hot, 0, 64) <= 1.0 && maxAbs(hot, 0, 64) > 0.99 && minOf(hot, 0, 64) >= -1.0,
                "max=" + fmt4(maxAbs(hot, 0, 64)) + " min=" + fmt4(minOf(hot, 0, 64)));
        check("limited value matches the tanh knee",
                near(hot[0], 0.7 + 0.3 * Math.tanh((8.0 - 0.7) / 0.3), 1e-6), "y=" + fmt4(hot[0]));
    }

    // ================================================================== reverb

    static void testReverb() {
        section("ReverbPlugin - Freeverb (4 comb + 2 allpass)");
        ReverbPlugin rv = new ReverbPlugin();
        check("paramCount == 3", rv.paramCount() == 3, "got " + rv.paramCount());
        check("id / name", "builtin.reverb".equals(rv.id()) && "Reverb".equals(rv.name()),
                rv.id() + " / " + rv.name());
        rv.setParam(0, 5.0);
        check("setParam clamps to range", rv.getParam(0) == 1.0, "mix after setParam(5) = " + rv.getParam(0));
        rv.setParam(0, 1.0);
        rv.setParam(1, 0.6);
        rv.setParam(2, 0.3);
        rv.prepare(SR, 512);

        int n = 3 * SR;
        float[] l = impulse(n);
        float[] r = impulse(n);
        rv.process(l, r, n);

        check("impulse response is finite", countBad(l, 0, n) == 0 && countBad(r, 0, n) == 0,
                countBad(l, 0, n) + " bad samples");
        check("fully wet: no dry leak at t=0 (y[0] == 0)", l[0] == 0.0f, "y[0]=" + fmt4(l[0]));
        check("pre-delay before the first comb (E[0..20ms] == 0)",
                energy(l, 0, (int) (0.020 * SR)) == 0.0, "E=" + energy(l, 0, (int) (0.020 * SR)));
        double eWet = energy(l, 0, n);
        check("wet output is non-zero", eWet > 1e-6, "E_total=" + fmt4(eWet) + " (peak " + fmt4(maxAbs(l, 0, n)) + ")");

        double e1 = energy(l, (int) (0.05 * SR), (int) (0.15 * SR));
        double e2 = energy(l, (int) (1.5 * SR), (int) (1.6 * SR));
        double decayDb = 10 * Math.log10(e1 / e2);
        check("tail decays strongly (0.05-0.15 s vs 1.5-1.6 s)", decayDb > 30,
                "decay " + fmt(decayDb) + " dB");

        boolean mono = true;
        double prev = Double.MAX_VALUE;
        double[] win = new double[8];
        for (int i = 0; i < win.length; i++) {
            int from = (int) ((0.25 + 0.25 * i) * SR);
            int to = (int) ((0.50 + 0.25 * i) * SR);
            win[i] = energy(l, from, to);
            if (win[i] >= prev) mono = false;
            prev = win[i];
        }
        check("tail energy falls monotonically in 250 ms windows",
                mono && win[0] > 0 && win[win.length - 1] > 0,
                "windows dB: " + dbList(win));

        double rt = rt60(l, 0, n);
        check("RT60 in a sane range (0.2 .. 4 s)", rt > 0.2 && rt < 4.0, "RT60 = " + fmt(rt) + " s");

        double rtSmall = rt60(renderIr(0.3, 0.3), 0, n);
        double rtBig = rt60(renderIr(0.9, 0.3), 0, n);
        check("room size lengthens RT60", rtSmall > 0 && rtBig > 0 && rtBig > rtSmall * 1.5,
                "RT60(room 0.3)=" + fmt(rtSmall) + " s, RT60(room 0.9)=" + fmt(rtBig) + " s");

        double lateBright = energy(renderIr(0.6, 0.05), (int) (0.4 * SR), (int) (0.9 * SR));
        double lateDamped = energy(renderIr(0.6, 0.9), (int) (0.4 * SR), (int) (0.9 * SR));
        check("damping shortens the tail", lateDamped < lateBright * 0.7,
                "late E(damp 0.05)=" + fmt4(lateBright) + ", E(damp 0.9)=" + fmt4(lateDamped));

        float[] dl = impulse(256);
        float[] dr = impulse(256);
        rv.setParam(0, 0.0);
        rv.reset();
        rv.process(dl, dr, 256);
        check("mix 0 passes the dry signal untouched", dl[0] == 1.0f && energy(dl, 1, 256) == 0.0,
                "y[0]=" + fmt4(dl[0]) + " E_tail=" + energy(dl, 1, 256));

        int seg = 4096;
        float[] sl = impulse(seg);
        float[] sr2 = impulse(seg);
        rv.setParam(0, 1.0);
        rv.reset();
        rv.process(sl, sr2, seg);
        double diff = 0.0;
        for (int i = 0; i < seg; i++) diff = Math.max(diff, Math.abs(sl[i] - sr2[i]));
        check("stereo spread: L and R tails differ", diff > 1e-3 && maxAbs(sl, 0, seg) > 0,
                "max |L-R| = " + fmt4(diff) + " (R uses the +23 sample spread)");

        float[] fr = impulse(seg);
        float[] frr = impulse(seg);
        ReverbPlugin fresh = new ReverbPlugin();
        fresh.prepare(SR, 512);
        fresh.setParam(0, 1.0);
        fresh.setParam(1, 0.6);
        fresh.setParam(2, 0.3);
        fresh.process(fr, frr, seg);
        rv.setParam(1, 0.6);
        rv.setParam(2, 0.3);
        rv.reset();
        float[] rl = impulse(seg);
        float[] rr = impulse(seg);
        rv.process(rl, rr, seg);
        double re = 0.0;
        for (int i = 0; i < seg; i++) re = Math.max(re, Math.abs(rl[i] - fr[i]));
        check("reset() clears the delay lines (matches a fresh instance)", re == 0.0 && maxAbs(fr, 0, seg) > 0,
                "max |reset - fresh| = " + re + ", fresh peak " + fmt4(maxAbs(fr, 0, seg)));
    }

    static float[] renderIr(double room, double damp) {
        ReverbPlugin rv = new ReverbPlugin();
        rv.prepare(SR, 512);
        rv.setParam(0, 1.0);
        rv.setParam(1, room);
        rv.setParam(2, damp);
        int n = 3 * SR;
        float[] l = impulse(n);
        float[] r = impulse(n);
        rv.process(l, r, n);
        return l;
    }

    // ================================================================== chorus

    static void testChorus() {
        section("ChorusPlugin - modulated delay");
        ChorusPlugin ch = new ChorusPlugin();
        check("paramCount == 3", ch.paramCount() == 3, "got " + ch.paramCount());
        check("id / name", "builtin.chorus".equals(ch.id()) && "Chorus".equals(ch.name()),
                ch.id() + " / " + ch.name());
        ch.prepare(SR, 512);

        double worstDry = 0.0;
        double[] mixes = { 0.0, 0.25, 0.5, 1.0 };
        for (int i = 0; i < mixes.length; i++) {
            ch.reset();
            ch.setParam(0, 4.0);
            ch.setParam(1, 0.8);
            ch.setParam(2, mixes[i]);
            float[] l = impulse(4096);
            float[] r = impulse(4096);
            ch.process(l, r, 4096);
            worstDry = Math.max(worstDry, Math.abs(l[0] - (1.0 - mixes[i])));
        }
        check("dry/wet: y[0] == 1 - mix for mix in {0,0.25,0.5,1}", worstDry < 1e-6,
                "max error " + worstDry);

        ch.reset();
        ch.setParam(0, 4.0);
        ch.setParam(1, 0.8);
        ch.setParam(2, 0.0);
        float[] l0 = impulse(4096);
        float[] r0 = impulse(4096);
        ch.process(l0, r0, 4096);
        check("mix 0 is bit-exact dry", l0[0] == 1.0f && energy(l0, 1, 4096) == 0.0,
                "E_tail=" + energy(l0, 1, 4096));

        ch.reset();
        ch.setParam(2, 1.0);
        float[] w = impulse(8192);
        float[] wr = impulse(8192);
        ch.process(w, wr, 8192);
        int lo = (int) ((20.0 - 15.0 - 1.0) * 0.001 * SR);
        int hi = (int) ((20.0 + 15.0 + 1.0) * 0.001 * SR);
        double lag = measureLag(w, 0, lo, hi);
        double predicted = predictedDelay(0, 20.0 * 0.001 * SR, 4.0 * 0.001 * SR, 0.8, SR);
        check("wet tap sits at the base delay (20 ms +/- depth)", Math.abs(lag - predicted) < 1.5,
                "measured " + fmt(lag) + " samples = " + fmt(lag / SR * 1000) + " ms, predicted "
                        + fmt(predicted) + " samples");
        double tapSum = sumAbs(w, lo, hi);
        double slew = 4.0 * 0.001 * SR * (2 * Math.PI * 0.8 / SR);   // delay change per output sample
        double doppler = 1.0 / (1.0 - slew);
        check("wet tap amplitude follows the moving-delay resampling ratio", near(tapSum, doppler, 0.01 * doppler),
                "sum |taps| = " + fmt4(tapSum) + ", expected " + fmt4(doppler) + " (slew " + fmt4(slew) + " sample/sample)");

        int burst = (int) (0.1 * SR);
        int total = 20 * burst;
        ch.reset();
        ch.setParam(0, 4.0);
        ch.setParam(1, 0.5);
        ch.setParam(2, 1.0);
        float[] tl = new float[total];
        float[] tr = new float[total];
        for (int k = 0; k < 20; k++) { tl[k * burst] = 1f; tr[k * burst] = 1f; }
        ch.process(tl, tr, total);
        double minLag = Double.MAX_VALUE, maxLag = -Double.MAX_VALUE, worstErr = 0.0;
        for (int k = 0; k < 20; k++) {
            int k0 = k * burst;
            double meas = measureLag(tl, k0, k0 + lo, k0 + hi);
            double pred = predictedDelay(k0, 20.0 * 0.001 * SR, 4.0 * 0.001 * SR, 0.5, SR);
            worstErr = Math.max(worstErr, Math.abs(meas - pred));
            minLag = Math.min(minLag, meas);
            maxLag = Math.max(maxLag, meas);
        }
        double sweep = maxLag - minLag;
        double expectedSweep = 2 * 4.0 * 0.001 * SR;
        check("delay is modulated (lag sweep over 1 LFO cycle)", Math.abs(sweep - expectedSweep) < 0.05 * expectedSweep,
                "sweep " + fmt(sweep) + " samples = " + fmt(sweep / SR * 1000) + " ms, expected "
                        + fmt(expectedSweep) + " samples");
        check("each tap matches the LFO prediction", worstErr < 1.5,
                "max error " + fmt(worstErr) + " samples (" + fmt(worstErr / SR * 1e6) + " us)");

        float[] noise = noise(4096);
        ch.reset();
        ch.setParam(0, 4.0);
        ch.setParam(1, 0.8);
        ch.setParam(2, 0.4);
        float[] a1 = noise.clone();
        float[] a2 = noise.clone();
        ch.process(a1, a2, 4096);
        double r1 = residualEnergy(noise, a1, 0.4);
        ch.reset();
        ch.setParam(2, 0.8);
        float[] b1 = noise.clone();
        float[] b2 = noise.clone();
        ch.process(b1, b2, 4096);
        double r2 = residualEnergy(noise, b1, 0.8);
        check("wet energy scales as mix^2 (0.4 vs 0.8 -> ratio 0.25)", near(r1 / r2, 0.25, 1e-4),
                "E_wet(0.4)/E_wet(0.8) = " + fmt4(r1 / r2));

        ch.reset();
        ch.setParam(2, 1.0);
        float[] q1 = noise.clone();
        float[] q2 = noise.clone();
        ch.process(q1, q2, 4096);
        double maxDiff = 0.0;
        for (int i = 0; i < 4096; i++) maxDiff = Math.max(maxDiff, Math.abs(q1[i] - q2[i]));
        check("L/R LFOs are 90 degrees apart (mono in -> different wet)", maxDiff > 1e-3,
                "max |wetL - wetR| = " + fmt4(maxDiff));
        check("chorus output is finite", countBad(q1, 0, 4096) == 0 && countBad(q2, 0, 4096) == 0,
                countBad(q1, 0, 4096) + " bad samples");
    }

    static double residualEnergy(float[] x, float[] y, double mix) {
        double e = 0.0;
        for (int i = 0; i < x.length; i++) {
            double w = y[i] - (1.0 - mix) * x[i];
            e += w * w;
        }
        return e;
    }

    /** Delay of the tap at output time (t0 + delay), solved by fixed-point iteration. */
    static double predictedDelay(double t0Samples, double base, double depthSamples, double rate, double sr) {
        double d = base;
        for (int it = 0; it < 8; it++) {
            double t = (t0Samples + d) / sr;
            d = base + depthSamples * Math.sin(2 * Math.PI * rate * t);
        }
        return d;
    }

    /** Sub-sample delay estimate of one impulse response: energy centroid of the peak. */
    static double measureLag(float[] y, int k0, int lo, int hi) {
        int best = -1;
        double bv = 0.0;
        for (int k = Math.max(0, lo); k < Math.min(hi, y.length); k++) {
            double v = Math.abs(y[k]);
            if (v > bv) { bv = v; best = k; }
        }
        if (best < 0 || bv <= 0) return -1;
        double num = 0.0, den = 0.0;
        for (int k = best - 3; k <= best + 3; k++) {
            if (k < 0 || k >= y.length) continue;
            double v = Math.abs(y[k]);
            num += v * (k - k0 + 1);
            den += v;
        }
        return den <= 0 ? -1 : num / den;
    }

    // ================================================================== compressor

    static void testCompressor() {
        section("CompressorPlugin - peak / RMS compressor");
        CompressorPlugin cp = new CompressorPlugin();
        check("paramCount == 5", cp.paramCount() == 5, "got " + cp.paramCount());
        check("id / name", "builtin.compressor".equals(cp.id()) && "Compressor".equals(cp.name()),
                cp.id() + " / " + cp.name());
        cp.prepare(SR, 512);

        double inPeak = dbToAmp(-6.0);
        cp.setParam(0, -20.0);
        cp.setParam(1, 4.0);
        cp.setParam(2, 0.5);
        cp.setParam(3, 300.0);
        cp.setParam(4, 0.0);
        cp.reset();
        float[] l = sine(inPeak, 440.0, SR, 1.0);
        float[] r = l.clone();
        cp.process(l, r, l.length);
        double outPeak = maxAbs(l, SR / 2, l.length);
        double grDb = 20 * Math.log10(outPeak / inPeak);
        double expected = (-6.0 - (-20.0)) * (1.0 - 1.0 / 4.0);
        check("-6 dBFS in, threshold -20, ratio 4 -> 10.48 dB reduction",
                Math.abs(-grDb - expected) < 0.5,
                "measured gain " + fmt(grDb) + " dB (reduction " + fmt(-grDb) + " dB), expected -"
                        + fmt(expected) + " dB; out peak " + fmt4(outPeak));

        cp.reset();
        cp.setParam(4, 6.0);
        cp.setParam(1, 4.0);
        float[] q = sine(dbToAmp(-30.0), 440.0, SR, 0.5);
        float[] qr = q.clone();
        cp.process(q, qr, q.length);
        double worst = 0.0;
        for (int i = 0; i < q.length; i++) {
            double expect = dbToAmp(-30.0) * dbToAmp(6.0) * Math.sin(2 * Math.PI * 440.0 * i / SR);
            worst = Math.max(worst, Math.abs(q[i] - expect));
        }
        check("below threshold: no reduction, makeup is exact (+6 dB)",
                worst < 1e-6 && cp.currentGainReductionDb() == 0.0,
                "max error " + worst + ", GR=" + fmt(cp.currentGainReductionDb()) + " dB");

        cp.setParam(4, 0.0);
        cp.setParam(0, -20.0);
        cp.setParam(1, 4.0);
        cp.setParam(2, 20.0);
        cp.setParam(3, 100.0);
        cp.reset();
        double target = (-6.0206 - (-20.0)) * (1.0 - 1.0 / 4.0);
        double[] atk = runStep(cp, 0.5, new double[] { 20, 40, 80 });
        check("attack: GR rises toward the target",
                atk[1] > atk[0] && atk[2] > atk[1],
                "GR(20ms)=" + fmt(atk[0]) + " GR(40ms)=" + fmt(atk[1]) + " GR(80ms)=" + fmt(atk[2])
                        + " dB, target " + fmt(target) + " dB");
        check("attack: 63% of the target after one attack time", atk[0] > 0.58 * target && atk[0] < 0.70 * target,
                "GR(20ms)/target = " + fmt(atk[0] / target));

        cp.setParam(2, 0.5);
        cp.setParam(3, 100.0);
        cp.reset();
        double[] rel = runRelease(cp, 0.5, 0.0001, 100.0);
        check("release: GR falls toward zero", rel[0] < rel[1] && rel[0] > 0.0,
                "GR(drop)=" + fmt(rel[1]) + " dB, GR(+100ms)=" + fmt(rel[0]) + " dB");
        check("release: 63% decayed after one release time",
                rel[0] > 0.30 * rel[1] && rel[0] < 0.45 * rel[1],
                "GR(100ms)/GR(drop) = " + fmt(rel[0] / rel[1]));

        double g2 = settledGr(2.0, -12.0);
        double g8 = settledGr(8.0, -12.0);
        check("higher ratio -> more reduction", g8 > g2 * 1.5,
                "GR(2:1)=" + fmt(g2) + " dB, GR(8:1)=" + fmt(g8) + " dB at -12 dBFS");

        cp.setParam(1, 4.0);
        cp.setParam(2, 1.0);
        cp.setParam(3, 300.0);
        cp.reset();
        float[] sl = new float[SR / 2];
        float[] sr2 = new float[SR / 2];
        for (int i = 0; i < sl.length; i++) {
            sl[i] = (float) (0.8 * Math.sin(2 * Math.PI * 220.0 * i / SR));
            sr2[i] = (float) (0.4 * Math.sin(2 * Math.PI * 220.0 * i / SR));
        }
        cp.process(sl, sr2, sl.length);
        double ratio = 0.0, maxErr = 0.0;
        for (int i = SR / 4; i < sl.length; i++) {
            if (Math.abs(sr2[i]) > 1e-6) ratio = sl[i] / sr2[i];
            maxErr = Math.max(maxErr, Math.abs(sl[i] - 2.0 * sr2[i]));
        }
        check("stereo-linked: both channels get the same gain",
                Math.abs(ratio - 2.0) < 1e-5 && maxErr < 1e-5,
                "outL/outR = " + fmt(ratio) + " (input ratio 2.0), max error " + maxErr);

        check("compressor output is finite", countBad(sl, 0, sl.length) == 0, countBad(sl, 0, sl.length) + " bad");
        cp.setParam(0, -100.0);
        check("threshold clamps at -60 dB", cp.getParam(0) == -60.0, "threshold=" + cp.getParam(0));
        cp.setParam(1, 0.5);
        check("ratio clamps at 1", cp.getParam(1) == 1.0, "ratio=" + cp.getParam(1));
    }

    /**
     * Attack measurement: reset, then feed a constant level and read the gain reduction at each
     * millisecond mark. A constant level makes the timing maths exact (one-pole in dB).
     */
    static double[] runStep(CompressorPlugin cp, double amp, double[] marksMs) {
        double[] out = new double[marksMs.length];
        for (int i = 0; i < marksMs.length; i++) {
            cp.reset();
            int upto = (int) (marksMs[i] * 0.001 * SR);
            float[] a = new float[upto];
            float[] b = new float[upto];
            for (int k = 0; k < upto; k++) { a[k] = (float) amp; b[k] = (float) amp; }
            cp.process(a, b, upto);
            out[i] = cp.currentGainReductionDb();
        }
        return out;
    }

    /**
     * Release measurement: settle at {@code hi}, then drop to {@code lo} and read the gain
     * reduction right after the drop and again one release time later.
     * Returns {grAfterMark, grAtDrop}.
     */
    static double[] runRelease(CompressorPlugin cp, double hi, double lo, double markMs) {
        int settle = 4 * SR;
        float[] a = new float[settle];
        float[] b = new float[settle];
        for (int i = 0; i < settle; i++) { a[i] = (float) hi; b[i] = (float) hi; }
        cp.process(a, b, settle);
        double grDrop = cp.currentGainReductionDb();
        int upto = (int) (markMs * 0.001 * SR);
        float[] c = new float[upto];
        float[] d = new float[upto];
        for (int i = 0; i < upto; i++) { c[i] = (float) lo; d[i] = (float) lo; }
        cp.process(c, d, upto);
        return new double[] { cp.currentGainReductionDb(), grDrop };
    }

    static double settledGr(double ratio, double levelDb) {
        CompressorPlugin cp = new CompressorPlugin();
        cp.prepare(SR, 512);
        cp.setParam(0, -20.0);
        cp.setParam(1, ratio);
        cp.setParam(2, 0.5);
        cp.setParam(3, 300.0);
        cp.setParam(4, 0.0);
        cp.reset();
        float[] l = sine(dbToAmp(levelDb), 440.0, SR, 1.0);
        float[] r = l.clone();
        cp.process(l, r, l.length);
        return cp.currentGainReductionDb();
    }

    // ================================================================== chain

    static void testChain() {
        section("PluginChain - order, bypass, snapshots");
        PluginChain chain = new PluginChain();
        Probe a = new Probe("A", 2);
        Probe b = new Probe("B", 2);
        Probe c = new Probe("C", 3);
        chain.add(a);
        chain.add(b);
        chain.add(c);
        check("add/size/get keep insertion order",
                chain.size() == 3 && chain.get(0) == a && chain.get(1) == b && chain.get(2) == c,
                "size=" + chain.size());
        check("get out of range -> null", chain.get(9) == null && chain.get(-1) == null, "ok");

        Probe.log.setLength(0);
        float[] l = new float[8];
        float[] r = new float[8];
        chain.process(l, r, 8);
        check("process runs slots front to back", "ABC".equals(Probe.log.toString()),
                "order " + Probe.log);

        chain.setBypass(1, true);
        Probe.log.setLength(0);
        chain.process(l, r, 8);
        check("bypassed slot is skipped", "AC".equals(Probe.log.toString()), "order " + Probe.log);
        check("isBypassed", chain.isBypassed(1) && !chain.isBypassed(0), "ok");

        chain.setBypass(1, false);
        check("move(0,2) -> B,C,A", chain.move(0, 2) && chain.get(0) == b && chain.get(1) == c && chain.get(2) == a,
                "order " + ids(chain));
        Probe.log.setLength(0);
        chain.process(l, r, 8);
        check("order after move", "BCA".equals(Probe.log.toString()), "order " + Probe.log);
        check("move rejects bad indices", !chain.move(-1, 0) && !chain.move(0, 5), "ok");

        Plugin removed = chain.remove(1);
        check("remove returns the plugin and shifts the rest",
                removed == c && chain.size() == 2 && chain.get(0) == b && chain.get(1) == a,
                "removed " + (removed == null ? "null" : removed.id()) + ", order " + ids(chain));
        check("remove out of range -> null", chain.remove(5) == null, "ok");

        b.setParam(0, 0.75);
        b.setParam(1, -2.0);
        double[] snap = chain.snapshotParams(0);
        check("snapshotParams copies current values",
                snap != null && snap.length == 2 && near(snap[0], 0.75, 1e-12) && near(snap[1], -2.0, 1e-12),
                "snapshot " + arr(snap));
        b.setParam(0, 0.1);
        check("snapshot is a copy, not a view", snap[0] == 0.75, "snapshot[0] still " + snap[0]);
        check("snapshotParams out of range -> null", chain.snapshotParams(7) == null, "ok");

        chain.prepareAll(SR, 256);
        chain.resetAll();
        check("prepareAll / resetAll reach every slot",
                a.prepares == 1 && b.prepares == 1 && a.resets == 1 && b.resets == 1,
                "A prepare=" + a.prepares + " reset=" + a.resets + " B prepare=" + b.prepares);
        check("indexOf / byId", chain.indexOf("probe.B") == 0 && chain.byId("probe.A") == a,
                "indexOf(B)=" + chain.indexOf("probe.B"));

        PluginChain real = new PluginChain();
        real.add(new CompressorPlugin());
        real.add(new ReverbPlugin());
        real.add(new ChorusPlugin());
        real.prepareAll(SR, 512);
        float[] x = noise(512);
        float[] y = noise(512);
        float[] expect = x.clone();
        float[] expectR = y.clone();
        real.process(x, y, 512);
        boolean changed = false;
        for (int i = 0; i < 512; i++) if (Math.abs(x[i] - expect[i]) > 1e-9) changed = true;
        check("real plugin chain alters the signal", changed, "changed=" + changed);
        for (int i = 0; i < 3; i++) real.setBypass(i, true);
        float[] x2 = expect.clone();
        float[] y2 = expectR.clone();
        real.process(x2, y2, 512);
        double d = 0.0;
        for (int i = 0; i < 512; i++) d = Math.max(d, Math.abs(x2[i] - expect[i]));
        check("fully bypassed chain is bit-transparent", d == 0.0, "max diff " + d);
        check("snapshotAll covers every slot", real.snapshotAll().length == 3
                && real.snapshotAll()[0].length == 5 && real.snapshotAll()[1].length == 3,
                "rows/cols ok");
    }

    static void testRegistry() {
        section("PluginRegistry - extension point");
        check("builtins registered", PluginRegistry.size() >= 3, "size=" + PluginRegistry.size());
        Plugin p = PluginRegistry.create("builtin.reverb");
        check("create by id", p != null && p instanceof ReverbPlugin, p == null ? "null" : p.id());
        check("unknown id -> null", PluginRegistry.create("nope.nope") == null, "ok");
        PluginRegistry.register("test.gain", new PluginRegistry.Factory() {
            public Plugin create() { return new Probe("G", 1); }
        });
        check("register + create custom plugin", PluginRegistry.create("test.gain") != null,
                "size=" + PluginRegistry.size() + " ids=" + join(PluginRegistry.ids()));
    }

    // ================================================================== probe plugin

    /** Minimal recording plugin used to observe chain order. */
    static class Probe implements Plugin {
        static final StringBuilder log = new StringBuilder();
        final String tag;
        final double[] p;
        int prepares;
        int resets;

        Probe(String tag, int params) {
            this.tag = tag;
            this.p = new double[params];
            for (int i = 0; i < params; i++) p[i] = i;
        }

        public String id() { return "probe." + tag; }

        public String name() { return "Probe " + tag; }

        public int paramCount() { return p.length; }

        public String paramName(int i) { return "p" + i; }

        public double paramMin(int i) { return -10.0; }

        public double paramMax(int i) { return 10.0; }

        public double paramDefault(int i) { return 0.0; }

        public void setParam(int i, double v) {
            if (i >= 0 && i < p.length) p[i] = v;
        }

        public double getParam(int i) { return (i >= 0 && i < p.length) ? p[i] : 0.0; }

        public void prepare(int sampleRate, int maxFrames) { prepares++; }

        public void reset() { resets++; }

        public void process(float[] l, float[] r, int frames) { log.append(tag); }
    }

    // ================================================================== helpers

    static void section(String s) {
        System.out.println("--- " + s + " ---");
    }

    static void check(String name, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("  [PASS] " + name + (detail == null || detail.isEmpty() ? "" : "   (" + detail + ")"));
        } else {
            failures++;
            System.out.println("  [FAIL] " + name + "   (" + detail + ")");
        }
    }

    static float[] impulse(int n) {
        float[] a = new float[n];
        a[0] = 1.0f;
        return a;
    }

    static float[] sine(double amp, double freq, int sr, double seconds) {
        int n = (int) (seconds * sr);
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = (float) (amp * Math.sin(2 * Math.PI * freq * i / sr));
        return a;
    }

    /** Deterministic pseudo-random noise in [-0.5, 0.5]. */
    static float[] noise(int n) {
        float[] a = new float[n];
        int s = 12345;
        for (int i = 0; i < n; i++) {
            s = s * 1103515245 + 12345;
            a[i] = (float) (((s >>> 8) & 0xFFFF) / 65535.0 - 0.5);
        }
        return a;
    }

    static double energy(float[] a, int from, int to) {
        if (a == null) return 0.0;
        int f = Math.max(0, from);
        int t = Math.min(a.length, to);
        double e = 0.0;
        for (int i = f; i < t; i++) e += (double) a[i] * a[i];
        return e;
    }

    static double maxAbs(float[] a, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(a.length, to);
        double m = 0.0;
        for (int i = f; i < t; i++) m = Math.max(m, Math.abs(a[i]));
        return m;
    }

    static double sumAbs(float[] a, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(a.length, to);
        double s = 0.0;
        for (int i = f; i < t; i++) s += Math.abs(a[i]);
        return s;
    }

    static double minOf(float[] a, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(a.length, to);
        double m = Double.MAX_VALUE;
        for (int i = f; i < t; i++) m = Math.min(m, a[i]);
        return m;
    }

    static int countBad(float[] a, int from, int to) {
        int n = 0;
        for (int i = Math.max(0, from); i < Math.min(a.length, to); i++) {
            if (a[i] != a[i] || a[i] == Float.POSITIVE_INFINITY || a[i] == Float.NEGATIVE_INFINITY) n++;
        }
        return n;
    }

    /** Schroeder backward integration, T20 extrapolated to RT60. */
    static double rt60(float[] h, int from, int to) {
        int n = Math.min(h.length, to) - from;
        if (n <= 0) return -1;
        double[] c = new double[n + 1];
        for (int i = n - 1; i >= 0; i--) {
            double v = h[from + i];
            c[i] = c[i + 1] + v * v;
        }
        if (c[0] <= 0) return -1;
        int i5 = -1, i35 = -1;
        for (int i = 0; i < n; i++) {
            double db = 10 * Math.log10(Math.max(c[i], 1e-300) / c[0]);
            if (i5 < 0 && db <= -5.0) i5 = i;
            if (i35 < 0 && db <= -35.0) i35 = i;
        }
        if (i5 < 0 || i35 < 0 || i35 <= i5) return -1;
        return (i35 - i5) / (double) SR * 3.0;
    }

    static double dbToAmp(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }

    static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.4f", v);
    }

    static String fmt4(double v) {
        return String.format(java.util.Locale.US, "%.6f", v);
    }

    static String arr(double[] a) {
        if (a == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fmt(a[i]));
        }
        return sb.append("]").toString();
    }

    static String dbList(double[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fmt(10 * Math.log10(Math.max(a[i], 1e-30))));
        }
        return sb.toString();
    }

    static String ids(PluginChain c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.size(); i++) {
            if (i > 0) sb.append(",");
            Plugin p = c.get(i);
            sb.append(p == null ? "null" : p.id());
        }
        return sb.toString();
    }

    static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(a[i]);
        }
        return sb.toString();
    }
}
