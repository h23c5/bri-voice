package com.dsh.v4tune.synth;

import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.TimeUtil;
import com.dsh.v4tune.phoneme.ChineseG2P;
import com.dsh.v4tune.phoneme.JapaneseG2P;
import com.dsh.v4tune.phoneme.PhonemeDef;
import com.dsh.v4tune.phoneme.PhonemeTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Offline singing synthesizer: score -> PCM.
 *
 * Signal path per sample: glottal source + aspiration noise -> Klatt cascade formant
 * filter (+ parallel fricative branch) -> amplitude envelope -> master gain.
 */
public class Renderer {

    public static class Cfg {
        public int sampleRate = 44100;
        public double masterGain = 0.85;
        public boolean breathSounds = true;
        public double tailSec = 0.25;
    }

    /** resolved phoneme segment inside the output buffer */
    static class Seg {
        int s0, s1;
        /** this segment starts right after the previous note: no fresh attack */
        boolean legatoIn;
        /** the next note starts right after this one: no long release */
        boolean legatoOut;
        PhonemeDef ph;
        boolean vowel;
    }

    static final double CTRL_MS = 1.0;
    /** level trim for the measured-harmonic path (see HarmonicVoice.TARGET_RMS) */
    static final double HARMONIC_GAIN = 0.20;
    /** level of the measured-noise path, matched to the formant path it replaces */
    // Conservative level: the measured path follows the formant path's RMS, but its spectrum is much
    // brighter (a real /s/ lives at 5-8 kHz) and at parity it reads as a hiss in front of the note.
    static final float NS_GAIN = 0.55f;
    /**
     * Frame-sequence synthesis (real measured frames concatenated by overlap-add) instead of the
     * single-period table of {@link HarmonicVoice}. Off falls back to the older path, so the two can
     * be compared on the device.
     */
    public static boolean frameSequence = false;
    /** level of the frame path, matched to the harmonic table path it replaces */
    static final double FRAME_GAIN = 0.18;   // measured: matches the table path level   // measured: matches the table path level
    /**
     * Diagnostic switch: when false, unvoiced consonants fall back to the generic formant-filtered
     * noise they used before the measured spectra were applied. Used by the A/B measurements in
     * tools/render_test and handy when tuning.
     */
    public static boolean measuredNoise = false;
    /** level trim for the measured fricative path: the settings slider scales it (1.0 = 100%) */
    public static float noiseGain = 1.0f;

    static double clamp(double v, double a, double b) { return v < a ? a : (v > b ? b : v); }

    static double param(Part p, String id, long tick, double dflt) {
        ControlCurve c = p.ctrlOrNull(id);
        if (c == null || c.isEmpty()) return dflt;
        return c.valueAt(tick);
    }

    // ------------------------------------------------------------------ entry

    public static float[] renderPart(Song song, Part part, VoiceModel vm, Cfg cfg) {
        return renderPart(song, part, vm, cfg, Integer.MAX_VALUE);
    }

