package com.dsh.v4tune.ai;

import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.TimeUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a decoded vocal take into tuning curves (v10.1).
 *
 * <p>This is the on-device answer to "can BriVoice take audio in natively": the user picks a dry
 * vocal WAV, YIN finds the sung f0 and RMS follows the level, and every note of the current part
 * gets the same PIT / DYN / VIB curves the assistant normally writes. Nothing leaves the phone and
 * no neural model is required, which keeps it usable on a mid-range device.
 *
 * <p>Why YIN: it is a time-domain estimator with an explicit aperiodicity test, so unvoiced
 * consonants and silence are rejected instead of producing octave-jumping garbage, and it needs no
 * training data. It costs about 0.1M multiply-adds per 20 ms frame, which is why the signal is
 * decimated to ~11 kHz first (f0 range 65..1000 Hz fits comfortably).
 *
 * <p>All output values are *physical parameter values*, not deltas: the normal
 * {@code applyTuning} path inserts them.
 */
public final class AudioTune {

    public static final class Result {
        /** curves ready for applyTuning; never null when error is null */
        public Tuning tuning = new Tuning();
        /** short Chinese report for the confirmation dialog */
        public String summary = "";
        /** human readable failure, or null */
        public String error;
    }

    private static final int TARGET_RATE = 11025;   // enough for f0 up to 1 kHz, 6x cheaper than 44.1k
    private static final double FMIN = 65.0;
    private static final double FMAX = 1000.0;
    private static final int HOP_MS = 20;
    private static final int WIN_MS = 60;   // shorter than a vibrato half-cycle keeps f0 tracking it
    private static final double YIN_THRESHOLD = 0.25;  // aperiodicity below this counts as voiced
    private static final double SILENCE_DB = -55.0;    // cheaper than running YIN on silence
    private static final double PIT_UNITS_PER_CENT = 8192.0 / 200.0; // PBS default = 2 semitones
    private static final double PIT_LIMIT = 2400.0;    // Params.MAX for PIT

    private AudioTune() { }

