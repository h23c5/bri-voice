package com.dsh.v4tune.voicebank;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharsetDecoder;
import java.nio.file.Files;
import java.util.*;

/**
 * Minimal UTAU voicebank -> .v4voice converter.
 * Reads oto.ini + wav samples, measures F0 / formants / 64-point harmonic envelope
 * for the five vowels, and builds a parametric pack with VoicebankWriter.
 * Consonants are not measured (they fall back to the engine's defaults).
 */
public final class UtauBankBuilder {

    private static final String[] VOWELS = {"a","i","u","e","o"};

    private UtauBankBuilder() { }

    public static VoicebankWriter build(File bankDir, String name) throws Exception {
        File oto = new File(bankDir, "oto.ini");
        if (!oto.isFile()) throw new FileNotFoundException("oto.ini not found in " + bankDir);
        List<Entry> entries = parseOto(oto);
        Map<String, File> fileIndex = new HashMap<String, File>();
        indexFiles(bankDir, bankDir, fileIndex, 0);
        int missingFiles = 0;
        Map<String, List<double[]>> formantSamples = new HashMap<String, List<double[]>>();
        Map<String, List<BandData>> bandSamples = new HashMap<String, List<BandData>>();
        Map<String, List<double[]>> noiseSamples = new HashMap<String, List<double[]>>();
        int frames = 0;
        for (Entry e : entries) {
            String sym = vowelOf(e.alias);
            if (sym == null) continue;
            File wav = resolveWav(bankDir, fileIndex, e);
            if (wav == null) { missingFiles++; continue; }
            float[] x; int sr;
            try { Object[] r = readWav(wav); x = (float[]) r[0]; sr = (Integer) r[1]; }
            catch (Throwable t) { continue; }
            int start = (int) Math.max(0, e.offset * sr / 1000.0);
            int usable = x.length - start;
            if (usable < 1024) { start = 0; usable = x.length; }
            if (usable < 1024) continue;
            int win = Math.min(usable, (int)(0.15 * sr));
            double[] fracs = {0.15, 0.45, 0.75};
            java.util.ArrayList<double[]> locF = new java.util.ArrayList<double[]>();
            java.util.ArrayList<double[]> locB = new java.util.ArrayList<double[]>();
            java.util.ArrayList<Double> locF0 = new java.util.ArrayList<Double>();
            for (double frac : fracs) {
                int s2 = start + (int)((usable - win) * frac);
                if (s2 < 0) s2 = 0;
                double[] seg = new double[win];
                for (int i = 0; i < win; i++) seg[i] = x[s2 + i];
                double f0 = f0(seg, sr);
                if (f0 < 60 || f0 > 1200) continue;
                double[] spec = magnitude(seg, sr);
                int bins = spec.length;
                double[] freqs = new double[bins];
                for (int i = 0; i < bins; i++) freqs[i] = i * (sr / 2.0) / (bins - 1);
                locF.add(formants(freqs, spec, sym));
                locB.add(bands(freqs, spec));
                locF0.add(Double.valueOf(f0));
            }
            if (!locF0.isEmpty()) {
                java.util.ArrayList<Double> sortedF0 = new java.util.ArrayList<Double>(locF0);
                java.util.Collections.sort(sortedF0);
                double medF0 = sortedF0.get(sortedF0.size() / 2).doubleValue();
                boolean any = false;
                for (int wi = 0; wi < locF0.size(); wi++) {
                    double f0 = locF0.get(wi).doubleValue();
                    if (f0 < medF0 * 0.67 || f0 > medF0 * 1.5) continue;
                    formantSamples.computeIfAbsent(sym, k -> new ArrayList<double[]>()).add(locF.get(wi));
                    bandSamples.computeIfAbsent(sym, k -> new ArrayList<BandData>()).add(new BandData(f0, locB.get(wi)));
                    any = true;
                }
                if (!any) {
                    int best = 0; double bd = Double.MAX_VALUE;
                    for (int wi = 0; wi < locF0.size(); wi++) {
                        double d = Math.abs(locF0.get(wi).doubleValue() - medF0);
                        if (d < bd) { bd = d; best = wi; }
                    }
                    formantSamples.computeIfAbsent(sym, k -> new ArrayList<double[]>()).add(locF.get(best));
                    bandSamples.computeIfAbsent(sym, k -> new ArrayList<BandData>()).add(new BandData(locF0.get(best).doubleValue(), locB.get(best)));
                }
            }
            String plos = plosiveOf(e.alias);
            if (plos != null) {
                int frame = Math.max(64, (int)(0.015 * sr));
                int search = Math.min(x.length - start, (int)(0.08 * sr));
                int bestOff = -1; double bestRms = 0;
                for (int off = 0; off + frame <= search; off += frame / 2) {
                    double rms = 0;
                    for (int i = 0; i < frame; i++) rms += (double)x[start + off + i] * x[start + off + i];
                    rms = Math.sqrt(rms / frame);
                    if (rms > bestRms) { bestRms = rms; bestOff = off; }
                }
                if (bestOff >= 0 && bestRms > 0.002) {
                    double[] ps = new double[frame];
                    for (int i = 0; i < frame; i++) ps[i] = x[start + bestOff + i];
                    double[] pspec = magnitude(ps, sr);
                    double[] pf = new double[pspec.length];
                    for (int i = 0; i < pf.length; i++) pf[i] = i * (sr / 2.0) / (pf.length - 1);
                    noiseSamples.computeIfAbsent(plos, k -> new ArrayList<double[]>()).add(bands(pf, pspec));
                }
            }
            String cons = consonantOf(e.alias);
            if (cons != null) {
                int nn = Math.min(x.length - start, (int)(0.06 * sr));
                if (nn > 256) {
                    double[] cs = new double[nn];
                    for (int i = 0; i < nn; i++) cs[i] = x[start + i];
                    double[] cspec = magnitude(cs, sr);
                    double[] cf = new double[cspec.length];
                    for (int i = 0; i < cf.length; i++) cf[i] = i * (sr / 2.0) / (cf.length - 1);
                    noiseSamples.computeIfAbsent(cons, k -> new ArrayList<double[]>()).add(bands(cf, cspec));
                }
            }
            frames++;
        }
        for (String sym : new ArrayList<String>(bandSamples.keySet())) {
            List<BandData> bl = bandSamples.get(sym);
            List<double[]> fl = formantSamples.get(sym);
            if (bl == null || fl == null || bl.size() != fl.size()) continue;
            java.util.ArrayList<Double> fs = new java.util.ArrayList<Double>();
            for (BandData bd : bl) if (bd.f0 > 0) fs.add(Double.valueOf(bd.f0));
            if (fs.size() < 3) continue;
            java.util.Collections.sort(fs);
            double med = fs.get(fs.size() / 2).doubleValue();
            List<BandData> keepB = new ArrayList<BandData>();
            List<double[]> keepF = new ArrayList<double[]>();
            for (int i = 0; i < bl.size(); i++) {
                double f0 = bl.get(i).f0;
                if (f0 >= med * 0.5 && f0 <= med * 2.0) { keepB.add(bl.get(i)); keepF.add(fl.get(i)); }
            }
            if (keepB.isEmpty()) continue;
            bandSamples.put(sym, keepB);
            formantSamples.put(sym, keepF);
        }
        if (formantSamples.isEmpty()) {
            StringBuilder diag = new StringBuilder();
            diag.append("找不到可用的人声采样（oto.ini 条目 ").append(entries.size())
                .append(" 个，缺失/无法解析的 wav ").append(missingFiles).append(" 个");
            if (!entries.isEmpty()) diag.append("，例如：").append(entries.get(0).file);
            diag.append("）");
            throw new IllegalStateException(diag.toString());
        }

        String charName = null;
        try {
            File ch = new File(bankDir, "character.txt");
            if (ch.isFile()) {
                String txt = new String(Files.readAllBytes(ch.toPath()), Charset.forName("Shift_JIS"));
                for (String ln : txt.split("\r?\n")) {
                    int eq = ln.indexOf('=');
                    if (eq > 0 && "name".equalsIgnoreCase(ln.substring(0, eq).trim())) { charName = ln.substring(eq + 1).trim(); break; }
                }
            }
        } catch (Throwable ignored) { }
        VoicebankWriter w = new VoicebankWriter();
        w.id = "utau-" + Integer.toHexString((bankDir.getName()+System.currentTimeMillis()).hashCode());
        w.name = charName != null && charName.length() > 0 ? charName : (name == null || name.length()==0 ? bankDir.getName() : name);
        w.nameEn = w.name; w.nameJa = w.name; w.lang = 0; w.compID = "utau";
        w.source = "UTAU bank " + bankDir.getAbsolutePath() + " measured in-app";
        w.voice.put("formantScale", 1.0);
        w.voice.put("f1Mul", 1.0); w.voice.put("f2Mul", 1.0); w.voice.put("f3Mul", 1.0);
        w.voice.put("tiltBias", 0.0); w.voice.put("breathBias", 0.10);
        w.voice.put("oqBias", 0.0); w.voice.put("vibratoRate", 5.4);
        double[][] shelf = {{60,0},{152,0},{386,0},{980,0},{2486,0},{6307,0},{16000,0}};
        for (double[] p : shelf) w.shelf.add(p);
        double[] grid = VoicebankPack.harmonicGrid(VoicebankPack.HARM_POINTS);
        w.harmFreqs = grid;
        int totalBands = 0;
        for (String sym : VOWELS) {
            List<double[]> fs = formantSamples.get(sym);
            List<BandData> bs = bandSamples.get(sym);
            if (fs == null || fs.isEmpty() || bs == null) continue;
            double[] mean = new double[3];
            for (double[] f : fs) for (int i=0;i<3;i++) mean[i] += f[i];
            for (int i=0;i<3;i++) mean[i] /= fs.size();
            int[] fi = new int[]{(int)Math.round(mean[0]),(int)Math.round(mean[1]),(int)Math.round(mean[2])};
            w.formants.put(sym, fi);
            w.formantCounts.put(sym + "_n", fs.size());
            ArrayList<VoicebankPack.Band> bands = new ArrayList<VoicebankPack.Band>();
            for (BandData b : bs) {
                VoicebankPack.Band band = new VoicebankPack.Band();
                band.f0 = Math.round(b.f0*10)/10.0;
                band.hasF0 = true;
                band.db = b.db;
                band.dyn = 0.5;
                bands.add(band);
                totalBands++;
            }
            w.phonemes.put(sym, bands);
        }
        for (Map.Entry<String, List<double[]>> ne : noiseSamples.entrySet()) {
            List<double[]> curves = ne.getValue();
            if (curves.isEmpty()) continue;
            double[] mean = new double[grid.length];
            for (double[] c : curves) for (int i = 0; i < mean.length; i++) mean[i] += Math.pow(10.0, c[i] / 20.0);
            for (int i = 0; i < mean.length; i++) mean[i] /= curves.size();
            double mx = -Double.MAX_VALUE;
            for (double v : mean) if (v > mx) mx = v;
            double[] db = new double[mean.length];
            for (int i = 0; i < mean.length; i++) {
                double v = Math.rint((20.0 * Math.log10(Math.max(mean[i], 1e-9)) - 20.0 * Math.log10(Math.max(mx, 1e-9))) / VoicebankPack.HARM_DB_STEP) * VoicebankPack.HARM_DB_STEP;
                if (v < VoicebankPack.HARM_DB_FLOOR) v = VoicebankPack.HARM_DB_FLOOR;
                if (v > 0.0) v = 0.0;
                db[i] = v;
            }
            w.noise.put(ne.getKey(), new double[][]{grid, db});
        }
        w.stats.put("noiseNamed", w.noise.size());
        double fmin = 1e9, fmax = 0; java.util.ArrayList<Double> f0s = new java.util.ArrayList<Double>();
        for (List<BandData> bl : bandSamples.values()) for (BandData bd : bl) { if (bd.f0 > 0) { f0s.add(bd.f0); if (bd.f0 < fmin) fmin = bd.f0; if (bd.f0 > fmax) fmax = bd.f0; } }
        java.util.Collections.sort(f0s);
        w.name = w.name + " · " + totalBands + "段";
        w.nameEn = w.name; w.nameJa = w.name;
        w.stats.put("f0Min", f0s.isEmpty() ? 0.0 : Math.round(fmin * 10) / 10.0);
        w.stats.put("f0Max", f0s.isEmpty() ? 0.0 : Math.round(fmax * 10) / 10.0);
        w.stats.put("f0Median", f0s.isEmpty() ? 0.0 : Math.round(f0s.get(f0s.size() / 2) * 10) / 10.0);
        w.stats.put("windows", f0s.size());
        w.stats.put("frames", frames);
        w.stats.put("clusters", w.phonemes.size());
        w.stats.put("matchedPhonemes", w.phonemes.size());
        w.stats.put("bands", totalBands);
        w.notes = "Converted from UTAU bank; vowels measured, consonants fall back to defaults.";
        return w;
    }