    /**
     * Renders the part up to {@code limitFrames}. The synthesis always starts at frame 0 so every
     * filter and envelope sees the same history as a full render; only the work past the limit is
     * skipped. This is what lets playback start after the first second instead of the whole song.
     */
    public static float[] renderPart(Song song, Part part, VoiceModel vm, Cfg cfg, int limitFrames) {
        final int fs = cfg.sampleRate;
        final double partStartSec = TimeUtil.tickToSec(song, part.pos);
        // The stored duration is only a hint: a part created in the editor (or written by another
        // program) can hold notes past its own dur, and sizing the buffer from dur alone made every
        // write past the end throw ArrayIndexOutOfBoundsException. Cover the notes too.
        long endTick = part.end();                       // already absolute: pos + dur
        // Note positions are relative to the part, so part.pos has to be added back. Without it the
        // end was under-reported by exactly part.pos - measured on HUG, where the notes really end
        // at 150.3 s but p.end() only says 142.3 s (pos = 7680 ticks = 8 s), the last eight seconds
        // of every part were never rendered and both playback and WAV export went silent there.
        for (Note nt : part.notes) endTick = Math.max(endTick, part.pos + nt.pos + nt.dur);
        final double partEndSec = TimeUtil.tickToSec(song, endTick);
        // The buffer must cover the absolute end of the part. Note times in a vsqx are absolute
        // ticks (a part's notes can start well after its own pos and the synthesis writes them at
        // that absolute position), so subtracting partStartSec here made every part render exactly
        // part.pos seconds too little. On a project whose parts begin late that cut the tail off
        // completely: measured on HUG (pos = 7680 ticks = 8 s) part 0 rendered 134.5 s of a 142.3 s
        // part, so everything past ~134 s was silent in both playback and WAV export.
        int n = (int) Math.ceil((partEndSec + cfg.tailSec) * fs);
        if (limitFrames > 0 && n > limitFrames) n = limitFrames;
        if (n < 1) n = 1;
        float[] out = new float[n];

        ArrayList<Note> notes = new ArrayList<Note>(part.notes);
        Collections.sort(notes, new Comparator<Note>() {
            public int compare(Note a, Note b) { return Long.compare(a.pos, b.pos); }
        });
        if (notes.isEmpty()) return out;

        ArrayList<Seg> segs = buildSegments(song, part, notes, vm, fs, partStartSec);
        if (segs.isEmpty()) return out;

        final int ctlStep = Math.max(1, (int) (fs * CTRL_MS / 1000.0));
        final int ctlN = n / ctlStep + 2;
        float[] f0c = new float[ctlN];
        for (int ci = 0; ci < ctlN; ci++) {
            int s = Math.min(n - 1, ci * ctlStep);
            f0c[ci] = (float) pitchHz(song, part, notes, vm, s / (double) fs, partStartSec);
        }

        VoiceSource src = new VoiceSource();
        src.setSampleRate(fs);
        src.reset();
        FormantFilter ff = new FormantFilter();
        ff.setSampleRate(fs);
        ff.reset();
        // singer-pack vocal tract EQ (measured from the user's own voicebank)
        // Real-voice harmonic source (v4voice packs carrying measured FRM2 spectra): when present it
        // replaces the formant model for voiced phonemes; the formant path keeps running for the
        // unvoiced consonants and the two are cross-faded so nothing clicks.
        HarmonicVoice hv = null;
        // measured spectra for unvoiced consonants: the pack ships the real band levels of every
        // fricative it measured, and they used to be thrown away (the harmonic path only runs for
        // voiced sounds), so /s/ and /x/ both came out as the same generic noise
        NoiseShape ns = null;
        FrameVoice fv = null;
        double nsRmsRef = 0.0, nsRmsNew = 0.0;                       // running levels for the match
        double nsMix = 0.0;                                          // 0 = formant path, 1 = measured
        boolean nsWasSilent = true;                                  // seed the level match on entry
        final double nsCoef = 1.0 - Math.exp(-1.0 / (0.030 * fs));   // 30 ms
        final double nsXCoef = 1.0 - Math.exp(-1.0 / (0.005 * fs));  // 5 ms path switch
        if (vm.harmonicFreqs != null && vm.harmonicFreqs.length > 4 && vm.harmonics.size() > 0) {
            hv = new HarmonicVoice();
            ns = new NoiseShape();
            fv = new FrameVoice();
            fv.setSampleRate(fs);
            ns.setSampleRate(fs);
            hv.setSampleRate(fs);
            hv.reset();
        }
        double hw = 0;                                  // 0 = formant path, 1 = harmonic path
        double[] envMix = (hv != null) ? new double[vm.harmonicFreqs.length] : null;
        double[] curveA = (hv != null) ? new double[vm.harmonicFreqs.length] : null;
        double[] curveB = (hv != null) ? new double[vm.harmonicFreqs.length] : null;
        double seqGain = 1.0;      // measured intra-note level swell of the stored frame sequence
        // octaves above 1 kHz for every grid point, so BRI can tilt the measured spectrum
        double[] octAbove1k = null;
        if (hv != null) {
            octAbove1k = new double[vm.harmonicFreqs.length];
            for (int k = 0; k < octAbove1k.length; k++) {
                double f = vm.harmonicFreqs[k];
                octAbove1k[k] = f > 1000.0 ? Math.log(f / 1000.0) / Math.log(2.0) : 0.0;
            }
        }

        // The pack shelf is the average spectral envelope measured from the user's own voicebank.
        // Applied raw it also changes the overall level and over-dips the presence band, so the
        // curve is centred on 0 dB (level preserved) and damped to 60% before it is used as colour.
        EqFilter eq = null;
        if (hv == null && vm.shelfFreqs != null && vm.shelfGains != null && vm.shelfFreqs.length > 0) {
            int sn = Math.min(vm.shelfFreqs.length, vm.shelfGains.length);
            double[] g = new double[sn];
            double mean = 0;
            for (int i = 0; i < sn; i++) mean += vm.shelfGains[i];
            mean /= Math.max(1, sn);
            for (int i = 0; i < sn; i++) g[i] = (vm.shelfGains[i] - mean) * 0.6;
            eq = new EqFilter();
            eq.setSampleRate(fs);
            eq.setCurve(vm.shelfFreqs, g);
            if (eq.isBypassed()) eq = null;
        }

        double[] fm = new double[5], bw = new double[5], fmT = new double[5], bwT = new double[5];
        int segIdx = 0;
        PhonemeDef cur = segs.get(0).ph;
        for (int k = 0; k < 5; k++) { fm[k] = formantOf(cur, k, vm); bw[k] = bwOf(cur, k); fmT[k] = fm[k]; bwT[k] = bw[k]; }

        double[] pv = new double[8];
        double f0 = 220;
        int lastCtl = -ctlStep;
        double smoothCtl = 1.0 - Math.exp(-(double) ctlStep / (0.005 * fs));
        double bwScale = 1.0;
        double genMul = 1.0;
        double hTarget = 0.0;

        for (int i = 0; i < n; i++) {
            while (segIdx < segs.size() - 1 && i >= segs.get(segIdx).s1) segIdx++;
            Seg sg = segs.get(segIdx);
            cur = sg.ph;

            if (i - lastCtl >= ctlStep) {
                lastCtl = i;
                double sec = i / (double) fs;
                long absTick = TimeUtil.secToTick(song, partStartSec + sec);
                sampleParams(part, absTick - part.pos, pv, vm);
                PhonemeDef next = segIdx + 1 < segs.size() ? segs.get(segIdx + 1).ph : null;
                int trans = (int) (0.035 * fs);
                int remain = sg.s1 - i;
                if (next != null && remain < trans) {
                    double w = 1.0 - remain / (double) trans;
                    blend(cur, next, w, vm, fmT, bwT);
                } else {
                    blend(cur, null, 0, vm, fmT, bwT);
                }
                for (int k = 0; k < 5; k++) {
                    fm[k] += (fmT[k] - fm[k]) * smoothCtl;
                    bw[k] += (bwT[k] - bw[k]) * smoothCtl;
                }
                bwScale = clamp(1.0 - pv[3] * 0.35, 0.55, 1.8);
                genMul = Math.pow(2.0, (pv[5] - 0.5) * 0.5);      // GEN: +-41% vocal tract length
                for (int k = 0; k < 5; k++) ff.setFormant(k, fm[k] * genMul, bw[k] * bwScale);
                ff.setFricGain(clamp(cur.noise, 0, 1));
                ff.setFricBand(0, Math.max(150, cur.noiseFc), Math.max(100, cur.noiseBw), 1.0);
                ff.setTilt(clamp((0.5 - pv[2] + vm.tiltBias) * 0.6, -1, 1));

                if (hv != null) {
                    // pick the measured band closest to the note's f0 for this phoneme (and the next one
                    // while we are inside the transition window), then blend the two spectra
                    VoiceModel.HarmonicBand b1 = vm.bandFor(cur.sym, f0);
                    VoiceModel.HarmonicBand b2 = (next != null) ? vm.bandFor(next.sym, f0) : null;
                    boolean voicedCur = isVoiced(cur);
                    double w2 = 0;
                    if (b2 != null && next != null && remain < trans) w2 = 1.0 - remain / (double) trans;
                    if (b1 != null || b2 != null) {
                        int len = vm.harmonicFreqs.length;
                        // BRI 0..1 (0.5 neutral) tilts the measured spectrum up to +-6 dB/oct above 1 kHz
                        double tiltDb = (pv[2] - 0.5) * 12.0 + vm.tiltBias * 6.0;
                        // Walk the REAL frame sequence of each band across the note (the converter
                        // stores the measured trajectory), so the timbre evolves instead of standing still.
                        double segPos = (sg.s1 > sg.s0) ? (i - sg.s0) / (double) (sg.s1 - sg.s0) : 0.0;
                        if (segPos < 0) segPos = 0; else if (segPos > 1) segPos = 1;
                        double[] c1 = (b1 != null) ? b1.curveAt(segPos, curveA) : null;
                        double[] c2 = (b2 != null) ? b2.curveAt(segPos, curveB) : null;
                        for (int k = 0; k < len; k++) {
                            double d1 = (c1 != null) ? c1[k] : ((c2 != null) ? c2[k] : -90.0);
                            double d2 = (c2 != null) ? c2[k] : d1;
                            envMix[k] = d1 + (d2 - d1) * w2;
                            if (tiltDb != 0.0 && octAbove1k != null) envMix[k] += tiltDb * octAbove1k[k];
                        }
                        double lvlDb = (b1 != null) ? b1.levelAt(segPos) : 0.0;
                        if (lvlDb > 6.0) lvlDb = 6.0; else if (lvlDb < -6.0) lvlDb = -6.0;
                        seqGain = Math.pow(10.0, lvlDb / 20.0);
                        if (fv != null) {
                            if (b1 != null) {
                                fv.setFrame(f0, vm.harmonicFreqs, envMix, voicedCur, seqGain * FRAME_GAIN);
                            } else {
                                // this phoneme was never measured: sing it with the formant path
                                fv.clear();
                            }
                        }
                        // an unvoiced consonant the bank measured: shape the noise with that spectrum
                        if (ns != null) {
                            // A fricative the bank's own SND records measured beats the generic shape:
                            // the converter names a class only when its spectrum really matches the
                            // CHN signature, so this is the bank's own noise.
                            double[] measured = voicedCur ? null : vm.noiseDb.get(cur.sym);
                            if (measured != null) {
                                double[] f = vm.noiseFreqs.length > 1 ? vm.noiseFreqs : vm.harmonicFreqs;
                                if (measured.length == f.length) ns.set(f, measured, 30.0);
                                else ns.reset();
                            } else if (!voicedCur && b1 != null) {
                                ns.set(vm.harmonicFreqs, envMix, 30.0);
                            } else {
                                ns.reset();
                            }
                        }
                        // A measured band only contains harmonics that its own frames had: below the
                        // band's f0 there is no data, and a low note would lose its fundamental.
                        // Extend the curve downwards from the band's f0 with a gentle source roll-off.
                        double anchorF = (b1 != null) ? b1.f0 : b2.f0;
                        if (anchorF > vm.harmonicFreqs[0]) {
                            double anchorDb = gridInterp(vm.harmonicFreqs, envMix, anchorF);
                            for (int k = 0; k < len; k++) {
                                double f = vm.harmonicFreqs[k];
                                if (f < anchorF) {
                                    double oct = Math.log(anchorF / f) / Math.log(2.0);
                                    envMix[k] = anchorDb - 6.0 * oct;
                                }
                            }
                        }
                        hv.setShape(vm.harmonicFreqs, envMix, f0);
                        hTarget = (b1 != null && voicedCur) ? 1.0 : ((b2 != null && w2 > 0.2) ? 1.0 : 0.0);
                    } else {
                        hTarget = 0.0;
                    }
                    hv.setBreath(clamp(pv[1] + vm.breathBias * 0.35, 0, 1) * 0.5);
                }
                hw += (hTarget - hw) * smoothCtl;
                if (hw < 0.001) hw = 0; else if (hw > 0.999) hw = 1;
            }

            // pitch (interpolated between control points)
            int ci = Math.min(i / ctlStep, ctlN - 1);
            int ci2 = Math.min(ci + 1, ctlN - 1);
            double frac = (i - ci * ctlStep) / (double) ctlStep;
            f0 = f0c[ci] + (f0c[ci2] - f0c[ci]) * clamp(frac, 0, 1);
            if (Double.isNaN(f0) || f0 < 20) f0 = 20;

            double dyn = pv[0], bre = pv[1], ope = pv[4], gwl = pv[7];
            // OPE 127 (default) is neutral; smaller = more pressed/closed glottis
            double oq = clamp(0.55 + (ope - 1.0) * 0.25 + vm.oqBias, 0.25, 0.95);
            double tilt = clamp(0.5 - pv[2] + vm.tiltBias, 0.0, 1.0);
            double breath = clamp(bre + vm.breathBias * 0.35, 0, 1);

            float exc = src.sample(f0, oq, tilt, breath * 0.35);
            float noise = src.noiseOnly(breath * 0.5 + 0.05);
            if (gwl > 0.02) {
                exc *= (float) (1.0 - gwl * 0.25 * (0.5 + 0.5 * Math.sin(2 * Math.PI * 24.0 * i / fs)));
            }

            // Entering or leaving the measured-noise path must not step: the first version switched
            // instantly and both the jump and the filter-state reset were heard as a buzz.
            double nsTarget = (measuredNoise && ns != null && ns.isActive()) ? 1.0 : 0.0;
            nsMix += (nsTarget - nsMix) * nsXCoef;
            if (nsMix < 1e-4) nsMix = 0.0; else if (nsMix > 0.9999) nsMix = 1.0;
            if (nsTarget == 0.0 && nsMix == 0.0) nsWasSilent = true;

            float y;
            if (frameSequence && fv != null && fv.isActive()) {
                // one real measured frame per hop, concatenated by overlap-add
                y = fv.sample();
                if (hv != null) hv.sample();   // keep the phase running for the fallback path
            } else if (nsMix > 0.0) {
                // Measured fricative spectrum. The level follows the formant path it replaces:
                // the measured curve is normalised to its own peak, and using it directly made
                // every fricative equally loud (s, x, f, z`, ts` all at -4.2 dB), which threw away
                // a real cue and was heard as a loss of clarity.
                float ref = ff.filter(exc, noise);
                float shaped = ns.process(src.noiseOnly(0.55f)) * NS_GAIN * noiseGain;
                if (nsWasSilent) {
                    // Seed the level comparison on the first sample of the path. Starting from zero
                    // made the ratio hit its ceiling for a few milliseconds: a burst of loud noise at
                    // the front of every fricative, heard as an electric crackle.
                    nsRmsRef = ref * ref;
                    nsRmsNew = shaped * shaped;
                    nsWasSilent = false;
                }
                nsRmsRef += (ref * ref - nsRmsRef) * nsCoef;
                nsRmsNew += (shaped * shaped - nsRmsNew) * nsCoef;
                double match = Math.sqrt(Math.max(1e-9, nsRmsRef) / Math.max(1e-9, nsRmsNew));
                // The floor matters: the path it replaces runs through the formant filter and is much
                // quieter, so the match has to be allowed to attenuate a lot. Clamping it at 0.3 left
                // the fricative several times too loud - a blast of noise in front of the note.
                match = clamp(match, 0.02, 3.0);
                // crossfade against the formant path instead of replacing it outright
                y = (float) ((shaped * match) * nsMix + ref * (1.0 - nsMix));
                if (hv != null) hv.sample();
            } else if (hv != null && hw > 0) {
                float yf = ff.filter(exc, noise);
                float yh = hv.sample();
                // the measured table is normalised to its own fixed RMS; match the formant path so
                // switching between a built-in and an imported singer does not jump in loudness
                y = (float) (yh * HARMONIC_GAIN * hw + yf * (1.0 - hw));
            } else {
                y = ff.filter(exc, noise);
                if (hv != null) hv.sample();   // keep the phase running so the fade-in is seamless
            }
            if (eq != null) y = eq.filter(y);
            double env = envelope(sg, i, fs);
            double g = env * dyn * cur.gain * vm.gain;
            if (hv != null) g *= (1.0 - hw) + hw * seqGain;   // measured swell only while the real frames drive it
            // Consonant balance. Measured before this fix, every noise-type consonant sat AT or ABOVE
            // the vowel (s: +4.8 dB, peak 0.38) which is heard as a "burst" in front of each syllable.
            // In real singing the consonant sits well below the vowel, so scale by manner:
            //   fricatives / affricates  ~ -7 dB,  nasals / liquids / glides ~ -4 dB,  stops ~ -10 dB
            switch (cur.type) {
                case PhonemeDef.T_FRIC:
                case PhonemeDef.T_AFFRIC:
                    g *= 0.45 * vm.consonantGain;    // -12 dB made syllables unrecognisable
                    break;
                case PhonemeDef.T_ASPIR:
                    g *= 0.35 * vm.consonantGain;
                    break;
                case PhonemeDef.T_NASAL:
                case PhonemeDef.T_LIQUID:
                case PhonemeDef.T_GLIDE:
                    g *= 0.75 * vm.consonantGain;    // nasals/liquids/glides stay audible
                    break;
                case PhonemeDef.T_STOP:
                    g *= 0.90;
                    break;
                default:
                    break;
            }
            out[i] = (float) (y * g);
        }

        if (cfg.breathSounds && vm.breathSoundAmount > 0.01) addBreathSounds(out, segs, fs, vm);

        for (int i = 0; i < n; i++) {
            double v = out[i] * cfg.masterGain;
            out[i] = (float) (Math.tanh(v * 1.1) * 0.95);
        }
        return out;
    }

