package com.dsh.v4tune.format;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;

/**
 * Writer for the legacy VOCALOID2 INI style project file (".vsq"), the sibling of
 * {@link Vsq2Reader}; read {@link Vsq2Reader} for the full evidence list and for the list of
 * details that could not be recovered from the evidence.
 *
 * <h3>Key layout written (in this order)</h3>
 * <pre>
 * VOCALOID2 Project File1.0
 * [Common]       Version=DSB301 / Name / Color=181,162,123 / DynamicsMode=1 / PlayMode=1
 * [Master]       PreMeasure=&lt;n&gt;
 * [MasterTrack]  Tempo=&lt;bpm, 2 decimals&gt; / TimeSig=&lt;nu&gt;,&lt;de&gt;   (event at tick 0)
 *                Tempo&lt;i&gt;=&lt;tick&gt;,&lt;bpm&gt; / TimeSig&lt;i&gt;=&lt;tick&gt;,&lt;nu&gt;,&lt;de&gt;   (further events)
 * [Mixer]        MasterFeder=0 / MasterPanpot=0 / MasterMute=0 / OutputMode=0 / Tracks=&lt;n&gt;
 *                Feder&lt;i&gt; / Panpot&lt;i&gt; / Mute&lt;i&gt; / Solo&lt;i&gt;          (per track, i = 0 based)
 * then one block per track:
 * [Track&lt;i&gt;]     Name / Comment / Vocaloid&lt;i&gt;=&lt;bs&gt; / Voice&lt;i&gt;=&lt;pc&gt;      (i = 1 based)
 * [EventList]    &lt;clock&gt;=ID#0000[,ID#nnnn] ... &lt;end+120&gt;=EOS
 * [ID#nnnn]      Type=Singer / IconHandle=h#0000
 *                Type=Anote / Length / Note# / Dynamics / PMBendDepth / PMBendLength /
 *                PMbPortamentoUse / DEMdecGainRate / DEMaccent / LyricHandle / VibratoHandle /
 *                VibratoDelay
 * [h#nnnn]       L0="lyric","phoneme",1.000000,0,...,0     or the singer / vibrato handles
 * [XxxBPList]    &lt;tick&gt;=&lt;value&gt;      (curves with points, names from luavsq.lua:4716-4787)
 * </pre>
 *
 * <h3>Encoding rule (documented)</h3>
 * Candidate order is Shift-JIS (MS932) first when any kana (U+3040..U+30FF) is present in the
 * project, GBK first when no kana is present but a voice is Chinese ({@link Voice#lang} == 2),
 * Shift-JIS otherwise. The first candidate that can encode <b>all</b> text of the project wins,
 * so a project that mixes Japanese and Chinese keeps the Japanese code page whenever every
 * character exists in MS932 and falls back to GBK otherwise; UTF-8 is the last resort. The
 * decision is kept in {@link #lastCharset}.
 *
 * <h3>Normalisations applied on write</h3>
 * <ul>
 *   <li>All parts of a track are flattened into one event list (the legacy format has no parts);
 *       note ticks are written absolute, as ExportVSQ.lua does ({@code noteEx.posTick + offset}).</li>
 *   <li>Note durations are written as at least 1 tick, pitches/velocities clamped to 0..127.</li>
 *   <li>PreMeasure is derived from the earliest part/note tick (min 1, like the V2 editor).</li>
 *   <li>Note style "opening" is not stored per note: the legacy format keeps opening in the
 *       [OpeningBPList] curve, so it is written from {@link Part#ctrls} "OPE" only.</li>
 *   <li>Unknown curve keys ending in "BPList" are written back verbatim as their own section,
 *       everything else in {@link Part#ctrls} that is not a known curve is dropped.</li>
 * </ul>
 */
public final class Vsq2Writer {

    /** The V2 writer used CRLF (VocaListener.exe template lines end with 0x0d 0x0a). */
    public static final String NL = "\r\n";
    /** Charset used by the last {@link #toBytes(Song)} / {@link #write(Song, OutputStream)}. */
    public static String lastCharset = Vsq2Reader.CS_SJIS;

    private Vsq2Writer() {
    }

    // ------------------------------------------------------------------ entry points

    public static void write(Song song, OutputStream out) throws IOException {
        out.write(toBytes(song));
        out.flush();
    }

