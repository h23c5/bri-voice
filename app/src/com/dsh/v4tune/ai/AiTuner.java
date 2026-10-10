package com.dsh.v4tune.ai;

import com.dsh.v4tune.model.ControlCurve;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks an OpenAI-compatible model for control curves and turns the answer into something that can
 * be applied to a part.
 *
 * <p>Nothing here trusts the model: the reply is searched for the first JSON object, every
 * parameter id is checked against {@link Params}, every tick is clipped to the part and every value
 * is clamped to the parameter's range. A reply that does not fit is rejected with a message rather
 * than written into the project.</p>
 */
public final class AiTuner {

    private AiTuner() { }

    /** what the model is asked to produce */
    public static String systemPrompt() {
        return "You are a vocal tuning assistant inside a singing synthesizer. "
                + "You receive a part (tempo, notes, current parameter curves) and a request in natural language, and you answer with JSON only. "
                + "Schema (every field optional): {\"curves\":{\"DYN\":[[tick,value],...]}, \"notes\":[{\"pos\":0,\"key\":64,\"dur\":480,\"vel\":64,\"lyric\":\"la\"}], \"tempo\":128.5, \"trackVolume\":100, \"trackPan\":64}. "
                + "Allowed parameters: " + java.util.Arrays.toString(Params.IDS) + ". "
                + "Ranges: DYN/BRE/BRI/CLE/OPE/GEN/POR/VIB/XSY/GWL 0..127, PIT -2400..2400 cents, PBS 0..24, "
                + "key 0..127, vel 0..127, dur in ticks (60 minimum), tempo 20..400 BPM, trackVolume 0..127, trackPan 0..127 with 64 as centre. "
                + "In \"notes\" use the pos of an EXISTING note as the key and send only the fields you want to change. Ticks use the same unit as the note positions. "
                + "Keep it musical: gentle dynamics on stressed syllables, small pitch movement, no values outside the ranges. Answer with the JSON object and nothing else."
                ;
    }

    /** a compact description of the part, so the request stays small */
    public static String partJson(Song song, Part part, int trackIndex) {
        StringBuilder notes = new StringBuilder("[");
        List<Note> ns = new ArrayList<Note>(part.notes);
        java.util.Collections.sort(ns, new java.util.Comparator<Note>() {
            public int compare(Note a, Note b) { return Long.compare(a.pos, b.pos); }
        });
        for (int i = 0; i < ns.size() && i < 256; i++) {
            Note n = ns.get(i);
            if (i > 0) notes.append(',');
            notes.append(Json.obj("pos", Long.valueOf(n.pos), "dur", Long.valueOf(n.dur),
                    "key", Integer.valueOf(n.key), "vel", Integer.valueOf(n.vel),
                    "lyric", n.lyric == null ? "" : n.lyric));
        }
        notes.append(']');
        StringBuilder cur = new StringBuilder("{");
        boolean first = true;
        for (String id : Params.IDS) {
            ControlCurve c = part.ctrlOrNull(id);
            if (c == null || c.pts.isEmpty()) continue;
            if (!first) cur.append(',');
            first = false;
            cur.append(Json.quote(id)).append(":[");
            for (int i = 0; i < c.pts.size() && i < 64; i++) {
                ControlCurve.P pt = c.pts.get(i);
                if (i > 0) cur.append(',');
                cur.append('[').append(pt.t).append(',').append(Math.round(pt.v * 100) / 100.0).append(']');
            }
            cur.append(']');
        }
        cur.append('}');
        return Json.obj("trackIndex", Integer.valueOf(trackIndex),
                "partStart", Long.valueOf(part.pos), "partDur", Long.valueOf(part.dur),
                "tempoCenti", Long.valueOf(song.tempos.isEmpty() ? 12000L : song.tempos.get(0)[1]),
                "notes", notes.toString(), "curves", cur.toString());
    }

    /**
     * Pulls the JSON object out of a reply that may be wrapped in prose or code fences. Only an
     * object that really parses as a JSON map counts, so a stray pair of braces in the prose is
     * skipped instead of being handed on as if it were the answer.
     */
    public static String extractJson(String reply) {
        if (reply == null) return null;
        int at = reply.indexOf('{');
        while (at >= 0) {
            String cand = balanced(reply, at);
            if (cand != null) {
                java.util.Map<String, Object> m = Json.asMap(Json.parse(cand));
                if (m != null && !m.isEmpty()) return cand;   // {} is not an answer
            }
            at = reply.indexOf('{', at + 1);
        }
        return null;
    }