    public static Result analyze(Song song, Part part, float[] mono, int sampleRate) {
        Result r = new Result();
        if (song == null || part == null || part.notes.isEmpty()) {
            r.error = "当前轨道没有音符，先画几个音再分析";
            return r;
        }
        if (mono == null || sampleRate <= 0 || mono.length < sampleRate / 2) {
            r.error = "音频太短或没有解码出采样";
            return r;
        }
        List<Note> notes = new ArrayList<Note>(part.notes);
        java.util.Collections.sort(notes, new java.util.Comparator<Note>() {
            public int compare(Note a, Note b) { return Long.compare(a.pos, b.pos); }
        });

        int factor = Math.max(1, (int) Math.round(sampleRate / (double) TARGET_RATE));
        float[] x = decimate(mono, factor);
        int rate = Math.max(4000, sampleRate / factor);
        int hop = Math.max(64, rate * HOP_MS / 1000);
        int win = Math.max(512, rate * WIN_MS / 1000);
        if (x.length < win + 32) {
            r.error = "音频太短（需要至少半秒）";
            return r;
        }
        int frames = 1 + (x.length - win) / hop;

        float[] f0 = new float[frames];
        float[] db = new float[frames];
        boolean[] voiced = new boolean[frames];
        int voicedCount = 0;
        for (int i = 0; i < frames; i++) {
            int off = i * hop;
            float level = rms(x, off, win);
            db[i] = level <= 1e-7f ? -120f : (float) (20.0 * Math.log10(level));
            if (db[i] < SILENCE_DB) continue;
            float hz = yin(x, off, win, rate);
            if (hz > 0) {
                f0[i] = hz;
                voiced[i] = true;
                voicedCount++;
            }
        }
        if (voicedCount < 12) {
            r.error = "检测不到足够的嗓音帧（请确认选的是干声演唱，而不是伴奏或静音）";
            return r;
        }

        // reference level: 60th percentile of the voiced frames, so a few shouts do not set the scale
        float[] voicedDb = new float[voicedCount];
        int vi = 0;
        for (int i = 0; i < frames; i++) if (voiced[i]) voicedDb[vi++] = db[i];
        Arrays.sort(voicedDb);
        float refDb = voicedDb[Math.min(voicedDb.length - 1, (int) (voicedDb.length * 0.60))];

        Map<String, List<double[]>> pit = new LinkedHashMap<String, List<double[]>>();
        Map<String, List<double[]>> dyn = new LinkedHashMap<String, List<double[]>>();
        Map<String, List<double[]>> vib = new LinkedHashMap<String, List<double[]>>();
        int pitPts = 0, dynPts = 0, vibPts = 0;
        double pitchAbsSum = 0;
        int pitchAbsN = 0;
        long firstTick = Long.MAX_VALUE, lastTick = Long.MIN_VALUE;

        for (int ni = 0; ni < notes.size(); ni++) {
            Note n = notes.get(ni);
            double startSec = TimeUtil.tickToSec(song, n.pos);
            double endSec = TimeUtil.tickToSec(song, n.pos + Math.max(30, n.dur));
            double durSec = Math.max(0.03, endSec - startSec);
            double refHz = keyHz(n.key);
            int a = frameAt(startSec, hop, rate, win);
            int b = frameAt(endSec, hop, rate, win);
            List<Integer> inner = new ArrayList<Integer>();
            // one frame of slack on either side: a frame centred just inside the note still reads it
            for (int i = Math.max(0, a - 1); i <= Math.min(frames - 1, b + 1); i++)
                if (voiced[i]) inner.add(i);
            if (inner.size() < 3) continue;

            // Segment by time, not by frame count. A scoop lives in the first ~70 ms; splitting the
            // voiced frames into equal thirds instead would mix the steady body into the head and
            // report "no approach" for every note.
            double headEnd = startSec + Math.min(0.07, durSec * 0.35);
            double tailStart = endSec - Math.min(0.09, durSec * 0.35);
            List<Integer> head = new ArrayList<Integer>();
            List<Integer> mid = new ArrayList<Integer>();
            List<Integer> tail = new ArrayList<Integer>();
            for (int k = 0; k < inner.size(); k++) {
                int idx = inner.get(k);
                double c = centerSec(idx, hop, rate, win);
                if (c < headEnd) head.add(idx);
                else if (c > tailStart) tail.add(idx);
                else mid.add(idx);
            }
            if (mid.size() < 3) { mid = inner; head = new ArrayList<Integer>(); tail = new ArrayList<Integer>(); }

            double midCents = medianCents(mid, f0, refHz);
            // octave errors and note transitions are the main outliers a YIN frame produces;
            // rejecting anything far from the body median keeps them out of head/tail
            double headCents = head.isEmpty() ? midCents : medianCentsNear(head, f0, refHz, midCents, 180);
            double tailCents = tail.isEmpty() ? midCents : medianCentsNear(tail, f0, refHz, midCents, 180);
            double tuneC = clamp(midCents, -50, 50);              // in-tune correction, 1/2 semitone max
            pitchAbsSum += Math.abs(midCents);
            pitchAbsN++;
            double scoop = clamp(headCents - midCents, -60, 60);
            double release = clamp(tailCents - midCents, -50, 50);
            if (Math.abs(scoop) < 6) scoop = 0;
            if (Math.abs(release) < 8) release = 0;
            // A scoop belongs to an entry and a fall-off to an exit. Notes butted together are
            // legato, where the portamento in the engine already joins them; writing both a
            // release and a scoop at the same boundary carved an audible dip between notes.
            long gapBefore = ni == 0 ? Long.MAX_VALUE : n.pos - notes.get(ni - 1).end();
            long gapAfter = ni == notes.size() - 1 ? Long.MAX_VALUE : notes.get(ni + 1).pos - n.pos - n.dur;
            if (gapBefore < 60) scoop = 0;
            if (gapAfter < 60) release = 0;

            long t0 = n.pos + 2;
            long t1 = n.pos + Math.max(6, Math.min(n.dur / 6, 120));
            long t3 = n.pos + n.dur - 2;
            long t2 = t3 - Math.max(6, Math.min(n.dur / 6, 120));
            if (t1 > t3) t1 = t3;
            if (t2 < t1) t2 = t1;
            add(pit, t0, pitValue(tuneC + scoop));
            add(pit, t1, pitValue(tuneC));
            if (n.dur > 240) {
                add(pit, t2, pitValue(tuneC));
                add(pit, t3, pitValue(tuneC + release));
                pitPts += 4;
            } else {
                pitPts += 2;
            }
            firstTick = Math.min(firstTick, n.pos);
            lastTick = Math.max(lastTick, n.pos + n.dur);

            double headDb = head.isEmpty() ? meanDb(mid, db) : meanDb(head, db);
            double midDb = meanDb(mid, db);
            double tailDb = tail.isEmpty() ? midDb : meanDb(tail, db);
            add(dyn, t0, clamp(64 + (headDb - refDb) * 2.0, 0, 127));
            add(dyn, n.pos + Math.max(4, n.dur / 2), clamp(64 + (midDb - refDb) * 2.0, 0, 127));
            add(dyn, t3, clamp(64 + (tailDb - refDb) * 2.0, 0, 127));
            dynPts += 3;

            double amp = vibratoAmpCents(mid, f0);
            if (amp > 6.0 && n.dur >= 480) {
                // 18 cents is a neutral natural vibrato at strength 1.0 for this engine (VIB 64)
                add(vib, n.pos + Math.max(4, n.dur / 2), clamp(64 + (amp * 1.15 - 18.0) * 2.0, 0, 127));
                vibPts++;
            }
        }
        if (pitPts == 0) {
            r.error = "音频里没有和这些音符对齐的演唱（检查工程速度/音符位置，或换一段干声）";
            return r;
        }
        r.tuning.curves.put("PIT", toArray(pit));
        r.tuning.curves.put("DYN", toArray(dyn));
        if (vibPts > 0) r.tuning.curves.put("VIB", toArray(vib));

        double voicedPct = 100.0 * voicedCount / frames;
        int meanAbsPitch = pitchAbsN == 0 ? 0 : (int) Math.round(pitchAbsSum / pitchAbsN);
        r.summary = "分析 " + frames + " 帧 · 浊音 " + Math.round(voicedPct) + "% · 平均音准偏差 "
                + meanAbsPitch + " 音分 · 写入 PIT " + pitPts + " 点 / DYN " + dynPts + " 点"
                + (vibPts > 0 ? " / VIB " + vibPts + " 点" : "");
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private static double pitValue(double cents) {
        return clamp(cents * PIT_UNITS_PER_CENT, -PIT_LIMIT, PIT_LIMIT);
    }

    private static void add(Map<String, List<double[]>> m, long tick, double value) {
        List<double[]> l = m.get("x");
        if (l == null) { l = new ArrayList<double[]>(); m.put("x", l); }
        l.add(new double[]{tick, value});
    }

    private static double[][] toArray(Map<String, List<double[]>> m) {
        List<double[]> l = m.get("x");
        if (l == null) return new double[0][];
        // one tick can only carry one value; a later note's point wins over an earlier note's tail
        java.util.TreeMap<Long, Double> byTick = new java.util.TreeMap<Long, Double>();
        for (double[] p : l) byTick.put((long) p[0], p[1]);
        double[][] out = new double[byTick.size()][];
        int i = 0;
        for (Map.Entry<Long, Double> e : byTick.entrySet()) out[i++] = new double[]{e.getKey(), e.getValue()};
        return out;
    }

    private static double centerSec(int frame, int hop, int rate, int win) {
        return (frame * hop + win / 2.0) / rate;
    }

    private static int frameAt(double sec, int hop, int rate, int win) {
        return (int) Math.round((sec * rate - win / 2.0) / hop);
    }

    private static float[] decimate(float[] in, int factor) {
        if (factor <= 1) return in;
        int n = in.length / factor;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            float s = 0;
            int base = i * factor;
            for (int k = 0; k < factor; k++) s += in[base + k];
            out[i] = s / factor;   // box average doubles as the anti-alias filter
        }
        return out;
    }