    // ------------------------------------------------------------- segmenting

    static ArrayList<Seg> buildSegments(Song song, Part part, List<Note> notes, VoiceModel vm,
                                        int fs, double partStartSec) {
        ArrayList<Seg> segs = new ArrayList<Seg>();
        for (int i = 0; i < notes.size(); i++) {
            Note nt = notes.get(i);
            double sA = TimeUtil.tickToSec(song, nt.pos);
            double sB = TimeUtil.tickToSec(song, nt.pos + nt.dur);
            int i0 = (int) Math.round(sA * fs);
            int i1 = (int) Math.round(sB * fs);
            if (i1 <= i0 + 4) continue;
            if (!segs.isEmpty()) {
                int prev = segs.get(segs.size() - 1).s1;
                if (prev > i0) i0 = prev;
                if (i1 <= i0 + 4) continue;
            }
            buildNoteSegments(segs, nt, vm, i0, i1, fs);
        }
        return segs;
    }

    static void buildNoteSegments(List<Seg> segs, Note nt, VoiceModel vm, int i0, int i1, int fs) {
        List<String> syms = resolvePhonemes(nt, vm);
        if (syms.isEmpty()) syms.add("a");
        ArrayList<PhonemeDef> defs = new ArrayList<PhonemeDef>();
        for (String s : syms) defs.add(PhonemeTable.getOrDefault(s));
        if (defs.isEmpty()) return;

        int total = i1 - i0;
        int lead = 0, firstVowel = 0;
        boolean sawVowel = false;
        for (int i = 0; i < defs.size(); i++) {
            PhonemeDef d = defs.get(i);
            if (d.type == PhonemeDef.T_VOWEL) { firstVowel = i; sawVowel = true; break; }
            lead += (int) (d.durDef * fs);
            firstVowel = i + 1;
        }
        if (!sawVowel) firstVowel = defs.size();
        // The vowel is what makes a syllable recognisable. Lead consonant and coda used to be
        // budgeted separately (45% + 25%), which on a 100 ms note left a 30 ms vowel - fast
        // passages turned to mush. They now share one budget and the vowel keeps the rest.
        boolean tight = total < 0.25 * fs;                       // fast notes
        int vowelFloor = (int) (total * (tight ? 0.60 : 0.55));
        int budget = Math.max(0, total - vowelFloor);            // what consonants may use together
        int maxLead = Math.min((int) (total * 0.45), budget);
        if (lead > maxLead) lead = maxLead;

        boolean hasCoda = false;
        int coda = 0;
        if (defs.size() > 1 && firstVowel < defs.size()) {
            PhonemeDef last = defs.get(defs.size() - 1);
            if (last.type == PhonemeDef.T_NASAL || last.type == PhonemeDef.T_LIQUID) {
                hasCoda = true;
                coda = Math.min((int) (last.durDef * fs),
                        Math.min((int) (total * 0.25), Math.max(0, budget - lead)));
            }
        }
        // VOCALOID sings the leading consonant BEFORE the note start ("borrowing" the time), so the
        // vowel lands on the beat. Without this the vowel arrives a consonant too late and fast
        // passages sound behind the grid.
        // a note that follows the previous one immediately is sung legato: the envelope must
        // not dip at the join (that dip is what made phrases sound chopped)
        int prevSegEnd = segs.isEmpty() ? -1 : segs.get(segs.size() - 1).s1;
        boolean legato = prevSegEnd >= 0 && i0 >= prevSegEnd && (i0 - prevSegEnd) <= (int) (0.035 * fs);
        if (legato) segs.get(segs.size() - 1).legatoOut = true;
        int leadStart = i0;
        if (lead > 0 && firstVowel > 0) {
            int prevEnd = 0;
            if (!segs.isEmpty()) prevEnd = segs.get(segs.size() - 1).s1;
            // The consonant is sung before the beat so the vowel lands on it, but 90 ms of that read
            // as a puff of air in front of every syllable. 55 ms still places the vowel on the
            // beat without turning the onset into a separate sound.
            int head = Math.min((int) (fs * 0.055), lead);
            leadStart = Math.max(prevEnd, i0 - head);
        }
        int bodyStart = leadStart + lead;      // the vowel starts here (== i0 unless it had to be squeezed)
        int bodyEnd = i1 - (hasCoda ? coda : 0);
        if (bodyEnd <= bodyStart) bodyEnd = Math.min(i1, bodyStart + 1);

        int curp = leadStart;
        for (int i = 0; i < firstVowel && i < defs.size(); i++) {
            int len = (lead * (i + 1)) / Math.max(1, firstVowel) - (lead * i) / Math.max(1, firstVowel);
            Seg sg = new Seg();
            sg.s0 = curp;
            sg.s1 = Math.min(i1, curp + Math.max(1, len));
            sg.ph = defs.get(i);
            sg.vowel = false;
            sg.legatoIn = legato;
            segs.add(sg);
            curp = sg.s1;
        }
        int vEnd = hasCoda ? defs.size() - 1 : defs.size();
        int nv = Math.max(1, vEnd - firstVowel);
        for (int i = firstVowel; i < vEnd; i++) {
            int a = bodyStart + (bodyEnd - bodyStart) * (i - firstVowel) / nv;
            int b = bodyStart + (bodyEnd - bodyStart) * (i - firstVowel + 1) / nv;
            Seg sg = new Seg();
            sg.s0 = a;
            sg.s1 = Math.max(a + 1, b);
            sg.ph = defs.get(i);
            sg.vowel = true;
            sg.legatoIn = legato;
            segs.add(sg);
        }
        if (hasCoda) {
            Seg sg = new Seg();
            sg.s0 = bodyEnd;
            sg.s1 = i1;
            sg.ph = defs.get(defs.size() - 1);
            sg.vowel = false;
            segs.add(sg);
        }
        for (int i = 1; i < segs.size(); i++) {
            if (segs.get(i).s0 < segs.get(i - 1).s1) segs.get(i).s0 = segs.get(i - 1).s1;
            if (segs.get(i).s1 <= segs.get(i).s0) segs.get(i).s1 = segs.get(i).s0 + 1;
        }
    }

