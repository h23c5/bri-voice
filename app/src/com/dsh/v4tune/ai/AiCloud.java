package com.dsh.v4tune.ai;

import com.dsh.v4tune.format.VsqxReader;
import com.dsh.v4tune.format.VsqxWriter;
import com.dsh.v4tune.model.Song;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Cloud save against a backend the user provides.
 *
 * <p>Protocol is deliberately tiny, so a single serverless function is enough:</p>
 * <pre>
 * POST &lt;cloudUrl&gt;   Authorization: Bearer &lt;token&gt; (optional)
 *   {"format":"brivoice-project-v1","name":"...","project":"&lt;VSQX xml&gt;"}
 * GET  &lt;cloudUrl&gt;   Authorization: Bearer &lt;token&gt; (optional)
 *   -> {"project":"&lt;VSQX xml&gt;"}    (a bare VSQX body is accepted too)
 * </pre>
 *
 * <p>The app never talks to a BriVoice server: the URL and token belong to the user.</p>
 */
public final class AiCloud {

    private AiCloud() { }

    public static String projectXml(Song s) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        VsqxWriter.write(s, bos);
        return new String(bos.toByteArray(), "UTF-8");
    }

    public static Song readProject(String text) throws Exception {
        if (text == null) throw new IllegalArgumentException("云端没有返回内容");
        String xml = null;
        String t = text.trim();
        if (t.startsWith("<?xml") || t.startsWith("<vsq")) {
            xml = t;
        } else {
            Map<String, Object> m = Json.asMap(Json.parse(t));
            if (m != null) xml = Json.str(m, "project", null);
            if (xml == null && m != null) xml = Json.str(m, "data", null);
        }
        if (xml == null || xml.trim().length() == 0) throw new IllegalArgumentException("返回里没有 project 字段");
        return VsqxReader.read(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    public static String save(String url, String token, String name, Song s) throws Exception {
        String body = "{\"format\":\"brivoice-project-v1\",\"name\":" + Json.quote(name == null ? "" : name)
                + ",\"project\":" + Json.quote(projectXml(s)) + "}";
        return send(url, token, "POST", body);
    }

    public static Song load(String url, String token) throws Exception {
        return readProject(send(url, token, "GET", null));
    }

    private static String send(String url, String token, String method, String body) throws Exception {
        String u = url == null ? "" : url.trim();
        if (u.length() == 0) throw new IllegalArgumentException("还没有填写云端地址");
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new IllegalArgumentException("云端地址必须以 http:// 或 https:// 开头");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (token != null && token.trim().length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + token.trim());
        }
        if (body != null) {
            byte[] payload = body.getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();
        }
        int code = c.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String text = read(in, 4 * 1024 * 1024);
        c.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + shortText(text));
        return text;
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
