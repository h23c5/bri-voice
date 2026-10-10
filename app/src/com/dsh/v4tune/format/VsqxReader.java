package com.dsh.v4tune.format;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;

/**
 * Reader for VOCALOID project files: VOCALOID3 "vsq3" and VOCALOID4 "vsq4" schemas.
 *
 * <p>The parser is namespace agnostic (it matches on local names only) and never fails on
 * unknown elements: anything the model cannot hold is preserved verbatim in
 * {@link Song#auxXml} so that {@link VsqxWriter} can put it back.</p>
 *
 * <h3>Preserved data (Song.auxXml)</h3>
 * auxXml is a concatenation of <b>namespace free</b> XML fragments (local names only, no
 * xmlns attributes). The writer re-emits them inside the document namespace. Fragment roots:
 * <ul>
 *   <li>{@code aux} - a real &lt;aux&gt; element, re-emitted at the end of the document.</li>
 *   <li>{@code dshVoice i="k"} - unknown vVoice children (for example id2 / vPrm2).</li>
 *   <li>{@code dshMasterTrack} - masterTrack scalars the model does not carry
 *       (comment, resolution, preMeasure).</li>
 *   <li>{@code dshMixer} - masterUnit plug/plugSR and the monoUnit/stUnit (vsq4) or
 *       seUnit/karaokeUnit (vsq3) blocks.</li>
 *   <li>{@code dshVsUnit tNo="n"} - per-track vsUnit VST plug elements.</li>
 *   <li>{@code dshTrack kind="monoTrack|stTrack|seTrack|karaokeTrack"} - the raw mono/st/se/
 *       karaoke track element (usually holding wavParts).</li>
 *   <li>{@code dshPart tNo="n" i="k"} - per part extras: stylePlugin/sPlug, plane, phraseStyle
 *       and any singer element after the first one.</li>
 *   <li>{@code dshProsody tNo="n"} - raw vsq3 prosodyPart elements (not modelled).</li>
 *   <li>{@code dshTop} - unknown top level elements.</li>
 * </ul>
 *
 * <h3>Documented normalisations on read</h3>
 * <ul>
 *   <li>vsq3 note/posTick, mCtrl/posTick and sCtrl/posTick are part local: they are converted
 *       to absolute track ticks (part.pos is added). vsq4 positions are already absolute.</li>
 *   <li>vsq3 noteStyle/seqAttr ({@code elem/posNrm/elv}) has no verified tick base; it is kept
 *       verbatim in {@link Note#ctrls} under the key {@code "seqAttr:"+id} with the raw posNrm
 *       as the tick, so it round trips exactly.</li>
 *   <li>vsq3 sCtrl ids other than PIT/PBS are stored as {@code "sCtrl:"+id} so the writer can
 *       put them back into sCtrl instead of mCtrl.</li>
 *   <li>&lt;timeSig&gt;&lt;m&gt; (vsq4) / &lt;posMes&gt; (vsq3) are measure indices; they become
 *       absolute ticks, assuming the implicit leading 4/4 meter and a tick origin at measure
 *       index 0 (preMeasure does not move the origin).</li>
 *   <li>{@link Voice#lang} is not stored in the file; it is derived from the voicebank name /
 *       component id ("*CHN*" = 2 chinese, "*ENG*" = 1 english, else 0 japanese).</li>
 *   <li>vsq4 singer/t is part local (the editor writes 0); only bs/pc are kept, further
 *       singer elements of a part are preserved verbatim.</li>
 *   <li>Unknown top level elements are preserved verbatim (dshTop), so a source document that
 *       is not schema conform keeps its extra elements - and stays non conform.</li>
 * </ul>
 */
public final class VsqxReader {

    public static final String NS_VSQ4 = "http://www.yamaha.co.jp/vocaloid/schema/vsq4/";
    public static final String NS_VSQ3 = "http://www.yamaha.co.jp/vocaloid/schema/vsq3/";

    /** canonical style plugin used by the editor when a part has no style */
    public static final String DEFAULT_STYLE_ID = "ACA9C502-A04B-42b5-B2EB-5CEA36D16FCE";
    public static final String DEFAULT_STYLE_NAME = "VOCALOID2 Compatible Style";
    public static final String DEFAULT_STYLE_VERSION = "3.0.0.1";

