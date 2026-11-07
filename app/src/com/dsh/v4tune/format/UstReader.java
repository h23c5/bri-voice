package com.dsh.v4tune.format;

import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;

/** Minimal UTAU .ust / OpenUTAU .ustx import. Notes map 1:1 to the internal 480-ppq model. */
public final class UstReader {

    private UstReader() { }

    public static Song read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        in.close();
        return parse(decode(out.toByteArray()));
    }

    static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF)
            return new String(b, 3, b.length - 3, Charset.forName("UTF-8"));
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)
            return new String(b, 2, b.length - 2, Charset.forName("UTF-16LE"));
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF)
            return new String(b, 2, b.length - 2, Charset.forName("UTF-16BE"));
        String utf8 = new String(b, Charset.forName("UTF-8"));
        if (utf8.indexOf('\uFFFD') < 0) return utf8;
        return new String(b, Charset.forName("Shift_JIS"));
    }

    public static Song parse(String text) {
        String[] lines = text.replace("\r", "").split("\n");
        double tempo = 120;
        String name = "UTAU";
        ArrayList<long[]> notes = new ArrayList<long[]>();
        ArrayList<String> lyrics = new ArrayList<String>();
        String section = "";
        long tick = 0;
        long len = 480;
        int key = 60;
        String lyric = "";
        int vel = 100;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("[")) {
                flush(section, tick, len, key, lyric, vel, notes, lyrics);
                if (section.length() > 1 && Character.isDigit(section.charAt(1))) tick += len;
                section = line.substring(1, line.endsWith("]") ? line.length() - 1 : line.length());
                len = 480; key = 60; lyric = ""; vel = 100;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1);
            if (section.equals("#SETTING")) {
                if (k.equals("Tempo")) { try { tempo = Double.parseDouble(v); } catch (Exception ignored) {} }
                else if (k.equals("ProjectName") && v.length() > 0) name = v;
            } else if (section.startsWith("#")) {
                if (k.equals("Length")) { try { len = Long.parseLong(v.trim()); } catch (Exception ignored) {} }
                else if (k.equals("NoteNum")) { try { key = Integer.parseInt(v.trim()); } catch (Exception ignored) {} }
                else if (k.equals("Lyric")) lyric = v;
                else if (k.equals("Velocity")) { try { vel = Integer.parseInt(v.trim()); } catch (Exception ignored) {} }
            }
        }
        flush(section, tick, len, key, lyric, vel, notes, lyrics);
        return build(name, tempo, notes, lyrics);
    }

    private static void flush(String section, long tick, long len, int key, String lyric, int vel,
                              ArrayList<long[]> notes, ArrayList<String> lyrics) {
        if (!section.startsWith("#") || section.equals("#SETTING") || section.equals("#TRACKEND")) return;
        if (section.length() < 2) return;
        char c = section.charAt(1);
        if (c < '0' || c > '9') return;
        String ly = lyric == null ? "" : lyric;
        if (ly.equalsIgnoreCase("R")) return;
        if (ly.length() == 0) ly = "a";
        notes.add(new long[]{tick, Math.max(60, len), key, vel});
        lyrics.add(ly);
    }

    private static Song build(String name, double tempo, ArrayList<long[]> notes, ArrayList<String> lyrics) {
        Song s = Song.empty();
        s.tracks.clear(); s.voices.clear(); s.tempos.clear(); s.timeSigs.clear();
        s.masterName = name;
        s.tempos.add(new long[]{0, Math.round(tempo * 100)});
        Voice v = new Voice();
        v.compID = "";
        v.name = "UTAU";
        v.lang = 0;
        s.voices.add(v);
        Track t = new Track();
        t.no = 0;
        t.name = name;
        Part p = new Part();
        p.pos = 0;
        p.bs = 0;
        p.pc = 0;
        long max = 1920;
        for (int i = 0; i < notes.size(); i++) {
            long[] n = notes.get(i);
            Note note = new Note();
            note.pos = n[0];
            note.dur = n[1];
            note.key = (int) n[2];
            note.vel = (int) n[3];
            note.lyric = lyrics.get(i);
            p.notes.add(note);
            max = Math.max(max, n[0] + n[1]);
        }
        p.dur = max;
        t.parts.add(p);
        s.tracks.add(t);
        return s;
    }
}
