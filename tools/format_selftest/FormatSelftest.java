import java.io.File;
import java.io.FilenameFilter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.dsh.v4tune.format.VsqxReader;
import com.dsh.v4tune.format.VsqxWriter;
import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;

/**
 * Desktop self test for the .vsqx layer.
 *
 * usage: FormatSelftest &lt;inDir&gt; &lt;outDir&gt;
 *
 * For every *.vsqx in inDir: parse it, print a summary, write it to outDir, parse it again and
 * compare both Songs field by field. Then a brand new Song is built, written and re-read.
 * Exit code is non zero when anything does not round trip.
 *
 * Documented normalisations that this test accepts (they cannot be stored in a vsqx file or are
 * forced by the XSD):
 *  - Voice.lang is derived from the voicebank id/name, the file has no language field.
 *  - Part/Note style values and control point values are ints in the schema (rounded on write).
 *  - vsq3 stores note/mCtrl/sCtrl positions part local; the model uses absolute ticks.
 *  - timeSig stores a measure index; the model uses absolute ticks.
 *  - A part without stylePlugin gets the editor's canonical "VOCALOID2 Compatible Style".
 *  - Strings are truncated to the XSD limits, numbers clamped to their XSD type,
 *    durations are written with at least 1 tick.
 */
public class FormatSelftest {