    // --- names of the preserved fragments stored in Song.auxXml -------------------------
    static final String K_MASTER = "dshMasterTrack";
    static final String K_MIXER = "dshMixer";
    static final String K_VSUNIT = "dshVsUnit";
    static final String K_TRACK = "dshTrack";
    static final String K_PART = "dshPart";
    static final String K_VOICE = "dshVoice";
    static final String K_PROSODY = "dshProsody";
    static final String K_TOP = "dshTop";
    static final String A_NO = "tNo";
    static final String A_INDEX = "i";
    static final String A_KIND = "kind";

    private VsqxReader() {
    }

    /** Reads a .vsqx document. Throws IOException for anything that is not a usable document. */
    public static Song read(InputStream in) throws IOException {
        Document doc;
        try {
            doc = newBuilder().parse(in);
        } catch (SAXException e) {
            throw new IOException("not a well formed vsqx document: " + e.getMessage(), e);
        } catch (ParserConfigurationException e) {
            throw new IOException("XML parser configuration failed: " + e.getMessage(), e);
        }
        Element root = doc.getDocumentElement();
        if (root == null) {
            throw new IOException("empty vsqx document");
        }
        Song song = new Song();
        String rn = local(root);
        String ns = root.getNamespaceURI();
        song.schema = ("vsq3".equals(rn) || NS_VSQ3.equals(ns)) ? 3 : 4;
        boolean v4 = song.schema == 4;

        String s = txt(root, "vender", null);
        if (s != null) song.vendor = s;
        s = txt(root, "version", null);
        if (s != null) song.version = s;

        StringBuilder keep = new StringBuilder(4096);

        // ---- voices ---------------------------------------------------------------
        Element vvt = kid(root, "vVoiceTable");
        if (vvt != null) {
            List<Element> vs = kids(vvt, "vVoice");
            for (int i = 0; i < vs.size(); i++) {
                Element vv = vs.get(i);
                song.voices.add(readVoice(vv, v4));
                StringBuilder ex = new StringBuilder();
                List<Element> ch = kids(vv);
                for (int k = 0; k < ch.size(); k++) {
                    if (!isVoiceField(local(ch.get(k)), v4)) ex.append(serializeFragment(ch.get(k)));
                }
                if (ex.length() > 0) {
                    keep.append(wrapOpen(K_VOICE, A_INDEX, i)).append(ex).append("</").append(K_VOICE).append('>');
                }
            }
        }

        // ---- mixer ----------------------------------------------------------------
        Element mixer = kid(root, "mixer");
        Element masterUnit = kid(mixer, "masterUnit");
        if (masterUnit != null) {
            song.outDev = in(masterUnit, v4 ? "oDev" : "outDev", 0);
            song.masterRetLevel = in(masterUnit, v4 ? "rLvl" : "retLevel", 0);
            song.masterVol = in(masterUnit, "vol", 0);
        }
        StringBuilder mix = new StringBuilder();
        if (masterUnit != null) {
            List<Element> ch = kids(masterUnit);
            for (int i = 0; i < ch.size(); i++) {
                if (isPlugin(local(ch.get(i)))) mix.append(serializeFragment(ch.get(i)));
            }
        }
        ArrayList<int[]> units = new ArrayList<int[]>();
        ArrayList<String> unitPlugs = new ArrayList<String>();
        if (mixer != null) {
            List<Element> us = kids(mixer, "vsUnit");
            for (int i = 0; i < us.size(); i++) {
                Element u = us.get(i);
                units.add(new int[]{
                        in(u, v4 ? "tNo" : "vsTrackNo", i),
                        in(u, v4 ? "iGin" : "inGain", 0),
                        in(u, v4 ? "sLvl" : "sendLevel", 0),
                        inAny(u, 0, "sEnable", "sendEnable"),
                        in(u, v4 ? "m" : "mute", 0),
                        in(u, v4 ? "s" : "solo", 0),
                        in(u, "pan", 0),
                        in(u, "vol", 0)});
                StringBuilder pl = new StringBuilder();
                List<Element> ch = kids(u);
                for (int k = 0; k < ch.size(); k++) {
                    if (isPlugin(local(ch.get(k)))) pl.append(serializeFragment(ch.get(k)));
                }
                unitPlugs.add(pl.toString());
            }
            List<Element> ch = kids(mixer);
            for (int i = 0; i < ch.size(); i++) {
                String ln = local(ch.get(i));
                if ("monoUnit".equals(ln) || "stUnit".equals(ln) || "seUnit".equals(ln) || "karaokeUnit".equals(ln)) {
                    mix.append(serializeFragment(ch.get(i)));
                }
            }
        }

        // ---- master track ---------------------------------------------------------
        Element mt = kid(root, "masterTrack");
        int res = Song.DEFAULT_RESOLUTION;
        if (mt != null) {
            song.masterName = txt(mt, "seqName", "");
            int pre = in(mt, "preMeasure", 4);
            res = in(mt, "resolution", Song.DEFAULT_RESOLUTION);
            if (res <= 0) res = Song.DEFAULT_RESOLUTION;
            keep.append('<').append(K_MASTER).append('>');
            keep.append("<comment>").append(escText(txt(mt, "comment", ""))).append("</comment>");
            keep.append("<resolution>").append(res).append("</resolution>");
            keep.append("<preMeasure>").append(pre).append("</preMeasure>");
            keep.append("</").append(K_MASTER).append('>');
            readTimeSigs(mt, song, v4, res);
            readTempos(mt, song, v4);
        }
        if (mix.length() > 0) {
            keep.append('<').append(K_MIXER).append('>').append(mix).append("</").append(K_MIXER).append('>');
        }

        // ---- vocal tracks ---------------------------------------------------------
        List<Element> vts = kids(root, "vsTrack");
        for (int ti = 0; ti < vts.size(); ti++) {
            Element vt = vts.get(ti);
            Track tr = new Track();
            tr.no = in(vt, v4 ? "tNo" : "vsTrackNo", ti);
            tr.name = txt(vt, v4 ? "name" : "trackName", "");
            tr.comment = txt(vt, "comment", "");
            int partIndex = 0;
            List<Element> ch = kids(vt);
            for (int ci = 0; ci < ch.size(); ci++) {
                Element ce = ch.get(ci);
                String ln = local(ce);
                if ("vsPart".equals(ln) || "musicalPart".equals(ln)) {
                    tr.parts.add(readPart(ce, song.schema, tr.no, partIndex, keep));
                    partIndex++;
                } else if ("prosodyPart".equals(ln)) {
                    keep.append(wrapOpen(K_PROSODY, A_NO, tr.no)).append(serializeFragment(ce))
                            .append("</").append(K_PROSODY).append('>');
                }
            }
            int[] uv = findUnit(units, ti, tr.no);
            if (uv != null) {
                tr.inGain = uv[1];
                tr.sendLevel = uv[2];
                tr.sendEnable = uv[3] != 0;
                tr.mute = uv[4] != 0;
                tr.solo = uv[5] != 0;
                tr.pan = uv[6];
                tr.vol = uv[7];
            }
            String plug = findUnitPlug(units, unitPlugs, ti, tr.no);
            if (plug != null && plug.length() > 0) {
                keep.append(wrapOpen(K_VSUNIT, A_NO, tr.no)).append(plug).append("</").append(K_VSUNIT).append('>');
            }
            song.tracks.add(tr);
        }

        // ---- mono / st / se / karaoke tracks, aux, unknown top level --------------
        List<Element> tops = kids(root);
        StringBuilder topKeep = new StringBuilder();
        for (int i = 0; i < tops.size(); i++) {
            Element e = tops.get(i);
            String ln = local(e);
            if ("monoTrack".equals(ln) || "stTrack".equals(ln) || "seTrack".equals(ln) || "karaokeTrack".equals(ln)) {
                keep.append(wrapOpen(K_TRACK, A_KIND, ln)).append(serializeFragment(e))
                        .append("</").append(K_TRACK).append('>');
            } else if ("aux".equals(ln)) {
                keep.append(serializeFragment(e));
            } else if (!isKnownTop(ln)) {
                topKeep.append(serializeFragment(e));
            }
        }
        if (topKeep.length() > 0) {
            keep.append('<').append(K_TOP).append('>').append(topKeep).append("</").append(K_TOP).append('>');
        }
        song.auxXml = keep.toString();
        return song;
    }

