package com.dsh.v4tune.format;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;

import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;

/**
 * Standard MIDI File importer (SMF format 0 and 1) producing a {@link Song} at the fixed project
 * resolution of 480 ticks per quarter note.
 *
 * <h3>What is parsed</h3>
 * <ul>
 *   <li>{@code MThd} / {@code MTrk} chunks, big endian lengths, variable length quantities,
 *       running status, note on / note off (a note on with velocity 0 is a note off), note
 *       pairing per MIDI track.</li>
 *   <li>Meta events: set tempo {@code 0x51}, time signature {@code 0x58}, track name {@code 0x03},
 *       end of track {@code 0x2F}. SysEx and every other meta event is skipped by its length.</li>
 *   <li>The division word from the header: PPQ values are rescaled to 480
 *       ({@code tick480 = round(tickMidi * 480 / division)}).</li>
 * </ul>
 *
 * <h3>Documented normalisations</h3>
 * <ul>
 *   <li>One {@link Track} is produced per MIDI track that contains at least one note, in MTrk
 *       order; the track name comes from the {@code 0x03} meta event ("MIDI Track n" otherwise).
 *       Tracks without notes (a conductor track) are dropped, but their tempo and time signature
 *       events are still collected. Each track gets a single {@link Part} with pos 0,
 *       dur = last note end; the notes keep absolute ticks.</li>
 *   <li>Tempo map: all collected set tempo events, de-duplicated by tick and sorted; when the map
 *       is empty or starts after tick 0, 120 BPM at tick 0 is inserted so that
 *       {@link Song#tempoAt(long)} is defined everywhere. Tempo is stored as BPM x 100
 *       (the value written by the {@code 0x51} meta event is converted exactly:
 *       {@code round(60000000 / usPerQuarter * 100)}).</li>
 *   <li>Time signatures: {@code de} is expanded from the SMF power of two ({@code 2^dd}),
 *       de-duplicated by tick, sorted, defaulted to 4/4 at tick 0.</li>
 *   <li>Lyrics are not part of a MIDI file: every imported note gets lyric "a" and an empty
 *       phoneme (auto), velocity from the note on event, duration = note on .. note off (at least
 *       1 tick, 480 ticks when a note is never turned off).</li>
 *   <li>SMPTE division (bit 15 of the division word set) cannot be expressed in musical ticks.
 *       The file is still imported: the frame rate and ticks per frame are converted to seconds
 *       and then to 480 PPQ ticks at a nominal 120 BPM ({@code tick480 = round(seconds * 960)}),
 *       and {@link #lastWasSmpte} is set. -29 means 29.97 drop frame.</li>
 *   <li>Track names are decoded as Shift-JIS (most MIDI files with Japanese names are), falling
 *       back to ISO-8859-1 when the bytes do not fit that code page.</li>
 * </ul>
 */
public final class MidiReader {

    /** Ticks per quarter note of the Song model. */
    public static final int TPQ = 480;

    /** Set when the file read last used SMPTE division (the tick mapping is then approximate). */
    public static boolean lastWasSmpte = false;
    /** Division word of the file read last (0 when unknown). */
    public static int lastDivision = 0;
    /** Number of MTrk chunks in the file read last. */
    public static int lastTrackCount = 0;
    /** SMF format of the file read last (0 or 1, -1 when unknown). */
    public static int lastFormat = -1;

    private MidiReader() {
    }

