package com.dsh.v4tune.format;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;

/**
 * Writer for VOCALOID project files (vsq3 / vsq4). Produces the canonical, editor style
 * document: plain UTF-8 XML, the same element order as the XSD sequences, CDATA for string
 * fields, plain text for numbers.
 *
 * <p>Everything {@link VsqxReader} parked in {@link Song#auxXml} is emitted again, so a
 * read-&gt;write cycle only changes the parts of the document that the model actually carries.
 * See the reader for the fragment layout of auxXml.</p>
 *
 * <h3>Normalisations applied on write (XSD driven)</h3>
 * <ul>
 *   <li>String fields are truncated to their XSD limits (256 / 32 / 15 chars); the component id
 *       is padded or cut to exactly 16 chars, the style plugin id to a 36 char GUID.</li>
 *   <li>All numeric fields are clamped to their XSD type. Style values and control point values
 *       are integers in the schema, so doubles are rounded half up (NaN becomes 0).</li>
 *   <li>Durations are written as at least 1 tick, part playTime as at least 0.</li>
 *   <li>vsq3 note/control positions are written part local (part.pos is subtracted); vsq4
 *       positions are absolute.</li>
 *   <li>vsq3 Part.ctrls keys "PIT"/"PBS" (and "sCtrl:x") become &lt;sCtrl&gt;, everything else
 *       becomes &lt;mCtrl&gt;. Keys "seqAttr:x" only exist in vsq3 note styles.</li>
 *   <li>&lt;timeSig&gt; measure indices are recomputed from the absolute ticks in
 *       {@link Song#timeSigs}. Tempos and time signatures are written sorted by tick.</li>
 *   <li>At most 16 vocal tracks / mixer units are written (XSD limit); at least one is always
 *       written.</li>
 *   <li>vsq4 singer/t is written as 0: the editor treats it as part local.</li>
 *   <li>A style plugin id that is not a 36 char GUID falls back to the canonical
 *       "VOCALOID2 Compatible Style" id, because the XSD fixes its length.</li>
 *   <li>Elements that only exist in the other schema (prosodyPart, phraseStyle, plane, the
 *       se/karaoke mixer units, wav track blocks) degrade to defaults or are omitted when
 *       {@link Song#schema} is flipped; a same schema round trip keeps them untouched.</li>
 * </ul>
 */
public final class VsqxWriter {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private VsqxWriter() {
    }