    /** Convenience: reads a file from disk. */
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

    // ------------------------------------------------------------------ parts/notes

    private static Part readPart(Element pe, int schema, int trackNo, int partIndex, StringBuilder keep) {
        boolean v4 = schema == 4;
        Part p = new Part();
        p.pos = lng(pe, v4 ? "t" : "posTick", 0);
        p.dur = lng(pe, "playTime", 1920);
        p.name = txt(pe, v4 ? "name" : "partName", "");
        p.comment = txt(pe, "comment", "");

        StringBuilder ext = new StringBuilder();
        Element sp = kid(pe, v4 ? "sPlug" : "stylePlugin");
        if (sp != null) ext.append(serializeFragment(sp));
        List<Element> phr = kids(pe, "phraseStyle");
        for (int i = 0; i < phr.size(); i++) ext.append(serializeFragment(phr.get(i)));

        Element ps = kid(pe, v4 ? "pStyle" : "partStyle");
        if (ps != null) {
            List<Element> vs = kids(ps);
            for (int i = 0; i < vs.size(); i++) {
                Element v = vs.get(i);
                String id = attr(v, "id", null);
                if (id != null && id.length() > 0) p.style.put(id, num(v.getTextContent(), 0));
            }
        }

        List<Element> singers = kids(pe, "singer");
        if (!singers.isEmpty()) {
            Element s0 = singers.get(0);
            p.bs = in(s0, v4 ? "bs" : "vBS", 0);
            p.pc = in(s0, v4 ? "pc" : "vPC", 0);
            for (int i = 1; i < singers.size(); i++) ext.append(serializeFragment(singers.get(i)));
        }

        if (v4) {
            List<Element> ccs = kids(pe, "cc");
            for (int i = 0; i < ccs.size(); i++) {
                Element cc = ccs.get(i);
                Element v = kid(cc, "v");
                if (v == null) continue;
                String id = attr(v, "id", null);
                if (id == null || id.length() == 0) continue;
                String canon = canonCtrl(id);
                if (!canon.equals(id)) p.rawCtrlIds.put(canon, id);
                // <cc><t> is part-relative in BOTH schemas; the model uses absolute track ticks
                addPoint(p.ctrls, canon, p.pos + lng(cc, "t", 0), num(v.getTextContent(), 0));
            }
            List<Element> ns = kids(pe, "note");
            for (int i = 0; i < ns.size(); i++) p.notes.add(readNote(ns.get(i), schema, p.pos));
        } else {
            List<Element> cs = kids(pe, "mCtrl");
            for (int i = 0; i < cs.size(); i++) {
                Element mc = cs.get(i);
                Element a = kid(mc, "attr");
                if (a == null) continue;
                String id = attr(a, "id", null);
                if (id == null || id.length() == 0) continue;
                addPoint(p.ctrls, id, p.pos + lng(mc, "posTick", 0), num(a.getTextContent(), 0));
            }
            cs = kids(pe, "sCtrl");
            for (int i = 0; i < cs.size(); i++) {
                Element sc = cs.get(i);
                Element a = kid(sc, "attr");
                if (a == null) continue;
                String id = attr(a, "id", null);
                if (id == null || id.length() == 0) continue;
                if (!"PIT".equals(id) && !"PBS".equals(id)) id = "sCtrl:" + id;
                addPoint(p.ctrls, id, p.pos + lng(sc, "posTick", 0), num(a.getTextContent(), 0));
            }
            List<Element> ns = kids(pe, "note");
            for (int i = 0; i < ns.size(); i++) p.notes.add(readNote(ns.get(i), schema, p.pos));
        }

        Element plane = kid(pe, "plane");
        if (plane != null) ext.append(serializeFragment(plane));
        if (ext.length() > 0) {
            keep.append('<').append(K_PART).append(' ').append(A_NO).append("=\"").append(trackNo).append('"')
                    .append(' ').append(A_INDEX).append("=\"").append(partIndex).append("\">")
                    .append(ext).append("</").append(K_PART).append('>');
        }
        return p;
    }