    private static float rms(float[] x, int off, int len) {
        int end = Math.min(x.length, off + len);
        if (end <= off) return 0;
        double s = 0;
        for (int i = off; i < end; i++) s += (double) x[i] * x[i];
        return (float) Math.sqrt(s / (end - off));
    }

    /** YIN pitch for one frame; returns Hz or 0 when unvoiced. */
    private static float yin(float[] x, int off, int win, int rate) {
        int tauMin = Math.max(2, (int) Math.floor(rate / FMAX));
        int tauMax = Math.min(win / 2 - 1, (int) Math.ceil(rate / FMIN));
        if (tauMax <= tauMin) return 0;
        int w = win - tauMax;
        if (w < tauMax) return 0;
        double[] d = new double[tauMax + 1];
        for (int tau = 1; tau <= tauMax; tau++) {
            double s = 0;
            for (int j = 0; j < w; j++) {
                double diff = x[off + j] - x[off + j + tau];
                s += diff * diff;
            }
            d[tau] = s;
        }
        double running = 0;
        double best = Double.MAX_VALUE;
        int bestTau = -1;
        for (int tau = 1; tau <= tauMax; tau++) {
            running += d[tau];
            if (tau < tauMin) continue;
            double cm = running <= 1e-12 ? 1.0 : d[tau] * tau / running;
            if (cm < YIN_THRESHOLD) {
                // first dip below the threshold is the fundamental, not a sub-octave
                while (tau + 1 <= tauMax) {
                    double next = d[tau + 1] * (tau + 1) / (running + d[tau + 1]);
                    if (next < cm) { tau++; cm = next; } else break;
                }
                bestTau = tau;
                best = cm;
                break;
            }
            if (cm < best) { best = cm; bestTau = tau; }
        }
        if (bestTau < 0 || best > 0.42) return 0;   // no convincing dip at all
        // Refine on the normalised autocorrelation instead of the difference function. Around its
        // minimum d is flat and noisy (at 392 Hz one 11 kHz sample is 61 cents), and interpolating
        // there made single frames jump by a semitone. The autocorrelation peak is smooth.
        int lo = Math.max(1, bestTau - 2), hi = Math.min(tauMax, bestTau + 2);
        int rl = lo - 1;
        double[] r = new double[hi - rl + 2];
        for (int t = rl; t <= hi + 1; t++) r[t - rl] = normAuto(x, off, w, t);
        int peak = bestTau;
        for (int t = lo; t <= hi; t++) if (r[t - rl] > r[peak - rl]) peak = t;
        if (peak - 1 < rl || peak + 1 - rl >= r.length) return (float) (rate / peak);
        double y0 = r[peak - 1 - rl], y1 = r[peak - rl], y2 = r[peak + 1 - rl];
        double denom = 2 * (y0 - 2 * y1 + y2);
        double t = peak;
        if (Math.abs(denom) > 1e-12) t = peak + (y0 - y2) / denom;
        if (!(t > tauMin * 0.8 && t < tauMax * 1.2)) t = peak;
        return (float) (rate / t);
    }

