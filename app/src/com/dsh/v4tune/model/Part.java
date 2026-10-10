package com.dsh.v4tune.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** A vsPart (a block of notes on a track). */
public class Part {
    public long pos = 0;     // part start, in ticks relative to the track sequence
    public long dur = 1920;
    public String name = "";
    public String comment = "";
    public int bs = 0, pc = 0;          // singer bank/program index into Song.voices
    public final ArrayList<Note> notes = new ArrayList<Note>();
    /** part-level control curves: PIT, PBS, DYN, BRE, BRI, CLE, OPE, GEN, POR, XSY, GWL, VIB... */
    public final LinkedHashMap<String, ControlCurve> ctrls = new LinkedHashMap<String, ControlCurve>();
    /** partStyle numeric fields keyed by element name (V4) */
    public final LinkedHashMap<String, Double> style = new LinkedHashMap<String, Double>();
    /** canonical control id -> the exact id spelling found in the source file (round-trip fidelity) */
    public final LinkedHashMap<String, String> rawCtrlIds = new LinkedHashMap<String, String>();

    public long end() { return pos + dur; }

    public ControlCurve ctrl(String id) {
        ControlCurve c = ctrls.get(id);
        if (c == null) { c = new ControlCurve(); ctrls.put(id, c); }
        return c;
    }

    public ControlCurve ctrlOrNull(String id) { return ctrls.get(id); }

    public void sortNotes() {
        java.util.Collections.sort(notes, new java.util.Comparator<Note>() {
            public int compare(Note a, Note b) {
                if (a.pos != b.pos) return Long.compare(a.pos, b.pos);
                return Integer.compare(a.key, b.key);
            }
        });
    }

    public Part copy() {
        Part p = new Part();
        p.pos = pos; p.dur = dur; p.name = name; p.comment = comment; p.bs = bs; p.pc = pc;
        for (Note n : notes) p.notes.add(n.copy());
        for (String k : ctrls.keySet()) p.ctrls.put(k, ctrls.get(k).copy());
        p.style.putAll(style);
        p.rawCtrlIds.putAll(rawCtrlIds);
        return p;
    }
}
