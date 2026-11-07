package com.dsh.v4tune.format;

import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;

/** Minimal OpenUTAU .ustx export. */
public final class UstxWriter {

    private UstxWriter() { }

    public static void write(Song s, OutputStream out) throws Exception {
        Writer w = new OutputStreamWriter(out, Charset.forName("UTF-8"));
        w.write("ustx_version: 0.6\n");
        w.write("name: " + (s.masterName == null ? "BriVoice" : s.masterName) + "\n");
        w.write("comment: exported by BriVoice\n");
        w.write("tempo: " + s.tempoAt(0) + "\n");
        w.write("tracks:\n");
        for (int ti = 0; ti < s.tracks.size(); ti++) {
            Track t = s.tracks.get(ti);
            w.write("  - track_name: " + (t.name == null ? "Track" : t.name) + "\n");
            w.write("    singer: BriVoice\n");
            w.write("    notes:\n");
            ArrayList<Note> notes = new ArrayList<Note>();
            for (Part p : t.parts) notes.addAll(p.notes);
            Collections.sort(notes, new java.util.Comparator<Note>() {
                public int compare(Note a, Note b) { return Long.compare(a.pos, b.pos); }
            });
            for (Note n : notes) {
                w.write("      - position: " + n.pos + "\n");
                w.write("        duration: " + Math.max(60, n.dur) + "\n");
                w.write("        tone: " + n.key + "\n");
                w.write("        lyrics: " + (n.lyric == null || n.lyric.length() == 0 ? "a" : n.lyric) + "\n");
                w.write("        velocity: " + n.vel + "\n");
            }
            if (notes.isEmpty()) w.write("    notes: []\n");
        }
        w.flush();
    }
}