    public static void writeFile(Song song, File f) throws IOException {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(f));
        try {
            write(song, out);
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static byte[] toBytes(Song song) {
        String text = toText(song);
        String cs = chooseCharset(song);
        lastCharset = cs;
        Charset charset;
        try {
            charset = Charset.forName(cs);
        } catch (Throwable t) {
            charset = Charset.forName("UTF-8");
            lastCharset = Vsq2Reader.CS_UTF8;
        }
        return text.getBytes(charset);
    }

    /**
     * Shift-JIS when the project contains kana, GBK when it contains only Chinese text and a
     * Chinese voice, otherwise Shift-JIS; the first candidate that can encode everything wins.
     */
    public static String chooseCharset(Song song) {
        String all = allText(song == null ? Song.empty() : song);
        boolean nonAscii = false;
        boolean kana = false;
        for (int i = 0; i < all.length(); i++) {
            char c = all.charAt(i);
            if (c > 0x7F) nonAscii = true;
            if (c >= 0x3040 && c <= 0x30FF) kana = true;
        }
        if (!nonAscii) return Vsq2Reader.CS_SJIS;
        boolean chinese = false;
        if (song != null) {
            for (Voice v : song.voices) if (v.lang == 2) chinese = true;
        }
        String[] order;
        if (kana) order = new String[]{Vsq2Reader.CS_SJIS, Vsq2Reader.CS_GBK, Vsq2Reader.CS_UTF8};
        else if (chinese) order = new String[]{Vsq2Reader.CS_GBK, Vsq2Reader.CS_SJIS, Vsq2Reader.CS_UTF8};
        else order = new String[]{Vsq2Reader.CS_SJIS, Vsq2Reader.CS_GBK, Vsq2Reader.CS_UTF8};
        for (int i = 0; i < order.length; i++) {
            if (canEncode(all, order[i])) return order[i];
        }
        return Vsq2Reader.CS_UTF8;
    }

    private static boolean canEncode(String text, String charset) {
        try {
            return Charset.forName(charset).newEncoder().canEncode(text);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String allText(Song s) {
        StringBuilder b = new StringBuilder();
        b.append(s.masterName);
        for (Voice v : s.voices) b.append(v.name).append(v.compID);
        for (Track t : s.tracks) {
            b.append(t.name).append(t.comment);
            for (Part p : t.parts) {
                b.append(p.name).append(p.comment);
                for (Note n : p.notes) b.append(n.lyric).append(n.phoneme);
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ document

    public static String toText(Song song) {
        if (song == null) song = Song.empty();
        StringBuilder b = new StringBuilder(4096);
        b.append(Vsq2Reader.HEADER).append(NL);

        // ---- [Common] ---------------------------------------------------------
        b.append("[Common]").append(NL);
        b.append("Version=").append(version(song)).append(NL);
        b.append("Name=").append(nz(song.masterName.length() > 0 ? song.masterName
                : (song.tracks.isEmpty() ? "" : song.tracks.get(0).name))).append(NL);
        b.append("Color=181,162,123").append(NL);           // vsqwrite.exe template value
        b.append("DynamicsMode=1").append(NL);
        b.append("PlayMode=1").append(NL);

        // ---- [Master] ---------------------------------------------------------
        b.append("[Master]").append(NL);
        b.append("PreMeasure=").append(preMeasure(song)).append(NL);

        // ---- [MasterTrack] ----------------------------------------------------
        b.append("[MasterTrack]").append(NL);
        writeMasterTrack(b, song);

        // ---- [Mixer] ----------------------------------------------------------
        b.append("[Mixer]").append(NL);
        b.append("MasterFeder=0").append(NL);
        b.append("MasterPanpot=0").append(NL);
        b.append("MasterMute=0").append(NL);
        b.append("OutputMode=0").append(NL);
        int n = Math.max(1, song.tracks.size());
        b.append("Tracks=").append(n).append(NL);
        for (int i = 0; i < n; i++) {
            Track t = i < song.tracks.size() ? song.tracks.get(i) : new Track();
            b.append("Feder").append(i).append('=').append(t.vol).append(NL);
            b.append("Panpot").append(i).append('=').append(t.pan).append(NL);
            b.append("Mute").append(i).append('=').append(t.mute ? 1 : 0).append(NL);
            b.append("Solo").append(i).append('=').append(t.solo ? 1 : 0).append(NL);
        }

        // ---- one block per track ---------------------------------------------
        if (song.tracks.isEmpty()) {
            writeTrack(b, song, new Track(), 0);
        } else {
            for (int i = 0; i < song.tracks.size(); i++) writeTrack(b, song, song.tracks.get(i), i);
        }
        return b.toString();
    }

    private static void writeMasterTrack(StringBuilder b, Song s) {
        ArrayList<long[]> tp = new ArrayList<long[]>();
        for (long[] t : s.tempos) tp.add(new long[]{t[0], t[1]});
        Collections.sort(tp, new Comparator<long[]>() {
            public int compare(long[] a, long[] c) {
                return a[0] < c[0] ? -1 : (a[0] > c[0] ? 1 : 0);
            }
        });
        ArrayList<int[]> ts = new ArrayList<int[]>();
        for (int[] t : s.timeSigs) ts.add(new int[]{t[0], t[1], t[2]});
        Collections.sort(ts, new Comparator<int[]>() {
            public int compare(int[] a, int[] c) {
                return a[0] < c[0] ? -1 : (a[0] > c[0] ? 1 : 0);
            }
        });
        if (tp.isEmpty()) tp.add(new long[]{0, 12000});
        if (ts.isEmpty()) ts.add(new int[]{0, 4, 4});

        int ti = 0;
        int si = 0;
        if (tp.get(0)[0] == 0) {
            b.append("Tempo=").append(bpm(tp.get(0)[1])).append(NL);
            ti = 1;
        }
        if (ts.get(0)[0] == 0) {
            b.append("TimeSig=").append(ts.get(0)[1]).append(',').append(ts.get(0)[2]).append(NL);
            si = 1;
        }
        for (int i = ti; i < tp.size(); i++) {
            b.append("Tempo").append(i - ti + 1).append('=').append(tp.get(i)[0]).append(',')
                    .append(bpm(tp.get(i)[1])).append(NL);
        }
        for (int i = si; i < ts.size(); i++) {
            b.append("TimeSig").append(i - si + 1).append('=').append(ts.get(i)[0]).append(',')
                    .append(ts.get(i)[1]).append(',').append(ts.get(i)[2]).append(NL);
        }
    }

    private static void writeTrack(StringBuilder b, Song song, Track tr, int index) {
        int no = index + 1;
        b.append("[Track").append(no).append(']').append(NL);
        b.append("Name=").append(nz(tr.name)).append(NL);
        b.append("Comment=").append(nz(tr.comment)).append(NL);
        int bs = 0;
        int pc = 0;
        if (!tr.parts.isEmpty()) {
            bs = tr.parts.get(0).bs;
            pc = tr.parts.get(0).pc;
        }
        b.append("Vocaloid").append(no).append('=').append(bs).append(NL);
        b.append("Voice").append(no).append('=').append(pc).append(NL);

        // ---- collect notes, absolute ticks, sorted ---------------------------
        ArrayList<Note> notes = new ArrayList<Note>();
        for (int i = 0; i < tr.parts.size(); i++) {
            Part p = tr.parts.get(i);
            for (int k = 0; k < p.notes.size(); k++) notes.add(p.notes.get(k));
        }
        Collections.sort(notes, new Comparator<Note>() {
            public int compare(Note a, Note c) {
                if (a.pos != c.pos) return a.pos < c.pos ? -1 : 1;
                return a.key < c.key ? -1 : (a.key > c.key ? 1 : 0);
            }
        });

        // event index 0 is the singer event at clock 0 (luavsq Track._initCor), handle h#0000
        // then one lyric handle (and optionally a vibrato handle) per note.
        int[] lyricHandle = new int[notes.size()];
        int[] vibHandle = new int[notes.size()];
        int handleCount = 1;
        for (int i = 0; i < notes.size(); i++) {
            lyricHandle[i] = handleCount++;
            if (hasVibrato(notes.get(i))) vibHandle[i] = handleCount++;
        }

        long end = 480;
        for (int i = 0; i < notes.size(); i++) end = Math.max(end, notes.get(i).end());
        for (int i = 0; i < tr.parts.size(); i++) {
            Part p = tr.parts.get(i);
            for (Map.Entry<String, ControlCurve> e : p.ctrls.entrySet()) {
                for (int k = 0; k < e.getValue().points().size(); k++) {
                    end = Math.max(end, e.getValue().points().get(k).t);
                }
            }
        }

        // ---- [EventList] ------------------------------------------------------
        b.append("[EventList]").append(NL);
        ArrayList<long[]> ev = new ArrayList<long[]>();
        ev.add(new long[]{0, 0});                                   // the singer event, ID#0000
        for (int k = 0; k < notes.size(); k++) ev.add(new long[]{notes.get(k).pos, k + 1});
        int i = 0;
        while (i < ev.size()) {
            long tick = ev.get(i)[0];
            StringBuilder ids = new StringBuilder();
            while (i < ev.size() && ev.get(i)[0] == tick) {
                if (ids.length() > 0) ids.append(',');
                ids.append("ID#").append(id((int) ev.get(i)[1]));
                i++;
            }
            b.append(tick).append('=').append(ids).append(NL);
        }
        b.append(end + 120).append("=EOS").append(NL);

        // ---- [ID#nnnn] --------------------------------------------------------
        b.append("[ID#").append(id(0)).append(']').append(NL);
        b.append("Type=Singer").append(NL);
        b.append("IconHandle=h#").append(id(0)).append(NL);
        for (int k = 0; k < notes.size(); k++) {
            Note n = notes.get(k);
            b.append("[ID#").append(id(k + 1)).append(']').append(NL);
            b.append("Type=Anote").append(NL);
            b.append("Length=").append(Math.max(1, n.dur)).append(NL);
            b.append("Note#=").append(clamp(n.key, 0, 127)).append(NL);
            b.append("Dynamics=").append(clamp(n.vel, 0, 127)).append(NL);
            b.append("PMBendDepth=").append(iv(n.st("bendDep", 8))).append(NL);
            b.append("PMBendLength=").append(iv(n.st("bendLen", 0))).append(NL);
            b.append("PMbPortamentoUse=").append(iv(n.st("risePort", 0)) + 2 * iv(n.st("fallPort", 0))).append(NL);
            b.append("DEMdecGainRate=").append(iv(n.st("decay", 50))).append(NL);
            b.append("DEMaccent=").append(iv(n.st("accent", 50))).append(NL);
            b.append("LyricHandle=h#").append(id(lyricHandle[k])).append(NL);
            if (vibHandle[k] > 0) {
                b.append("VibratoHandle=h#").append(id(vibHandle[k])).append(NL);
                b.append("VibratoDelay=").append(vibratoDelay(n)).append(NL);
            }
        }

        // ---- [h#nnnn] ---------------------------------------------------------
        Voice v = voiceOf(song, bs, pc);
        b.append("[h#").append(id(0)).append(']').append(NL);
        b.append("IconID=$").append(hex2(bs)).append(hex2(pc)).append(NL);   // lang=bs, program=pc
        b.append("IDS=").append(v == null ? "" : nz(v.compID)).append(NL);
        b.append("Original=0").append(NL);
        b.append("Caption=").append(NL);
        b.append("Length=1").append(NL);
        b.append("Language=").append(bs).append(NL);
        b.append("Program=").append(pc).append(NL);
        for (int k = 0; k < notes.size(); k++) {
            Note n = notes.get(k);
            b.append("[h#").append(id(lyricHandle[k])).append(']').append(NL);
            b.append("L0=").append(lyricField(n)).append(NL);
            if (vibHandle[k] > 0) {
                b.append("[h#").append(id(vibHandle[k])).append(']').append(NL);
                writeVibratoHandle(b, n);
            }
        }

        // ---- curves -----------------------------------------------------------
        for (int pi = 0; pi < tr.parts.size(); pi++) {
            Part p = tr.parts.get(pi);
            for (Map.Entry<String, ControlCurve> e : p.ctrls.entrySet()) {
                ControlCurve c = e.getValue();
                if (c == null || c.isEmpty()) continue;
                String sec = Vsq2Reader.sectionForCtrl(e.getKey());
                if (sec == null) {
                    if (e.getKey().endsWith("BPList")) sec = e.getKey();
                    else continue;
                }
                b.append('[').append(sec).append(']').append(NL);
                ArrayList<ControlCurve.P> pts = new ArrayList<ControlCurve.P>(c.points());
                Collections.sort(pts, new Comparator<ControlCurve.P>() {
                    public int compare(ControlCurve.P a, ControlCurve.P d) {
                        return a.t < d.t ? -1 : (a.t > d.t ? 1 : 0);
                    }
                });
                for (int k = 0; k < pts.size(); k++) {
                    b.append(pts.get(k).t).append('=').append(num(pts.get(k).v)).append(NL);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** "L0=" value: "lyric","phoneme",1.000000,&lt;consonant adjustment per symbol&gt;,0 (isProtected). */
    static String lyricField(Note n) {
        String lyric = q(n.lyric == null || n.lyric.length() == 0 ? "a" : n.lyric);
        String phon = q(n.phoneme == null ? "" : n.phoneme);
        int symbols = 1;
        String ph = n.phoneme == null ? "" : n.phoneme.trim();
        if (ph.length() > 0) {
            symbols = 1;
            for (int i = 0; i < ph.length(); i++) if (ph.charAt(i) == ' ') symbols++;
        }
        StringBuilder b = new StringBuilder();
        b.append(lyric).append(',').append(phon).append(",1.000000");
        for (int i = 0; i < symbols; i++) b.append(",0");
        b.append(",0");
        return b.toString();
    }

    private static boolean hasVibrato(Note n) {
        return iv(n.st("vibType", 0)) != 0 || n.style.containsKey("vibLen") || n.ctrls.containsKey("vibDep")
                || n.ctrls.containsKey("vibRate");
    }

    private static long vibratoDelay(Note n) {
        double vibLen = n.st("vibLen", 66);
        long tickLen = (long) Math.floor(n.dur * vibLen / 100.0);
        if (tickLen < 0) tickLen = 0;
        if (tickLen > n.dur) tickLen = n.dur;
        return n.dur - tickLen;
    }

    private static void writeVibratoHandle(StringBuilder b, Note n) {
        double vibLen = n.st("vibLen", 66);
        long tickLen = (long) Math.floor(n.dur * vibLen / 100.0);
        if (tickLen < 0) tickLen = 0;
        if (tickLen > n.dur) tickLen = n.dur;
        int type = iv(n.st("vibType", 1));
        double dep = firstValue(n.ctrls.get("vibDep"), 64);
        double rate = firstValue(n.ctrls.get("vibRate"), 50);
        b.append("IconID=$0404").append(hex4(type)).append(NL);
        b.append("IDS=").append(NL);
        b.append("Original=0").append(NL);
        b.append("Caption=").append(NL);
        b.append("Length=").append(tickLen).append(NL);
        b.append("StartDepth=0").append(NL);
        b.append("DepthBPNum=1").append(NL);
        b.append("DepthBPX=0.000000").append(NL);
        b.append("DepthBPY=").append(iv(dep)).append(NL);
        b.append("StartRate=0").append(NL);
        b.append("RateBPNum=1").append(NL);
        b.append("RateBPX=0.000000").append(NL);
        b.append("RateBPY=").append(iv(rate)).append(NL);
    }

    private static double firstValue(ControlCurve c, double dflt) {
        if (c == null || c.isEmpty()) return dflt;
        return c.points().get(0).v;
    }

    private static Voice voiceOf(Song s, int bs, int pc) {
        for (Voice v : s.voices) if (v.bs == bs && v.pc == pc) return v;
        return s.voices.isEmpty() ? null : s.voices.get(0);
    }

    private static int preMeasure(Song s) {
        long earliest = Long.MAX_VALUE;
        for (Track t : s.tracks) {
            for (Part p : t.parts) {
                earliest = Math.min(earliest, p.pos);
                for (Note n : p.notes) earliest = Math.min(earliest, n.pos);
            }
        }
        if (earliest == Long.MAX_VALUE || earliest <= 0) return 1;
        int nu = 4;
        int de = 4;
        if (!s.timeSigs.isEmpty()) {
            nu = s.timeSigs.get(0)[1];
            de = s.timeSigs.get(0)[2];
        }
        long measure = 480L * 4L * Math.max(1, nu) / Math.max(1, de);
        long pre = (earliest + measure - 1) / measure;
        if (pre < 1) pre = 1;
        return (int) pre;
    }

    private static String version(Song s) {
        String v = s.version == null ? "" : s.version;
        if (v.startsWith("DSB")) return v;          // a legacy file keeps its own version string
        return Vsq2Reader.VERSION_V2;               // DSB301 = VOCALOID2 (luavsq.lua:1140)
    }

    static String id(int n) {
        String s = Integer.toString(n);
        while (s.length() < 4) s = "0" + s;
        return s;
    }

    private static String hex2(int v) {
        String s = Integer.toHexString(v & 0xFF).toUpperCase(Locale.US);
        return s.length() < 2 ? "0" + s : s;
    }

    private static String hex4(int v) {
        String s = Integer.toHexString(v & 0xFFFF).toUpperCase(Locale.US);
        while (s.length() < 4) s = "0" + s;
        return s;
    }

    private static String bpm(long v100) {
        return String.format(Locale.US, "%.2f", v100 / 100.0);
    }

    private static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1.0e15) return Long.toString((long) v);
        return String.valueOf(v);
    }

    private static int iv(double v) {
        if (Double.isNaN(v)) return 0;
        return (int) Math.round(v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Quotes a lyric field the way luavsq does (" becomes ""). */
    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append('"');
            if (c != 10 && c != 13) b.append(c);
        }
        return b.append('"').toString();
    }
}