    /** real VOCALOID4 files write the four common control parameters as single letters */
    public static String canonCtrl(String id) {
        if (id == null || id.length() != 1) return id;
        char c = id.charAt(0);
        if (c == 'D') return "DYN";
        if (c == 'B') return "BRE";
        if (c == 'P') return "PIT";
        if (c == 'S') return "PBS";
        return id;
    }

    private static Note readNote(Element ne, int schema, long partPos) {
        boolean v4 = schema == 4;
        Note n = new Note();
        // note positions are PART-RELATIVE in both vsq3 and vsq4 (proved: 魔术之心.vsqx has a
        // note at t=708 inside a part starting at 1920); the model stores absolute track ticks.
        n.pos = lng(ne, v4 ? "t" : "posTick", 0) + partPos;
        n.dur = lng(ne, v4 ? "dur" : "durTick", 480);
        n.key = in(ne, v4 ? "n" : "noteNum", 60);
        n.vel = in(ne, v4 ? "v" : "velocity", 64);
        n.lyric = txt(ne, v4 ? "y" : "lyric", "a");
        Element ph = kid(ne, v4 ? "p" : "phnms");
        if (ph != null) {
            String t = ph.getTextContent();
            n.phoneme = t == null ? "" : t;
            String lock = attr(ph, "lock", null);
            n.lockPhoneme = "1".equals(lock) || "true".equalsIgnoreCase(lock);
        }
        Element st = kid(ne, v4 ? "nStyle" : "noteStyle");
        if (st != null) {
            List<Element> ch = kids(st);
            for (int i = 0; i < ch.size(); i++) {
                Element c = ch.get(i);
                String ln = local(c);
                if ("v".equals(ln) || "attr".equals(ln)) {
                    String id = attr(c, "id", null);
                    if (id != null && id.length() > 0) n.style.put(id, num(c.getTextContent(), 0));
                } else if ("seq".equals(ln) || "seqAttr".equals(ln)) {
                    String id = attr(c, "id", null);
                    if (id == null || id.length() == 0) continue;
                    if (v4) {
                        List<Element> ccs = kids(c, "cc");
                        for (int k = 0; k < ccs.size(); k++) {
                            Element cc = ccs.get(k);
                            addPoint(n.ctrls, id, lng(cc, "p", 0), num(txt(cc, "v", "0"), 0));
                        }
                    } else {
                        List<Element> els = kids(c, "elem");
                        for (int k = 0; k < els.size(); k++) {
                            Element el = els.get(k);
                            addPoint(n.ctrls, "seqAttr:" + id, lng(el, "posNrm", 0), num(txt(el, "elv", "0"), 0));
                        }
                    }
                }
            }
        }
        return n;
    }