    public static Song readFile(File f) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(f));
        try {
            return read(in);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static Song read(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) all.write(buf, 0, r);
        return read(all.toByteArray());
    }

    // ------------------------------------------------------------------ parser

    private static final class RawNote {
        long tick;
        long dur = 1;
        int key;
        int vel;
        int trackIndex;
        String trackName = "";
    }

    private static final class Cursor {
        final byte[] d;
        int p;

        Cursor(byte[] d, int p) {
            this.d = d;
            this.p = p;
        }

        int u8() {
            return p < d.length ? d[p++] & 0xFF : 0;
        }

        int u16() {
            return (u8() << 8) | u8();
        }

        long u32() {
            return ((long) u8() << 24) | ((long) u8() << 16) | ((long) u8() << 8) | (long) u8();
        }

        long vlq() {
            long v = 0;
            for (int i = 0; i < 4; i++) {
                int b = u8();
                v = (v << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) break;
            }
            return v;
        }
    }

    public static Song read(byte[] d) throws IOException {
        lastWasSmpte = false;
        lastDivision = 0;
        lastTrackCount = 0;
        lastFormat = -1;
        if (d == null || d.length < 14 || d[0] != 'M' || d[1] != 'T' || d[2] != 'h' || d[3] != 'd') {
            throw new IOException("not a standard MIDI file (missing MThd)");
        }
        Cursor c = new Cursor(d, 0);
        c.p = 4;
        long hlen = c.u32();
        lastFormat = c.u16();
        int ntrks = c.u16();
        int division = c.u16();
        lastDivision = division;
        c.p = (int) Math.min(d.length, 8 + hlen);

        double scale = 1.0;
        double smpteFps = 0;
        if ((division & 0x8000) != 0) {
            lastWasSmpte = true;
            int fps = (division >> 8) & 0xFF;
            int tpf = division & 0xFF;
            if (fps > 127) fps = 256 - fps;                   // -29 means 29.97 drop frame
            if (fps <= 0) fps = 25;
            if (tpf <= 0) tpf = 4;
            smpteFps = (fps == 29 ? 29.97 : fps) * tpf;       // ticks per second
        } else if (division > 0) {
            scale = (double) TPQ / (double) division;
        }

        ArrayList<RawNote> notes = new ArrayList<RawNote>();
        ArrayList<long[]> tempos = new ArrayList<long[]>();
        ArrayList<int[]> sigs = new ArrayList<int[]>();
        int found = 0;

        while (c.p + 8 <= d.length && found < Math.max(ntrks, 0)) {
            if (!(d[c.p] == 'M' && d[c.p + 1] == 'T' && d[c.p + 2] == 'r' && d[c.p + 3] == 'k')) {
                int q = indexOfMTrk(d, c.p + 1);
                if (q < 0) break;
                c.p = q;
            }
            c.p += 4;
            long len = c.u32();
            int end = (int) Math.min((long) d.length, (long) c.p + len);
            found++;
            parseTrack(c, end, lastWasSmpte ? -smpteFps : scale, notes, tempos, sigs,
                    found - 1, "MIDI Track " + found);
            c.p = end;
        }
        lastTrackCount = found;

        // ---- assemble the Song ------------------------------------------------
        Collections.sort(notes, new Comparator<RawNote>() {
            public int compare(RawNote a, RawNote b) {
                if (a.trackIndex != b.trackIndex) return a.trackIndex < b.trackIndex ? -1 : 1;
                if (a.tick != b.tick) return a.tick < b.tick ? -1 : 1;
                return a.key < b.key ? -1 : (a.key > b.key ? 1 : 0);
            }
        });
        return build(notes, tempos, sigs);
    }

    private static Song build(ArrayList<RawNote> notes, ArrayList<long[]> tempos, ArrayList<int[]> sigs) {
        Song song = new Song();
        ArrayList<Track> tracks = new ArrayList<Track>();
        LinkedHashMap<Integer, Track> byIndex = new LinkedHashMap<Integer, Track>();
        for (int i = 0; i < notes.size(); i++) {
            RawNote rn = notes.get(i);
            Integer key = Integer.valueOf(rn.trackIndex);
            Track tr = byIndex.get(key);
            if (tr == null) {
                tr = new Track();
                tr.no = tracks.size();
                tr.name = rn.trackName;
                tr.parts.add(new Part());
                byIndex.put(key, tr);
                tracks.add(tr);
            }
            Part p = tr.parts.get(0);
            Note n = new Note();
            n.pos = rn.tick;
            n.dur = rn.dur > 0 ? rn.dur : 1;
            n.key = rn.key;
            n.vel = rn.vel;
            n.lyric = "a";
            n.phoneme = "";
            p.notes.add(n);
        }
        for (int i = 0; i < tracks.size(); i++) {
            Track tr = tracks.get(i);
            Part p = tr.parts.get(0);
            p.sortNotes();
            long end = 0;
            for (int k = 0; k < p.notes.size(); k++) {
                Note n = p.notes.get(k);
                if (n.pos + n.dur > end) end = n.pos + n.dur;
            }
            p.dur = end;
            p.name = tr.name;
            p.comment = "Imported from MIDI";
            song.tracks.add(tr);
        }
        if (song.tracks.isEmpty()) {
            Track tr = new Track();
            tr.no = 0;
            tr.name = "MIDI Track";
            tr.parts.add(new Part());
            song.tracks.add(tr);
        }

        Collections.sort(tempos, new Comparator<long[]>() {
            public int compare(long[] a, long[] b) {
                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
            }
        });
        long lastTick = Long.MIN_VALUE;
        for (int i = 0; i < tempos.size(); i++) {
            long[] t = tempos.get(i);
            if (t[0] == lastTick) song.tempos.set(song.tempos.size() - 1, t);
            else {
                song.tempos.add(t);
                lastTick = t[0];
            }
        }
        if (song.tempos.isEmpty() || song.tempos.get(0)[0] > 0) {
            song.tempos.add(0, new long[]{0, 12000});
        }
        Collections.sort(sigs, new Comparator<int[]>() {
            public int compare(int[] a, int[] b) {
                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
            }
        });
        int lastSig = Integer.MIN_VALUE;
        for (int i = 0; i < sigs.size(); i++) {
            int[] t = sigs.get(i);
            if (t[0] == lastSig) song.timeSigs.set(song.timeSigs.size() - 1, t);
            else {
                song.timeSigs.add(t);
                lastSig = t[0];
            }
        }
        if (song.timeSigs.isEmpty() || song.timeSigs.get(0)[0] > 0) {
            song.timeSigs.add(0, new int[]{0, 4, 4});
        }

        Voice v = new Voice();
        v.bs = 0;
        v.pc = 0;
        v.name = "MIDI";
        v.compID = "";
        v.lang = 0;
        song.voices.add(v);
        song.masterName = "";
        for (int i = 0; i < song.tracks.size(); i++) {
            Part p = song.tracks.get(i).parts.get(0);
            p.bs = 0;
            p.pc = 0;
        }
        return song;
    }

    private static int indexOfMTrk(byte[] d, int from) {
        for (int i = Math.max(0, from); i + 4 <= d.length; i++) {
            if (d[i] == 'M' && d[i + 1] == 'T' && d[i + 2] == 'r' && d[i + 3] == 'k') return i;
        }
        return -1;
    }

    private static void parseTrack(Cursor c, int end, double scale, ArrayList<RawNote> notes,
                                   ArrayList<long[]> tempos, ArrayList<int[]> sigs, int trackIndex,
                                   String name) {
        int status = 0;
        String trackName = name;
        ArrayList<RawNote> open = new ArrayList<RawNote>();
        long tick = 0;
        while (c.p < end) {
            long delta = c.vlq();
            tick += delta;
            int b = c.u8();
            if (b == 0xFF) {
                int type = c.u8();
                long len = c.vlq();
                int start = c.p;
                if (type == 0x51 && len >= 3) {
                    int us = ((c.d[c.p] & 0xFF) << 16) | ((c.d[c.p + 1] & 0xFF) << 8) | (c.d[c.p + 2] & 0xFF);
                    if (us > 0) tempos.add(new long[]{tick480(tick, scale), Math.round(60000000.0 / us * 100.0)});
                } else if (type == 0x58 && len >= 2) {
                    int nu = c.d[c.p] & 0xFF;
                    int dd = c.d[c.p + 1] & 0xFF;
                    int de = 1;
                    for (int i = 0; i < dd && i < 8; i++) de *= 2;
                    sigs.add(new int[]{(int) tick480(tick, scale), nu > 0 ? nu : 4, de > 0 ? de : 4});
                } else if (type == 0x03) {
                    String nm = decodeText(c.d, c.p, (int) len);
                    if (nm.length() > 0) trackName = nm;
                } else if (type == 0x2F) {
                    c.p = end;
                    break;
                }
                c.p = start + (int) len;
                continue;
            }
            if (b == 0xF0 || b == 0xF7) {
                long len = c.vlq();
                c.p += (int) len;
                continue;
            }
            if (b >= 0x80) {
                status = b;
            } else {
                c.p--;
                b = status;
            }
            int hi = b & 0xF0;
            if (hi == 0x90 || hi == 0x80) {
                int key = c.u8();
                int vel = c.u8();
                if (hi == 0x90 && vel > 0) {
                    RawNote rn = new RawNote();
                    rn.tick = tick480(tick, scale);
                    rn.key = key;
                    rn.vel = vel;
                    rn.trackIndex = trackIndex;
                    rn.trackName = trackName;
                    open.add(rn);
                } else {
                    closeNote(open, key, tick480(tick, scale), notes);
                }
            } else if (hi == 0xA0 || hi == 0xB0 || hi == 0xE0) {
                c.p += 2;
            } else if (hi == 0xC0 || hi == 0xD0) {
                c.p += 1;
            } else {
                c.p = end;      // unknown status: stop this track instead of reading garbage
                break;
            }
        }
        for (int i = 0; i < open.size(); i++) {
            RawNote rn = open.get(i);
            rn.dur = 480;           // note on without a note off: assume a quarter note
            notes.add(rn);
        }
    }

    private static void closeNote(ArrayList<RawNote> open, int key, long endTick, ArrayList<RawNote> notes) {
        for (int i = 0; i < open.size(); i++) {
            RawNote rn = open.get(i);
            if (rn.key != key) continue;
            RawNote out = new RawNote();
            out.tick = rn.tick;
            long dur = endTick - rn.tick;
            out.dur = dur < 1 ? 1 : dur;
            out.key = rn.key;
            out.vel = rn.vel;
            out.trackIndex = rn.trackIndex;
            out.trackName = rn.trackName;
            notes.add(out);
            open.remove(i);
            return;
        }
    }

    private static long tick480(long tick, double scale) {
        if (scale < 0) {
            double seconds = tick / (-scale);       // scale carries ticks per second (negative marker)
            return Math.round(seconds * 960.0);     // 480 PPQ at a nominal 120 BPM
        }
        if (scale == 1.0) return tick;
        return Math.round(tick * scale);
    }

    private static String decodeText(byte[] d, int off, int len) {
        if (len <= 0 || off < 0 || off + len > d.length) return "";
        try {
            String s = new String(d, off, len, "MS932");
            if (s.indexOf(0xFFFD) < 0) return s.trim();
        } catch (Throwable ignored) {
        }
        try {
            return new String(d, off, len, "ISO-8859-1").trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
