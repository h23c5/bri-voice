package com.dsh.v4tune.voicebank;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Stage C-4: assemble the pack exactly as the PC converter lays it out.
 *
 * <p>The contract was read from a real pack (build/packs_v64/luotianyi_chn_meng_bank.v4voice)
 * and is written down in docs/voicebank-port-spec.md section 6: a ZIP holding voice.json and
 * LICENSE.txt, with the top level keys format, id, name, nameEn, nameJa, lang, compID, source,
 * voice, shelf, formants, harmonics, stats, notes.
 *
 * <p>JSON is written by hand so the key order is stable and the class runs on the desktop JVM
 * (org.json does neither). Cluster labels may differ from the PC build - see the class comment in
 * VoicebankCluster - but the shape, the array lengths and the value ranges must not.
 */
public final class VoicebankWriter {

    public String format = "v4voice/1";
    public String id = "";
    public String name = "";
    public String nameEn = "";
    public String nameJa = "";
    public int lang = 2;
    public String compID = "";
    public String notes = "";

    /** free-form provenance, e.g. "VOCALOID4 FRM2 frames of <bank> (<compID>)" */
    public String source = "";
    /** voice section: formantScale, f1Mul, f2Mul, f3Mul, tiltBias, breathBias, ... */
    public final LinkedHashMap<String, Double> voice = new LinkedHashMap<String, Double>();
    /** shelf: 7 pairs {freq, dB} */
    public final ArrayList<double[]> shelf = new ArrayList<double[]>();
    /** formants: symbol -> {F1,F2,F3} */
    public final LinkedHashMap<String, int[]> formants = new LinkedHashMap<String, int[]>();
    /** formants: symbol_n -> how many measured frames backed it */
    public final LinkedHashMap<String, Integer> formantCounts = new LinkedHashMap<String, Integer>();
    /** stats: frames, f0Median, f0Min, f0Max, clusters, matchedPhonemes, ... */
    public final LinkedHashMap<String, Object> stats = new LinkedHashMap<String, Object>();
    /** harmonics: 64 point grid, written verbatim */
    public double[] harmFreqs = new double[0];
    /** harmonics.phonemes: symbol -> bands */
    public final LinkedHashMap<String, ArrayList<VoicebankPack.Band>> phonemes =
            new LinkedHashMap<String, ArrayList<VoicebankPack.Band>>();
    /** harmonics.noise: symbol -> {freqs, db} */
    public final LinkedHashMap<String, double[][]> noise = new LinkedHashMap<String, double[][]>();

    // ------------------------------------------------------------------ json

