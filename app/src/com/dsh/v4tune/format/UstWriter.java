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

/** Minimal UTAU .ust export (UTF-8, readable by OpenUTAU; classic UTAU prefers Shift-JIS). */
public final class UstWriter {

    private UstWriter() { }

    public static void write(Song s, OutputStream out) throws Exception {
        Writer w = new OutputStreamWriter(out, Charset.forName("UTF-8"));
        w.write("\uFEFF");
        w.write("[#SETTING]\r\n");
        w.write("Tempo=" + s.tempoAt(0) + "\r\n");
        w.write("Tracks=" + Math.max(1, s.tracks.size()) + "\r\n");
        w.write("ProjectName=" + (s.masterName == null ? "" : s.masterName) + "\r\n");
        w.write("VoiceDir=\r\n");
        Track t = s.tracks.isEmpty() ? null : s.tracks.get(0);
        ArrayList<Note> notes = new ArrayList<Note>();
        if (t != null) for (Part p : t.parts) notes.addAll(p.notes);
        Collections.sort(notes, new java.util.Comparator<Note>() {
            public int compare(Note a, Note b) { return Long.compare(a.pos, b.pos); }
        });
        for (int i = 0; i < notes.size(); i++) {
            Note n = notes.get(i);
            w.write("[#" + String.format("%04d", i) + "]\r\n");
            w.write("Length=" + Math.max(60, n.dur) + "\r\n");
            w.write("Lyric=" + (n.lyric == null || n.lyric.length() == 0 ? "a" : n.lyric) + "\r\n");
            w.write("NoteNum=" + n.key + "\r\n");
            w.write("Velocity=" + n.vel + "\r\n");
            w.write("PreUtterance=\r\n\r\n");
        }
        w.write("[#TRACKEND]\r\n");
        w.flush();
    }
}
