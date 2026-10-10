package com.dsh.v4tune.format;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;

/**
 * Reader for the legacy VOCALOID2 INI style project file (".vsq").
 *
 * <h3>Layout this reader accepts (evidence in brackets)</h3>
 * <pre>
 * VOCALOID2 Project File1.0            &lt;- first line, the version string of the V2 writer
 * [Common]      Version / Name / Color / DynamicsMode / PlayMode
 * [Master]      PreMeasure
 * [MasterTrack] Tempo / TimeSig        &lt;- see "undetermined" note below
 * [Mixer]       MasterFeder / MasterPanpot / MasterMute / OutputMode / Tracks / Feder&lt;i&gt; ...
 * [Track&lt;i&gt;]    Name / Comment / Vocaloid&lt;i&gt;(bs) / Voice&lt;i&gt;(pc)      &lt;- multi track extension
 * [EventList]   &lt;clock&gt;=ID#nnnn[,ID#nnnn] ... &lt;end&gt;=EOS
 * [ID#nnnn]     Type / Length / Note# / Dynamics / PMBendDepth / PMBendLength /
 *               PMbPortamentoUse / DEMdecGainRate / DEMaccent / LyricHandle / VibratoHandle /
 *               VibratoDelay
 * [h#nnnn]      L0="lyric","phoneme",1.000000,0,0   (lyric handle)
 *               IconID / IDS / Original / Caption / Length / Language / Program (singer handle)
 *               IconID / IDS / Original / Caption / Length / StartDepth / DepthBPNum/DepthBPX/
 *               DepthBPY / StartRate / RateBPNum/RateBPX/RateBPY   (vibrato handle)
 * [XxxBPList]   &lt;tick&gt;=&lt;value&gt;           (control curves, names from luavsq.lua:4716-4787)
 * </pre>
 *
 * <h3>Evidence</h3>
 * <ul>
 *   <li>Section and key names, their order and the value formats are taken literally from
 *       {@code E:\V4\JobPlugins\ExportVSQ\luavsq.lua} (VOCALOID4's own "Export VSQ" library:
 *       common 1191, master 2648, mixer 3110, eventList 1522, event 1286, handle 2076,
 *       BP lists 4716-4787) and from the hard coded VOCALOID2 era writer template of
 *       {@code vsqwrite.exe} embedded in
 *       {@code E:\V4\JobPlugins\Vocalistener\V3VocaListener.exe} (offset 1017024..1017988:
 *       "[Common]", "Version=DSB301", "Color=181,162,123", "[Master]", "PreMeasure=%d",
 *       "[Mixer]", "Tracks=1", "[EventList]", "0=ID#0000", "%u=EOS", "Type=Singer",
 *       "Type=%s", "Length=%u", "Note#=%d", "Dynamics=%d", "PMBendDepth=%d",
 *       "PMbPortamentoUse=%d", "LyricHandle=h#%04d", "L0=...", "[DynamicsBPList]" ...).</li>
 *   <li>[Track&lt;i&gt;] / Vocaloid&lt;i&gt; / Voice&lt;i&gt; are the multi track extension of this
 *       implementation; "Track%d" is a string of the VOCALOID4 Vsq manager (Vsq4.dll 0x7d440)
 *       and "Voice1" is the vocal track name the V2 writer used (V3VocaListener.exe:1017024,
 *       luavsq.lua:3393 {@code Track.new("Voice1", singer)}). A file without [Track...] - a real
 *       VOCALOID2 file, which holds exactly one vocal part - is read as one track named after
 *       [Common] Name.</li>
 *   <li>VOCALOID2 .vsq files carry no resolution field: the unit is fixed at 480 ticks per quarter
 *       (luavsq.lua:3381 {@code this._tickPerQuarter = 480}, TempoList.gatetimePerQuater = 480).
 *       Note and curve ticks are absolute in the song timeline - ExportVSQ.lua adds the musical
 *       part offset to every note ({@code noteEx.posTick + offset}) and to every control point.</li>
 * </ul>
 *
 * <h3>Undetermined details (marked, not invented silently)</h3>
 * <ul>
 *   <li>Tempo / time signature: neither the VOCALOID4 Lua writer (it puts the tempo map into a
 *       MIDI master track) nor the V2 era template above contains a tempo key. This implementation
 *       stores the map in a [MasterTrack] section as {@code Tempo=120.00} / {@code TimeSig=4,4}
 *       for the event at tick 0 (the classic single tempo form) and as
 *       {@code Tempo<i>=tick,bpm} / {@code TimeSig<i>=tick,nu,de} for every further event, so a
 *       tempo/time signature map survives a round trip. The reader also accepts repeated
 *       {@code Tempo=} lines, {@code Tempo=tick,bpm} and {@code tick=Tempo,bpm} spellings.</li>
 *   <li>The header text of a real file is assumed to be "VOCALOID2 Project File1.0"; it is not
 *       validated (any first line is accepted and kept in {@link #lastHeader}).</li>
 * </ul>
 *
 * <h3>Documented normalisations</h3>
 * <ul>
 *   <li>One [Track&lt;i&gt;] block becomes exactly one {@link Track} with exactly one {@link Part}
 *       (pos 0, dur = last note end): the legacy format has no part concept.</li>
 *   <li>{@link Voice#name} is not stored in a .vsq (only the component id is); it is set to the
 *       component id on read. {@link Voice#lang} is inferred from the component id
 *       ("CHN" = 2, "ENG" = 1, otherwise 0).</li>
 *   <li>{@link Song#schema} is set to 2 to mark a legacy document (3 = vsq3, 4 = vsq4).</li>
 *   <li>Note style keys follow {@link VsqxReader} ("accent", "decay", "bendDep", "bendLen",
 *       "vibLen", "vibType", "risePort", "fallPort"); the vibrato depth/rate of a vibrato handle
 *       lands in {@link Note#ctrls} under "vibDep"/"vibRate".</li>
 * </ul>
 */