    public String toJson() {
        StringBuilder b = new StringBuilder(1 << 20);
        b.append('{');
        str(b, "format", format); b.append(',');
        str(b, "id", id); b.append(',');
        str(b, "name", name); b.append(',');
        str(b, "nameEn", nameEn); b.append(',');
        str(b, "nameJa", nameJa); b.append(',');
        num(b, "lang", lang); b.append(',');
        str(b, "compID", compID); b.append(',');
        str(b, "source", source); b.append(',');
        b.append("\"voice\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : voice.entrySet()) {
            if (!first) b.append(',');
            first = false;
            quote(b, e.getKey()); b.append(':').append(fmt(e.getValue().doubleValue()));
        }
        b.append("},\"shelf\":[");
        for (int i = 0; i < shelf.size(); i++) {
            if (i > 0) b.append(',');
            double[] p = shelf.get(i);
            b.append('[').append(fmt(p[0])).append(',').append(fmt(p[1])).append(']');
        }
        b.append("],\"formants\":{");
        first = true;
        for (Map.Entry<String, int[]> e : formants.entrySet()) {
            if (!first) b.append(',');
            first = false;
            quote(b, e.getKey()); b.append(":[");
            int[] v = e.getValue();
            for (int i = 0; i < v.length; i++) { if (i > 0) b.append(','); b.append(v[i]); }
            b.append(']');
        }
        for (Map.Entry<String, Integer> e : formantCounts.entrySet()) {
            if (!first) b.append(',');
            first = false;
            quote(b, e.getKey()); b.append(":[").append(e.getValue().intValue()).append(']');
        }
        b.append("},\"harmonics\":{\"freqs\":[");
        // the PC converter writes the grid rounded to four decimals
        for (int i = 0; i < harmFreqs.length; i++) { if (i > 0) b.append(','); b.append(fmt(round4(harmFreqs[i]))); }
        b.append("],\"source\":").append(js(source)).append(",\"phonemes\":{");
        first = true;
        for (Map.Entry<String, ArrayList<VoicebankPack.Band>> e : phonemes.entrySet()) {
            if (!first) b.append(',');
            first = false;
            quote(b, e.getKey()); b.append(":[");
            List<VoicebankPack.Band> bands = e.getValue();
            for (int i = 0; i < bands.size(); i++) {
                if (i > 0) b.append(',');
                band(b, bands.get(i));
            }
            b.append(']');
        }
        b.append('}');
        if (!noise.isEmpty()) {
            b.append(",\"noise\":{");
            first = true;
            for (Map.Entry<String, double[][]> e : noise.entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, e.getKey()); b.append(":{\"freqs\":[");
                double[] f = e.getValue()[0];
                for (int i = 0; i < f.length; i++) { if (i > 0) b.append(','); b.append(fmt(f[i])); }
                b.append("],\"db\":[");
                double[] d = e.getValue()[1];
                for (int i = 0; i < d.length; i++) { if (i > 0) b.append(','); b.append(fmt(d[i])); }
                b.append("]}");
            }
            b.append('}');
        }
        b.append("},\"stats\":{");
        first = true;
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            if (!first) b.append(',');
            first = false;
            quote(b, e.getKey()); b.append(':');
            Object v = e.getValue();
            if (v instanceof String) b.append(js((String) v));
            else if (v instanceof Integer) b.append(((Integer) v).intValue());
            else if (v instanceof Double) b.append(fmt(((Double) v).doubleValue()));
            else b.append(js(String.valueOf(v)));
        }
        b.append('}');
        b.append(",\"notes\":").append(js(notes));
        b.append('}');
        return b.toString();
    }

    private static void band(StringBuilder b, VoicebankPack.Band x) {
        b.append('{');
        if (x.db != null) {
            b.append("\"f0\":").append(fmt(x.f0)).append(",\"db\":[");
            for (int i = 0; i < x.db.length; i++) { if (i > 0) b.append(','); b.append(fmt(x.db[i])); }
            b.append(']');
        } else {
            b.append("\"f0\":").append(fmt(x.f0));
        }
        if (x.dyn != null) b.append(",\"dyn\":").append(fmt(x.dyn.doubleValue()));
        if (x.seqB64 != null) {
            b.append(",\"seqB64\":").append(js(x.seqB64));
            b.append(",\"seqN\":").append(x.seqN);
            b.append(",\"seqQ\":").append(fmt(x.seqQ));
            if (x.lvl != null) {
                b.append(",\"lvl\":[");
                for (int i = 0; i < x.lvl.length; i++) { if (i > 0) b.append(','); b.append(fmt(x.lvl[i])); }
                b.append(']');
            }
        }
        b.append('}');
    }

    // ------------------------------------------------------------------ zip

    public static final String DEFAULT_LICENSE =
            "This pack was generated by the BriVoice in-app converter from a voicebank the user"
          + " owns. It contains measured spectral statistics only - no audio samples. Do not"
          + " redistribute it; it is not an official voicebank release and is not affiliated"
          + " with Yamaha or any voicebank vendor.\n";

    /** Writes the .v4voice archive: voice.json plus LICENSE.txt, deterministically. */
    public void writeZip(File out, String license) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out));
        try {
            z.setLevel(6);
            z.putNextEntry(new ZipEntry("voice.json"));
            Writer w = new BufferedWriter(new OutputStreamWriter(z, "UTF-8"));
            w.write(toJson());
            w.flush();
            z.closeEntry();
            z.putNextEntry(new ZipEntry("LICENSE.txt"));
            w.write(license == null ? DEFAULT_LICENSE : license);
            w.flush();
            z.closeEntry();
        } finally {
            z.close();
        }
    }

    // ------------------------------------------------------------------ json helpers

    static void quote(StringBuilder b, String s) { b.append(js(s)); }

    static void str(StringBuilder b, String key, String v) {
        quote(b, key); b.append(':').append(js(v));
    }

    static void num(StringBuilder b, String key, int v) {
        quote(b, key); b.append(':').append(v);
    }

    static String js(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** Python-like float text: 0.5 -> "0.5", 329.0 -> "329.0", 1.037 -> "1.037". */
    /** round(v, 4) like the Python side */
    public static double round4(double v) { return Math.rint(v * 10000.0) / 10000.0; }

    static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0.0";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v) + ".0";
        String s = String.valueOf(v);
        if (s.indexOf('E') >= 0 || s.indexOf('e') >= 0) {
            java.math.BigDecimal d = new java.math.BigDecimal(v).round(new java.math.MathContext(12));
            s = d.stripTrailingZeros().toPlainString();
        }
        return s;
    }
}
