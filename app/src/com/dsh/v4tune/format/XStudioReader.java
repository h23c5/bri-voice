package com.dsh.v4tune.format;

import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * NetEase Cloud Music XStudio .svip3 project import.
 *
 * <p>A .svip3 file is a protobuf AppModel: field 1 is the original project path, field 2 the
 * writer version (3.0.x), field 3 the duration in milliseconds, field 4 the tempo, field 5 the
 * time signature and field 6 repeated google.protobuf.Any tracks. A SingingTrack carries its
 * name in field 4 and one or more SingingPattern messages in field 8; each pattern's field 8
 * holds notes. Note fields are 1 = start ms, 2 = duration ms, 3 = MIDI key, 4 = lyric,
 * 5 = pinyin/pronouncing, 8 = velocity (0..127). Positions are absolute milliseconds, not ticks.
 *
 * <p>The reader is deliberately dependency-free: the app ships no protobuf runtime. It rejects
 * malformed lengths instead of trusting them, and ignores audio tracks (their media files are
 * external to the project anyway).
 */
public final class XStudioReader {

    private XStudioReader() { }

    private static final int PPQ = Song.DEFAULT_RESOLUTION;
    private static final int MAX_TRACKS = 128;
    private static final int MAX_NOTES = 200000;

    private static final class XNote {
        int posMs, durMs = 100, key = 60, vel = 64;
        String lyric = "a";
    }

    private static final class XTrack {
        String name = "";
        final ArrayList<XNote> notes = new ArrayList<XNote>();
    }