    public static List<String> resolvePhonemes(Note nt, VoiceModel vm) {
        ArrayList<String> out = new ArrayList<String>();
        if (nt.phoneme != null && nt.phoneme.trim().length() > 0) {
            // user typed phonemes: keep the case-sensitive VOCALOID CHN symbols (iE_r, N, Sil...)
            for (String s : nt.phoneme.trim().split("[\\s,]+")) {
                if (s.length() == 0) continue;
                String n = PhonemeTable.normalize(s);
                out.add(n.length() == 0 ? s : n);
            }
            return out;
        }
        String ly = nt.lyric == null ? "" : nt.lyric.trim();
        if (ly.length() == 0) { out.add("a"); return out; }
        List<String> r = null;
        if (vm != null && vm.lang == 0) {
            r = JapaneseG2P.lyricToPhonemes(ly);
            if (r == null || r.isEmpty()) r = ChineseG2P.lyricToPhonemes(ly);
        } else {
            r = ChineseG2P.lyricToPhonemes(ly);
            if (r == null || r.isEmpty()) r = JapaneseG2P.lyricToPhonemes(ly);
        }
        if (r == null || r.isEmpty()) {
            String nl = PhonemeTable.normalize(ly);
            r = PhonemeTable.get(nl) != null
                    ? Collections.singletonList(nl)
                    : Collections.singletonList("a");
        }
        out.addAll(r);
        return out;
    }