    /** normalised autocorrelation of one frame at integer lag tau */
    private static double normAuto(float[] x, int off, int w, int tau) {
        double num = 0, e1 = 0, e2 = 0;
        for (int j = 0; j < w; j++) {
            double a = x[off + j], b = x[off + j + tau];
            num += a * b;
            e1 += a * a;
            e2 += b * b;
        }
        double den = Math.sqrt(e1 * e2);
        return den <= 1e-12 ? 0 : num / den;
    }

    private static double medianCents(List<Integer> idx, float[] f0, double refHz) {
        double[] c = new double[idx.size()];
        for (int i = 0; i < idx.size(); i++) c[i] = cents(f0[idx.get(i)], refHz);
        return median(c);
    }

    /** median after dropping frames that disagree with the body by more than `limit` cents */
    private static double medianCentsNear(List<Integer> idx, float[] f0, double refHz, double around, double limit) {
        double[] keep = new double[idx.size()];
        int n = 0;
        for (int i = 0; i < idx.size(); i++) {
            double c = cents(f0[idx.get(i)], refHz);
            if (Math.abs(c - around) <= limit) keep[n++] = c;
        }
        if (n == 0) return around;
        return median(Arrays.copyOf(keep, n));
    }

    private static double meanDb(List<Integer> idx, float[] db) {
        double s = 0;
        for (int i = 0; i < idx.size(); i++) s += db[idx.get(i)];
        return s / Math.max(1, idx.size());
    }

    /**
     * Vibrato depth in cents: the RMS of the f0 track after removing its least-squares drift,
     * times sqrt(2) so a pure sine reads back as its amplitude. Peak-to-peak of a high-passed
     * track was tried first and under-read real vibrato because the filter also removed part of
     * the modulation itself.
     */
    private static double vibratoAmpCents(List<Integer> idx, float[] f0) {
        if (idx.size() < 10) return 0;
        double[] c = new double[idx.size()];
        for (int i = 0; i < c.length; i++) c[i] = cents(f0[idx.get(i)], f0[idx.get(0)]);
        double med = median(c);
        // drop octave errors and the note's own transitions before measuring the swing
        double[] keep = new double[c.length];
        int n = 0;
        for (double v : c) if (Math.abs(v - med) <= 100) keep[n++] = v;
        if (n < 10) return 0;
        c = Arrays.copyOf(keep, n);
        // least-squares line removes a slow scoop/glide but keeps a 4..8 Hz wobble
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < c.length; i++) { sx += i; sy += c[i]; sxx += (double) i * i; sxy += (double) i * c[i]; }
        double denom = c.length * sxx - sx * sx;
        double slope = denom == 0 ? 0 : (c.length * sxy - sx * sy) / denom;
        double icpt = (sy - slope * sx) / c.length;
        double ss = 0;
        for (int i = 0; i < c.length; i++) { double r = c[i] - (icpt + slope * i); ss += r * r; }
        return Math.sqrt(ss / c.length) * Math.sqrt(2.0);
    }

    private static double cents(double hz, double refHz) {
        return 1200.0 * Math.log(hz / refHz) / Math.log(2);
    }

    private static double median(double[] a) {
        if (a.length == 0) return 0;
        double[] b = a.clone();
        Arrays.sort(b);
        int m = b.length / 2;
        return b.length % 2 == 1 ? b[m] : (b[m - 1] + b[m]) / 2.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double keyHz(int key) {
        return 440.0 * Math.pow(2, (key - 69) / 12.0);
    }
}