    private static final ArrayList<String> DIFFS = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: FormatSelftest <inDir> <outDir>");
            System.exit(2);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        if (!out.isDirectory() && !out.mkdirs()) {
            System.out.println("cannot create output directory " + out);
            System.exit(2);
        }
        File[] files = in.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".vsqx");
            }
        });
        if (files == null) {
            System.out.println("cannot list " + in);
            System.exit(2);
            return;
        }
        Arrays.sort(files);
        System.out.println("vsqx format self test - " + files.length + " file(s) from " + in.getPath());
        int total = 0;
        int pass = 0;
        ArrayList<String> failed = new ArrayList<String>();
        for (int i = 0; i < files.length; i++) {
            total++;
            File f = files[i];
            Song a = VsqxReader.readFile(f);
            System.out.println("[" + total + "] " + f.getName() + "  " + summary(a));
            File of = new File(out, f.getName());
            VsqxWriter.writeFile(a, of);
            Song b = VsqxReader.readFile(of);
            DIFFS.clear();
            compare(a, b, true);
            if (DIFFS.isEmpty()) {
                pass++;
                System.out.println("      round-trip OK  -> " + of.getPath());
            } else {
                failed.add(f.getName());
                System.out.println("      ROUND-TRIP FAILED, " + DIFFS.size() + " difference(s):");
                for (int k = 0; k < DIFFS.size() && k < 40; k++) System.out.println("        - " + DIFFS.get(k));
            }
        }

        // ---- brand new project -------------------------------------------------
        total++;
        Song n = newSong();
        System.out.println("[" + total + "] (new Song)  " + summary(n));
        File nf = new File(out, "__selftest_new_song.vsqx");
        VsqxWriter.writeFile(n, nf);
        Song nr = VsqxReader.readFile(nf);
        DIFFS.clear();
        compare(n, nr, false);
        if (DIFFS.isEmpty()) {
            pass++;
            System.out.println("      round-trip OK  -> " + nf.getPath());
        } else {
            failed.add("(new Song)");
            System.out.println("      ROUND-TRIP FAILED, " + DIFFS.size() + " difference(s):");
            for (int k = 0; k < DIFFS.size() && k < 40; k++) System.out.println("        - " + DIFFS.get(k));
        }

        System.out.println();
        System.out.println("normalisations accepted: voice language is derived (not stored), style/ctrl"
                + " values are integers, vsq3 positions are part local, timeSig is a measure index,"
                + " missing stylePlugin gets the canonical editor GUID, strings/numbers are clamped"
                + " to the XSD limits.");
        if (failed.isEmpty()) {
            System.out.println("PASS " + pass + "/" + total);
        } else {
            System.out.println("FAIL " + pass + "/" + total + "  failed: " + failed);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ new song

    private static Song newSong() {
        Song s = Song.empty();
        s.vendor = "Yamaha corporation";
        s.version = "4.0.0.3";
        s.masterName = "自检 工程";
        s.masterVol = -3;
        s.masterRetLevel = 0;
        s.outDev = 0;
        s.voices.get(0).name = "Luotianyi_CHN_Meng";
        s.voices.get(0).compID = "BK8H76TAEHXWSKDB";
        s.voices.get(0).lang = 2;
        s.voices.get(0).bri = -8;
        s.voices.get(0).ope = 12;

        Track t = s.tracks.get(0);
        t.name = "人声";
        t.comment = "自检轨道";
        t.vol = 12;
        t.pan = -20;
        t.inGain = 3;
        t.sendLevel = -100;
        t.sendEnable = true;
        t.solo = true;

        Part p = t.parts.get(0);
        p.name = "第一部分";
        p.comment = "中文歌词";
        p.pos = 1920;
        p.dur = 7680;
        p.bs = 0;
        p.pc = 0;
        p.style.put("accent", 50.0);
        p.style.put("bendDep", 8.0);
        p.style.put("decay", 50.0);
        p.style.put("opening", 127.0);

        ControlCurve dyn = p.ctrl("DYN");
        dyn.putRaw(1920, 64);
        dyn.putRaw(2400, 90);
        dyn.putRaw(3840, 40);
        dyn.putRaw(9600, 64);
        ControlCurve bre = p.ctrl("BRE");
        bre.putRaw(1920, 0);
        bre.putRaw(4800, 30);
        bre.putRaw(9600, 0);
        ControlCurve pit = p.ctrl("PIT");
        pit.putRaw(1920, 0);
        pit.putRaw(2160, -300);
        pit.putRaw(2640, 200);
        pit.putRaw(9600, 0);

        String[] lyrics = new String[]{"你", "好", "世", "界"};
        String[] phn = new String[]{"n i", "x au", "s^ i", "tɕ ie"};
        int[] keys = new int[]{62, 64, 65, 67};
        for (int i = 0; i < 4; i++) {
            Note note = new Note();
            note.pos = 1920 + i * 960L;
            note.dur = 900;
            note.key = keys[i];
            note.vel = 64 + i;
            note.lyric = lyrics[i];
            note.phoneme = phn[i];
            note.lockPhoneme = (i == 1);
            note.style.put("accent", 50.0);
            note.style.put("bendDep", 8.0);
            note.style.put("vibLen", 66.0);
            note.style.put("vibType", 1.0);
            if (i >= 2) {
                ControlCurve dep = ctrl(note.ctrls, "vibDep");
                dep.putRaw(note.pos + 400, 80);
                dep.putRaw(note.pos + 600, 64);
                ControlCurve rate = ctrl(note.ctrls, "vibRate");
                rate.putRaw(note.pos + 400, 58);
            }
            p.notes.add(note);
        }
        return s;
    }

    private static ControlCurve ctrl(LinkedHashMap<String, ControlCurve> m, String id) {
        ControlCurve c = m.get(id);
        if (c == null) {
            c = new ControlCurve();
            m.put(id, c);
        }
        return c;
    }

    // ------------------------------------------------------------------ comparison

    private static String summary(Song s) {
        int parts = 0, notes = 0, curves = 0;
        for (int i = 0; i < s.tracks.size(); i++) {
            Track t = s.tracks.get(i);
            parts += t.parts.size();
            for (int k = 0; k < t.parts.size(); k++) {
                Part p = t.parts.get(k);
                notes += p.notes.size();
                curves += p.ctrls.size();
                for (int j = 0; j < p.notes.size(); j++) curves += p.notes.get(j).ctrls.size();
            }
        }
        return "schema=vsq" + s.schema + " tracks=" + s.tracks.size() + " parts=" + parts
                + " notes=" + notes + " tempos=" + s.tempos.size() + " timeSigs=" + s.timeSigs.size()
                + " ctrlCurves=" + curves;
    }

    private static void compare(Song a, Song b, boolean aux) {
        eq("schema", a.schema, b.schema);
        eq("vendor", a.vendor, b.vendor);
        eq("version", a.version, b.version);
        eq("masterName", a.masterName, b.masterName);
        eq("masterVol", a.masterVol, b.masterVol);
        eq("masterRetLevel", a.masterRetLevel, b.masterRetLevel);
        eq("outDev", a.outDev, b.outDev);

        eq("voice count", a.voices.size(), b.voices.size());
        int nv = Math.min(a.voices.size(), b.voices.size());
        for (int i = 0; i < nv; i++) {
            Voice x = a.voices.get(i), y = b.voices.get(i);
            String p = "voice[" + i + "]";
            eq(p + ".bs", x.bs, y.bs);
            eq(p + ".pc", x.pc, y.pc);
            eq(p + ".compID", x.compID, y.compID);
            eq(p + ".name", x.name, y.name);
            eq(p + ".bre", x.bre, y.bre);
            eq(p + ".bri", x.bri, y.bri);
            eq(p + ".cle", x.cle, y.cle);
            eq(p + ".gen", x.gen, y.gen);
            eq(p + ".ope", x.ope, y.ope);
            // x.lang / y.lang: derived from the name, never stored -> not compared
        }

        eq("tempo count", a.tempos.size(), b.tempos.size());
        for (int i = 0; i < Math.min(a.tempos.size(), b.tempos.size()); i++) {
            eq("tempo[" + i + "].tick", a.tempos.get(i)[0], b.tempos.get(i)[0]);
            eq("tempo[" + i + "].value", a.tempos.get(i)[1], b.tempos.get(i)[1]);
        }
        eq("timeSig count", a.timeSigs.size(), b.timeSigs.size());
        for (int i = 0; i < Math.min(a.timeSigs.size(), b.timeSigs.size()); i++) {
            int[] x = a.timeSigs.get(i), y = b.timeSigs.get(i);
            eq("timeSig[" + i + "].tick", x[0], y[0]);
            eq("timeSig[" + i + "].nu", x[1], y[1]);
            eq("timeSig[" + i + "].de", x[2], y[2]);
        }

        eq("track count", a.tracks.size(), b.tracks.size());
        for (int i = 0; i < Math.min(a.tracks.size(), b.tracks.size()); i++) {
            Track x = a.tracks.get(i), y = b.tracks.get(i);
            String p = "track[" + i + "]";
            eq(p + ".no", x.no, y.no);
            eq(p + ".name", x.name, y.name);
            eq(p + ".comment", x.comment, y.comment);
            eq(p + ".vol", x.vol, y.vol);
            eq(p + ".pan", x.pan, y.pan);
            eq(p + ".inGain", x.inGain, y.inGain);
            eq(p + ".sendLevel", x.sendLevel, y.sendLevel);
            eq(p + ".mute", x.mute, y.mute);
            eq(p + ".solo", x.solo, y.solo);
            eq(p + ".sendEnable", x.sendEnable, y.sendEnable);
            eq(p + ".part count", x.parts.size(), y.parts.size());
            for (int k = 0; k < Math.min(x.parts.size(), y.parts.size()); k++) {
                Part pa = x.parts.get(k), pb = y.parts.get(k);
                String pp = p + ".part[" + k + "]";
                eq(pp + ".pos", pa.pos, pb.pos);
                eq(pp + ".dur", pa.dur, pb.dur);
                eq(pp + ".name", pa.name, pb.name);
                eq(pp + ".comment", pa.comment, pb.comment);
                eq(pp + ".bs", pa.bs, pb.bs);
                eq(pp + ".pc", pa.pc, pb.pc);
                cmpStyle(pp + ".style", pa.style, pb.style);
                cmpCtrls(pp + ".ctrls", pa.ctrls, pb.ctrls);
                eq(pp + ".note count", pa.notes.size(), pb.notes.size());
                for (int j = 0; j < Math.min(pa.notes.size(), pb.notes.size()); j++) {
                    Note na = pa.notes.get(j), nb = pb.notes.get(j);
                    String np = pp + ".note[" + j + "]";
                    eq(np + ".pos", na.pos, nb.pos);
                    eq(np + ".dur", na.dur, nb.dur);
                    eq(np + ".key", na.key, nb.key);
                    eq(np + ".vel", na.vel, nb.vel);
                    eq(np + ".lyric", na.lyric, nb.lyric);
                    eq(np + ".phoneme", na.phoneme, nb.phoneme);
                    eq(np + ".lock", na.lockPhoneme, nb.lockPhoneme);
                    cmpStyle(np + ".style", na.style, nb.style);
                    cmpCtrls(np + ".ctrls", na.ctrls, nb.ctrls);
                }
            }
        }
        if (aux) {
            String ca = canonAux(a.auxXml), cb = canonAux(b.auxXml);
            if (!ca.equals(cb)) {
                eq("auxXml", shorten(ca), shorten(cb));
            }
        }
    }

    private static void cmpStyle(String path, LinkedHashMap<String, Double> a, LinkedHashMap<String, Double> b) {
        if (!a.keySet().equals(b.keySet())) {
            DIFFS.add(path + " keys " + a.keySet() + " != " + b.keySet());
            return;
        }
        for (String k : a.keySet()) {
            long x = Math.round(a.get(k).doubleValue()), y = Math.round(b.get(k).doubleValue());
            if (x != y) DIFFS.add(path + "[" + k + "] " + a.get(k) + " != " + b.get(k));
        }
    }

    private static void cmpCtrls(String path, LinkedHashMap<String, ControlCurve> a, LinkedHashMap<String, ControlCurve> b) {
        if (!a.keySet().equals(b.keySet())) {
            DIFFS.add(path + " keys " + a.keySet() + " != " + b.keySet());
            return;
        }
        for (String k : a.keySet()) {
            ControlCurve x = a.get(k), y = b.get(k);
            if (x.pts.size() != y.pts.size()) {
                DIFFS.add(path + "[" + k + "] point count " + x.pts.size() + " != " + y.pts.size());
                continue;
            }
            for (int i = 0; i < x.pts.size(); i++) {
                if (x.pts.get(i).t != y.pts.get(i).t || Math.round(x.pts.get(i).v) != Math.round(y.pts.get(i).v)) {
                    DIFFS.add(path + "[" + k + "][" + i + "] (" + x.pts.get(i).t + "," + x.pts.get(i).v + ") != ("
                            + y.pts.get(i).t + "," + y.pts.get(i).v + ")");
                    break;
                }
            }
        }
    }

    private static void eq(String path, Object a, Object b) {
        boolean same = a == null ? b == null : a.equals(b);
        if (!same) DIFFS.add(path + ": " + a + " != " + b);
    }

    private static String shorten(String s) {
        if (s == null) return "null";
        return s.length() <= 400 ? s : s.substring(0, 400) + "...(" + s.length() + " chars)";
    }

    /** Canonical form of the preserved XML: element order kept, attributes sorted. */
    private static String canonAux(String xml) {
        if (xml == null || xml.trim().length() == 0) return "";
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver(new org.xml.sax.EntityResolver() {
                public InputSource resolveEntity(String publicId, String systemId) {
                    return new InputSource(new StringReader(""));
                }
            });
            Document d = b.parse(new InputSource(new StringReader("<dshRoot>" + xml + "</dshRoot>")));
            StringBuilder sb = new StringBuilder();
            NodeList nl = d.getDocumentElement().getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                if (nl.item(i).getNodeType() == Node.ELEMENT_NODE) canon(sb, (Element) nl.item(i));
            }
            return sb.toString();
        } catch (Exception e) {
            return xml;
        }
    }

    private static void canon(StringBuilder sb, Element e) {
        sb.append('<').append(e.getNodeName());
        NamedNodeMap at = e.getAttributes();
        String[] names = new String[at == null ? 0 : at.getLength()];
        for (int i = 0; i < names.length; i++) names[i] = at.item(i).getNodeName();
        Arrays.sort(names);
        for (int i = 0; i < names.length; i++) {
            if (names[i].startsWith("xmlns")) continue;
            sb.append(' ').append(names[i]).append("=\"").append(e.getAttribute(names[i])).append('"');
        }
        sb.append('>');
        NodeList nl = e.getChildNodes();
        boolean any = false;
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                canon(sb, (Element) n);
                any = true;
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                String t = n.getNodeValue();
                if (t != null && t.trim().length() > 0) {
                    sb.append(t.trim());
                    any = true;
                }
            }
        }
        sb.append("</").append(e.getNodeName()).append('>');
        if (!any) sb.append(';');
    }
}
