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

    /** the detailed Chinese tuning prompt ported from the web build */
    public static String systemPrompt() {
        StringBuilder b = new StringBuilder();
        b.append("你是 BriVoice 的虚拟歌手中文调音助手。请根据下面的工程内容提出调音修改。").append('\n');
        b.append("所有音符的 tick 均为绝对位置；音轨参数仅作用于当前音轨。").append('\n');
        b.append('\n');
        b.append("【调音参数说明】（范围 / 默认值 / 作用）").append('\n');
        b.append("- DYN 力度：0–127，默认 64，每 +1 约 +0.3 dB（约 ±19 dB）。控制整句/单字的强弱，重音、渐强渐弱都用它。").append('\n');
        b.append("- BRE 气息：0–127，默认 0。越高气声越多、越轻；抒情句、句尾可用 60–90，快歌保持 20–50。").append('\n');
        b.append("- BRI 明亮度：0–127，默认 64。越高高频越亮、越靠前；过低会闷。").append('\n');
        b.append("- CLE 清晰度：0–127，默认 0。越高咬字/音头越清晰，过高会生硬。").append('\n');
        b.append("- OPE 开口度：0–127，默认 127。控制元音张口程度，偏低更含蓄、偏高更外放。").append('\n');
        b.append("- GEN 性别/音色：0–127，默认 64。64 为中性，偏低偏男、偏高偏女；一般 50–75，不要大幅改。").append('\n');
        b.append("- POR 滑音时间：0–127，默认 64，映射到 0–110ms。越大音与音之间越连、越小越断。").append('\n');
        b.append("- PIT 音高曲线：-2400–2400（音分），默认 0，受 PBS 缩放。用于滑音、转音、轻微走音修正。").append('\n');
        b.append("- PBS 滑音敏感度：0–24（半音），默认 2。决定 PIT 的最大作用范围，一般 2–6。").append('\n');
        b.append("- VIB 颤音：0–127，默认 64。越大颤音越深；长音可用 70–95，短音不要加。").append('\n');
        b.append("- XSY 交叉合成：0–127，默认 64。本引擎暂未参与合成，保留字段，建议不要修改。").append('\n');
        b.append("- GWL 嘶吼/咆哮：0–127，默认 0。增加粗糙与喉音，仅摇滚/强情绪句少量使用（10–40）。").append('\n');
        b.append('\n');
        b.append("【你可以使用的技能】").append('\n');
        b.append("1. 写参数曲线：curves 里每个参数给 [[tick,value],...]，可只改局部区间，不要整首都画。").append('\n');
        b.append("2. 修改已有音符：notes 里用 pos=已有音符的 tick，可选 key/dur/vel/lyric/phoneme，只改需要改的字段。").append('\n');
        b.append("3. 调整速度 tempo（20–400）与当前音轨 trackVolume/trackPan（0–127，64 居中）。").append('\n');
        b.append("4. 不能新增或删除音符、不能改音色库/插件/混音器结构、不能改工程文件名。").append('\n');
        b.append("5. 不能输出 JSON 以外的内容；不能使用未列出的字段。").append('\n');
        b.append('\n');
        b.append("【调音原则】").append('\n');
        b.append("- 先理解歌词分句与重音，再决定曲线，不要机械地给每个音加同样处理。").append('\n');
        b.append("- 长音：轻微渐弱 + 延迟进入的颤音；短音：干净、少颤音。").append('\n');
        b.append("- 连音：用 POR/PIT 做小滑音；跳进：提前 1–2 个 tick 起滑。").append('\n');
        b.append("- 句首句尾：BRE 略高、DYN 略低；高音：DYN 不要顶满，避免刺耳。").append('\n');
        b.append("- 所有改动以“自然、像真人演唱”为准，宁可少改也不要过度。").append('\n');
        b.append('\n');
        b.append("如果你能联网/使用搜索，请先查找这首歌（或同类中文虚拟歌手翻调）的调音视频、教程与成品，参考它们的调音标准，再给出调整：").append('\n');
        b.append("- 音高曲线：滑音、颤音、转音的幅度与时机；").append('\n');
        b.append("- 力度与气息：重音、连断、呼吸位置；").append('\n');
        b.append("- 咬字与辅音：清晰但不生硬。").append('\n');
        b.append("目标：调音后声音更自然、演唱连贯、没有违和感；避免过度修音、机械感或与原曲风格不符的处理。").append('\n');
        b.append("如果检索不到直接可用的参考，请按中文流行演唱的通用习惯处理。").append('\n');
        b.append('\n');
        b.append("【输出格式：必须严格满足，否则整份回复作废】").append('\n');
        b.append("1. 只输出 1 个 JSON 对象。第一个字符必须是 {，最后一个字符必须是 }。").append('\n');
        b.append("2. 禁止任何其他内容：不要 Markdown、不要代码块包裹、不要注释、不要解释、不要前后缀文字、不要多段 JSON。").append('\n');
        b.append("3. 禁止：单引号字符串、尾随逗号、NaN、Infinity、undefined、null、科学计数法、字符串形式的数字、重复键、未列出的字段。").append('\n');
        b.append("4. 顶层只允许这 5 个键（都可省略，省略=不改）：curves、notes、tempo、trackVolume、trackPan。").append('\n');
        b.append("5. curves：对象。键只能是 DYN/BRE/BRI/CLE/OPE/GEN/POR/PIT/PBS/VIB/XSY/GWL 之一；值必须是数组，元素只能是 [tick, value] 两个数字组成的数组。").append('\n');
        b.append("   - tick：非负整数（可为 0）；value：有限数字；同一参数内 tick 必须严格递增、不得重复。").append('\n');
        b.append("   - 数值范围：DYN/BRE/BRI/CLE/OPE/GEN/POR/VIB/XSY/GWL 为 0–127；PIT 为 -2400–2400；PBS 为 0–24。").append('\n');
        b.append("   - 想清空某参数曲线可给空数组 []；不要用对象、字符串或 [tick,value,extra]。").append('\n');
        b.append("6. notes：数组。每项必须是对象，只允许 pos、key、dur、vel、lyric、phoneme 这 6 个键，pos 必填。").append('\n');
        b.append("   - pos：必须是上面已有音符的 tick（整数），不得新增、不得删除、不得重复。").append('\n');
        b.append("   - key：整数 0–127；dur：整数 ≥60；vel：整数 0–127；lyric：字符串；phoneme：字符串（可为空）。").append('\n');
        b.append("   - 不要输出 id、tick、velocity、style 等字段；不需要改的字段直接省略。").append('\n');
        b.append("7. tempo：数字 20–400（最多 2 位小数）；trackVolume / trackPan：整数 0–127。").append('\n');
        b.append("8. 没有任何修改时只输出 {}。").append('\n');
        b.append("9. 输出前自检：是否为单个对象、键名大小写是否完全一致、数字是否在范围内、notes.pos 是否都来自已有音符。").append('\n');
        b.append("示例（仅示意结构，不要照抄数值）：").append('\n');
        b.append("{\"curves\":{\"DYN\":[[0,80],[480,95]],\"PIT\":[[960,120]]},\"notes\":[{\"pos\":480,\"key\":64,\"dur\":480,\"vel\":90,\"lyric\":\"la\",\"phoneme\":\"l a\"}],\"tempo\":126.5,\"trackVolume\":100,\"trackPan\":64}").append('\n');
        return b.toString();
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

    /** readable project section for a prompt a human will copy (not the compact API JSON) */
    public static String partText(Song song, Part part, int trackIndex) {
        StringBuilder b = new StringBuilder();
        double bpm = song.tempos.isEmpty() ? 120.0 : song.tempos.get(0)[1] / 100.0;
        b.append("速度：").append(bpm).append(" BPM；当前片段起点：").append(part.pos)
                .append(" tick；长度：").append(part.dur).append(" tick；音轨序号：").append(trackIndex + 1).append('\n');
        b.append("音符（tick,dur,key,vel,lyric,phoneme）：").append('\n');
        List<Note> ns = new ArrayList<Note>(part.notes);
        java.util.Collections.sort(ns, new java.util.Comparator<Note>() {
            public int compare(Note a, Note b) { return Long.compare(a.pos, b.pos); }
        });
        for (int i = 0; i < ns.size() && i < 256; i++) {
            Note n = ns.get(i);
            b.append("  ").append(n.pos).append(',').append(n.dur).append(',').append(n.key).append(',')
                    .append(n.vel).append(',').append(n.lyric == null ? "" : n.lyric).append(',')
                    .append(n.phoneme == null ? "" : n.phoneme).append('\n');
        }
        b.append("已有调音曲线（tick,value）：").append('\n');
        boolean any = false;
        for (String id : Params.IDS) {
            ControlCurve c = part.ctrlOrNull(id);
            if (c == null || c.pts.isEmpty()) continue;
            any = true;
            b.append("  ").append(id).append(": ");
            for (int i = 0; i < c.pts.size() && i < 128; i++) {
                ControlCurve.P pt = c.pts.get(i);
                if (i > 0) b.append(' ');
                b.append('[').append(pt.t).append(',').append(Math.round(pt.v * 100) / 100.0).append(']');
            }
            b.append('\n');
        }
        if (!any) b.append("  （当前片段没有已写曲线）").append('\n');
        return b.toString();
    }

    /** the whole prompt a user can copy into any chat UI (detailed rules + this part + optional request) */
    public static String fullPrompt(Song song, Part part, int trackIndex, String request) {
        StringBuilder b = new StringBuilder();
        b.append(systemPrompt());
        b.append('\n');
        b.append("【当前工程数据】").append('\n');
        b.append(partText(song, part, trackIndex)).append('\n');
        if (request != null && request.trim().length() > 0) {
            b.append('\n');
            b.append("【用户要求】").append('\n');
            b.append(request.trim()).append('\n');
        }
        b.append('\n');
        b.append("只输出一个 JSON 对象，不要任何解释。");
        return b.toString();
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