    /** Cheap signature test used by ProjectIO before the generic text/XML sniffers. */
    public static boolean looksLike(byte[] d) {
        if (d == null || d.length < 8 || d[0] != 0x0A) return false;
        int n = Math.min(d.length, 600);
        String head = new String(d, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(java.util.Locale.US);
        return head.indexOf(".svip3") >= 0;
    }

    public static Song read(byte[] data) throws Exception {
        if (data == null || data.length < 8) throw new Exception("XStudio 工程为空");
        if (!looksLike(data)) throw new Exception("不是 XStudio .svip3 工程（缺少 .svip3 头）");
        ArrayList<XTrack> xTracks = new ArrayList<XTrack>();
        ArrayList<int[]> tempos = new ArrayList<int[]>();     // {posMs, bpm*100}
        ArrayList<int[]> beats = new ArrayList<int[]>();      // {posMs, num, den}
        int durationMs = 0;
        int audioTracks = 0;

        Cursor c = new Cursor(data, 0, data.length);
        while (c.hasMore()) {
            int tag = c.tag();
            int field = tag >>> 3, wire = tag & 7;
            if (wire == 2) {
                byte[] v = c.bytes();
                if (field == 4) {
                    int[] t = parseTempo(v);
                    if (t != null) tempos.add(t);
                } else if (field == 5) {
                    int[] b = parseBeat(v);
                    if (b != null) beats.add(b);
                } else if (field == 6) {
                    String type = null;
                    byte[] payload = null;
                    Cursor a = new Cursor(v, 0, v.length);
                    while (a.hasMore()) {
                        int at = a.tag();
                        int af = at >>> 3, aw = at & 7;
                        if (aw == 2) {
                            byte[] av = a.bytes();
                            if (af == 1) type = utf8(av);
                            else if (af == 2) payload = av;
                        } else a.skip(aw);
                    }
                    if (type != null && type.endsWith("SingingTrack") && payload != null) {
                        if (xTracks.size() < MAX_TRACKS) {
                            XTrack t = parseSingingTrack(payload, xTracks.size() + 1);
                            if (t != null && !t.notes.isEmpty()) xTracks.add(t);
                        }
                    } else if (type != null && type.endsWith("AudioTrack")) {
                        audioTracks++;
                    }
                }
            } else if (wire == 0) {
                long v = c.varint();
                if (field == 3) durationMs = (int) Math.min(Integer.MAX_VALUE, v);
            } else {
                c.skip(wire);
            }
        }

        if (xTracks.isEmpty()) {
            throw new Exception("XStudio 工程里没有可导入的音符"
                    + (audioTracks > 0 ? "（只有 " + audioTracks + " 条音频轨）" : ""));
        }

        int bpm100 = tempos.isEmpty() ? 12000 : tempos.get(0)[1];
        if (bpm100 < 1000 || bpm100 > 60000) bpm100 = 12000;   // 10..600 BPM sanity
        double bpm = bpm100 / 100.0;

        Song song = new Song();
        song.vendor = "NetEase XStudio";
        song.version = "3.0.0";
        song.schema = 4;
        song.masterName = "XStudio";
        Voice voice = new Voice();
        voice.bs = 4; voice.pc = 1; voice.name = "Luotianyi_CHN_Meng";
        voice.compID = "BK8H76TAEHXWSKDB"; voice.lang = 2;
        song.voices.add(voice);
        song.tempos.add(new long[]{0, bpm100});
        int[] beat = beats.isEmpty() ? new int[]{0, 4, 4} : beats.get(0);
        int num = beat.length > 1 ? beat[1] : 4, den = beat.length > 2 ? beat[2] : 4;
        if (num < 1) num = 4;
        if (den < 1) den = 4;
        song.timeSigs.add(new int[]{0, num, den, den});

        int noteTotal = 0;
        long maxTick = 0;
        for (int ti = 0; ti < xTracks.size(); ti++) {
            XTrack xt = xTracks.get(ti);
            Track tr = new Track();
            tr.no = ti;
            tr.name = xt.name == null || xt.name.trim().isEmpty() ? ("XStudio " + (ti + 1)) : xt.name.trim();
            Part part = new Part();
            part.pos = 0;
            part.name = tr.name;
            part.bs = 0; part.pc = 0;
            for (int ni = 0; ni < xt.notes.size(); ni++) {
                XNote xn = xt.notes.get(ni);
                if (xn == null || xn.key < 0 || xn.key > 127) continue;
                Note n = new Note();
                n.pos = msToTick(xn.posMs, bpm);
                n.dur = Math.max(1L, msToTick(xn.durMs, bpm));
                n.key = xn.key;
                n.vel = Math.max(0, Math.min(127, xn.vel));
                n.lyric = (xn.lyric == null || xn.lyric.trim().isEmpty()) ? "a" : xn.lyric;
                n.phoneme = "";
                part.notes.add(n);
                maxTick = Math.max(maxTick, n.pos + n.dur);
                noteTotal++;
                if (noteTotal >= MAX_NOTES) break;
            }
            if (part.notes.isEmpty()) continue;
            part.sortNotes();
            long end = maxTick + PPQ * 4L;
            long projectEnd = durationMs > 0 ? msToTick(durationMs, bpm) : 0;
            if (projectEnd > end) end = projectEnd;
            part.dur = Math.max(PPQ * 4L, end);
            tr.parts.add(part);
            song.tracks.add(tr);
            if (noteTotal >= MAX_NOTES) break;
        }
        if (song.tracks.isEmpty()) throw new Exception("XStudio 工程里没有可导入的音符");
        return song;
    }

    private static long msToTick(int ms, double bpm) {
        if (ms <= 0) return 0;
        return Math.max(0L, Math.round(ms * (double) PPQ * bpm / 60000.0));
    }

    private static int[] parseTempo(byte[] msg) {
        if (msg == null) return null;
        int pos = 0, bpm100 = 0;
        Cursor c = new Cursor(msg, 0, msg.length);
        try {
            while (c.hasMore()) {
                int tag = c.tag();
                int f = tag >>> 3, w = tag & 7;
                if (w == 0) {
                    long v = c.varint();
                    if (f == 1) pos = (int) Math.min(Integer.MAX_VALUE, v);
                    else if (f == 2) bpm100 = (int) Math.min(60000, v);
                } else c.skip(w);
            }
        } catch (Exception e) { return null; }
        if (bpm100 <= 0) return null;
        return new int[]{pos, bpm100};
    }

    private static int[] parseBeat(byte[] msg) {
        if (msg == null) return null;
        int pos = 0, num = 4, den = 4;
        Cursor c = new Cursor(msg, 0, msg.length);
        try {
            while (c.hasMore()) {
                int tag = c.tag();
                int f = tag >>> 3, w = tag & 7;
                if (w == 2) {
                    byte[] v = c.bytes();
                    if (f == 2) {
                        Cursor b = new Cursor(v, 0, v.length);
                        while (b.hasMore()) {
                            int bt = b.tag();
                            int bf = bt >>> 3, bw = bt & 7;
                            if (bw == 0) {
                                long x = b.varint();
                                if (bf == 1) num = (int) x;
                                else if (bf == 2) den = (int) x;
                            } else b.skip(bw);
                        }
                    }
                } else if (w == 0) {
                    long v = c.varint();
                    if (f == 1) pos = (int) Math.min(Integer.MAX_VALUE, v);
                } else c.skip(w);
            }
        } catch (Exception e) { return null; }
        if (num < 1 || num > 32) num = 4;
        if (den < 1 || den > 32) den = 4;
        return new int[]{pos, num, den};
    }

    private static XTrack parseSingingTrack(byte[] msg, int index) {
        XTrack t = new XTrack();
        Cursor c = new Cursor(msg, 0, msg.length);
        try {
            while (c.hasMore()) {
                int tag = c.tag();
                int f = tag >>> 3, w = tag & 7;
                if (w == 2) {
                    byte[] v = c.bytes();
                    if (f == 4) {
                        t.name = utf8(v);
                    } else if (f == 8) {
                        parsePattern(v, t);
                    }
                } else c.skip(w);
            }
        } catch (Exception e) {
            t.notes.clear();
        }
        if (t.name == null) t.name = "";
        if (t.name.trim().isEmpty()) t.name = "XStudio " + index;
        return t;
    }

    private static void parsePattern(byte[] msg, XTrack t) {
        Cursor c = new Cursor(msg, 0, msg.length);
        try {
            while (c.hasMore()) {
                int tag = c.tag();
                int f = tag >>> 3, w = tag & 7;
                if (w == 2) {
                    byte[] v = c.bytes();
                    if (f == 8) {
                        XNote n = parseNote(v);
                        if (n != null && t.notes.size() < MAX_NOTES) t.notes.add(n);
                    }
                } else c.skip(w);
            }
        } catch (Exception ignored) {
        }
    }

    private static XNote parseNote(byte[] msg) {
        XNote n = new XNote();
        Cursor c = new Cursor(msg, 0, msg.length);
        boolean hasPos = false;
        try {
            while (c.hasMore()) {
                int tag = c.tag();
                int f = tag >>> 3, w = tag & 7;
                if (w == 0) {
                    long v = c.varint();
                    if (f == 1) { n.posMs = (int) Math.min(Integer.MAX_VALUE, v); hasPos = true; }
                    else if (f == 2) n.durMs = (int) Math.min(Integer.MAX_VALUE, v);
                    else if (f == 3) n.key = (int) v;
                    else if (f == 8) n.vel = (int) v;
                } else if (w == 2) {
                    byte[] v = c.bytes();
                    if (f == 4) n.lyric = utf8(v);
                    // field 5 is XStudio's own pinyin; BriVoice re-derives phonemes from the lyric
                } else c.skip(w);
            }
        } catch (Exception e) { return null; }
        if (!hasPos || n.durMs <= 0) return null;
        return n;
    }

    private static String utf8(byte[] b) {
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Minimal protobuf wire reader with strict bounds. */
    private static final class Cursor {
        private final byte[] b;
        private int p, end;

        Cursor(byte[] b, int from, int to) {
            this.b = b;
            this.p = from;
            this.end = Math.min(to, b.length);
        }

        boolean hasMore() { return p < end; }

        int tag() {
            long k = varint();
            if (k <= 0 || (k >>> 3) == 0) throw new IllegalArgumentException("bad protobuf tag");
            return (int) k;
        }

        long varint() {
            long v = 0; int s = 0;
            while (p < end) {
                int x = b[p++] & 0xff;
                v |= (long) (x & 0x7f) << s;
                if ((x & 0x80) == 0) return v;
                s += 7;
                if (s > 63) throw new IllegalArgumentException("protobuf varint too long");
            }
            throw new IllegalArgumentException("truncated protobuf varint");
        }

        byte[] bytes() {
            long n = varint();
            if (n < 0 || n > end - p) throw new IllegalArgumentException("protobuf length out of range");
            byte[] out = new byte[(int) n];
            System.arraycopy(b, p, out, 0, (int) n);
            p += (int) n;
            return out;
        }

        void skip(int wire) {
            if (wire == 0) { varint(); return; }
            if (wire == 1) { p += 8; }
            else if (wire == 2) { long n = varint(); if (n < 0 || n > end - p) throw new IllegalArgumentException("bad length"); p += (int) n; }
            else if (wire == 5) { p += 4; }
            else throw new IllegalArgumentException("unsupported protobuf wire type " + wire);
            if (p > end) throw new IllegalArgumentException("truncated protobuf field");
        }
    }
}