    private static void addPoint(java.util.LinkedHashMap<String, com.dsh.v4tune.model.ControlCurve> map, String id, long t, double v) {
        com.dsh.v4tune.model.ControlCurve c = map.get(id);
        if (c == null) {
            c = new com.dsh.v4tune.model.ControlCurve();
            map.put(id, c);
        }
        c.putRaw(t, v);
    }

    // ------------------------------------------------------------------ voices / master

    private static Voice readVoice(Element vv, boolean v4) {
        Voice v = new Voice();
        v.bs = in(vv, v4 ? "bs" : "vBS", 0);
        v.pc = in(vv, v4 ? "pc" : "vPC", 0);
        v.compID = txt(vv, v4 ? "id" : "compID", "");
        v.name = txt(vv, v4 ? "name" : "vVoiceName", "");
        Element prm = kid(vv, v4 ? "vPrm" : "vVoiceParam");
        if (prm != null) {
            v.bre = in(prm, "bre", 0);
            v.bri = in(prm, "bri", 0);
            v.cle = in(prm, "cle", 0);
            v.gen = in(prm, "gen", 0);
            v.ope = in(prm, "ope", 0);
        }
        v.lang = inferLang(v.compID, v.name);
        return v;
    }

    /** The language is not part of the file format; it is derived from the voicebank id/name. */
    public static int inferLang(String compID, String name) {
        String s = ((compID == null ? "" : compID) + " " + (name == null ? "" : name)).toUpperCase(Locale.US);
        if (s.indexOf("CHN") >= 0 || s.indexOf("CHINESE") >= 0 || s.indexOf("_ZH") >= 0) return 2;
        if (s.indexOf("ENG") >= 0 || s.indexOf("ENGLISH") >= 0 || s.indexOf("_EN") >= 0) return 1;
        return 0;
    }

    /** number of ticks of one measure of a nu/de signature at the given resolution */
    public static long measureTicks(int resolution, int nu, int de) {
        if (de <= 0) de = 4;
        if (nu <= 0) nu = 4;
        return Math.round((double) resolution * 4.0 * nu / de);
    }

