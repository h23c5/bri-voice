package com.dsh.v4tune.format;

import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;

/** Minimal OpenUTAU .ustx (YAML) import: tempo, track_name, notes (position/duration/tone/lyrics/velocity). */
public final class UstxReader {

    private UstxReader() { }

    public static Song parse(String text) {
        Song s = Song.empty();
        s.tracks.clear(); s.voices.clear(); s.tempos.clear(); s.timeSigs.clear();
        double tempo = 120;
        String name = "OpenUTAU";
        s.tempos.add(new long[]{0, 12000});
        Voice v = new Voice();
        v.compID = "";
        v.name = "UTAU";
        v.lang = 0;
        s.voices.add(v);
        String[] lines = text.replace("\r", "").split("\n");
        Track track = null;
        Part part = null;
        boolean inNotes = false;
        Note cur = null;
        int no = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            int ti = line.indexOf("track_name:");
            if (ti >= 0) {
                if (part != null && track != null) { finishPart(part); track.parts.add(part); }
                if (track != null) s.tracks.add(track);
                track = new Track();
                track.no = no++;
                track.name = line.substring(ti + 11).trim();
                part = new Part();
                part.pos = 0;
                part.bs = 0;
                part.pc = 0;
                inNotes = false;
                cur = null;
                continue;
            }
            if (line.equals("notes:") || line.endsWith(" notes:")) { inNotes = true; continue; }
            if (track == null && line.startsWith("tempo:")) {
                try { tempo = Double.parseDouble(line.substring(6).trim()); } catch (Exception ignored) {}
                continue;
            }
            if (track == null && line.startsWith("name:")) {
                String n = line.substring(5).trim();
                if (n.length() > 0) name = n;
                continue;
            }
            if (!inNotes || part == null) continue;
            if (line.startsWith("- position:")) {
                if (cur != null) part.notes.add(cur);
                cur = new Note();
                cur.pos = (long) parseNum(line.substring(11).trim(), 0);
                cur.dur = 480;
                cur.key = 60;
                cur.vel = 100;
                cur.lyric = "a";
                continue;
            }
            if (cur == null) continue;
            if (line.startsWith("duration:")) cur.dur = Math.max(60, (long) parseNum(line.substring(9).trim(), 480));
            else if (line.startsWith("tone:")) cur.key = (int) parseNum(line.substring(5).trim(), 60);
            else if (line.startsWith("lyrics:")) {
                String ly = line.substring(7).trim();
                if (ly.startsWith("\"") && ly.endsWith("\"") && ly.length() > 1) ly = ly.substring(1, ly.length() - 1);
                if (ly.length() > 0) cur.lyric = ly;
            } else if (line.startsWith("velocity:")) cur.vel = (int) parseNum(line.substring(9).trim(), 100);
        }
        if (cur != null && part != null) part.notes.add(cur);
        if (part != null && track != null) { finishPart(part); track.parts.add(part); }
        if (track != null) s.tracks.add(track);
        if (s.tracks.isEmpty()) { track = new Track(); track.name = name; part = new Part(); part.pos = 0; finishPart(part); track.parts.add(part); s.tracks.add(track); }
        s.masterName = name;
        s.tempos.clear();
        s.tempos.add(new long[]{0, Math.round(tempo * 100)});
        return s;
    }

    private static void finishPart(Part p) {
        long max = 1920;
        for (Note n : p.notes) max = Math.max(max, n.pos + n.dur);
        p.dur = max;
    }

    private static double parseNum(String s, double dflt) {
        try { return Double.parseDouble(s); } catch (Exception e) { return dflt; }
    }
}
