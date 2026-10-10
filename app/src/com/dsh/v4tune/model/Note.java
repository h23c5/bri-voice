package com.dsh.v4tune.model;

import java.util.LinkedHashMap;

/** A vsNote. pos/dur are in ticks, ABSOLUTE in the track timeline (vsq3 part-local values are converted on read). */
public class Note {
    public long pos;
    public long dur = 480;
    public int key = 60;            // MIDI note number (n)
    public int vel = 64;            // velocity (v)
    public String lyric = "a";      // y
    public String phoneme = "";     // p  (space separated phoneme symbols, "" = auto)
    public boolean lockPhoneme = false;

    /** nStyle numeric fields, keyed by their vsqx element name, kept in schema order. */
    public final LinkedHashMap<String, Double> style = new LinkedHashMap<String, Double>();
    /** per-note control curves (PIT/PBS/DYN/... when the file stores them inside the note) */
    public final LinkedHashMap<String, ControlCurve> ctrls = new LinkedHashMap<String, ControlCurve>();

    public long end() { return pos + dur; }

    public double st(String k) { Double d = style.get(k); return d == null ? 0 : d; }
    public double st(String k, double dflt) { Double d = style.get(k); return d == null ? dflt : d; }
    public void setSt(String k, double v) { style.put(k, v); }

    public Note copy() {
        Note n = new Note();
        n.pos = pos; n.dur = dur; n.key = key; n.vel = vel;
        n.lyric = lyric; n.phoneme = phoneme; n.lockPhoneme = lockPhoneme;
        n.style.putAll(style);
        for (String k : ctrls.keySet()) n.ctrls.put(k, ctrls.get(k).copy());
        return n;
    }
}