    // ---------------------------------------------------------------- shaping

    static double formantOf(PhonemeDef ph, int i, VoiceModel vm) {
        double[] ov = vm.override(ph.sym);
        if (ov != null && i < ov.length && ov[i] > 0) return ov[i];
        double f;
        switch (i) {
            case 0: f = ph.f1; break;
            case 1: f = ph.f2; break;
            case 2: f = ph.f3; break;
            case 3: f = ph.f4; break;
            default: f = ph.f5; break;
        }
        double mul;
        switch (i) {
            case 0: mul = vm.f1Mul; break;
            case 1: mul = vm.f2Mul; break;
            case 2: mul = vm.f3Mul; break;
            case 3: mul = vm.f4Mul; break;
            default: mul = vm.f5Mul; break;
        }
        if (f <= 0) f = (i == 0 ? 500 : (i == 1 ? 1500 : (i == 2 ? 2500 : (i == 3 ? 3500 : 4500))));
        return f * mul * vm.formantScale;
    }

    static double bwOf(PhonemeDef ph, int i) {
        double b;
        switch (i) {
            case 0: b = ph.b1; break;
            case 1: b = ph.b2; break;
            case 2: b = ph.b3; break;
            case 3: b = ph.b4; break;
            default: b = ph.b5; break;
        }
        return b <= 0 ? (i == 0 ? 60 : (i == 1 ? 90 : (i == 2 ? 120 : 150))) : b;
    }