    /** the substring from an opening brace to its matching close, or null */
    private static String balanced(String s, int from) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(from, i + 1);
            }
        }
        return null;
    }


    
    /**
     * The full tuning schema. Everything is optional, so a model that only sends curves still works.
     *
     * <pre>
     * { "curves": { "DYN": [[tick,value],...] },
     *   "notes":  [ {"pos":0, "key":64, "dur":480, "vel":80, "lyric":"la", "phoneme":"l a"} ],
     *   "tempo": 128.5, "trackVolume": 100, "trackPan": 64 }
     * </pre>
     *
     * @throws IllegalArgumentException with a readable reason when nothing usable came back
     */
    public static Tuning parseTuning(String reply, long maxTick) {
        String js = extractJson(reply);
        if (js == null) throw new IllegalArgumentException("答复里没有 JSON 对象");
        Map<String, Object> root = Json.asMap(Json.parse(js));
        if (root == null) throw new IllegalArgumentException("JSON 不是对象");
        Tuning t = new Tuning();

        // curves
        Object cv = root.get("curves");
        if (cv == null) cv = root;                       // tolerate a flat answer
        Map<String, Object> curveMap = Json.asMap(cv);
        if (curveMap != null) {
            for (Map.Entry<String, Object> e : curveMap.entrySet()) {
                int idx = Params.indexOf(e.getKey());
                if (idx < 0) continue;
                List<Object> pts = Json.asList(e.getValue());
                if (pts == null || pts.isEmpty()) continue;
                ArrayList<double[]> keep = new ArrayList<double[]>();
                for (Object o : pts) {
                    List<Object> pair = Json.asList(o);
                    if (pair == null || pair.size() < 2) continue;
                    double tick = toD(pair.get(0));
                    double val = toD(pair.get(1));
                    if (tick < 0) tick = 0;
                    if (maxTick > 0 && tick > maxTick) tick = maxTick;
                    keep.add(new double[]{tick, Params.clamp(idx, val)});
                    if (keep.size() >= 256) break;
                }
                if (keep.isEmpty()) continue;
                java.util.Collections.sort(keep, new java.util.Comparator<double[]>() {
                    public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
                });
                t.curves.put(Params.IDS[idx], keep.toArray(new double[keep.size()][]));
            }
        }

        // notes
        List<Object> ns = Json.asList(root.get("notes"));
        if (ns != null) {
            for (Object o : ns) {
                Map<String, Object> m = Json.asMap(o);
                if (m == null) continue;
                Tuning.NoteEdit e = new Tuning.NoteEdit();
                e.pos = (long) Json.num(m, "pos", -1);
                if (e.pos < 0) continue;
                if (m.containsKey("key")) e.key = Integer.valueOf(clampInt(Json.num(m, "key", 60), 0, 127));
                if (m.containsKey("vel")) e.vel = Integer.valueOf(clampInt(Json.num(m, "vel", 64), 0, 127));
                if (m.containsKey("dur")) e.dur = Long.valueOf((long) clampInt(Json.num(m, "dur", 480), 60, 1920 * 8));
                if (m.containsKey("newPos")) e.newPos = Long.valueOf(Math.max(0, (long) Json.num(m, "newPos", e.pos)));
                String ly = Json.str(m, "lyric", null);
                if (ly != null && ly.length() > 0 && ly.length() <= 32) e.lyric = ly;
                String ph = Json.str(m, "phoneme", null);
                if (ph != null && ph.length() > 0 && ph.length() <= 64) e.phoneme = ph;
                t.notes.add(e);
                if (t.notes.size() >= 512) break;
            }
        }

        // tempo and track
        if (root.containsKey("tempo")) {
            double bpm = Json.num(root, "tempo", -1);
            if (bpm >= 20 && bpm <= 400) t.tempo = Double.valueOf(bpm);
        }
        if (root.containsKey("trackVolume")) {
            t.trackVolume = Integer.valueOf(clampInt(Json.num(root, "trackVolume", 100), 0, 127));
        }
        if (root.containsKey("trackPan")) {
            t.trackPan = Integer.valueOf(clampInt(Json.num(root, "trackPan", 64), 0, 127));
        }

        if (t.isEmpty()) throw new IllegalArgumentException("答复里没有可用的调整");
        return t;
    }

    private static int clampInt(double v, int lo, int hi) {
        int i = (int) Math.round(v);
        return i < lo ? lo : (i > hi ? hi : i);
    }

    /** the curve-only view, kept for callers that just want the parameters */
    public static Map<String, double[][]> parseCurves(String reply, long maxTick) {
        return parseTuning(reply, maxTick).curves;
    }

    private static double toD(Object o) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof String) {
            try { return Double.parseDouble((String) o); } catch (Throwable ignored) { }
        }
        return 0;
    }

    /** the raw HTTP round trip; throws with a readable message on any failure */
    public static String chat(AiConfig cfg, String userContent, int timeoutMs) throws Exception {
        String body = Json.obj(
                "model", cfg.model,
                "temperature", Double.valueOf(0.2),
                "messages", Json.raw("[" + Json.obj("role", "system", "content", systemPrompt()) + ","
                        + Json.obj("role", "user", "content", userContent) + "]"));
        HttpURLConnection c = (HttpURLConnection) new URL(cfg.chatUrl()).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (cfg.apiKey != null && cfg.apiKey.trim().length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + cfg.apiKey.trim());
        }
        byte[] payload = body.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(payload.length);
        OutputStream os = c.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();
        int code = c.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String text = read(in, 512 * 1024);
        c.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + shortText(text));
        }
        Map<String, Object> root = Json.asMap(Json.parse(text));
        List<Object> choices = Json.asList(root == null ? null : root.get("choices"));
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("答复里没有 choices");
        Map<String, Object> msg = Json.asMap(Json.asMap(choices.get(0)).get("message"));
        String content = Json.str(msg, "content", null);
        if (content == null) throw new IllegalStateException("答复里没有 content");
        return content;
    }

    private static String read(InputStream in, int limit) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && bos.size() < limit) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String shortText(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}