    private static void readTimeSigs(Element mt, Song song, boolean v4, int resolution) {
        List<Element> ts = kids(mt, "timeSig");
        long tick = 0;
        int curM = 0;
        int prevNu = 4, prevDe = 4;
        boolean first = true;
        for (int i = 0; i < ts.size(); i++) {
            Element e = ts.get(i);
            int nu = in(e, v4 ? "nu" : "nume", 4);
            int de = in(e, v4 ? "de" : "denomi", 4);
            int m = in(e, v4 ? "m" : "posMes", 0);
            if (first) {
                tick = (long) m * measureTicks(resolution, 4, 4); // implicit 4/4 before the first event
                first = false;
            } else {
                tick += (long) (m - curM) * measureTicks(resolution, prevNu, prevDe);
            }
            curM = m;
            prevNu = nu;
            prevDe = de;
            song.timeSigs.add(new int[]{(int) clampI(tick), nu, de});
        }
    }

    private static void readTempos(Element mt, Song song, boolean v4) {
        List<Element> ts = kids(mt, "tempo");
        for (int i = 0; i < ts.size(); i++) {
            Element e = ts.get(i);
            song.tempos.add(new long[]{lng(e, v4 ? "t" : "posTick", 0), lng(e, v4 ? "v" : "bpm", 12000)});
        }
    }

    // ------------------------------------------------------------------ xml helpers

    private static boolean isVoiceField(String ln, boolean v4) {
        if (v4) return "bs".equals(ln) || "pc".equals(ln) || "id".equals(ln) || "name".equals(ln) || "vPrm".equals(ln);
        return "vBS".equals(ln) || "vPC".equals(ln) || "compID".equals(ln) || "vVoiceName".equals(ln) || "vVoiceParam".equals(ln);
    }

    private static boolean isPlugin(String ln) {
        return "plug".equals(ln) || "plugSR".equals(ln) || "vstPlugin".equals(ln) || "vstPluginSR".equals(ln);
    }

    private static boolean isKnownTop(String ln) {
        return "vender".equals(ln) || "version".equals(ln) || "vVoiceTable".equals(ln) || "mixer".equals(ln)
                || "masterTrack".equals(ln) || "vsTrack".equals(ln) || "monoTrack".equals(ln) || "stTrack".equals(ln)
                || "seTrack".equals(ln) || "karaokeTrack".equals(ln) || "aux".equals(ln);
    }

    private static int[] findUnit(ArrayList<int[]> units, int index, int no) {
        for (int i = 0; i < units.size(); i++) if (units.get(i)[0] == no) return units.get(i);
        if (index >= 0 && index < units.size()) return units.get(index);
        return null;
    }

    private static String findUnitPlug(ArrayList<int[]> units, ArrayList<String> plugs, int index, int no) {
        for (int i = 0; i < units.size(); i++) if (units.get(i)[0] == no && i < plugs.size()) return plugs.get(i);
        if (index >= 0 && index < plugs.size()) return plugs.get(index);
        return null;
    }

    private static String wrapOpen(String name, String attrName, Object attrValue) {
        return "<" + name + " " + attrName + "=\"" + attrValue + "\">";
    }

    static DocumentBuilder newBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setValidating(false);
        f.setExpandEntityReferences(false);
        feature(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
        feature(f, "http://xml.org/sax/features/external-general-entities", false);
        feature(f, "http://xml.org/sax/features/external-parameter-entities", false);
        feature(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        setAttr(f, javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setAttr(f, javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try {
            f.setXIncludeAware(false);
        } catch (UnsupportedOperationException ignored) {
        }
        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver(new org.xml.sax.EntityResolver() {
            public InputSource resolveEntity(String publicId, String systemId) {
                return new InputSource(new StringReader(""));
            }
        });
        return b;
    }

    private static void feature(DocumentBuilderFactory f, String name, boolean value) {
        try {
            f.setFeature(name, value);
        } catch (ParserConfigurationException ignored) {
        }
    }

    private static void setAttr(DocumentBuilderFactory f, String name, String value) {
        try {
            f.setAttribute(name, value);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** local name of a node, prefix stripped */
    public static String local(Node n) {
        String s = n.getLocalName();
        if (s == null) s = n.getNodeName();
        if (s == null) return "";
        int i = s.indexOf(':');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** all element children of a parent */
    static List<Element> kids(Element parent) {
        ArrayList<Element> out = new ArrayList<Element>();
        if (parent == null) return out;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) out.add((Element) n);
        }
        return out;
    }

    /** element children with the given local name */
    static List<Element> kids(Element parent, String name) {
        ArrayList<Element> out = new ArrayList<Element>();
        if (parent == null) return out;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(local(n))) out.add((Element) n);
        }
        return out;
    }

    /** first element child with the given local name, or null */
    static Element kid(Element parent, String name) {
        if (parent == null) return null;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(local(n))) return (Element) n;
        }
        return null;
    }

