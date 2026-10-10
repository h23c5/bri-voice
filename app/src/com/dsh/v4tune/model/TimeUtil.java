package com.dsh.v4tune.model;

/** Tick <-> second conversion following the project tempo map. */
public class TimeUtil {
    /** ticks per quarter note used by VOCALOID4 (fixed) */
    public static final int TPQ = 480;

    /** Exact seconds of a tick position, walking the tempo map. */
    public static double tickToSec(Song s, long tick) {
        double sec = 0;
        long prev = 0;
        double bpm = 120.0;
        if (!s.tempos.isEmpty()) {
            bpm = s.tempos.get(0)[1] / 100.0;
            if (s.tempos.get(0)[0] > 0) { /* nothing before first tempo event */ }
        }
        for (int i = 0; i < s.tempos.size(); i++) {
            long[] e = s.tempos.get(i);
            if (e[0] >= tick) break;
            sec += ticksToSec(e[0] - prev, bpm);
            prev = e[0];
            bpm = e[1] / 100.0;
        }
        sec += ticksToSec(tick - prev, bpm);
        return sec;
    }

    public static double ticksToSec(long ticks, double bpm) {
        return ticks / (double) TPQ * 60.0 / bpm;
    }

    public static long secToTick(Song s, double sec) {
        long lo = 0, hi = Math.max(1, s.maxTick()) + TPQ * 64;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            if (tickToSec(s, mid) < sec) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    public static double bpmAt(Song s, long tick) { return s.tempoAt(tick); }

    /** "3:2.240" = bar:beat.tick, the way the transport shows the cursor */
    public static String tickToBarBeat(long tick) {
        long bar = tick / (TPQ * 4L) + 1;
        long beat = (tick / TPQ) % 4 + 1;
        long rest = tick % TPQ;
        return bar + ":" + beat + "." + rest;
    }
}
