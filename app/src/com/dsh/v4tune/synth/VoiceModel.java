package com.dsh.v4tune.synth;

import java.util.LinkedHashMap;

/**
 * A built-in singer ("歌姬"). Purely parametric: no sampled voicebank data is bundled.
 * Formant targets come from PhonemeTable and are scaled by the values below.
 */
public class VoiceModel {
    public String id = "";
    public String name = "";          // display name, e.g. 洛天依 V4 萌
    public String nameEn = "";
    public String nameJa = "";
    public int lang = 2;              // 0 = JPN, 1 = ENG, 2 = CHN

    /**
     * The voicebank name in the language the UI is currently using, falling back to whatever the
     * pack does provide. Packs made before the Japanese name existed simply return the base name.
     *
     * @param uiLang 0 = Chinese, 1 = English, 2 = Japanese (same numbering as {@code i18n.L})
     */
    public String nameFor(int uiLang) {
        String v = null;
        if (uiLang == 1) v = nameEn;
        else if (uiLang == 2) v = nameJa;
        if (v == null || v.trim().isEmpty()) v = (uiLang == 1) ? (nameJa != null && !nameJa.trim().isEmpty() ? nameJa : name) : name;
        if (v == null || v.trim().isEmpty()) v = nameEn;
        return (v == null || v.trim().isEmpty()) ? id : v;
    }
    public String compID = "";        // id written into vsqx vVoice/compID (informational)

    /** vocal tract length scaling applied to every formant (1.0 = the reference table) */
    public double formantScale = 1.0;
    public double f1Mul = 1.0, f2Mul = 1.0, f3Mul = 1.0, f4Mul = 1.0, f5Mul = 1.0;
    /** spectral tilt bias, -1..1 (negative = darker) */
    public double tiltBias = 0.0;
    /** default breathiness added on top of BRE, 0..1 */
    public double breathBias = 0.0;
    /** glottal open-quotient bias, -0.2..0.2 */
    public double oqBias = 0.0;
    /** default vibrato used when the note has none: rate Hz, depth in cents */
    public double vibratoRate = 5.5;
    public double vibratoDepthCents = 0.0;
    public double vibratoDelayMs = 250;
    /** overall level trim */
    public double gain = 1.0;
    /** gentle fixed EQ shelf to give the voice its identity: {freqHz, gainDb} pairs, applied as a 1st order shelf chain */
    public double[] shelfFreqs = new double[0];
    public double[] shelfGains = new double[0];
    /** consonant strength multiplier (singer articulates more/less) */
    public double consonantGain = 1.0;
    /** breath noise level at phrase starts/ends */
    public double breathSoundAmount = 0.35;
    public String description = "";

    /** optional per-phoneme formant overrides: symbol -> {f1,f2,f3} absolute Hz */
    public final LinkedHashMap<String, double[]> overrides = new LinkedHashMap<String, double[]>();

    /**
     * Measured harmonic voice data (v4voice/2 packs): the shared log-spaced frequency grid (Hz)
     * plus, per CHN phoneme symbol, one or more spectral bands taken from the real voicebank
     * FRM2 frames. {@code db[i]} are the harmonic magnitudes (dB, relative) at
     * {@code harmonicFreqs[i]}; a note picks the band whose f0 is closest.
     */
    public double[] harmonicFreqs = new double[0];
    public final LinkedHashMap<String, HarmonicBand[]> harmonics =
            new LinkedHashMap<String, HarmonicBand[]>();

    public static class HarmonicBand {
        public double f0;          // centre f0 of the frames this band was measured from
        public double[] db;        // same length as harmonicFreqs, relative dB (max = 0)
        public double dyn = 0.5;   // loudness variant: 0 = softest, 1 = loudest (measured, see the converter)
        /** real consecutive FRM2 frames of this cell (each a dB curve on the shared grid), or null */
        public double[][] seq;
        /** relative level of each frame in {@link #seq}, dB (measured swell of the original take) */
        public double[] lvl;

        /** interpolated frame curve at a position 0..1 across the note, or the mean curve when absent */
        public double[] curveAt(double pos, double[] out) {
            if (seq == null || seq.length < 2) {
                int n = Math.min(out.length, db.length);
                for (int i = 0; i < out.length; i++) out[i] = (i < n) ? db[i] : -90.0;
                return out;
            }
            double f = (pos < 0 ? 0 : (pos > 1 ? 1 : pos)) * (seq.length - 1);
            int i0 = (int) f;
            if (i0 > seq.length - 2) i0 = seq.length - 2;
            double w = f - i0;
            double[] a = seq[i0], b = seq[i0 + 1];
            for (int k = 0; k < out.length; k++) {
                double va = (k < a.length) ? a[k] : -90.0;
                double vb = (k < b.length) ? b[k] : va;
                out[k] = va + (vb - va) * w;
            }
            return out;
        }

        /** relative frame level (dB) at a position 0..1 across the note */
        public double levelAt(double pos) {
            if (lvl == null || lvl.length == 0) return 0.0;
            if (lvl.length == 1) return lvl[0];
            double f = (pos < 0 ? 0 : (pos > 1 ? 1 : pos)) * (lvl.length - 1);
            int i0 = (int) f;
            if (i0 > lvl.length - 2) i0 = lvl.length - 2;
            double w = f - i0;
            return lvl[i0] + (lvl[i0 + 1] - lvl[i0]) * w;
        }
    }

    /** optional measured fricative noise spectra (same grid as harmonicFreqs) */
    public double[] noiseFreqs = new double[0];
    public final LinkedHashMap<String, double[]> noiseDb = new LinkedHashMap<String, double[]>();

    /** nearest measured band for a note pitch, or null when this voice has no data for the symbol */
    public HarmonicBand bandFor(String symbol, double f0) {
        return bandFor(symbol, f0, 0.5);
    }

    /**
     * Pick the measured spectrum for a phoneme at a pitch AND a dynamic.
     *
     * <p>A voicebank stores frames at many loudness levels and the spectrum really changes with
     * effort, so each pitch band is stored as up to three loudness variants (dyn 0 / 0.5 / 1).
     * We first choose the pitch band closest to the note's f0 and then, among the variants that
     * share that f0, the one whose measured loudness matches the note's DYN. That is why moving
     * the DYN curve now changes the timbre and not only the level.</p>
     */
    public HarmonicBand bandFor(String symbol, double f0, double dyn) {
        HarmonicBand[] bs = harmonics.get(symbol);
        if (bs == null || bs.length == 0) return null;
        HarmonicBand near = bs[0];
        double bd = Math.abs(Math.log(Math.max(1e-6, near.f0) / f0));
        for (int i = 1; i < bs.length; i++) {
            double d = Math.abs(Math.log(Math.max(1e-6, bs[i].f0) / f0));
            if (d < bd) { bd = d; near = bs[i]; }
        }
        HarmonicBand best = near;
        double bdd = Math.abs(near.dyn - dyn);
        for (int i = 0; i < bs.length; i++) {
            if (Math.abs(Math.log(Math.max(1e-6, bs[i].f0) / Math.max(1e-6, near.f0))) > 0.02) continue;
            double d = Math.abs(bs[i].dyn - dyn);
            if (d < bdd) { bdd = d; best = bs[i]; }
        }
        return best;
    }

    public double[] override(String sym) { return overrides.get(sym); }
}
