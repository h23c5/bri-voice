package com.dsh.v4tune.model;

import java.util.ArrayList;

/** Whole project. Maps 1:1 onto a .vsqx document. */
public class Song {
    /** VOCALOID4 writes it with a lower-case c; the editor's verify compares the text literally. */
    public String vendor = "Yamaha corporation";
    public String version = "4.0.0.3";
    public int schema = 4;                       // 3 = vsq3 document, 4 = vsq4 document

    public final ArrayList<Voice> voices = new ArrayList<Voice>();
    public final ArrayList<Track> tracks = new ArrayList<Track>();

    // master track
    public final ArrayList<long[]> tempos = new ArrayList<long[]>();      // {tick, value*100}
    public final ArrayList<int[]> timeSigs = new ArrayList<int[]>();      // {tick, m, nu, de}
    public String masterName = "";

    // mixer (masterUnit)
    public int masterVol = 0, masterRetLevel = 0;
    public int outDev = 0;

    // aux / unknown top level xml preserved verbatim for round-tripping
    public String auxXml = "";

    public static final int DEFAULT_RESOLUTION = 480;

    public double tempoAt(long tick) {
        double v = 120.0;
        for (long[] t : tempos) if (t[0] <= tick) v = t[1] / 100.0;
        if (tempos.isEmpty()) return 120.0;
        return v;
    }

    public int[] timeSigAt(long tick) {
        int[] v = new int[]{0, 4, 4, 4};
        for (int[] t : timeSigs) if (t[0] <= tick) v = t;
        if (timeSigs.isEmpty()) return new int[]{0, 4, 4, 4};
        return v;
    }

    public Track track(int i) { return i >= 0 && i < tracks.size() ? tracks.get(i) : null; }

    public Voice voice(int bs, int pc) {
        for (Voice v : voices) if (v.bs == bs && v.pc == pc) return v;
        return voices.isEmpty() ? null : voices.get(0);
    }

    public long maxTick() {
        long m = 0;
        for (Track t : tracks) for (Part p : t.parts) m = Math.max(m, p.end());
        return m;
    }

    public Song copy() {
        Song s = new Song();
        s.vendor = vendor; s.version = version; s.schema = schema;
        s.masterName = masterName; s.masterVol = masterVol; s.masterRetLevel = masterRetLevel;
        s.outDev = outDev; s.auxXml = auxXml;
        for (Voice v : voices) s.voices.add(v.copy());
        for (Track t : tracks) s.tracks.add(t.copy());
        for (long[] a : tempos) s.tempos.add(a.clone());
        // the entries are not always 4 long (MIDI import produces {tick, nu, de}), so clone instead
        // of rebuilding a fixed-size array - that mismatch used to crash every MIDI import
        for (int[] a : timeSigs) s.timeSigs.add(a.clone());
        return s;
    }

    public static Song empty() {
        Song s = new Song();
        Voice v = new Voice();
        // VOCALOID4 identifies a singer by the id of the bank directory it was installed under.
        // The real values for 洛天依 CHN Meng are BK8H76TAEHXWSKDB / bs=4 / pc=1; an invented id
        // made the PC editor open the part with no data at all.
        v.bs = 4; v.pc = 1; v.name = "Luotianyi_CHN_Meng"; v.compID = "BK8H76TAEHXWSKDB"; v.lang = 2;
        s.voices.add(v);
        // A fresh project mirrors what VOCALOID itself writes. <seqName> was left empty before,
        // and an unnamed sequence is what made the PC editor report "no sequence, no data".
        // The part stays at tick 0: moving it a bar in turned the first notes into negative ticks.
        s.masterName = "Untitled0";
        Track t = new Track();
        t.no = 0; t.name = "Track1";
        Part p = new Part();
        p.pos = 0; p.dur = 1920 * 4;
        t.parts.add(p);
        s.tracks.add(t);
        s.tempos.add(new long[]{0, 12000});
        s.timeSigs.add(new int[]{0, 4, 4, 4});
        return s;
    }
}
