package com.dsh.v4tune.synth;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.io.PushbackInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Reader/writer for {@code .v4voice} singer packs (format {@value #FORMAT}).
 *
 * <p>A pack is a UTF-8 ZIP holding {@code voice.json} (required), {@code LICENSE.txt} and
 * {@code README.txt} (optional). Everything except {@code id} and {@code name} is optional and
 * falls back to the built-in defaults of a fresh {@link VoiceModel}; numbers are clamped to sane
 * ranges and the shelf points are sorted ascending by frequency.</p>
 *
 * <p>The JSON subset needed by {@code voice.json} is parsed by a small dependency-free reader
 * implemented here (no org.json, no Android APIs), so the class also runs on a desktop JVM.
 * {@link #load} never throws: every failure is reported through {@link Result#error}.</p>
 */
public class VoicePack {

    /** The only accepted value of the {@code format} key. */
    public static final String FORMAT = "v4voice/1";
    /** File extension of a singer pack, including the dot. */
    public static final String EXT = ".v4voice";

    /** Maximum size of voice.json (bytes) - voicebanks may carry large measured payloads. */
    private static final int MAX_JSON_BYTES = 256 * 1024 * 1024;
    /** Maximum pack file size (bytes): the user may install voicebanks up to 2 GB. */
    private static final long MAX_PACK_BYTES = 2L * 1024 * 1024 * 1024;
    /** Maximum number of zip entries inspected. */
    private static final int MAX_ZIP_ENTRIES = 256;
    /** Maximum JSON nesting depth. */
    private static final int MAX_DEPTH = 64;
    /** Maximum number of shelf points / formant overrides kept. */
    private static final int MAX_SHELF = 12, MAX_OVERRIDES = 1024;
    /** Text length caps. */
    private static final int MAX_ID = 96, MAX_NAME = 200, MAX_COMPID = 64, MAX_DESC = 1000, MAX_SYM = 64;

    private static final double MIN_SHELF_HZ = 20.0, MAX_SHELF_HZ = 20000.0, MAX_SHELF_DB = 12.0;
    private static final double MIN_FMT_HZ = 80.0, MAX_FMT_HZ = 12000.0;

    /** Fixed zip timestamp so repeated exports are byte-stable (1980-01-01T00:00:00Z). */
    private static final long FIXED_ZIP_TIME = 315532800000L;

    /** Outcome of a parse/import: exactly one of {@link #model} / {@link #error} is set. */
    public static class Result {
        public VoiceModel model;
        public String error;

        public boolean ok() {
            return model != null;
        }
    }

    /**
     * Parses a {@code .v4voice} file. Never throws; on failure the returned {@link Result} carries
     * the reason in {@link Result#error}.
     */
    public static Result load(File packFile) {
        if (packFile == null) return fail("no file");
        String hint = hintOf(packFile.getName());
        try {
            if (!packFile.isFile()) return fail(hint + "file not found");
            if (packFile.length() > MAX_PACK_BYTES) return fail(hint + "pack is larger than 2 GB");
            InputStream in = new FileInputStream(packFile);
            try {
                return load(in, packFile.getName());
            } finally {
                closeQuietly(in);
            }
        } catch (Exception e) {
            return fail(hint + msg(e));
        }
    }

    /**
     * Parses a pack from a stream (used for {@code content://} imports). The caller keeps ownership
     * of the stream: it is fully consumed but not closed. Never throws.
     */
    public static Result load(InputStream in, String fileNameHint) {
        String hint = hintOf(fileNameHint);
        if (in == null) return fail(hint + "no data");
        try {
            // Sniff the first bytes before parsing: on a real phone the stream is empty far more
            // often than the file is corrupt (cloud placeholders, revoked grants, "recent" entries
            // in the picker), and blaming the format for an empty stream sent users on a wild goose
            // chase. Every branch below reports exactly what was received.
            PushbackInputStream pin = new PushbackInputStream(
                    new BufferedInputStream(new NonClosingInputStream(in), 16384), 8);
            byte[] head = new byte[4];
            int n = 0, r;
            while (n < 4 && (r = pin.read(head, n, 4 - n)) > 0) n += r;
            if (n == 0) return fail(hint + "读不到任何内容：文件为空，或系统没给 BriVoice 读取权限");
            if (n < 4) return fail(hint + "文件只有 " + n + " 字节，传输不完整");
            pin.unread(head, 0, n);

            boolean isZip = (head[0] == 'P' && head[1] == 'K');
            byte[] json = null;
            if (isZip) {
                json = readVoiceJson(pin);
                if (json == null) {
                    return fail(hint + "是个 ZIP 但里面没有 voice.json。实际条目：" + lastEntryNames());
                }
            } else {
                // a plain (uncompressed) pack is also accepted: raw JSON starting with '{'
                byte[] all = readLimited(pin, MAX_JSON_BYTES);
                if (all.length > 0 && new String(all, 0, 1, StandardCharsets.UTF_8).equals("{")) {
                    json = all;
                } else {
                    return fail(hint + "不是声库文件：开头是 " + hex(head) + "（" + hexMore(all) + "），"
                            + "既不是 ZIP (PK) 也不是 JSON ({)");
                }
            }
            JsonReader reader = new JsonReader(new String(json, StandardCharsets.UTF_8));
            Object root = reader.parse();
            return build(root, hint);
        } catch (Exception e) {
            return fail(hint + msg(e));
        } catch (StackOverflowError e) {
            return fail(hint + "voice.json is nested too deeply");
        }
    }

    /** entry names of the last zip scan, only used to explain a failed import */
    private static volatile String entryNames = "";

    private static String lastEntryNames() {
        String s = entryNames;
        return (s == null || s.isEmpty()) ? "（没有可读条目）" : s;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02X ", v & 0xff));
        return sb.toString().trim();
    }

    private static String hexMore(byte[] all) {
        int n = Math.min(8, all.length);
        StringBuilder sb = new StringBuilder("前 ").append(all.length).append(" 字节: ");
        for (int i = 0; i < n; i++) sb.append(String.format("%02X ", all[i] & 0xff));
        return sb.toString().trim();
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int n = 0, r;
        while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        return n;
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[8192];
        int r, total = 0;
        while ((r = in.read(buf)) > 0) {
            total += r;
            if (total > max) throw new IOException("file is larger than " + (max >> 20) + " MB");
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }

    /**
     * Serializes a model back to the pack format (the app uses this to re-export a singer). The
     * output is deterministic: fixed key order, sorted formant symbols, sorted shelf points and a
     * fixed zip timestamp. Values are clamped exactly like the loader clamps them.
     *
     * @param licenseText optional license/attribution text stored as LICENSE.txt (null = none)
     * @throws IOException when the model has no usable id/name or the file cannot be written
     */
    public static void write(File out, VoiceModel m, String licenseText) throws IOException {
        if (out == null) throw new IOException("no output file");
        if (m == null) throw new IOException("no model to write");
        String id = cleanText(m.id, MAX_ID);
        String name = cleanText(m.name, MAX_NAME);
        if (id.isEmpty()) throw new IOException("model id is empty");
        if (name.isEmpty()) throw new IOException("model name is empty");

        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent.getAbsolutePath());
        }
        byte[] json = toJson(m, id, name).getBytes(StandardCharsets.UTF_8);

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 8192));
        try {
            zos.setLevel(Deflater.BEST_COMPRESSION);
            putEntry(zos, "voice.json", json);
            if (licenseText != null && licenseText.trim().length() > 0) {
                putEntry(zos, "LICENSE.txt", licenseText.getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            zos.close();
        }
    }

    // ------------------------------------------------------------ zip in / out

    /** @return the bytes of voice.json, or null when the archive has no such entry. */
    private static byte[] readVoiceJson(InputStream in) throws IOException {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new NonClosingInputStream(in), 8192));
        try {
            byte[] best = null;
            boolean bestExact = false;
            byte[] buf = new byte[8192];
            StringBuilder seen = new StringBuilder();
            for (int entry = 0; entry < MAX_ZIP_ENTRIES; entry++) {
                ZipEntry e = zip.getNextEntry();
                if (e == null) break;
                String name = e.getName();
                if (name == null) continue;
                if (seen.length() < 200) seen.append(seen.length() > 0 ? ", " : "").append(name);
                entryNames = seen.toString();
                name = name.replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                String lower = name.toLowerCase(Locale.ROOT);
                boolean exact = lower.equals("voice.json");
                boolean nested = lower.endsWith("/voice.json");
                if (!exact && !nested) continue;
                if (best != null && bestExact && !exact) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
                int total = 0, r;
                while ((r = zip.read(buf)) > 0) {
                    total += r;
                    if (total > MAX_JSON_BYTES) throw new IOException("voice.json is larger than 256 MB");
                    bos.write(buf, 0, r);
                }
                best = bos.toByteArray();
                bestExact = exact;
            }
            return best;
        } finally {
            closeQuietly(zip);
        }
    }

    private static void putEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(FIXED_ZIP_TIME);
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    /** Wrapper that keeps {@link #load(InputStream, String)} from closing the caller's stream. */
    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // the caller owns the stream
        }
    }

    // ------------------------------------------------------------ model build

    private static Result build(Object root, String hint) {
        Map<String, Object> m = asObj(root);
        if (m == null) return fail(hint + "voice.json must contain a JSON object");
        Object fmt = m.get("format");
        String fs = fmt instanceof String ? ((String) fmt).trim() : "";
        if (!FORMAT.equals(fs)) {
            return fail(hint + "unsupported format " + (fs.isEmpty() ? "(missing)" : "\"" + fs + "\"")
                    + ", expected \"" + FORMAT + "\"");
        }

        VoiceModel vm = new VoiceModel();
        vm.id = cleanText(str(m, "id", ""), MAX_ID);
        vm.name = cleanText(str(m, "name", ""), MAX_NAME);
        if (vm.id.isEmpty()) return fail(hint + "required key \"id\" is missing or empty");
        if (vm.name.isEmpty()) return fail(hint + "required key \"name\" is missing or empty");
        vm.nameEn = cleanText(str(m, "nameEn", ""), MAX_NAME);
        vm.nameJa = cleanText(str(m, "nameJa", ""), MAX_NAME);
        vm.lang = langOf(m, vm.lang);
        vm.compID = cleanText(str(m, "compID", ""), MAX_COMPID);

        Map<String, Object> v = asObj(m.get("voice"));
        vm.formantScale = num(v, "formantScale", vm.formantScale, 0.5, 2.0);
        vm.f1Mul = num(v, "f1Mul", vm.f1Mul, 0.5, 2.0);
        vm.f2Mul = num(v, "f2Mul", vm.f2Mul, 0.5, 2.0);
        vm.f3Mul = num(v, "f3Mul", vm.f3Mul, 0.5, 2.0);
        vm.f4Mul = num(v, "f4Mul", vm.f4Mul, 0.5, 2.0);
        vm.f5Mul = num(v, "f5Mul", vm.f5Mul, 0.5, 2.0);
        vm.tiltBias = num(v, "tiltBias", vm.tiltBias, -1.0, 1.0);
        vm.breathBias = num(v, "breathBias", vm.breathBias, 0.0, 1.0);
        vm.oqBias = num(v, "oqBias", vm.oqBias, -0.2, 0.2);
        vm.vibratoRate = num(v, "vibratoRate", vm.vibratoRate, 0.0, 12.0);
        vm.vibratoDepthCents = num(v, "vibratoDepthCents", vm.vibratoDepthCents, 0.0, 200.0);
        vm.vibratoDelayMs = num(v, "vibratoDelayMs", vm.vibratoDelayMs, 0.0, 2000.0);
        vm.gain = num(v, "gain", vm.gain, 0.0, 4.0);
        vm.consonantGain = num(v, "consonantGain", vm.consonantGain, 0.0, 4.0);
        vm.breathSoundAmount = num(v, "breathSoundAmount", vm.breathSoundAmount, 0.0, 1.0);
        String desc = str(v, "description", str(m, "description", ""));
        vm.description = cleanText(desc, MAX_DESC);

        readShelf(m.get("shelf"), vm);
        readFormants(m.get("formants"), vm);
        readHarmonics(m.get("harmonics"), vm);

        Result r = new Result();
        r.model = vm;
        return r;
    }

    private static void readShelf(Object raw, VoiceModel vm) {
        double[] f = new double[MAX_SHELF];
        double[] g = new double[MAX_SHELF];
        int n = 0;
        if (raw instanceof List) {
            List<?> pts = (List<?>) raw;
            for (int i = 0; i < pts.size() && n < MAX_SHELF; i++) {
                Object o = pts.get(i);
                if (!(o instanceof List)) continue;
                List<?> p = (List<?>) o;
                if (p.size() < 2) continue;
                Double fr = dbl(p.get(0)), gn = dbl(p.get(1));
                if (fr == null || gn == null) continue;
                double fv = fr.doubleValue(), gv = gn.doubleValue();
                if (!finite(fv) || !finite(gv) || fv <= 0.0) continue;
                f[n] = clamp(fv, MIN_SHELF_HZ, MAX_SHELF_HZ);
                g[n] = clamp(gv, -MAX_SHELF_DB, MAX_SHELF_DB);
                n++;
            }
        }
        n = normalizePoints(f, g, n);
        vm.shelfFreqs = Arrays.copyOf(f, n);
        vm.shelfGains = Arrays.copyOf(g, n);
    }

    private static void readFormants(Object raw, VoiceModel vm) {
        if (!(raw instanceof Map)) return;
        Map<?, ?> map = (Map<?, ?>) raw;
        int n = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (n >= MAX_OVERRIDES) break;
            if (!(e.getKey() instanceof String)) continue;
            String sym = cleanText((String) e.getKey(), MAX_SYM);
            if (sym.isEmpty()) continue;
            Object val = e.getValue();
            if (!(val instanceof List)) continue;
            List<?> a = (List<?>) val;
            if (a.size() < 3) continue;
            Double x = dbl(a.get(0)), y = dbl(a.get(1)), z = dbl(a.get(2));
            if (x == null || y == null || z == null) continue;
            double f1 = x.doubleValue(), f2 = y.doubleValue(), f3 = z.doubleValue();
            if (!finite(f1) || !finite(f2) || !finite(f3)) continue;
            if (f1 <= 0.0 || f2 <= 0.0 || f3 <= 0.0) continue;
            vm.overrides.put(sym, new double[]{
                    clamp(f1, MIN_FMT_HZ, MAX_FMT_HZ),
                    clamp(f2, MIN_FMT_HZ, MAX_FMT_HZ),
                    clamp(f3, MIN_FMT_HZ, MAX_FMT_HZ)});
            n++;
        }
    }

    // ------------------------------------------------------------ harmonics (v2)

    /** Harmonic grid limits: 8..256 points inside 20 Hz..20 kHz, ascending and unique. */
    private static final int MIN_HARM_POINTS = 8, MAX_HARM_POINTS = 256, MAX_HARM_RAW = 512;
    /** Bands kept per phoneme symbol, and symbols kept per pack. */
    private static final int MAX_HARM_BANDS = 16, MAX_HARM_SYMBOLS = 4096;
    /** Accepted frequency range of the grid and of a band's centre f0. */
    private static final double MIN_HARM_HZ = 20.0, MAX_HARM_HZ = 20000.0;
    private static final double MIN_HARM_F0 = 16.0, MAX_HARM_F0 = 8000.0;
    /** Accepted dB range of one band, and the peak below which a band carries no usable data. */
    private static final double MIN_HARM_DB = -160.0, MAX_HARM_DB = 24.0, MIN_BAND_PEAK_DB = -80.0;

    /** Validated frequency grid plus the mapping back to the (possibly unsorted) source array. */
    private static final class Grid {
        double[] freqs;   // ascending, unique, inside MIN_HARM_HZ..MAX_HARM_HZ
        int[] src;        // src[i] = index in the source array that freqs[i] was taken from
        int rawLen;       // length of the source array
    }

    /**
     * Reads the v2 {@code harmonics} section
     * ({@code "freqs":[...], "phonemes":{sym:[{"f0":x,"db":[...]}, ...]}, "noise":{sym:{...}}}).
     *
     * <p>The shared grid is sorted ascending, de-duplicated and range checked; a section that does
     * not end up with {@value #MIN_HARM_POINTS}..{@value #MAX_HARM_POINTS} usable points is dropped
     * entirely. Every band is re-indexed onto that grid, so a band that is kept always satisfies
     * {@code db.length == harmonicFreqs.length}; single malformed bands are skipped one by one.</p>
     */
    private static void readHarmonics(Object raw, VoiceModel vm) {
        if (!(raw instanceof Map)) return;
        Map<?, ?> hm = (Map<?, ?>) raw;
        Grid grid = readGrid(hm.get("freqs"));
        if (grid == null) return;

        Object ph = hm.get("phonemes");
        if (ph instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) ph;
            int seen = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (vm.harmonics.size() >= MAX_HARM_SYMBOLS || ++seen > MAX_HARM_SYMBOLS * 4) break;
                if (!(e.getKey() instanceof String)) continue;
                String sym = cleanText((String) e.getKey(), MAX_SYM);
                if (sym.isEmpty()) continue;
                VoiceModel.HarmonicBand[] bands = readBands(e.getValue(), grid);
                if (bands != null) vm.harmonics.put(sym, bands);
            }
            if (!vm.harmonics.isEmpty()) vm.harmonicFreqs = grid.freqs;
        }
        readNoise(hm.get("noise"), vm, grid);
    }

    /** Bands of one phoneme symbol, sorted by centre f0; null when none of them is usable. */
    /** base64 -> bytes, or null when the text is not valid base64 */
    private static byte[] b64(String s) {
        int n = s.length();
        if (n == 0 || (n & 3) != 0) return null;
        int pad = 0;
        if (s.charAt(n - 1) == '=') pad++;
        if (n > 1 && s.charAt(n - 2) == '=') pad++;
        byte[] out = new byte[n / 4 * 3 - pad];
        int o = 0;
        for (int i = 0; i < n; i += 4) {
            int v = 0;
            for (int j = 0; j < 4; j++) {
                char c = s.charAt(i + j);
                int d = (c < 128) ? B64DEC[c] : -1;
                if (d < 0) {
                    if (c != '=') return null;
                    d = 0;
                }
                v = (v << 6) | d;
            }
            if (o < out.length) out[o++] = (byte) (v >> 16);
            if (o < out.length) out[o++] = (byte) (v >> 8);
            if (o < out.length) out[o++] = (byte) v;
        }
        return out;
    }

    private static final int[] B64DEC = new int[128];

    static {
        for (int i = 0; i < B64DEC.length; i++) B64DEC[i] = -1;
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < a.length(); i++) B64DEC[a.charAt(i)] = i;
    }

    private static VoiceModel.HarmonicBand[] readBands(Object raw, Grid grid) {
        if (!(raw instanceof List)) return null;
        List<?> list = (List<?>) raw;
        int n = Math.min(list.size(), MAX_HARM_BANDS * 4);
        ArrayList<VoiceModel.HarmonicBand> out = new ArrayList<VoiceModel.HarmonicBand>();
        for (int i = 0; i < n && out.size() < MAX_HARM_BANDS; i++) {
            Object o = list.get(i);
            if (!(o instanceof Map)) continue;
            Map<?, ?> bm = (Map<?, ?>) o;
            Double f0 = dbl(bm.get("f0"));
            if (f0 == null) continue;
            double f0v = f0.doubleValue();
            if (!finite(f0v) || f0v < MIN_HARM_F0 || f0v > MAX_HARM_F0) continue;
            // The sequence (when present) is the real data; the mean "db" curve is only a fallback
            // and newer packs omit it to keep voice.json small, so a band is valid with either.
            Object b64Raw = bm.get("seqB64");
            double[] db = bandDb(bm.get("db"), grid);
            if (db == null && !(b64Raw instanceof String)) continue;
            int ptsNeeded = (db != null) ? db.length : grid.freqs.length;
            VoiceModel.HarmonicBand b = new VoiceModel.HarmonicBand();
            b.f0 = f0v;
            b.db = db;
            if (b64Raw instanceof String) {
                byte[] bytes = b64((String) b64Raw);
                Double qv = dbl(bm.get("seqQ"));
                double q = (qv != null && finite(qv.doubleValue()) && qv.doubleValue() > 0) ? qv.doubleValue() : 0.5;
                int pts = ptsNeeded;
                if (bytes != null && pts > 0 && bytes.length >= 2 * pts && bytes.length % pts == 0) {
                    int nf = bytes.length / pts;
                    if (nf > 64) nf = 64;
                    double[][] frames = new double[nf][];
                    for (int fi = 0; fi < nf; fi++) {
                        double[] fr = new double[pts];
                        int base = fi * pts;
                        for (int k = 0; k < pts; k++) {
                            double v = bytes[base + k] * q;
                            fr[k] = (v < 0) ? v : 0.0;          // 0 dB is the frame's peak
                        }
                        frames[fi] = fr;
                    }
                    b.seq = frames;
                }
            }
            if (b.db == null && b.seq != null) {
                // rebuild the mean curve from the sequence so every reader can rely on db
                double[] mean = new double[b.seq[0].length];
                for (double[] fr : b.seq) {
                    for (int k = 0; k < mean.length; k++) mean[k] += Math.pow(10.0, fr[k] / 20.0);
                }
                double peak = 0;
                for (int k = 0; k < mean.length; k++) {
                    mean[k] = 20.0 * Math.log10(Math.max(1e-12, mean[k] / b.seq.length));
                    if (mean[k] > peak) peak = mean[k];
                }
                for (int k = 0; k < mean.length; k++) mean[k] -= peak;
                b.db = mean;
            }
            Object seqRaw = bm.get("seq");
            if (seqRaw instanceof List) {
                List<?> sl = (List<?>) seqRaw;
                int maxFrames = Math.min(sl.size(), 64);
                ArrayList<double[]> frames = new ArrayList<double[]>();
                for (int k = 0; k < maxFrames; k++) {
                    double[] fdb = bandDb(sl.get(k), grid);
                    if (fdb != null) frames.add(fdb);
                }
                if (frames.size() >= 2) b.seq = frames.toArray(new double[frames.size()][]);
            }
            Object lvlRaw = bm.get("lvl");
            if (lvlRaw instanceof List && b.seq != null) {
                List<?> ll = (List<?>) lvlRaw;
                double[] lv = new double[b.seq.length];
                int k = 0;
                for (Object lvObj : ll) {
                    if (k >= lv.length) break;
                    Double dv = dbl(lvObj);
                    lv[k++] = (dv != null && finite(dv.doubleValue())) ? clamp(dv.doubleValue(), -30.0, 18.0) : 0.0;
                }
                b.lvl = lv;
            }
            // "dyn" marks a loudness variant (0 = softest .. 1 = loudest); if the pack predates it,
            // spread the variants that share an f0 evenly so the DYN lookup still has something to pick
            Double dyn = dbl(bm.get("dyn"));
            if (dyn != null && finite(dyn.doubleValue())) {
                b.dyn = clamp(dyn.doubleValue(), 0.0, 1.0);
            } else {
                int same = 0;
                for (int k = 0; k < out.size(); k++) {
                    if (Math.abs(out.get(k).f0 - f0v) < 0.5) same++;
                }
                b.dyn = same == 0 ? 0.5 : Math.min(1.0, same * 0.5);
            }
            out.add(b);
        }
        if (out.isEmpty()) return null;
        for (int i = 1; i < out.size(); i++) {                  // stable insertion sort by f0
            VoiceModel.HarmonicBand b = out.get(i);
            int j = i - 1;
            while (j >= 0 && out.get(j).f0 > b.f0) {
                out.set(j + 1, out.get(j));
                j--;
            }
            out.set(j + 1, b);
        }
        return out.toArray(new VoiceModel.HarmonicBand[out.size()]);
    }

    /**
     * Maps a band's {@code db} array onto the shared grid (or onto a grid handed in with the same
     * layout) and clamps it. Returns null when the array does not line up with the grid at all or
     * carries no usable level.
     */
    private static double[] bandDb(Object raw, Grid grid) {
        if (!(raw instanceof List)) return null;
        List<?> list = (List<?>) raw;
        int len = list.size();
        int n = grid.freqs.length;
        boolean bySource = len == grid.rawLen;
        if (!bySource && len != n) return null;
        double[] out = new double[n];
        double peak = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            Double d = dbl(list.get(bySource ? grid.src[i] : i));
            double v = d == null ? MIN_HARM_DB : clamp(d.doubleValue(), MIN_HARM_DB, MAX_HARM_DB);
            out[i] = v;
            if (v > peak) peak = v;
        }
        return peak < MIN_BAND_PEAK_DB ? null : out;
    }

    /**
     * Reads the optional fricative noise spectra. The shared grid is the one inside
     * {@code "noise"} itself, else the one the first entry carries, else the harmonic grid - so both
     * the documented per-entry layout and a compact shared-grid layout load.
     */
    private static void readNoise(Object raw, VoiceModel vm, Grid fallback) {
        if (!(raw instanceof Map)) return;
        Map<?, ?> nm = (Map<?, ?>) raw;
        Grid grid = readGrid(nm.get("freqs"));
        if (grid == null) {
            for (Map.Entry<?, ?> e : nm.entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                Grid g = readGrid(((Map<?, ?>) e.getValue()).get("freqs"));
                if (g != null) { grid = g; break; }
            }
        }
        if (grid == null) grid = fallback;
        if (grid == null) return;
        int seen = 0;
        for (Map.Entry<?, ?> e : nm.entrySet()) {
            if (vm.noiseDb.size() >= MAX_HARM_SYMBOLS || ++seen > MAX_HARM_SYMBOLS * 4) break;
            if (!(e.getKey() instanceof String)) continue;
            String sym = cleanText((String) e.getKey(), MAX_SYM);
            if (sym.isEmpty()) continue;
            Object v = e.getValue();
            Grid g = grid;
            Object dbo = v;
            if (v instanceof Map) {
                Map<?, ?> em = (Map<?, ?>) v;
                dbo = em.get("db");
                Grid own = readGrid(em.get("freqs"));
                if (own != null && own.freqs.length == grid.freqs.length) g = own;
            }
            double[] db = bandDb(dbo, g);
            if (db != null) {
                vm.noiseFreqs = g.freqs;
                vm.noiseDb.put(sym, db);
            }
        }
    }

    /**
     * Numeric array -> validated grid: finite points inside {@value #MIN_HARM_HZ}..
     * {@value #MAX_HARM_HZ} Hz, sorted ascending and de-duplicated, together with the index every
     * kept point came from. Returns null when fewer than {@value #MIN_HARM_POINTS} points survive.
     */
    private static Grid readGrid(Object raw) {
        if (!(raw instanceof List)) return null;
        List<?> list = (List<?>) raw;
        int rawLen = list.size();
        if (rawLen < MIN_HARM_POINTS || rawLen > MAX_HARM_RAW) return null;
        double[] f = new double[rawLen];
        int[] src = new int[rawLen];
        int k = 0;
        for (int i = 0; i < rawLen; i++) {
            Double v = dbl(list.get(i));
            if (v == null) continue;
            double fv = v.doubleValue();
            if (!finite(fv) || fv < MIN_HARM_HZ || fv > MAX_HARM_HZ) continue;
            int j = k - 1;                                      // insertion sort, keeps src aligned
            while (j >= 0 && f[j] > fv) {
                f[j + 1] = f[j];
                src[j + 1] = src[j];
                j--;
            }
            f[j + 1] = fv;
            src[j + 1] = i;
            k++;
        }
        int u = 0;
        for (int i = 0; i < k; i++) {
            if (u > 0 && f[i] == f[u - 1]) continue;            // equal frequencies: first one wins
            f[u] = f[i];
            src[u] = src[i];
            u++;
        }
        if (u < MIN_HARM_POINTS || u > MAX_HARM_POINTS) return null;
        Grid g = new Grid();
        g.freqs = Arrays.copyOf(f, u);
        g.src = Arrays.copyOf(src, u);
        g.rawLen = rawLen;
        return g;
    }

    /** Sorts (freq, gain) pairs ascending and drops duplicate frequencies (last gain wins). */
    private static int normalizePoints(double[] f, double[] g, int n) {
        for (int i = 1; i < n; i++) {
            double fv = f[i], gv = g[i];
            int j = i - 1;
            while (j >= 0 && f[j] > fv) {
                f[j + 1] = f[j];
                g[j + 1] = g[j];
                j--;
            }
            f[j + 1] = fv;
            g[j + 1] = gv;
        }
        int u = 0;
        for (int i = 0; i < n; i++) {
            if (u > 0 && f[i] == f[u - 1]) {
                g[u - 1] = g[i];
                continue;
            }
            f[u] = f[i];
            g[u] = g[i];
            u++;
        }
        return u;
    }

    // ------------------------------------------------------------ model write

    private static String toJson(VoiceModel m, String id, String name) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        kv(sb, 1, "format", quote(FORMAT), true);
        kv(sb, 1, "id", quote(id), true);
        kv(sb, 1, "name", quote(name), true);
        kv(sb, 1, "nameEn", quote(cleanText(m.nameEn, MAX_NAME)), true);
    if (m.nameJa != null && !m.nameJa.trim().isEmpty()) {
        kv(sb, 1, "nameJa", quote(cleanText(m.nameJa, MAX_NAME)), true);
    }
        kv(sb, 1, "lang", Integer.toString(langInt(m.lang)), true);
        kv(sb, 1, "compID", quote(cleanText(m.compID, MAX_COMPID)), true);
        sb.append("  \"voice\": {\n");
        kv(sb, 2, "formantScale", numStr(m.formantScale), true);
        kv(sb, 2, "f1Mul", numStr(m.f1Mul), true);
        kv(sb, 2, "f2Mul", numStr(m.f2Mul), true);
        kv(sb, 2, "f3Mul", numStr(m.f3Mul), true);
        kv(sb, 2, "f4Mul", numStr(m.f4Mul), true);
        kv(sb, 2, "f5Mul", numStr(m.f5Mul), true);
        kv(sb, 2, "tiltBias", numStr(m.tiltBias), true);
        kv(sb, 2, "breathBias", numStr(m.breathBias), true);
        kv(sb, 2, "oqBias", numStr(m.oqBias), true);
        kv(sb, 2, "vibratoRate", numStr(m.vibratoRate), true);
        kv(sb, 2, "vibratoDepthCents", numStr(m.vibratoDepthCents), true);
        kv(sb, 2, "vibratoDelayMs", numStr(m.vibratoDelayMs), true);
        kv(sb, 2, "gain", numStr(m.gain), true);
        kv(sb, 2, "consonantGain", numStr(m.consonantGain), true);
        kv(sb, 2, "breathSoundAmount", numStr(m.breathSoundAmount), true);
        kv(sb, 2, "description", quote(cleanText(m.description, MAX_DESC)), false);
        sb.append("  },\n");

        double[] f = new double[MAX_SHELF];
        double[] g = new double[MAX_SHELF];
        int n = 0;
        if (m.shelfFreqs != null && m.shelfGains != null) {
            int len = Math.min(m.shelfFreqs.length, m.shelfGains.length);
            for (int i = 0; i < len && n < MAX_SHELF; i++) {
                double fv = m.shelfFreqs[i], gv = m.shelfGains[i];
                if (!finite(fv) || !finite(gv) || fv <= 0.0) continue;
                f[n] = clamp(fv, MIN_SHELF_HZ, MAX_SHELF_HZ);
                g[n] = clamp(gv, -MAX_SHELF_DB, MAX_SHELF_DB);
                n++;
            }
        }
        n = normalizePoints(f, g, n);
        sb.append("  \"shelf\": [");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append('[').append(numStr(f[i])).append(", ").append(numStr(g[i])).append(']');
        }
        sb.append("],\n");

        TreeMap<String, double[]> fm = new TreeMap<String, double[]>();
        for (Map.Entry<String, double[]> e : m.overrides.entrySet()) {
            if (fm.size() >= MAX_OVERRIDES) break;
            if (e.getKey() == null || e.getValue() == null || e.getValue().length < 3) continue;
            String sym = cleanText(e.getKey(), MAX_SYM);
            if (sym.isEmpty()) continue;
            double[] t = e.getValue();
            if (!finite(t[0]) || !finite(t[1]) || !finite(t[2])) continue;
            if (t[0] <= 0.0 || t[1] <= 0.0 || t[2] <= 0.0) continue;
            fm.put(sym, new double[]{
                    clamp(t[0], MIN_FMT_HZ, MAX_FMT_HZ),
                    clamp(t[1], MIN_FMT_HZ, MAX_FMT_HZ),
                    clamp(t[2], MIN_FMT_HZ, MAX_FMT_HZ)});
        }
        sb.append("  \"formants\": {");
        boolean firstFmt = true;
        for (Map.Entry<String, double[]> e : fm.entrySet()) {
            double[] t = e.getValue();
            if (!firstFmt) sb.append(',');
            firstFmt = false;
            sb.append(' ').append(quote(e.getKey())).append(": [")
              .append(numStr(t[0])).append(", ")
              .append(numStr(t[1])).append(", ")
              .append(numStr(t[2])).append(']');
        }
        if (!firstFmt) sb.append(' ');
        sb.append("}\n}\n");
        return sb.toString();
    }

    private static void kv(StringBuilder sb, int indent, String key, String rawValue, boolean comma) {
        for (int i = 0; i < indent; i++) sb.append("  ");
        sb.append('"').append(key).append("\": ").append(rawValue);
        sb.append(comma ? ",\n" : "\n");
    }

    /** Deterministic number formatting: fixed decimals with trailing zeros stripped. */
    private static String numStr(double v) {
        if (!finite(v) || v == 0.0) return "0";
        if (v == Math.rint(v) && Math.abs(v) < 1.0e15) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.6f", v);
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == '0') e--;
        if (e > 0 && s.charAt(e - 1) == '.') e--;
        return e > 0 ? s.substring(0, e) : "0";
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ------------------------------------------------------------ json reader

    /**
     * Minimal JSON reader for the voice.json subset: objects, arrays, strings, numbers, booleans
     * and null. Objects become {@link LinkedHashMap} (key order preserved), arrays {@link ArrayList}.
     * Nesting depth, duplicates handling and error positions are all bounded.
     */
    private static final class JsonReader {
        private static final Pattern NUMBER =
                Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?");

        private final String s;
        private int i;
        private int depth;

        JsonReader(String s) {
            this.s = s;
        }

        Object parse() throws IOException {
            ws();
            Object v = value();
            ws();
            if (i < s.length()) throw err("unexpected trailing text");
            return v;
        }

        private Object value() throws IOException {
            if (i >= s.length()) throw err("unexpected end of input");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private Map<String, Object> object() throws IOException {
            depth++;
            if (depth > MAX_DEPTH) throw err("nested too deeply");
            try {
                LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
                i++;                                  // '{'
                ws();
                if (peek() == '}') {
                    i++;
                    return map;
                }
                while (true) {
                    ws();
                    if (peek() != '"') throw err("object key must be a string");
                    String key = string();
                    ws();
                    if (peek() != ':') throw err("expected ':' after object key");
                    i++;
                    ws();
                    map.put(key, value());
                    ws();
                    char c = peek();
                    if (c == ',') {
                        i++;
                        continue;
                    }
                    if (c == '}') {
                        i++;
                        return map;
                    }
                    throw err("expected ',' or '}' in object");
                }
            } finally {
                depth--;
            }
        }

        private List<Object> array() throws IOException {
            depth++;
            if (depth > MAX_DEPTH) throw err("nested too deeply");
            try {
                ArrayList<Object> list = new ArrayList<Object>();
                i++;                                  // '['
                ws();
                if (peek() == ']') {
                    i++;
                    return list;
                }
                while (true) {
                    ws();
                    list.add(value());
                    ws();
                    char c = peek();
                    if (c == ',') {
                        i++;
                        continue;
                    }
                    if (c == ']') {
                        i++;
                        return list;
                    }
                    throw err("expected ',' or ']' in array");
                }
            } finally {
                depth--;
            }
        }

        private String string() throws IOException {
            i++;                                      // '"'
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw err("unterminated escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err("bad \\u escape");
                            int cp = 0;
                            for (int k = 0; k < 4; k++) {
                                int d = Character.digit(s.charAt(i + k), 16);
                                if (d < 0) throw err("bad \\u escape");
                                cp = (cp << 4) | d;
                            }
                            i += 4;
                            sb.append((char) cp);
                            break;
                        default: throw err("unknown escape \\" + e);
                    }
                } else if (c < 0x20) {
                    throw err("control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        private Double number() throws IOException {
            Matcher mt = NUMBER.matcher(s);
            mt.region(i, s.length());
            if (!mt.lookingAt()) throw err("invalid value");
            i = mt.end();
            try {
                return Double.valueOf(Double.parseDouble(mt.group()));
            } catch (NumberFormatException e) {
                throw err("invalid number");
            }
        }

        private void expect(String word) throws IOException {
            if (!s.startsWith(word, i)) throw err("invalid value");
            i += word.length();
        }

        private char peek() throws IOException {
            if (i >= s.length()) throw err("unexpected end of input");
            return s.charAt(i);
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        private IOException err(String what) {
            return new IOException(what + " at offset " + i);
        }
    }

    // ------------------------------------------------------------ small helpers

    private static Result fail(String error) {
        Result r = new Result();
        r.model = null;
        r.error = error == null ? "unknown error" : error;
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static Object get(Map<String, Object> m, String key) {
        return m == null ? null : m.get(key);
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object o = get(m, key);
        return o instanceof String ? (String) o : def;
    }

    private static double num(Map<String, Object> m, String key, double def, double lo, double hi) {
        Double d = dbl(get(m, key));
        if (d == null) return def;
        double v = d.doubleValue();
        return finite(v) ? clamp(v, lo, hi) : def;
    }

    /** Accepts a JSON number or a numeric string; returns null for anything else. */
    private static Double dbl(Object o) {
        if (o instanceof Number) {
            double v = ((Number) o).doubleValue();
            return Double.isNaN(v) ? null : Double.valueOf(v);
        }
        if (o instanceof String) {
            String t = ((String) o).trim();
            if (t.isEmpty()) return null;
            try {
                return Double.valueOf(Double.parseDouble(t));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static int langOf(Map<String, Object> m, int def) {
        Object o = get(m, "lang");
        if (o instanceof String) {
            String t = ((String) o).trim().toUpperCase(Locale.ROOT);
            if (t.startsWith("JPN") || t.startsWith("JP")) return 0;
            if (t.startsWith("ENG") || t.startsWith("EN")) return 1;
            if (t.startsWith("CHN") || t.startsWith("CN") || t.startsWith("ZH") || t.startsWith("MAN")) return 2;
            return def;
        }
        Double d = dbl(o);
        if (d == null) return def;
        return (int) Math.round(clamp(d.doubleValue(), 0.0, 2.0));
    }

    private static int langInt(int lang) {
        return lang < 0 ? 0 : (lang > 2 ? 2 : lang);
    }

    /** Drops control characters and unpaired surrogates, trims and truncates. */
    private static String cleanText(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) continue;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String hintOf(String fileNameHint) {
        String h = cleanText(fileNameHint, 120);
        return h.isEmpty() ? "" : h + ": ";
    }

    private static String msg(Throwable t) {
        if (t == null) return "unknown error";
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t.getClass().getSimpleName();
        return m;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
            // nothing useful to do
        }
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