    static String attr(Element e, String name, String dflt) {
        if (e == null) return dflt;
        NamedNodeMap m = e.getAttributes();
        if (m == null) return dflt;
        for (int i = 0; i < m.getLength(); i++) {
            Node a = m.item(i);
            if (name.equals(local(a))) return a.getNodeValue();
        }
        return dflt;
    }

    static String txt(Element parent, String name, String dflt) {
        Element e = kid(parent, name);
        if (e == null) return dflt;
        String s = e.getTextContent();
        return s == null ? dflt : s;
    }

    /** first present element of the given names (schema aliases) */
    static int inAny(Element parent, int dflt, String... names) {
        for (int i = 0; i < names.length; i++) {
            Element e = kid(parent, names[i]);
            if (e != null) return (int) clampI(parseLong(e.getTextContent(), dflt));
        }
        return dflt;
    }

    static long lng(Element parent, String name, long dflt) {
        return parseLong(txt(parent, name, null), dflt);
    }

    static int in(Element parent, String name, int dflt) {
        long v = parseLong(txt(parent, name, null), dflt);
        return (int) clampI(v);
    }

    static long parseLong(String s, long dflt) {
        if (s == null) return dflt;
        s = s.trim();
        if (s.length() == 0) return dflt;
        try {
            int dot = s.indexOf('.');
            if (dot >= 0) s = s.substring(0, dot);
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    static double num(String s, double dflt) {
        if (s == null) return dflt;
        s = s.trim();
        if (s.length() == 0) return dflt;
        try {
            double d = Double.parseDouble(s);
            return Double.isNaN(d) ? dflt : d;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** clamps to the xsd:int range */
    public static long clampI(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return v;
    }

    // ------------------------------------------------------------------ serialisation

    static String escText(String s) {
        return esc(s, false);
    }

    static String escAttr(String s) {
        return esc(s, true);
    }

    private static String esc(String s, boolean attribute) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    b.append("&amp;");
                    break;
                case '<':
                    b.append("&lt;");
                    break;
                case '>':
                    b.append("&gt;");
                    break;
                case '"':
                    if (attribute) b.append("&quot;");
                    else b.append('"');
                    break;
                case '\'':
                    if (attribute) b.append("&apos;");
                    else b.append('\'');
                    break;
                case '\t':
                case '\n':
                case '\r':
                    b.append(attribute ? ' ' : c);
                    break;
                default:
                    if (c < 0x20) b.append(' ');
                    else b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * Serialises an element as a namespace free fragment (local names only). Used to keep
     * everything the model cannot hold; {@link VsqxWriter} re-emits these fragments inside
     * the document namespace.
     */
    static String serializeFragment(Element e) {
        StringBuilder sb = new StringBuilder(256);
        appendFragment(sb, e);
        return sb.toString();
    }

    private static void appendFragment(StringBuilder sb, Element e) {
        String name = local(e);
        sb.append('<').append(name);
        NamedNodeMap at = e.getAttributes();
        if (at != null) {
            for (int i = 0; i < at.getLength(); i++) {
                Node a = at.item(i);
                String an = a.getNodeName();
                if (an == null || an.startsWith("xmlns")) continue;
                String ln = local(a);
                if ("schemaLocation".equals(ln) || "noNamespaceSchemaLocation".equals(ln)) continue;
                String ns = a.getNamespaceURI();
                if (ns != null && "http://www.w3.org/2000/xmlns/".equals(ns)) continue;
                if (ns != null && javax.xml.XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(ns)) continue;
                sb.append(' ').append(ln).append("=\"").append(escAttr(a.getNodeValue())).append('"');
            }
        }
        NodeList nl = e.getChildNodes();
        boolean hasElement = false;
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getNodeType() == Node.ELEMENT_NODE) {
                hasElement = true;
                break;
            }
        }
        if (!hasElement) {
            String t = e.getTextContent();
            if (t == null || t.length() == 0) {
                sb.append("/>");
                return;
            }
            sb.append('>').append(escText(t)).append("</").append(name).append('>');
            return;
        }
        sb.append('>');
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) appendFragment(sb, (Element) n);
            else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(escText(n.getNodeValue()));
            }
        }
        sb.append("</").append(name).append('>');
    }
}