    static void blend(PhonemeDef a, PhonemeDef b, double w, VoiceModel vm, double[] fmT, double[] bwT) {
        for (int k = 0; k < 5; k++) {
            double fa = formantOf(a, k, vm), ba = bwOf(a, k);
            if (b != null && w > 0) {
                double fb = formantOf(b, k, vm), bb = bwOf(b, k);
                fmT[k] = fa + (fb - fa) * w;
                bwT[k] = ba + (bb - ba) * w;
            } else {
                fmT[k] = fa;
                bwT[k] = ba;
            }
        }
    }

    /**
     * VOCALOID4 control parameters and their documented defaults
     * (DYN=64 BRE=0 BRI=64 CLE=0 GEN=64 OPE=127 POR=64 PIT=0 PBS=2; see docs/vsqx-format-spec.md §4).
     * v = {dynGain, breath, bri, cle, ope, gen, vib, growl}
     */
    static void sampleParams(Part part, long tick, double[] v, VoiceModel vm) {
        v[0] = dbGain(param(part, "DYN", tick, 64));
        v[1] = param(part, "BRE", tick, 0) / 127.0;
        v[2] = param(part, "BRI", tick, 64) / 127.0;
        v[3] = param(part, "CLE", tick, 0) / 127.0;
        v[4] = param(part, "OPE", tick, 127) / 127.0;
        v[5] = param(part, "GEN", tick, 64) / 127.0;
        v[6] = param(part, "VIB", tick, 64) / 64.0;
        v[7] = param(part, "GWL", tick, 0) / 127.0;
    }

