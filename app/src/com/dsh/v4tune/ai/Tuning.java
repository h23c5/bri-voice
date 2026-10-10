package com.dsh.v4tune.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the model is allowed to change: control curves, individual notes, tempo and the track's
 * volume/pan. Everything is validated before it reaches the project.
 */
public final class Tuning {

    /** parameter id -> [[tick, value], ...] */
    public final Map<String, double[][]> curves = new LinkedHashMap<String, double[][]>();
    /** per-note edits, only the fields the model actually sent are set */
    public final List<NoteEdit> notes = new ArrayList<NoteEdit>();
    /** new tempo in BPM, or null to leave it alone */
    public Double tempo;
    /** track volume 0..127, or null */
    public Integer trackVolume;
    /** track pan 0..127 (64 = centre), or null */
    public Integer trackPan;

    public boolean isEmpty() {
        return curves.isEmpty() && notes.isEmpty() && tempo == null && trackVolume == null && trackPan == null;
    }

    public int pointCount() {
        int n = 0;
        for (double[][] pts : curves.values()) n += pts.length;
        return n;
    }

    /** one note the model wants to change; {@code pos} is the key that finds it */
    public static final class NoteEdit {
        public long pos;
        public Long newPos;
        public Integer key, vel;
        public Long dur;
        public String lyric, phoneme;
    }

    public String summary() {
        StringBuilder b = new StringBuilder();
        if (!curves.isEmpty()) b.append(curves.size()).append(" params/");
        if (!notes.isEmpty()) b.append(notes.size()).append(" notes/");
        if (tempo != null) b.append("tempo/");
        if (trackVolume != null || trackPan != null) b.append("track/");
        if (b.length() > 0) b.setLength(b.length() - 1);
        return b.toString();
    }
}