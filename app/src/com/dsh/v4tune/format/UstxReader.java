package com.dsh.v4tune.format;

import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;

import java.util.ArrayList;

/**
 * OpenUTAU .ustx (YAML) import.
 *
 * <p>Real files keep notes in top-level {@code voice_parts}, each pointing at a track with
 * {@code track_no}; notes are relative to the part's {@code position}. The old reader only
 * looked at {@code track_name} and dumped every note into the last track, so multi-track
 * projects showed an empty piano roll and lyrics were dropped ({@code lyric}, singular).
 */
public final class UstxReader {

    private UstxReader() { }

    private static final class PartData {
        final Part part = new Part();
        int trackNo = 0;
        boolean hasNotes = false;
    }

    public static Song parse(String text) {
        String[] lines = text.replace("\r", "").split("\n");
        double tempo = 120;
        String name = "OpenUTAU";
        ArrayList<String> trackNames = new ArrayList<String>();
        ArrayList<PartData> parts = new ArrayList<PartData>();
        boolean inTracks = false, inParts = false, inNotes = false;
        PartData curPart = null;
        Note curNote = null;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            String[] kv = kv(line);
            if (kv == null) continue;
            String key = kv[0], val = kv[1];

            if (key.equals("ustx_version")) continue;
            if (key.equals("name") && !inTracks && !inParts) {
                if (val.length() > 0 && name.equals("OpenUTAU")) name = val;
                continue;
            }
            if (key.equals("tempo") && !inParts) {
                tempo = num(val, tempo);
                continue;
            }
            if (key.equals("tracks")) { inTracks = true; inParts = false; inNotes = false; continue; }
            if (key.equals("voice_parts") || key.equals("parts")) {
                inTracks = false; inParts = true; inNotes = false; curPart = null; continue;
            }
            if (inTracks) {
                if (key.equals("track_name")) {
                    String n = val;
                    if (n.startsWith("\"") && n.endsWith("\"") && n.length() > 1) n = n.substring(1, n.length() - 1);
                    trackNames.add(n);
                }
                continue;
            }
            if (!inParts) {
                // top-level tempos list: "- position: 0" is skipped, "bpm: 120" is read
                if (key.equals("bpm")) tempo = num(val, tempo);
                continue;
            }

            // A new voice part starts with "- name:"; this also ends the previous note list.
            if (line.startsWith("-") && key.equals("name")) {
                curPart = new PartData();
                parts.add(curPart);
                inNotes = false;
                curNote = null;
                continue;
            }
            if (!inNotes && line.startsWith("-") && key.equals("position")) {
                if (curPart == null || curPart.hasNotes) {
                    curPart = new PartData();
                    parts.add(curPart);
                }
                curPart.part.pos = (long) num(val, 0);
                continue;
            }
            if (key.equals("notes")) {
                if (curPart == null) { curPart = new PartData(); parts.add(curPart); }
                inNotes = !val.equals("[]");
                curNote = null;
                continue;
            }
            if (inNotes) {
                if (line.startsWith("-") && key.equals("position")) {
                    if (curPart == null) { curPart = new PartData(); parts.add(curPart); }
                    curNote = new Note();
                    curNote.pos = (long) num(val, 0);
                    curNote.dur = 480;
                    curNote.key = 60;
                    curNote.vel = 100;
                    curNote.lyric = "a";
                    curPart.part.notes.add(curNote);
                    curPart.hasNotes = true;
                    continue;
                }
                if (curNote == null) continue;
                if (key.equals("duration")) curNote.dur = Math.max(60, (long) num(val, 480));
                else if (key.equals("tone")) curNote.key = (int) num(val, 60);
                else if (key.equals("lyrics") || key.equals("lyric")) {
                    String ly = unquote(val);
                    if (ly.length() > 0) curNote.lyric = ly;
                } else if (key.equals("velocity")) curNote.vel = (int) num(val, 100);
                continue;
            }
            // part-level fields
            if (key.equals("track_no")) {
                if (curPart != null) curPart.trackNo = (int) num(val, 0);
            } else if (key.equals("position") && curPart != null) {
                curPart.part.pos = (long) num(val, 0);
            } else if (key.equals("curves")) {
                inNotes = false;
                curNote = null;
            }
        }

        Song s = Song.empty();
        s.tracks.clear(); s.voices.clear(); s.tempos.clear(); s.timeSigs.clear();
        s.masterName = name;
        s.tempos.add(new long[]{0, Math.round(tempo * 100)});
        Voice v = new Voice();
        v.compID = "";
        v.name = "UTAU";
        v.lang = 0;
        s.voices.add(v);

        int trackCount = trackNames.size();
        for (PartData pd : parts) if (pd.trackNo + 1 > trackCount) trackCount = pd.trackNo + 1;
        if (trackCount == 0) trackCount = 1;
        for (int i = 0; i < trackCount; i++) {
            Track t = new Track();
            t.no = i;
            t.name = i < trackNames.size() && trackNames.get(i).length() > 0 ? trackNames.get(i) : ("Track " + (i + 1));
            s.tracks.add(t);
        }
        for (PartData pd : parts) {
            long max = 1920;
            for (Note n : pd.part.notes) max = Math.max(max, n.pos + n.dur);
            pd.part.dur = max;
            int ti = Math.max(0, Math.min(pd.trackNo, s.tracks.size() - 1));
            s.tracks.get(ti).parts.add(pd.part);
        }
        // a file with no parts still gets one empty part so editing works
        if (s.tracks.isEmpty()) { Track t = new Track(); t.name = name; t.parts.add(new Part()); s.tracks.add(t); }
        for (Track t : s.tracks) if (t.parts.isEmpty()) t.parts.add(new Part());
        return s;
    }

    /** split "key: value" or "- key : value"; null when the line is not a mapping */
    private static String[] kv(String line) {
        int c = line.indexOf(':');
        if (c < 0) return null;
        String k = line.substring(0, c).trim();
        if (k.startsWith("-")) k = k.substring(1).trim();
        String v = line.substring(c + 1).trim();
        return new String[]{k, v};
    }

    private static String unquote(String s) {
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        if (s.length() > 1 && s.startsWith("'") && s.endsWith("'")) return s.substring(1, s.length() - 1);
        return s;
    }

    private static double num(String s, double dflt) {
        try { return Double.parseDouble(unquote(s)); } catch (Exception e) { return dflt; }
    }
}