    static double dbGain(double dyn) {
        return Math.pow(10, ((dyn - 64.0) * 0.30) / 20.0);
    }

    static double envelope(Seg sg, int i, int fs) {
        // outside the segment there is no sound at all: before the fix, i < s0 produced a large
        // NEGATIVE gain (-|a|/atk) which leaked a loud decaying tone into every gap and before the
        // first note - heard as a "piano" note at the start of playback.
        if (i < sg.s0 || i >= sg.s1) return 0.0;
        double a = (i - sg.s0) / (double) fs;
        double d = (sg.s1 - sg.s0) / (double) fs;
        int t = sg.ph.type;
        if (t == PhonemeDef.T_SIL) return 0.0;
        if (t == PhonemeDef.T_STOP) {
            // Closure then release burst. The burst used to run at full phoneme gain, which made
            // every plosive syllable start with an audible "pop"; a real stop release sits far
            // below the vowel (aspirated ones louder than unaspirated) and is only a few ms long.
            double burst = Math.min(sg.ph.aspirated ? 0.010 : 0.006, d * 0.30);
            if (a < d - burst) return 0.008;                        // closure: nearly silent
            double u = (a - (d - burst)) / burst;
            double amp = sg.ph.gain * (sg.ph.aspirated ? 0.22 : 0.11);
            double atk = Math.min(0.0015, burst * 0.4);              // 1.5 ms rise, no click
            if (u * burst < atk) return amp * (u * burst / atk);
            return amp * Math.max(0.0, 1.0 - (u * burst - atk) / Math.max(1e-6, burst - atk));
        }
        if (t == PhonemeDef.T_FRIC || t == PhonemeDef.T_ASPIR) {
            double atk = Math.min(0.014, d * 0.3), rel = Math.min(0.02, d * 0.2);
            if (a < atk) return (a / atk) * sg.ph.gain;
            if (a > d - rel) return Math.max(0, (d - a) / rel) * sg.ph.gain;
            return sg.ph.gain;
        }
        // legato joins keep their level: a long release followed by a fresh attack punched a
        // hole in the middle of every phrase
        double atk = Math.min(sg.legatoIn ? 0.010 : 0.02, d * 0.15);
        double rel = Math.min(sg.legatoOut ? 0.018 : 0.06, d * 0.25);
        if (a < atk) return (a / atk) * sg.ph.gain;
        if (a > d - rel) return Math.max(0, (d - a) / rel) * sg.ph.gain;
        return sg.ph.gain;
    }

    static void addBreathSounds(float[] out, List<Seg> segs, int fs, VoiceModel vm) {
        Noise nz = new Noise(12345);
        for (int k = 1; k < segs.size(); k++) {
            Seg prev = segs.get(k - 1), cur = segs.get(k);
            int gap = cur.s0 - prev.s1;
            if (gap > (int) (0.18 * fs)) {
                int len = Math.min((int) (0.12 * fs), gap);
                int st = cur.s0 - len;
                if (st < 0) continue;
                for (int i = 0; i < len; i++) {
                    int idx = st + i;
                    if (idx < 0 || idx >= out.length) continue;      // segments may reach past the buffer
                    double e = Math.sin(Math.PI * i / len);
                    out[idx] += (float) (nz.next() * e * e * 0.018 * Math.min(0.5, vm.breathSoundAmount));
                }
            }
        }
    }

    // ------------------------------------------------------------------ pitch