    /** index every sample recursively, by both relative path and bare filename */
    private static void indexFiles(File root, File dir, Map<String, File> out, int depth) {
        if (dir == null || depth > 5) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isFile()) {
                out.put(normName(k.getName()), k);
                try {
                    String rel = root.toURI().relativize(k.toURI()).getPath();
                    out.put(normName(rel), k);
                } catch (Throwable ignored) { }
            } else if (k.isDirectory()) {
                indexFiles(root, k, out, depth + 1);
            }
        }
    }

    /** normalize a file name for matching: Unicode form + case + separators */
    private static String normName(String n) {
        if (n == null) return "";
        try { n = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFKC); } catch (Throwable ignored) { }
        return n.trim().replace('\u3000', ' ').toLowerCase(java.util.Locale.US).replace('\\', '/');
    }

    private static File resolveWav(File bankDir, Map<String, File> index, Entry e) {
        String[] names = {e.file, e.fileAlt, e.fileUtf8};
        for (String n : names) {
            if (n == null || n.length() == 0) continue;
            File f = new File(bankDir, n);
            if (f.isFile()) return f;
            File hit = index.get(normName(n));
            if (hit != null) return hit;
            int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
            if (slash >= 0) {
                File h2 = index.get(normName(n.substring(slash + 1)));
                if (h2 != null) return h2;
            }
        }
        return null;
    }

    private static final class Entry { String file, fileAlt, fileUtf8, alias; double offset; }
    private static final class BandData { final double f0; final double[] db; BandData(double f0,double[] db){this.f0=f0;this.db=db;} }

    private static List<Entry> parseOto(File f) throws IOException {
        byte[] b = Files.readAllBytes(f.toPath());
        String s;
        if (b.length > 2 && (b[0] == (byte)0xFF && b[1] == (byte)0xFE)) {
            s = new String(b, "UTF-16LE");
        } else if (b.length > 2 && (b[0] == (byte)0xFE && b[1] == (byte)0xFF)) {
            s = new String(b, "UTF-16BE");
        } else {
            try {
                CharsetDecoder dec = Charset.forName("UTF-8").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                s = dec.decode(java.nio.ByteBuffer.wrap(b)).toString();
            } catch (Throwable t) {
                s = new String(b, Charset.forName("Shift_JIS"));
            }
        }
        if (s.startsWith("\uFEFF")) s = s.substring(1);
        String gbk = new String(b, Charset.forName("GBK"));
        String utf8 = new String(b, Charset.forName("UTF-8"));
        if (utf8.startsWith("\uFEFF")) utf8 = utf8.substring(1);
        if (gbk.startsWith("\uFEFF")) gbk = gbk.substring(1);
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        gbk = gbk.replace("\r\n", "\n").replace('\r', '\n');
        utf8 = utf8.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = s.split("\n");
        String[] alt = gbk.split("\n");
        String[] u8 = utf8.split("\n");
        List<Entry> out = new ArrayList<Entry>();
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String[] p = line.substring(eq + 1).split(",");
            if (p.length < 2) continue;
            Entry e = new Entry();
            e.file = line.substring(0, eq).trim();
            if (li < alt.length) {
                int aeq = alt[li].indexOf('=');
                if (aeq > 0) e.fileAlt = alt[li].substring(0, aeq).trim();
            }
            if (li < u8.length) {
                int ueq = u8[li].indexOf('=');
                if (ueq > 0) e.fileUtf8 = u8[li].substring(0, ueq).trim();
            }
            e.alias = p[0].trim();
            try { e.offset = Double.parseDouble(p[1].trim()); } catch (Exception ex) { e.offset = 0; }
            if (e.file.length() > 0) out.add(e);
        }
        return out;
    }

    private static String vowelOf(String alias) {
        if (alias == null) return null;
        String a = alias.trim().toLowerCase();
        String[] rows = {
                "あかさたなはまやらわがざだばぱぁゃゎ",
                "いきしちにひみりぎじぢびぴぃ",
                "うくすつぬふむゆるぐずづぶぷぅゅ",
                "えけせてねへめれげぜでべぺぇ",
                "おこそとほもよろごぞどぼぽぉょ"
        };
        for (int i = a.length() - 1; i >= 0; i--) {
            char c = a.charAt(i);
            for (int r = 0; r < rows.length; r++) {
                if (rows[r].indexOf(c) >= 0) return "aiueo".substring(r, r + 1);
            }
        }
        for (int i = a.length() - 1; i >= 0; i--) {
            char c = a.charAt(i);
            if (c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o') return String.valueOf(c);
        }
        return null;
    }

    private static String consonantOf(String alias) {
        if (alias == null) return null;
        String a = alias.trim().toLowerCase();
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if ("さすせそ".indexOf(c) >= 0) return "s";
            if ("し".indexOf(c) >= 0) return "s\\";
            if ("はへほ".indexOf(c) >= 0) return "x";
            if ("ふ".indexOf(c) >= 0) return "f";
            if ("つ".indexOf(c) >= 0) return "ts";
        }
        if (a.startsWith("s")) return "s";
        if (a.startsWith("sh")) return "s\\";
        if (a.startsWith("h")) return "x";
        if (a.startsWith("f")) return "f";
        if (a.startsWith("ts")) return "ts";
        return null;
    }

    private static String plosiveOf(String alias) {
        if (alias == null) return null;
        String a = alias.trim().toLowerCase();
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if ("かきくけこがぎぐげご".indexOf(c) >= 0) return "k_h";
            if ("たてとだでど".indexOf(c) >= 0) return "t_h";
            if ("ぱぴぷぺぽ".indexOf(c) >= 0) return "p_h";
        }
        if (a.startsWith("k")) return "k_h";
        if (a.startsWith("t") && !a.startsWith("ts")) return "t_h";
        if (a.startsWith("p")) return "p_h";
        return null;
    }

    private static Object[] readWav(File f) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
        try {
            byte[] riff = new byte[12]; in.readFully(riff);
            if (riff[0]!='R'||riff[1]!='I'||riff[2]!='F'||riff[3]!='F') throw new IOException("not RIFF");
            int fmt = 0, ch = 1, sr = 44100, bits = 16;
            byte[] data = null;
            while (true) {
                int id;
                try { id = in.readInt(); } catch (EOFException e) { break; }
                int len = Integer.reverseBytes(in.readInt());
                if (id == 0x666d7420) { // fmt
                    byte[] b = new byte[len]; in.readFully(b);
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    fmt = bb.getShort() & 0xffff; ch = bb.getShort() & 0xffff; sr = bb.getInt();
                    bb.getInt();
                    if (b.length >= 16) bits = bb.getShort(14) & 0xffff;
                } else if (id == 0x64617461) { // data
                    data = new byte[len]; in.readFully(data); break;
                } else {
                    in.skipBytes(len);
                }
            }
            if (data == null) throw new IOException("no data chunk");
            int bytes = (fmt == 3) ? 4 : bits/8;
            int n = data.length / (bytes*ch);
            float[] out = new float[n];
            if (bits == 16) {
                for (int i=0;i<n;i++) {
                    int s = 0;
                    for (int c=0;c<ch;c++) {
                        int idx = (i*ch+c)*2;
                        s += (short)(((data[idx+1]&0xFF)<<8) | (data[idx]&0xFF));
                    }
                    out[i] = (float)(s / (32768.0*ch));
                }
            } else if (bits == 8) {
                for (int i=0;i<n;i++) {
                    int s=0; for (int c=0;c<ch;c++) s += (data[i*ch+c]&0xFF)-128;
                    out[i] = (float)(s/(128.0*ch));
                }
            } else if (fmt == 3 && bits == 32) {
                for (int i=0;i<n;i++) {
                    float s = 0;
                    for (int c=0;c<ch;c++) {
                        int idx = (i*ch+c)*4;
                        int v = (data[idx]&0xFF) | ((data[idx+1]&0xFF)<<8) | ((data[idx+2]&0xFF)<<16) | ((data[idx+3]&0xFF)<<24);
                        s += Float.intBitsToFloat(v);
                    }
                    out[i] = s/ch;
                }
            } else if (bits == 24) {
                for (int i=0;i<n;i++) {
                    int s=0;
                    for (int c=0;c<ch;c++) {
                        int idx=(i*ch+c)*3;
                        int v=(data[idx]&0xFF)|((data[idx+1]&0xFF)<<8)|((data[idx+2]&0xFF)<<16);
                        if ((v & 0x800000) != 0) v |= 0xFF000000;
                        s += v;
                    }
                    out[i] = (float)(s/(8388608.0*ch));
                }
            } else throw new IOException("unsupported wav format " + fmt + ", bits " + bits);
            return new Object[]{out, sr};
        } finally { in.close(); }
    }

    private static double f0(double[] x, int sr) {
        int n = Math.min(x.length, 4096);
        double mean = 0; for (int i=0;i<n;i++) mean += x[i]; mean /= n;
        double best = 0; int bestLag = 0;
        int lo = Math.max(2, sr/800), hi = Math.min(n-1, sr/60);
        double[] y = new double[n];
        for (int i=0;i<n;i++) y[i] = x[i]-mean;
        for (int lag=lo; lag<=hi; lag++) {
            double s=0;
            for (int i=0;i<n-lag;i++) s += y[i]*y[i+lag];
            s /= (n-lag);
            if (s > best) { best = s; bestLag = lag; }
        }
        return bestLag>0 ? (double)sr/bestLag : 0;
    }

    private static double[] magnitude(double[] x, int sr) {
        int n = 1; while (n < x.length) n <<= 1;
        double[] re = new double[n], im = new double[n];
        for (int i=0;i<x.length;i++) re[i] = x[i] * (0.5 - 0.5*Math.cos(2*Math.PI*i/(x.length-1)));
        fft(re, im);
        int half = n/2+1;
        double[] mag = new double[half];
        for (int i=0;i<half;i++) mag[i] = Math.hypot(re[i], im[i]);
        return mag;
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i=1,j=0;i<n;i++) {
            int bit = n>>1;
            for (; (j&bit)!=0; bit>>=1) j ^= bit;
            j ^= bit;
            if (i<j) { double t=re[i];re[i]=re[j];re[j]=t; t=im[i];im[i]=im[j];im[j]=t; }
        }
        for (int len=2; len<=n; len<<=1) {
            double ang = -2*Math.PI/len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i=0;i<n;i+=len) {
                double cr=1, ci=0;
                for (int j=0;j<len/2;j++) {
                    double ur=re[i+j], ui=im[i+j];
                    double vr=re[i+j+len/2]*cr - im[i+j+len/2]*ci;
                    double vi=re[i+j+len/2]*ci + im[i+j+len/2]*cr;
                    re[i+j]=ur+vr; im[i+j]=ui+vi;
                    re[i+j+len/2]=ur-vr; im[i+j+len/2]=ui-vi;
                    double ncr = cr*wr - ci*wi;
                    ci = cr*wi + ci*wr; cr = ncr;
                }
            }
        }
    }

    private static double[] formants(double[] freqs, double[] spec, String sym) {
        int k = Math.max(3, spec.length/200);
        double[] smooth = new double[spec.length];
        for (int i=0;i<spec.length;i++) {
            double s=0; int c=0;
            for (int j=-k;j<=k;j++) { int idx=i+j; if (idx>=0&&idx<spec.length){s+=spec[idx];c++;} }
            smooth[i]=s/c;
        }
        double[][] win;
        if ("a".equals(sym)) win = new double[][]{{600,1100},{900,1700},{2200,3200}};
        else if ("i".equals(sym)) win = new double[][]{{200,450},{1800,2800},{2500,3500}};
        else if ("u".equals(sym)) win = new double[][]{{250,550},{600,1200},{2000,3000}};
        else if ("e".equals(sym)) win = new double[][]{{350,700},{1400,2300},{2300,3200}};
        else win = new double[][]{{350,700},{700,1200},{2000,3000}};
        double[] out = new double[3];
        for (int w=0;w<3;w++) {
            int best=-1; double bv=-1;
            for (int i=0;i<freqs.length;i++) {
                if (freqs[i]>=win[w][0] && freqs[i]<=win[w][1] && smooth[i]>bv) { bv=smooth[i]; best=i; }
            }
            out[w] = best>=0 ? freqs[best] : (win[w][0]+win[w][1])/2;
        }
        return out;
    }

    private static double[] bands(double[] freqs, double[] spec) {
        double[] grid = VoicebankPack.harmonicGrid(VoicebankPack.HARM_POINTS);
        double[] db = new double[grid.length];
        for (int g=0; g<grid.length; g++) {
            double lo = grid[g]*0.93, hi = grid[g]*1.07;
            double s=0; int c=0;
            for (int i=0;i<freqs.length;i++) if (freqs[i]>=lo && freqs[i]<=hi) { s+=spec[i]; c++; }
            db[g] = c>0 ? s/c : 0;
        }
        double max=1e-9; for (double v: db) if (v>max) max=v;
        for (int g=0;g<db.length;g++) db[g] = Math.round(20*Math.log10(Math.max(1e-6, db[g]/max))*2)/2.0;
        return db;
    }
}