    public static void write(Song song, OutputStream out) throws IOException {
        out.write(toXml(song).getBytes(UTF8));
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

    /** Builds the whole document as a string (handy for tests). */
    public static String toXml(Song song) {
        if (song == null) song = Song.empty();
        boolean v4 = song.schema != 3;
        String rootName = v4 ? "vsq4" : "vsq3";
        String ns = v4 ? VsqxReader.NS_VSQ4 : VsqxReader.NS_VSQ3;
        Extras x = Extras.parse(song.auxXml);
        Out o = new Out();

        o.raw("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
        o.raw("<" + rootName + " xmlns=\"" + ns + "\"\n"
                + "      xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "      xsi:schemaLocation=\"" + ns + " " + rootName + ".xsd\">");
        o.depth = 1;

        // A part whose singer is bs=0/pc=0 binds to no voice, and VOCALOID4 then opens the file
        // with an empty sequence even though every note is in it. Use the ids of the bank in use
        // when the project carries them, otherwise the values a VOCALOID4 CHN bank reports
        // (measured on the 洛天依 CHN bank: bs=4, pc=1).
        singerBs = 0;
        singerPc = 0;
        for (int vi = 0; vi < song.voices.size(); vi++) {
            Voice vv = song.voices.get(vi);
            if (vv.bs != 0 || vv.pc != 0) { singerBs = vv.bs; singerPc = vv.pc; break; }
        }
        if (singerBs == 0 && singerPc == 0) { singerBs = 4; singerPc = 1; }

        o.leaf("vender", cdata(trunc(song.vendor, 256)));
        o.leaf("version", cdata(trunc(song.version, 15)));

        // ---- vVoiceTable ---------------------------------------------------------
        o.open("vVoiceTable");
        List<Voice> voices = song.voices;
        int nv = Math.max(1, voices.size());
        for (int i = 0; i < nv; i++) {
            Voice v = i < voices.size() ? voices.get(i) : new Voice();
            o.open("vVoice");
            o.num(v4 ? "bs" : "vBS", clamp(v.bs != 0 || v.pc != 0 ? v.bs : singerBs, 0, 127));
            o.num(v4 ? "pc" : "vPC", clamp(v.bs != 0 || v.pc != 0 ? v.pc : singerPc, 0, 127));
            o.leaf(v4 ? "id" : "compID", cdata(componentId(v.compID)));
            o.leaf(v4 ? "name" : "vVoiceName", cdata(trunc(v.name, 256)));
            o.open(v4 ? "vPrm" : "vVoiceParam");
            o.num("bre", clamp(v.bre, -127, 127));
            o.num("bri", clamp(v.bri, -127, 127));
            o.num("cle", clamp(v.cle, -127, 127));
            o.num("gen", clamp(v.gen, -127, 127));
            o.num("ope", clamp(v.ope, -127, 127));
            o.close(v4 ? "vPrm" : "vVoiceParam");
            List<Element> ve = x.withAttr(VsqxReader.K_VOICE, VsqxReader.A_INDEX, String.valueOf(i));
            for (int k = 0; k < ve.size(); k++) {
                List<Element> ch = VsqxReader.kids(ve.get(k));
                for (int c = 0; c < ch.size(); c++) {
                    String ln = VsqxReader.local(ch.get(c));
                    if (!v4 && ("id2".equals(ln) || "vPrm2".equals(ln))) continue;
                    o.rawLine(VsqxReader.serializeFragment(ch.get(c)));
                }
            }
            o.close("vVoice");
        }
        o.close("vVoiceTable");

        // ---- mixer ---------------------------------------------------------------
        List<Track> tracks = song.tracks;
        int nt = Math.min(16, Math.max(1, tracks.size()));
        o.open("mixer");
        o.open("masterUnit");
        o.num(v4 ? "oDev" : "outDev", clamp(song.outDev, Short.MIN_VALUE, Short.MAX_VALUE));
        writePlugFragments(o, x, VsqxReader.K_MIXER, v4);
        o.num(v4 ? "rLvl" : "retLevel", clampI(song.masterRetLevel));
        o.num("vol", clampI(song.masterVol));
        o.close("masterUnit");
        for (int i = 0; i < nt; i++) {
            Track t = i < tracks.size() ? tracks.get(i) : null;
            o.open("vsUnit");
            o.num(v4 ? "tNo" : "vsTrackNo", t == null ? i : clamp(t.no, 0, 127));
            o.num(v4 ? "iGin" : "inGain", t == null ? 0 : clampI(t.inGain));
            writeTrackPlugFragments(o, x, v4, t == null ? i : t.no, i);
            o.num(v4 ? "sLvl" : "sendLevel", t == null ? 0 : clampI(t.sendLevel));
            o.num(v4 ? "sEnable" : "sendEnable", (t != null && t.sendEnable) ? 1 : 0);
            o.num(v4 ? "m" : "mute", (t != null && t.mute) ? 1 : 0);
            o.num(v4 ? "s" : "solo", (t != null && t.solo) ? 1 : 0);
            o.num("pan", t == null ? 64 : clampI(t.pan));
            o.num("vol", t == null ? 0 : clampI(t.vol));
            o.close("vsUnit");
        }
        if (v4) {
            writePreservedUnit(o, x, "monoUnit", defaults("iGin", 0, "sLvl", -898, "sEnable", 0, "m", 0, "s", 0, "pan", 64, "vol", 0));
            writePreservedUnit(o, x, "stUnit", defaults("iGin", 0, "m", 0, "s", 0, "vol", -129));
        } else {
            writePreservedUnit(o, x, "seUnit", defaults("inGain", 0, "sendLevel", -898, "sendEnable", 0, "mute", 0, "solo", 0, "pan", 64, "vol", 0));
            writePreservedUnit(o, x, "karaokeUnit", defaults("inGain", 0, "mute", 0, "solo", 0, "vol", -129));
        }
        o.close("mixer");

        // ---- master track ---------------------------------------------------------
        Element mk = x.only(VsqxReader.K_MASTER);
        String masterComment = mk == null ? "" : VsqxReader.txt(mk, "comment", "");
        int resolution = mk == null ? Song.DEFAULT_RESOLUTION : (int) VsqxReader.lng(mk, "resolution", Song.DEFAULT_RESOLUTION);
        if (resolution <= 0) resolution = Song.DEFAULT_RESOLUTION;
        // 1, as VOCALOID's own projects use (a project loaded from a file keeps its value)
        int preMeasure = mk == null ? 1 : (int) VsqxReader.lng(mk, "preMeasure", 1);
        o.open("masterTrack");
        o.leaf("seqName", cdata(trunc(song.masterName, 256)));
        o.leaf("comment", cdata(trunc(masterComment, 256)));
        o.num("resolution", clamp(resolution, 0, 65535));
        o.num("preMeasure", clamp(preMeasure, 0, 127));
        writeTimeSigs(o, song, v4, resolution);
        writeTempos(o, song, v4);
        o.close("masterTrack");

        // ---- vocal tracks ---------------------------------------------------------
        for (int i = 0; i < nt; i++) {
            Track t = i < tracks.size() ? tracks.get(i) : new Track();
            o.open("vsTrack");
            o.num(v4 ? "tNo" : "vsTrackNo", clamp(t.no, 0, 127));
            o.leaf(v4 ? "name" : "trackName", cdata(trunc(t.name, 256)));
            o.leaf("comment", cdata(trunc(t.comment, 256)));
            for (int pi = 0; pi < t.parts.size(); pi++) {
                writePart(o, t.parts.get(pi), v4, x, t.no, pi);
            }
            List<Element> pros = v4 ? Collections.<Element>emptyList()
                    : x.withAttr(VsqxReader.K_PROSODY, VsqxReader.A_NO, String.valueOf(t.no));
            for (int k = 0; k < pros.size(); k++) {
                List<Element> ch = VsqxReader.kids(pros.get(k));
                for (int c = 0; c < ch.size(); c++) o.rawLine(VsqxReader.serializeFragment(ch.get(c)));
            }
            o.close("vsTrack");
        }

        // ---- mono / st / se / karaoke tracks --------------------------------------
        if (v4) {
            writeRawTrack(o, x, "monoTrack");
            writeRawTrack(o, x, "stTrack");
        } else {
            writeRawTrack(o, x, "seTrack");
            writeRawTrack(o, x, "karaokeTrack");
        }

        // ---- unknown top level elements, then aux --------------------------------
        Element top = x.only(VsqxReader.K_TOP);
        if (top != null) {
            List<Element> ch = VsqxReader.kids(top);
            for (int i = 0; i < ch.size(); i++) o.rawLine(VsqxReader.serializeFragment(ch.get(i)));
        }
        for (int i = 0; i < x.aux.size(); i++) writeAux(o, x.aux.get(i), v4);
        // A VOCALOID4 document ends with its VST host chunk. Both reference projects carry the
        // identical block, so a project that has none gets the same one.
        if (v4 && x.aux.isEmpty()) {
            o.open("aux");
            o.leaf("id", cdata("AUX_VST_HOST_CHUNK_INFO"));
            o.leaf("content", cdata("VlNDSwAAAAADAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
            o.close("aux");
        }

        o.depth = 0;
        o.raw("\n</" + rootName + ">\n");
        return o.sb.toString();
    }

    // ------------------------------------------------------------------ parts

    private static void writePart(Out o, Part p, boolean v4, Extras x, int trackNo, int partIndex) {
        Element ext = x.part(trackNo, partIndex);
        o.open(v4 ? "vsPart" : "musicalPart");
        o.num(v4 ? "t" : "posTick", clampI(p.pos));
        o.num("playTime", Math.max(0, clampI(p.dur)));
        o.leaf(v4 ? "name" : "partName", cdata(trunc(p.name, 256)));
        o.leaf("comment", cdata(trunc(p.comment, 256)));

        Element plug = ext == null ? null : firstOf(ext, v4 ? "sPlug" : "stylePlugin", v4 ? "stylePlugin" : "sPlug");
        String plugId = plug == null ? null : VsqxReader.txt(plug, v4 ? "id" : "stylePluginID", null);
        if (plugId == null && plug != null) plugId = VsqxReader.txt(plug, v4 ? "stylePluginID" : "id", null);
        String plugName = plug == null ? null : VsqxReader.txt(plug, v4 ? "name" : "stylePluginName", null);
        if (plugName == null && plug != null) plugName = VsqxReader.txt(plug, v4 ? "stylePluginName" : "name", null);
        String plugVer = plug == null ? null : VsqxReader.txt(plug, "version", null);
        if (plugId == null || plugId.length() != 36) plugId = VsqxReader.DEFAULT_STYLE_ID;
        if (plugName == null) plugName = VsqxReader.DEFAULT_STYLE_NAME;
        if (plugVer == null) plugVer = VsqxReader.DEFAULT_STYLE_VERSION;
        if (plug == null) {
            o.open(v4 ? "sPlug" : "stylePlugin");
            o.leaf(v4 ? "id" : "stylePluginID", cdata(VsqxReader.DEFAULT_STYLE_ID));
            o.leaf(v4 ? "name" : "stylePluginName", cdata(VsqxReader.DEFAULT_STYLE_NAME));
            o.leaf("version", cdata(VsqxReader.DEFAULT_STYLE_VERSION));
            o.close(v4 ? "sPlug" : "stylePlugin");
        } else {
            o.open(v4 ? "sPlug" : "stylePlugin");
            o.leaf(v4 ? "id" : "stylePluginID", cdata(plugId));
            o.leaf(v4 ? "name" : "stylePluginName", cdata(trunc(plugName, 256)));
            o.leaf("version", cdata(trunc(plugVer, 15)));
            o.close(v4 ? "sPlug" : "stylePlugin");
        }

        // Always emitted. A VOCALOID4 part with no <pStyle> opened on the PC with an empty
        // sequence even though the notes were in the file; the editor's own parts always carry
        // these seven values.
        {
            String el = v4 ? "pStyle" : "partStyle";
            String attrEl = v4 ? "v" : "attr";
            o.open(el);
            for (String[] d : PART_STYLE_DEFAULTS) {
                Double v = p.style.get(d[0]);
                int val = iv(v != null ? v.doubleValue() : Double.parseDouble(d[1]));
                o.rawLine("<" + attrEl + " id=\"" + d[0] + "\">" + val + "</" + attrEl + ">");
            }
            for (Map.Entry<String, Double> e : p.style.entrySet()) {
                String id = trunc(e.getKey(), 32);
                if (id.length() == 0) continue;
                boolean known = false;
                for (String[] d : PART_STYLE_DEFAULTS) if (d[0].equals(id)) known = true;
                if (known) continue;
                o.rawLine("<" + attrEl + " id=\"" + VsqxReader.escAttr(id) + "\">" + iv(e.getValue().doubleValue()) + "</" + attrEl + ">");
            }
            o.close(el);
        }

        if (ext != null && !v4) {
            List<Element> phr = VsqxReader.kids(ext, "phraseStyle");
            for (int i = 0; i < phr.size(); i++) o.rawLine(VsqxReader.serializeFragment(phr.get(i)));
        }

        writeSinger(o, p, v4, ext);

        if (v4) {
            writePartCcs(o, p);
        } else {
            writePartCtrls3(o, p, p.ctrls);
        }

        for (int i = 0; i < p.notes.size(); i++) writeNote(o, p.notes.get(i), p, v4);

        // <plane> closes a VOCALOID4 part. The editor writes it on every part; a part without it
        // came up empty on the PC. The source value is kept when the project had one.
        if (v4) {
            Element plane = ext == null ? null : firstOf(ext, "plane");
            if (plane != null) o.rawLine(VsqxReader.serializeFragment(plane));
            else o.leaf("plane", "0");
        }

        o.close(v4 ? "vsPart" : "musicalPart");
    }

    private static void writeSinger(Out o, Part p, boolean v4, Element ext) {
        o.open("singer");
        o.num(v4 ? "t" : "posTick", 0);
        // never 0/0: that is exactly what made the editor load the part with no data at all
        o.num(v4 ? "bs" : "vBS", clamp(p.bs != 0 || p.pc != 0 ? p.bs : singerBs, 0, 127));
        o.num(v4 ? "pc" : "vPC", clamp(p.bs != 0 || p.pc != 0 ? p.pc : singerPc, 0, 127));
        o.close("singer");
        if (ext != null) {
            List<Element> singers = VsqxReader.kids(ext, "singer");
            for (int i = 0; i < singers.size(); i++) {
                Element s = singers.get(i);
                boolean fits = v4
                        ? VsqxReader.kid(s, "bs") != null || VsqxReader.kid(s, "t") != null
                        : VsqxReader.kid(s, "vBS") != null || VsqxReader.kid(s, "posTick") != null;
                if (fits) o.rawLine(VsqxReader.serializeFragment(s));
            }
        }
    }

    /** vsq4 part curves: one &lt;cc&gt; per point, grouped by id, ascending tick. */
    /** canonical id -> the spelling the real editor writes (single letters for the common four) */
    static String ctrlIdOut(String canonical, String rawFromFile) {
        if (rawFromFile != null && rawFromFile.length() > 0) return rawFromFile;
        if ("DYN".equals(canonical)) return "D";
        if ("BRE".equals(canonical)) return "B";
        if ("PIT".equals(canonical)) return "P";
        if ("PBS".equals(canonical)) return "S";
        return canonical;
    }

    private static void writePartCcs(Out o, Part p) {
        LinkedHashMap<String, ControlCurve> ctrls = p.ctrls;
        ArrayList<Row> rows = new ArrayList<Row>();
        int ci = 0;
        for (Map.Entry<String, ControlCurve> e : ctrls.entrySet()) {
            String key = e.getKey();
            if (key == null || key.startsWith("seqAttr:")) {
                ci++;
                continue;
            }
            String canon = key.startsWith("sCtrl:") ? key.substring(6) : key;
            String id = trunc(ctrlIdOut(canon, p.rawCtrlIds.get(canon)), 32);
            if (id.length() == 0) {
                ci++;
                continue;
            }
            List<ControlCurve.P> pts = e.getValue().points();
            for (int i = 0; i < pts.size(); i++) {
                ControlCurve.P pt = pts.get(i);
                // part-relative on disk again
                rows.add(new Row(pt.t - p.pos, ci, pt.v, id));
            }
            ci++;
        }
        sortRows(rows);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            o.rawLine("<cc><t>" + clampI(r.t) + "</t><v id=\"" + VsqxReader.escAttr(r.id) + "\">" + iv(r.v) + "</v></cc>");
        }
    }

    /** vsq3 part curves: &lt;mCtrl&gt; for musical parameters, &lt;sCtrl&gt; for pitch bend. */
    private static void writePartCtrls3(Out o, Part p, LinkedHashMap<String, ControlCurve> ctrls) {
        ArrayList<Row> m = new ArrayList<Row>();
        ArrayList<Row> s = new ArrayList<Row>();
        int ci = 0;
        for (Map.Entry<String, ControlCurve> e : ctrls.entrySet()) {
            String key = e.getKey();
            if (key == null || key.startsWith("seqAttr:")) {
                ci++;
                continue;
            }
            boolean sctrl = "PIT".equals(key) || "PBS".equals(key) || key.startsWith("sCtrl:");
            String id = key.startsWith("sCtrl:") ? key.substring(6) : key;
            id = trunc(id, 32);
            if (id.length() == 0) {
                ci++;
                continue;
            }
            List<ControlCurve.P> pts = e.getValue().points();
            for (int i = 0; i < pts.size(); i++) {
                ControlCurve.P pt = pts.get(i);
                (sctrl ? s : m).add(new Row(pt.t - p.pos, ci, pt.v, id));
            }
            ci++;
        }
        sortRows(m);
        sortRows(s);
        for (int i = 0; i < m.size(); i++) {
            Row r = m.get(i);
            o.rawLine("<mCtrl><posTick>" + clampI(r.t) + "</posTick><attr id=\"" + VsqxReader.escAttr(r.id) + "\">" + iv(r.v) + "</attr></mCtrl>");
        }
        for (int i = 0; i < s.size(); i++) {
            Row r = s.get(i);
            o.rawLine("<sCtrl><posTick>" + clampI(r.t) + "</posTick><attr id=\"" + VsqxReader.escAttr(r.id) + "\">" + iv(r.v) + "</attr></sCtrl>");
        }
    }

    private static void writeNote(Out o, Note n, Part p, boolean v4) {
        o.open("note");
        if (v4) {
            o.num("t", clampI(n.pos - p.pos));
            o.num("dur", Math.max(1, clampI(n.dur)));
            o.num("n", clamp(n.key, 0, 127));
            o.num("v", clamp(n.vel, 0, 127));
            o.leaf("y", cdata(trunc(n.lyric, 32)));
            String ph = phonemeFor(n);
            if (n.lockPhoneme) o.rawLine("<p lock=\"1\">" + cdata(ph) + "</p>");
            else o.leaf("p", cdata(ph));
            // <nStyle> is written even when the project sets no style: VOCALOID4 verifies every note
            // and rejects one without it, and the editor's own files carry the nine standard
            // attributes on every single note.
            o.open("nStyle");
            writeStyleDefaults(o, n, true);
                for (Map.Entry<String, ControlCurve> e : n.ctrls.entrySet()) {
                    String id = e.getKey();
                    if (id == null || id.startsWith("seqAttr:")) continue;
                    id = trunc(id, 32);
                    if (id.length() == 0 || e.getValue().isEmpty()) continue;
                    o.openAttr("seq", " id=\"" + VsqxReader.escAttr(id) + "\"");
                    List<ControlCurve.P> pts = e.getValue().points();
                    ArrayList<Row> rows = new ArrayList<Row>();
                    for (int i = 0; i < pts.size(); i++) rows.add(new Row(pts.get(i).t, 0, pts.get(i).v, id));
                    sortRows(rows);
                    for (int i = 0; i < rows.size(); i++) {
                        Row r = rows.get(i);
                        o.rawLine("<cc><p>" + clampI(r.t) + "</p><v>" + iv(r.v) + "</v></cc>");
                    }
                    o.close("seq");
                }
            o.close("nStyle");
        } else {
            o.num("posTick", clampI(n.pos - p.pos));
            o.num("durTick", Math.max(1, clampI(n.dur)));
            o.num("noteNum", clamp(n.key, 0, 127));
            o.num("velocity", clamp(n.vel, 0, 127));
            o.leaf("lyric", cdata(trunc(n.lyric, 32)));
            String ph = phonemeFor(n);
            if (n.lockPhoneme) o.rawLine("<phnms lock=\"1\">" + cdata(ph) + "</phnms>");
            else o.leaf("phnms", cdata(ph));
            // same as the v4 branch: the editor expects the full style block on every note
            o.open("noteStyle");
            writeStyleDefaults(o, n, false);
                for (Map.Entry<String, ControlCurve> e : n.ctrls.entrySet()) {
                    String key = e.getKey();
                    if (key == null || !key.startsWith("seqAttr:")) continue;
                    String id = trunc(key.substring(8), 32);
                    if (id.length() == 0 || e.getValue().isEmpty()) continue;
                    o.openAttr("seqAttr", " id=\"" + VsqxReader.escAttr(id) + "\"");
                    List<ControlCurve.P> pts = e.getValue().points();
                    ArrayList<Row> rows = new ArrayList<Row>();
                    for (int i = 0; i < pts.size(); i++) rows.add(new Row(pts.get(i).t, 0, pts.get(i).v, id));
                    sortRows(rows);
                    for (int i = 0; i < rows.size(); i++) {
                        Row r = rows.get(i);
                        o.rawLine("<elem><posNrm>" + clampI(r.t) + "</posNrm><elv>" + iv(r.v) + "</elv></elem>");
                    }
                    o.close("seqAttr");
                }
            o.close("noteStyle");
        }
        o.close("note");
    }

    /** singer ids used when the project does not carry them; set at the start of every write */
    private static int singerBs = 4, singerPc = 1;

    /** Part style ids VOCALOID4 writes on every part (note: no vibLen / vibType here). */
    private static final String[][] PART_STYLE_DEFAULTS = {
        {"accent", "50"}, {"bendDep", "8"}, {"bendLen", "0"}, {"decay", "50"},
        {"fallPort", "0"}, {"opening", "127"}, {"risePort", "0"},
    };

    /** Note style ids VOCALOID writes on every note, with the values the editor itself uses. */
    private static final String[][] NOTE_STYLE_DEFAULTS = {
        {"accent", "50"}, {"bendDep", "8"}, {"bendLen", "0"}, {"decay", "50"},
        {"fallPort", "0"}, {"opening", "127"}, {"risePort", "0"}, {"vibLen", "0"},
        {"vibType", "0"},
    };

    /**
     * A phoneme for the note, never empty.
     *
     * VOCALOID4 keeps the sung phoneme in {@code <p>} and verifies it. A note created here has none
     * until the user edits it, and writing an empty element made the PC editor refuse the whole file
     * ("Verify error ... CVSMusicalNote"). The lyric is converted with the same G2P the renderer uses.
     */
    private static String phonemeFor(Note n) {
        String ph = trunc(n.phoneme, 32).trim();
        if (!ph.isEmpty()) return ph;
        try {
            java.util.List<String> syms = com.dsh.v4tune.phoneme.ChineseG2P.lyricToPhonemes(n.lyric);
            if (syms != null && !syms.isEmpty()) {
                // VOCALOID4 keeps ONE symbol per note - the syllable, e.g. <y>luo</y><p>uo</p> -
                // and re-derives the initial from the lyric. The G2P returns the initial and the
                // final separately ("l", "uo"), and writing both made the PC editor drop notes.
                // the whole sequence, the way VOCALOID writes it: a real project carries
                // <y>kan</y><p>k_h a_n</p>, so initial + final is the correct form here
                ph = trunc(com.dsh.v4tune.phoneme.PhonemeTable.join(syms), 32).trim();
            }
        } catch (Throwable ignored) { }
        if (ph.isEmpty()) ph = trunc(n.lyric, 32).trim();
        return ph;
    }

    /** the nine standard attributes first, then anything else the note carries */
    private static void writeStyleDefaults(Out o, Note n, boolean v4) {
        for (String[] d : NOTE_STYLE_DEFAULTS) {
            Double v = n.style.get(d[0]);
            int val = iv(v != null ? v.doubleValue() : Double.parseDouble(d[1]));
            o.rawLine(v4 ? ("<v id=\"" + d[0] + "\">" + val + "</v>")
                         : ("<attr id=\"" + d[0] + "\">" + val + "</attr>"));
        }
        for (Map.Entry<String, Double> e : n.style.entrySet()) {
            String id = trunc(e.getKey(), 32);
            if (id.length() == 0) continue;
            boolean known = false;
            for (String[] d : NOTE_STYLE_DEFAULTS) if (d[0].equals(id)) known = true;
            if (known) continue;
            o.rawLine(v4 ? ("<v id=\"" + VsqxReader.escAttr(id) + "\">" + iv(e.getValue().doubleValue()) + "</v>")
                         : ("<attr id=\"" + VsqxReader.escAttr(id) + "\">" + iv(e.getValue().doubleValue()) + "</attr>"));
        }
    }
    // ------------------------------------------------------------------ master track

    private static void writeTimeSigs(Out o, Song song, boolean v4, int resolution) {
        ArrayList<int[]> ts = new ArrayList<int[]>(song.timeSigs);
        if (ts.isEmpty()) ts.add(new int[]{0, 4, 4});
        Collections.sort(ts, new Comparator<int[]>() {
            public int compare(int[] a, int[] b) {
                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
            }
        });
        long prevTick = 0;
        int curM = 0;
        int prevNu = 4, prevDe = 4;
        boolean first = true;
        for (int i = 0; i < ts.size(); i++) {
            int[] e = ts.get(i);
            long mt = VsqxReader.measureTicks(resolution, prevNu, prevDe);
            if (first) {
                long base = VsqxReader.measureTicks(resolution, 4, 4);
                curM = base <= 0 ? 0 : (int) Math.round((double) e[0] / base);
                first = false;
            } else {
                curM += mt <= 0 ? 0 : (int) Math.round((double) (e[0] - prevTick) / mt);
            }
            int nu = clamp(e.length > 1 ? e[1] : 4, 1, 255);
            int de = clamp(e.length > 2 ? e[2] : 4, 1, 255);
            o.open("timeSig");
            o.num(v4 ? "m" : "posMes", clampI(curM));
            o.num(v4 ? "nu" : "nume", nu);
            o.num(v4 ? "de" : "denomi", de);
            o.close("timeSig");
            prevTick = e[0];
            prevNu = nu;
            prevDe = de;
        }
    }

    private static void writeTempos(Out o, Song song, boolean v4) {
        ArrayList<long[]> tp = new ArrayList<long[]>(song.tempos);
        if (tp.isEmpty()) tp.add(new long[]{0, 12000});
        Collections.sort(tp, new Comparator<long[]>() {
            public int compare(long[] a, long[] b) {
                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
            }
        });
        for (int i = 0; i < tp.size(); i++) {
            long[] e = tp.get(i);
            o.open("tempo");
            o.num(v4 ? "t" : "posTick", clampI(e[0]));
            o.num(v4 ? "v" : "bpm", clampI(e[1]));
            o.close("tempo");
        }
    }

    // ------------------------------------------------------------------ preserved extras

    private static void writeRawTrack(Out o, Extras x, String kind) {
        List<Element> l = x.withAttr(VsqxReader.K_TRACK, VsqxReader.A_KIND, kind);
        for (int i = 0; i < l.size(); i++) {
            List<Element> ch = VsqxReader.kids(l.get(i));
            for (int c = 0; c < ch.size(); c++) {
                Element e = ch.get(c);
                if (kind.equals(VsqxReader.local(e))) {
                    o.rawLine(VsqxReader.serializeFragment(e));
                    return;
                }
            }
        }
        o.open(kind);
        o.close(kind);
    }

    private static void writePreservedUnit(Out o, Extras x, String name, LinkedHashMap<String, Long> dflt) {
        Element keep = x.find(VsqxReader.K_MIXER, name);
        if (keep != null) {
            o.rawLine(VsqxReader.serializeFragment(keep));
            return;
        }
        o.open(name);
        for (Map.Entry<String, Long> e : dflt.entrySet()) o.num(e.getKey(), e.getValue().longValue());
        o.close(name);
    }

    private static void writePlugFragments(Out o, Extras x, String block, boolean v4) {
        Element keep = x.only(block);
        if (keep == null) return;
        List<Element> ch = VsqxReader.kids(keep);
        for (int i = 0; i < ch.size(); i++) {
            Element e = ch.get(i);
            String ln = VsqxReader.local(e);
            if (!ln.startsWith("plug") && !ln.startsWith("vstPlugin")) continue;
            if (!matchesSchema(ln, v4)) continue;
            o.rawLine(VsqxReader.serializeFragment(e));
        }
    }

    private static void writeTrackPlugFragments(Out o, Extras x, boolean v4, int tNo, int index) {
        List<Element> blocks = x.withAttr(VsqxReader.K_VSUNIT, VsqxReader.A_NO, String.valueOf(tNo));
        if (blocks.isEmpty()) blocks = x.withAttr(VsqxReader.K_VSUNIT, VsqxReader.A_NO, String.valueOf(index));
        for (int i = 0; i < blocks.size(); i++) {
            List<Element> ch = VsqxReader.kids(blocks.get(i));
            for (int c = 0; c < ch.size(); c++) {
                Element e = ch.get(c);
                if (!matchesSchema(VsqxReader.local(e), v4)) continue;
                o.rawLine(VsqxReader.serializeFragment(e));
            }
        }
    }

    private static void writeAux(Out o, Element aux, boolean v4) {
        o.open("aux");
        List<Element> ch = VsqxReader.kids(aux);
        for (int i = 0; i < ch.size(); i++) {
            Element c = ch.get(i);
            String ln = VsqxReader.local(c);
            if ("id".equals(ln) || "auxID".equals(ln)) o.leaf(v4 ? "id" : "auxID", cdata(trunc(c.getTextContent(), 256)));
            else if ("content".equals(ln)) o.leaf("content", cdata(c.getTextContent()));
            else o.rawLine(VsqxReader.serializeFragment(c));
        }
        o.close("aux");
    }

    /** vsq3 uses vstPlugin* names, vsq4 uses plug* - never mix them up. */
    private static boolean matchesSchema(String localName, boolean v4) {
        boolean vst = localName.startsWith("vstPlugin");
        boolean plug = localName.startsWith("plug");
        if (!vst && !plug) return true;
        return v4 ? plug : vst;
    }

    private static Element firstOf(Element parent, String... names) {
        for (int i = 0; i < names.length; i++) {
            Element e = VsqxReader.kid(parent, names[i]);
            if (e != null) return e;
        }
        return null;
    }

    private static LinkedHashMap<String, Long> defaults(Object... kv) {
        LinkedHashMap<String, Long> m = new LinkedHashMap<String, Long>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], Long.valueOf(((Number) kv[i + 1]).longValue()));
        return m;
    }

    // ------------------------------------------------------------------ small helpers

    private static final class Row {
        final long t;
        final int order;
        final double v;
        final String id;

        Row(long t, int order, double v, String id) {
            this.t = t;
            this.order = order;
            this.v = v;
            this.id = id;
        }
    }

    private static void sortRows(ArrayList<Row> rows) {
        Collections.sort(rows, new Comparator<Row>() {
            public int compare(Row a, Row b) {
                if (a.t != b.t) return a.t < b.t ? -1 : 1;
                if (a.order != b.order) return a.order < b.order ? -1 : 1;
                return 0;
            }
        });
    }

    static int iv(double d) {
        if (Double.isNaN(d)) return 0;
        if (d >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (d <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) Math.round(d);
    }

    static int clampI(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) v;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static String componentId(String s) {
        StringBuilder b = new StringBuilder();
        if (s != null) {
            for (int i = 0; i < s.length() && b.length() < 16; i++) {
                char c = s.charAt(i);
                if (c >= 0x20) b.append(c);
            }
        }
        while (b.length() < 16) b.append('0');
        return b.toString();
    }

    static String trunc(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            if (b.length() >= max) break;
            b.append(c);
        }
        if (b.length() > 0 && Character.isHighSurrogate(b.charAt(b.length() - 1))) b.setLength(b.length() - 1);
        return b.toString();
    }

    static String cdata(String s) {
        if (s == null) s = "";
        if (s.indexOf("]]>") >= 0) s = s.replace("]]>", "]]]]><![CDATA[>");
        return "<![CDATA[" + s + "]]>";
    }

    /** Minimal, editor style serialiser: tab indented, one element per line. */
    private static final class Out {
        final StringBuilder sb = new StringBuilder(16384);
        int depth = 0;

        void line() {
            sb.append('\n');
            for (int i = 0; i < depth; i++) sb.append('\t');
        }

        void raw(String s) {
            sb.append(s);
        }

        void rawLine(String s) {
            line();
            sb.append(s);
        }

        void open(String name) {
            line();
            sb.append('<').append(name).append('>');
            depth++;
        }

        void openAttr(String name, String attrs) {
            line();
            sb.append('<').append(name).append(attrs).append('>');
            depth++;
        }

        void close(String name) {
            depth--;
            line();
            sb.append("</").append(name).append('>');
        }

        void leaf(String name, String body) {
            line();
            sb.append('<').append(name).append('>').append(body).append("</").append(name).append('>');
        }

        void num(String name, long v) {
            line();
            sb.append('<').append(name).append('>').append(v).append("</").append(name).append('>');
        }
    }

    // ------------------------------------------------------------------ auxXml index

    /** Index over the preserved fragments of Song.auxXml. Never throws: bad data is ignored. */
    static final class Extras {
        final ArrayList<Element> aux = new ArrayList<Element>();
        private final HashMap<String, ArrayList<Element>> byName = new HashMap<String, ArrayList<Element>>();
        private final HashMap<String, Element> parts = new HashMap<String, Element>();

        static Extras parse(String auxXml) {
            Extras x = new Extras();
            if (auxXml == null || auxXml.trim().length() == 0) return x;
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(false);
                f.setValidating(false);
                f.setExpandEntityReferences(false);
                try {
                    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                } catch (Exception ignored) {
                }
                DocumentBuilder b = f.newDocumentBuilder();
                b.setEntityResolver(new org.xml.sax.EntityResolver() {
                    public InputSource resolveEntity(String publicId, String systemId) {
                        return new InputSource(new StringReader(""));
                    }
                });
                Document d = b.parse(new InputSource(new StringReader("<dshRoot>" + auxXml + "</dshRoot>")));
                Element root = d.getDocumentElement();
                List<Element> ch = VsqxReader.kids(root);
                for (int i = 0; i < ch.size(); i++) {
                    Element e = ch.get(i);
                    String ln = VsqxReader.local(e);
                    if ("aux".equals(ln)) {
                        x.aux.add(e);
                        continue;
                    }
                    ArrayList<Element> l = x.byName.get(ln);
                    if (l == null) {
                        l = new ArrayList<Element>();
                        x.byName.put(ln, l);
                    }
                    l.add(e);
                    if (VsqxReader.K_PART.equals(ln)) {
                        x.parts.put(VsqxReader.attr(e, VsqxReader.A_NO, "0") + ":" + VsqxReader.attr(e, VsqxReader.A_INDEX, "0"), e);
                    }
                }
            } catch (Exception ignored) {
                // malformed preserved data: write the document without it
            }
            return x;
        }

        Element only(String name) {
            ArrayList<Element> l = byName.get(name);
            return l == null || l.isEmpty() ? null : l.get(0);
        }

        List<Element> withAttr(String name, String attr, String value) {
            ArrayList<Element> out = new ArrayList<Element>();
            ArrayList<Element> l = byName.get(name);
            if (l == null) return out;
            for (int i = 0; i < l.size(); i++) {
                if (value.equals(VsqxReader.attr(l.get(i), attr, null))) out.add(l.get(i));
            }
            return out;
        }

        Element find(String block, String childName) {
            Element b = only(block);
            return b == null ? null : VsqxReader.kid(b, childName);
        }

        Element part(int trackNo, int index) {
            return parts.get(trackNo + ":" + index);
        }
    }
}