    static double pitchHz(Song song, Part part, List<Note> notes, VoiceModel vm, double sec, double partStartSec) {
        long absTick = TimeUtil.secToTick(song, partStartSec + sec);
        long localTick = absTick - part.pos;
        Note cur = null;
        int ci = -1;
        for (int i = 0; i < notes.size(); i++) {
            Note nt = notes.get(i);
            double a = TimeUtil.tickToSec(song, nt.pos);
            double b = TimeUtil.tickToSec(song, nt.pos + nt.dur);
            if (sec >= a && sec < b) { cur = nt; ci = i; break; }
            if (a <= sec) { cur = nt; ci = i; }
        }
        if (cur == null) { cur = notes.get(0); ci = 0; }

        double a = TimeUtil.tickToSec(song, cur.pos);
        double f = keyHz(cur.key);

        Note prev = ci > 0 ? notes.get(ci - 1) : null;
        double porMs = param(part, "POR", localTick, 64) / 127.0 * 110.0;
        if (prev != null && sec >= a) {
            double pe = TimeUtil.tickToSec(song, prev.pos + prev.dur);
            if (a - pe <= 0.06 && porMs > 3) {       // also glide across small gaps
                double prog = (sec - a) / (porMs / 1000.0);
                if (prog < 1.0) {
                    double from = keyHz(prev.key), target = keyHz(cur.key);
                    f = from + (target - from) * clamp(prog, 0, 1);
                }
            }
        }

        // vibrato: <seq id="vibDep"/"vibRate"> curves (absolute ticks) win over the vibType preset
        double vibDepth = noteSeq(cur, "vibDep", absTick, -1);
        double vibRate = noteSeq(cur, "vibRate", absTick, -1);
        double vibType = cur.st("vibType", 0);
        double delayCents = -1;
        if (vibDepth < 0) {
            switch ((int) vibType) {
                case 1: vibDepth = 90; vibRate = 92; break;   // Extreme
                case 2: vibDepth = 40; vibRate = 108; break;  // Fast
                case 3: vibDepth = 25; vibRate = 46; break;   // Slight
                case 0:
                default: vibDepth = 0; vibRate = 64; break;   // Normal = no automatic vibrato
            }
        }
        if (vibRate < 0) vibRate = 64;
        double vibScale = param(part, "VIB", localTick, 64) / 64.0;
        double depthCents = vibDepth > 0.5 ? Math.pow(vibDepth / 127.0, 1.5) * 120.0 : 0.0;
        if (vm.vibratoDepthCents > depthCents) depthCents = vm.vibratoDepthCents;
        // A perfectly steady, perfectly in-tune tone is exactly what makes a synthesizer sound
        // electronic. Real singing always carries a little vibrato, so a note with no vibrato data
        // gets a natural default: about 22 cents at 5.3 Hz, after a 0.22 s delay and a 0.3 s fade,
        // so short notes stay steady and long ones bloom. VIB=0 still switches it off completely.
        boolean explicitVib = depthCents > 0.5;
        if (!explicitVib) {
            depthCents = 22.0;
            if (vibRate < 0) vibRate = 41;        // 4 + (41/127)*4 = 5.3 Hz
        }
        depthCents *= clamp(vibScale, 0, 2.0);
        if (depthCents > 0.5 && sec >= a) {
            double ta = sec - a;
            double rate = 4.0 + (vibRate / 127.0) * 4.0;
            double delay = delayCents < 0 ? (explicitVib ? 0.18 : 0.22) : delayCents;
            double fade = clamp((ta - delay) / 0.25, 0, 1);
            f *= Math.pow(2, (depthCents * fade * Math.sin(2 * Math.PI * rate * ta)) / 1200.0);
        }

        // PIT is a 14-bit bend (-8192..8191) scaled by PBS semitones (docs/vsqx-format-spec.md §4.4)
        double cents = (pitCents(part, cur, localTick) / 8192.0) * pbsRange(part, localTick) * 100.0;
        f *= Math.pow(2, cents / 1200.0);
        return f;
    }

    /** per-note <seq> curve lookup; ticks are absolute, stored note curves are absolute too */
    /** log-frequency interpolation of a grid curve */
    static double gridInterp(double[] f, double[] v, double x) {
        int n = Math.min(f.length, v.length);
        if (n == 0) return -90;
        if (x <= f[0]) return v[0];
        if (x >= f[n - 1]) return v[n - 1];
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) { int m = (lo + hi) >>> 1; if (f[m] <= x) lo = m; else hi = m; }
        double t = Math.log(x / f[lo]) / Math.log(f[hi] / f[lo]);
        return v[lo] + (v[hi] - v[lo]) * t;
    }

    /** phoneme types that carry a measurable harmonic (pitched) spectrum */
    static boolean isVoiced(PhonemeDef ph) {
        int t = ph.type;
        return t == PhonemeDef.T_VOWEL || t == PhonemeDef.T_NASAL
                || t == PhonemeDef.T_LIQUID || t == PhonemeDef.T_GLIDE;
    }

    static double noteSeq(Note n, String id, long absTick, double dflt) {
        ControlCurve c = n.ctrls.get(id);
        if (c == null || c.isEmpty()) return dflt;
        return c.valueAt(absTick);
    }

    static double pitCents(Part part, Note n, long localTick) {
        ControlCurve nc = n.ctrls.get("PIT");
        if (nc != null && !nc.isEmpty()) return nc.valueAt(localTick - n.pos);
        ControlCurve pc = part.ctrlOrNull("PIT");
        if (pc != null && !pc.isEmpty()) return pc.valueAt(localTick);
        return 0;
    }

    static double pbsRange(Part part, long tick) {
        ControlCurve c = part.ctrlOrNull("PBS");
        if (c == null || c.isEmpty()) return 2.0;
        return Math.max(0.01, c.valueAt(tick));
    }

    static double keyHz(int key) { return 440.0 * Math.pow(2, (key - 69) / 12.0); }
}