public final class Vsq2Reader {

    /** First line written by the VOCALOID2 era writer (vsqwrite.exe template). */
    public static final String HEADER = "VOCALOID2 Project File1.0";
    public static final String VERSION_V2 = "DSB301";
    public static final String VERSION_V1 = "DSB202";

    public static final String CS_UTF8 = "UTF-8";
    public static final String CS_SJIS = "MS932";
    public static final String CS_GBK = "GBK";

    /** Charset that decoded the file read last (also set by {@link #decode(byte[])}). */
    public static String lastCharset = CS_SJIS;
    /** First line of the file read last (normally {@link #HEADER}). */
    public static String lastHeader = "";
    /** [Common] Version value of the file read last (DSB301 = VOCALOID2, DSB202 = VOCALOID1). */
    public static String lastVersion = "";
    /** [Master] PreMeasure of the file read last (informational: ticks stay absolute). */
    public static int lastPreMeasure = 1;
    /** True when the file read last had no [Track...] section (a genuine one track VOCALOID2 file). */
    public static boolean lastWasSingleTrack = false;

    /** Control curve sections in the order luavsq.lua writes them (4716-4787). */
    private static final String[] SEC = {
        "PitchBendBPList", "PitchBendSensBPList", "DynamicsBPList", "EpRResidualBPList",
        "EpRESlopeBPList", "EpRESlopeDepthBPList", "GenderFactorBPList", "PortamentoTimingBPList",
        "OpeningBPList"
    };
    /** Model control ids for {@link #SEC}. */
    private static final String[] CTRL = {"PIT", "PBS", "DYN", "BRE", "BRI", "CLE", "GEN", "POR", "OPE"};
    /** Defaults and ranges, luavsq.lua:4632-4655. */
    private static final double[] DEF = {0, 2, 64, 0, 64, 0, 64, 64, 127};
    private static final double[] MIN = {-8192, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final double[] MAX = {8191, 24, 127, 127, 127, 127, 127, 127, 127};

    private Vsq2Reader() {
    }

    // ------------------------------------------------------------------ decoding

    /**
     * Tolerant text decoding: strict UTF-8 first, then Shift-JIS (MS932) and GBK scored against
     * each other (Shift-JIS byte pairs that are really GBK text break the decode or come out as
     * half width katakana and Greek/Cyrillic/box drawing rows, all of which the score penalises;
     * full width kana are the strongest Japanese signal), finally a lossy fallback. An equal score
     * keeps Shift-JIS, the code page of a Japanese VOCALOID2 system. {@link #lastCharset} receives
     * the decision. Pure ASCII data is reported as MS932 because it is a legacy Japanese file."
     */
    public static String decode(byte[] data) {
        if (data == null) data = new byte[0];
        boolean ascii = true;
        for (int i = 0; i < data.length; i++) {
            if ((data[i] & 0xFF) >= 0x80) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            lastCharset = CS_SJIS;   // ASCII is identical in every candidate; legacy file -> MS932
            return stripBom(new String(data, Charset.forName("US-ASCII")));
        }
        String utf8 = strictDecode(data, CS_UTF8);
        if (utf8 != null) {
            lastCharset = CS_UTF8;
            return stripBom(utf8);
        }
        String sjis = strictDecode(data, CS_SJIS);
        String gbk = strictDecode(data, CS_GBK);
        String pick;
        if (sjis != null && gbk != null) {
            pick = score(sjis) >= score(gbk) ? sjis : gbk;   // tie -> Shift-JIS (documented)
            lastCharset = pick == sjis ? CS_SJIS : CS_GBK;
        } else if (sjis != null) {
            pick = sjis;
            lastCharset = CS_SJIS;
        } else if (gbk != null) {
            pick = gbk;
            lastCharset = CS_GBK;
        } else {
            String s = lossy(data, CS_SJIS);
            String g = lossy(data, CS_GBK);
            int ns = count(s, (char) 0xFFFD);
            int ng = count(g, (char) 0xFFFD);
            pick = ng < ns ? g : s;
            lastCharset = ng < ns ? CS_GBK : CS_SJIS;
        }
        return stripBom(pick);
    }

    private static String strictDecode(byte[] data, String name) {
        Charset cs;
        try {
            cs = Charset.forName(name);
        } catch (Throwable t) {
            return null;
        }
        CharsetDecoder dec = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = dec.decode(ByteBuffer.wrap(data));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String lossy(byte[] data, String name) {
        try {
            return new String(data, Charset.forName(name));
        } catch (Throwable t) {
            return new String(data, Charset.forName("ISO-8859-1"));
        }
    }

    /**
     * Plausibility of a decode. Full width kana are the strongest Japanese signal (GBK text read
     * as Shift-JIS either breaks the decode or comes out as half width katakana, which is
     * penalised), CJK ideographs are neutral evidence, Greek/Cyrillic/box drawing are the classic
     * Shift-JIS mojibake rows. Equal scores fall back to Shift-JIS (see {@link #decode(byte[])}).
     */
    private static int score(String s) {
        int sc = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0xFFFD) sc -= 60;
            else if (c >= 0x3040 && c <= 0x30FF) sc += 8;      // kana
            else if (c >= 0x4E00 && c <= 0x9FFF) sc += 3;      // CJK ideograph
            else if (c >= 0x0370 && c <= 0x03FF) sc -= 6;      // Greek (Shift-JIS row 0x83-0x86)
            else if (c >= 0x0400 && c <= 0x04FF) sc -= 6;      // Cyrillic
            else if (c >= 0x2500 && c <= 0x257F) sc -= 6;      // box drawing
            else if (c >= 0x2190 && c <= 0x22FF) sc -= 4;      // arrows and maths
            else if (c >= 0xFF61 && c <= 0xFF9F) sc -= 4;      // half width katakana
            else if (c < 0x20 && c != 9 && c != 10 && c != 13) sc -= 20;
        }
        return sc;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static String stripBom(String s) {
        if (s.length() > 0 && s.charAt(0) == 0xFEFF) return s.substring(1);
        return s;
    }

    // ------------------------------------------------------------------ curves

    /** Model control id for a "[XxxBPList]" section name, null when the section is not mapped. */
    public static String ctrlForSection(String section) {
        for (int i = 0; i < SEC.length; i++) if (SEC[i].equals(section)) return CTRL[i];
        return null;
    }

    /** "[XxxBPList]" section name for a model control id, null when the id is not a curve. */
    public static String sectionForCtrl(String id) {
        if (id == null) return null;
        for (int i = 0; i < CTRL.length; i++) if (CTRL[i].equals(id)) return SEC[i];
        return null;
    }

    /** Curve with the documented default/range for a model control id. */
    public static ControlCurve newCurve(String id) {
        for (int i = 0; i < CTRL.length; i++) {
            if (CTRL[i].equals(id)) return new ControlCurve(DEF[i], MIN[i], MAX[i]);
        }
        return new ControlCurve(0, -100000, 100000);
    }

    static ControlCurve curveFor(LinkedHashMap<String, ControlCurve> map, String id) {
        ControlCurve c = map.get(id);
        if (c == null) {
            c = newCurve(id);
            map.put(id, c);
        }
        return c;
    }

    // ------------------------------------------------------------------ entry points

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

    public static Song read(byte[] data) {
        return parse(decode(data));
    }

    // ------------------------------------------------------------------ parsing

    private static final class RawEvent {
        int index;
        long clock;
        String type = "";
        long length;
        int note;
        int dynamics = 64;
        int pmBendDepth = 8;
        int pmBendLength;
        int pmbPortamentoUse;
        int demDecGainRate = 50;
        int demAccent = 50;
        String lyricHandle;
        String vibratoHandle;
        String iconHandle;
        long vibratoDelay;
    }

    private static final class RawHandle {
        int index;
        final ArrayList<String> lyrics = new ArrayList<String>();
        final LinkedHashMap<String, String> kv = new LinkedHashMap<String, String>();
    }

    private static final class TrackBuf {
        int no = 1;
        String name = "";
        String comment = "";
        int bs;
        int pc;
        boolean explicit;
        final LinkedHashMap<Integer, RawEvent> events = new LinkedHashMap<Integer, RawEvent>();
        final LinkedHashMap<Integer, RawHandle> handles = new LinkedHashMap<Integer, RawHandle>();
        final LinkedHashMap<Integer, Long> clocks = new LinkedHashMap<Integer, Long>();
        final LinkedHashMap<String, ControlCurve> curves = new LinkedHashMap<String, ControlCurve>();
        long eos = -1;
    }

    private static Song parse(String text) {
        Song song = new Song();
        song.schema = 2;
        lastHeader = "";
        lastVersion = "";
        lastPreMeasure = 1;
        lastWasSingleTrack = true;

        ArrayList<TrackBuf> bufs = new ArrayList<TrackBuf>();
        TrackBuf cur = null;
        RawEvent curEvent = null;
        RawHandle curHandle = null;
        String section = "";
        String curveSection = null;
        String commonName = "";
        int mixTracks = -1;
        final LinkedHashMap<Integer, int[]> mix = new LinkedHashMap<Integer, int[]>();

        int from = 0;
        boolean firstLine = true;
        for (int i = 0; i <= text.length(); i++) {
            if (i != text.length() && text.charAt(i) != 10) continue;
            String line = text.substring(from, i);
            from = i + 1;
            if (line.length() > 0 && line.charAt(line.length() - 1) == 13) {
                line = line.substring(0, line.length() - 1);
            }
            if (firstLine) {
                lastHeader = line.trim();
                firstLine = false;
                continue;
            }
            String t = line.trim();
            if (t.length() == 0) continue;
            if (t.charAt(0) == '[' && t.charAt(t.length() - 1) == ']') {
                section = t.substring(1, t.length() - 1);
                curEvent = null;
                curHandle = null;
                curveSection = null;
                if (section.startsWith("Track")) {
                    int no = intOf(section.substring(5), bufs.size() + 1);
                    if (no < 1) no = bufs.size() + 1;
                    cur = new TrackBuf();
                    cur.no = no;
                    cur.explicit = true;
                    bufs.add(cur);
                    lastWasSingleTrack = false;
                } else if (section.startsWith("ID#")) {
                    if (cur == null) cur = lazy(bufs);
                    int idx = intOf(section.substring(3), 0);
                    curEvent = new RawEvent();
                    curEvent.index = idx;
                    cur.events.put(Integer.valueOf(idx), curEvent);
                } else if (section.startsWith("h#")) {
                    if (cur == null) cur = lazy(bufs);
                    int idx = intOf(section.substring(2), 0);
                    curHandle = new RawHandle();
                    curHandle.index = idx;
                    cur.handles.put(Integer.valueOf(idx), curHandle);
                } else if (section.endsWith("BPList")) {
                    if (cur == null) cur = lazy(bufs);
                    curveSection = section;
                } else if ("EventList".equals(section)) {
                    if (cur == null) cur = lazy(bufs);
                }
                continue;
            }
            int eq = t.indexOf('=');
            String key = eq < 0 ? t : t.substring(0, eq).trim();
            String val = eq < 0 ? "" : t.substring(eq + 1).trim();

            if (section.startsWith("ID#")) {
                if (curEvent == null) continue;
                if ("Type".equals(key)) curEvent.type = val;
                else if ("Length".equals(key)) curEvent.length = longOf(val, 0);
                else if ("Note#".equals(key)) curEvent.note = intOf(val, 60);
                else if ("Dynamics".equals(key)) curEvent.dynamics = (int) Math.round(doubleOf(val, 64));
                else if ("PMBendDepth".equals(key)) curEvent.pmBendDepth = intOf(val, 8);
                else if ("PMBendLength".equals(key)) curEvent.pmBendLength = intOf(val, 0);
                else if ("PMbPortamentoUse".equals(key)) curEvent.pmbPortamentoUse = intOf(val, 0);
                else if ("DEMdecGainRate".equals(key)) curEvent.demDecGainRate = intOf(val, 50);
                else if ("DEMaccent".equals(key)) curEvent.demAccent = intOf(val, 50);
                else if ("LyricHandle".equals(key)) curEvent.lyricHandle = val;
                else if ("VibratoHandle".equals(key)) curEvent.vibratoHandle = val;
                else if ("IconHandle".equals(key)) curEvent.iconHandle = val;
                else if ("VibratoDelay".equals(key)) curEvent.vibratoDelay = longOf(val, 0);
                continue;
            }
            if (section.startsWith("h#")) {
                if (curHandle == null) continue;
                if (key.length() > 1 && key.charAt(0) == 'L') {
                    String num = key.substring(1);
                    boolean digits = num.length() > 0;
                    for (int k = 0; k < num.length(); k++) {
                        if (num.charAt(k) < '0' || num.charAt(k) > '9') digits = false;
                    }
                    if (digits) {
                        curHandle.lyrics.add(val);
                        continue;
                    }
                }
                curHandle.kv.put(key, val);
                continue;
            }
            if (curveSection != null) {
                long tick = longOf(key, Long.MIN_VALUE);
                if (tick != Long.MIN_VALUE) {
                    String id = ctrlForSection(curveSection);
                    ControlCurve c = curveFor(cur.curves, id == null ? curveSection : id);
                    c.putRaw(tick, doubleOf(val, c.def));
                }
                continue;
            }
            if ("EventList".equals(section)) {
                if ("EOS".equals(key) || "EOS".equals(val)) {
                    cur.eos = longOf(key, -1);
                    continue;
                }
                long clock = longOf(key, Long.MIN_VALUE);
                if (clock == Long.MIN_VALUE) continue;
                String[] parts = val.split(",");
                for (int k = 0; k < parts.length; k++) {
                    String id = handleRef(parts[k].trim());
                    if (id != null) {
                        int idx = intOf(id, -1);
                        if (idx >= 0) cur.clocks.put(Integer.valueOf(idx), Long.valueOf(clock));
                    }
                }
                continue;
            }
            if ("Common".equals(section)) {
                if ("Version".equals(key)) {
                    if (lastVersion.length() == 0) lastVersion = val;
                } else if ("Name".equals(key)) {
                    if (commonName.length() == 0) commonName = val;
                }
                continue;
            }
            if ("Master".equals(section)) {
                if ("PreMeasure".equals(key)) lastPreMeasure = intOf(val, 1);
                continue;
            }
            if ("MasterTrack".equals(section)) {
                readMasterLine(song, key, val);
                continue;
            }
            if ("Mixer".equals(section)) {
                if ("Tracks".equals(key)) mixTracks = intOf(val, -1);
                else if (key.startsWith("Feder")) putMix(mix, key, 5, intOf(val, 0), 0);
                else if (key.startsWith("Panpot")) putMix(mix, key, 6, intOf(val, 0), 1);
                else if (key.startsWith("Mute")) putMix(mix, key, 4, intOf(val, 0), 2);
                else if (key.startsWith("Solo")) putMix(mix, key, 4, intOf(val, 0), 3);
                continue;
            }
            if (section.startsWith("Track")) {
                if (cur == null) cur = lazy(bufs);
                if ("Name".equals(key)) cur.name = val;
                else if ("Comment".equals(key)) cur.comment = val;
                else if (key.startsWith("Vocaloid")) cur.bs = intOf(val, 0);
                else if (key.startsWith("Voice")) cur.pc = intOf(val, 0);
                continue;
            }
            // unknown section: ignored on purpose (tolerance)
        }

        if (bufs.isEmpty()) {
            TrackBuf b = new TrackBuf();
            b.no = 1;
            b.name = commonName;
            bufs.add(b);
        }
        while (mixTracks > bufs.size()) {
            TrackBuf b = new TrackBuf();
            b.no = bufs.size() + 1;
            bufs.add(b);
        }

        LinkedHashMap<String, Voice> voices = new LinkedHashMap<String, Voice>();
        for (int i = 0; i < bufs.size(); i++) {
            TrackBuf b = bufs.get(i);
            if (!b.explicit && commonName.length() > 0 && b.name.length() == 0) b.name = commonName;
            if (song.masterName.length() == 0) song.masterName = commonName;

            Track tr = new Track();
            tr.no = i;
            tr.name = b.name;
            tr.comment = b.comment;
            int[] mx = mix.get(Integer.valueOf(i));
            if (mx != null) {
                tr.vol = mx[0];
                tr.pan = mx[1];
                tr.mute = mx[2] != 0;
                tr.solo = mx[3] != 0;
            }

            Part p = new Part();
            p.pos = 0;
            p.name = b.name;
            p.comment = b.comment;
            p.bs = b.bs;
            p.pc = b.pc;
            p.ctrls.putAll(b.curves);

            ArrayList<RawEvent> evs = new ArrayList<RawEvent>(b.events.values());
            for (int k = 0; k < evs.size(); k++) {
                RawEvent e = evs.get(k);
                Long c = b.clocks.get(Integer.valueOf(e.index));
                e.clock = c == null ? 0 : c.longValue();
            }
            java.util.Collections.sort(evs, new java.util.Comparator<RawEvent>() {
                public int compare(RawEvent a, RawEvent b2) {
                    if (a.clock != b2.clock) return a.clock < b2.clock ? -1 : 1;
                    return a.index < b2.index ? -1 : (a.index > b2.index ? 1 : 0);
                }
            });
            long end = 0;
            for (int k = 0; k < evs.size(); k++) {
                RawEvent e = evs.get(k);
                if ("Singer".equals(e.type)) {
                    RawHandle h = b.handles.get(Integer.valueOf(intOf(handleRef(e.iconHandle), -1)));
                    if (h != null) {
                        if (b.bs == 0 && b.pc == 0) {
                            b.bs = intOf(h.kv.get("Language"), 0);
                            b.pc = intOf(h.kv.get("Program"), 0);
                            p.bs = b.bs;
                            p.pc = b.pc;
                        }
                        voiceFor(voices, b.bs, b.pc, h.kv.get("IDS"));
                    }
                    continue;
                }
                if (!"Anote".equals(e.type) && !"Note".equals(e.type)) continue;
                Note n = new Note();
                n.pos = e.clock;
                n.dur = e.length > 0 ? e.length : 1;
                n.key = e.note;
                n.vel = e.dynamics;
                n.lyric = "a";
                n.phoneme = "";
                RawHandle lh = b.handles.get(Integer.valueOf(intOf(handleRef(e.lyricHandle), -1)));
                if (lh != null && !lh.lyrics.isEmpty()) {
                    String[] f = splitQuoted(lh.lyrics.get(0));
                    if (f.length > 0 && f[0].length() > 0) n.lyric = f[0];
                    if (f.length > 1) n.phoneme = f[1];
                }
                RawHandle vh = b.handles.get(Integer.valueOf(intOf(handleRef(e.vibratoHandle), -1)));
                if (vh != null) {
                    int vibType = iconType(vh.kv.get("IconID"));
                    long l = longOf(vh.kv.get("Length"), 0);
                    if (vibType != 0) n.style.put("vibType", (double) vibType);
                    if (l > 0 && n.dur > 0) n.style.put("vibLen", (double) Math.round(100.0 * l / n.dur));
                    if (vh.kv.containsKey("DepthBPY")) {
                        curveFor(n.ctrls, "vibDep").putRaw(0, firstOf(vh.kv.get("DepthBPY"), 64));
                    }
                    if (vh.kv.containsKey("RateBPY")) {
                        curveFor(n.ctrls, "vibRate").putRaw(0, firstOf(vh.kv.get("RateBPY"), 50));
                    }
                }
                n.style.put("bendDep", (double) e.pmBendDepth);
                n.style.put("bendLen", (double) e.pmBendLength);
                n.style.put("decay", (double) e.demDecGainRate);
                n.style.put("accent", (double) e.demAccent);
                n.style.put("risePort", (double) (e.pmbPortamentoUse & 1));
                n.style.put("fallPort", (double) ((e.pmbPortamentoUse >> 1) & 1));
                p.notes.add(n);
                if (n.end() > end) end = n.end();
            }
            p.dur = end;
            p.sortNotes();
            tr.parts.add(p);
            song.tracks.add(tr);
        }
        for (Voice v : voices.values()) song.voices.add(v);

        if (song.tempos.isEmpty()) song.tempos.add(new long[]{0, 12000});
        if (song.timeSigs.isEmpty()) song.timeSigs.add(new int[]{0, 4, 4});
        java.util.Collections.sort(song.tempos, new java.util.Comparator<long[]>() {
            public int compare(long[] a, long[] b) {
                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
            }
        });
        java.util.Collections.sort(song.timeSigs, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) {
                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
            }
        });
        return song;
    }

    private static TrackBuf lazy(ArrayList<TrackBuf> bufs) {
        if (!bufs.isEmpty()) return bufs.get(bufs.size() - 1);
        TrackBuf b = new TrackBuf();
        b.no = 1;
        bufs.add(b);
        return b;
    }

    private static void putMix(LinkedHashMap<Integer, int[]> mix, String key, int cut, int val, int slot) {
        int idx = intOf(key.substring(cut), 0);
        Integer k = Integer.valueOf(idx);
        int[] a = mix.get(k);
        if (a == null) {
            a = new int[4];
            mix.put(k, a);
        }
        a[slot] = val;
    }

    private static void readMasterLine(Song song, String key, String val) {
        // Tempo=120.00 | Tempo=1920,140.00 | Tempo1=1920,140.00 | 1920=Tempo,140.00
        String k = key;
        String v = val;
        if (k.length() > 0 && k.charAt(0) >= '0' && k.charAt(0) <= '9') {
            String[] f = v.split(",");
            if (f.length >= 2) {
                k = f[0].trim();
                v = v.substring(f[0].length() + 1);
            }
        }
        boolean tempo;
        if (k.startsWith("Tempo")) tempo = true;
        else if (k.startsWith("TimeSig")) tempo = false;
        else return;
        String[] f = v.split(",");
        long tick = 0;
        if (tempo) {
            double bpm;
            if (f.length >= 2) {
                tick = longOf(f[0].trim(), 0);
                bpm = doubleOf(f[1].trim(), 120);
            } else {
                bpm = doubleOf(f[0].trim(), 120);
            }
            song.tempos.add(new long[]{tick, Math.round(bpm * 100.0)});
        } else {
            int nu = 4;
            int de = 4;
            if (f.length >= 3) {
                tick = longOf(f[0].trim(), 0);
                nu = intOf(f[1].trim(), 4);
                de = intOf(f[2].trim(), 4);
            } else if (f.length == 2) {
                nu = intOf(f[0].trim(), 4);
                de = intOf(f[1].trim(), 4);
            } else if (f.length == 1) {
                nu = intOf(f[0].trim(), 4);
            }
            song.timeSigs.add(new int[]{(int) tick, nu, de});
        }
    }

    private static Voice voiceFor(LinkedHashMap<String, Voice> map, int bs, int pc, String compID) {
        String key = bs + ":" + pc;
        Voice v = map.get(key);
        if (v == null) {
            v = new Voice();
            v.bs = bs;
            v.pc = pc;
            v.compID = compID == null ? "" : compID;
            v.name = v.compID;
            v.lang = inferLang(v.compID);
            map.put(key, v);
        }
        return v;
    }

    /** "CHN" = 2 (chinese), "ENG" = 1 (english), otherwise 0 (japanese). */
    public static int inferLang(String compID) {
        if (compID == null) return 0;
        String u = compID.toUpperCase(java.util.Locale.US);
        if (u.indexOf("CHN") >= 0) return 2;
        if (u.indexOf("ENG") >= 0) return 1;
        return 0;
    }

    // ------------------------------------------------------------------ small helpers

    static String handleRef(String v) {
        if (v == null) return null;
        String s = v.trim();
        int i = s.indexOf('#');
        if (i < 0) return null;
        return s.substring(i + 1).trim();
    }

    static int intOf(String s, int dflt) {
        if (s == null) return dflt;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    static long longOf(String s, long dflt) {
        if (s == null) return dflt;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    static double doubleOf(String s, double dflt) {
        if (s == null) return dflt;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static double firstOf(String csv, double dflt) {
        if (csv == null) return dflt;
        int i = csv.indexOf(',');
        return doubleOf(i < 0 ? csv : csv.substring(0, i), dflt);
    }

    /** Vibrato icon ids are "$0404%04X" (ExportVSQ.lua); returns the vibrato type or 0. */
    static int iconType(String iconID) {
        if (iconID == null) return 0;
        String s = iconID.trim();
        if (s.startsWith("$")) s = s.substring(1);
        if (s.length() < 8) return 0;
        try {
            return (int) Long.parseLong(s.substring(4, 8), 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Splits a lyric handle value such as {@code "a","a",1.000000,0,0} into its comma separated
     * fields, honouring double quotes (a doubled quote inside a field is one literal quote, the
     * convention of luavsq.lua Lyric.toString).
     */
    static String[] splitQuoted(String v) {
        ArrayList<String> out = new ArrayList<String>();
        if (v == null) return new String[0];
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"') {
                if (inQuote && i + 1 < v.length() && v.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (c == ',' && !inQuote) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        String[] r = new String[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }
}
