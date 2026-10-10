package com.dsh.v4tune.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The experimental control interface for external code.
 *
 * <p>A tiny HTTP server bound to <b>127.0.0.1 only</b>: a script on the device, or a desktop
 * script through {@code adb forward}, can read the state and drive the editor with JSON. Nothing
 * is reachable from the network, and the server only runs while the user has switched it on.</p>
 *
 * <pre>
 *   GET  /status              -> {"ok":true, ...state...}
 *   GET  /help                -> the list of actions
 *   POST /command  {"action":"addNote","key":60,"pos":0,"dur":480,"lyric":"la"}
 *   POST /command  {"action":"setParam","param":"DYN","points":[[0,70],[960,50]]}
 *   POST /command  {"action":"play"|"stop"|"save"|"aiTune"}
 * </pre>
 */
public final class ExtApi {

    /** implemented by the activity; every method is called on the UI thread */
    public interface Handler {
        String statusJson();
        /** @return a JSON string describing the outcome */
        String command(String action, Map<String, Object> args) throws Exception;
    }

    private final ServerSocket server;
    private final Handler handler;
    private volatile boolean running = true;
    private Thread acceptor;

    private ExtApi(ServerSocket s, Handler h) {
        this.server = s;
        this.handler = h;
    }

    /** @throws IOException when the port is taken or cannot be bound */
    public static ExtApi start(int port, Handler h) throws IOException {
        ServerSocket s = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        final ExtApi api = new ExtApi(s, h);
        api.acceptor = new Thread(new Runnable() {
            public void run() { api.loop(); }
        }, "brivoice-extapi");
        api.acceptor.setDaemon(true);
        api.acceptor.start();
        return api;
    }

    public int port() { return server.getLocalPort(); }
    public boolean isRunning() { return running; }

    public void stop() {
        running = false;
        try { server.close(); } catch (Throwable ignored) { }
    }

    private void loop() {
        while (running) {
            Socket c = null;
            try {
                c = server.accept();
                c.setSoTimeout(15000);
                serve(c);
            } catch (Throwable t) {
                if (running && !(t instanceof IOException)) { /* keep serving */ }
            } finally {
                try { if (c != null) c.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private void serve(Socket c) throws Exception {
        InputStream in = c.getInputStream();
        OutputStream out = c.getOutputStream();
        String head = readHead(in);
        if (head == null) return;
        String[] lines = head.split("\r\n");
        String[] req = lines[0].split(" ");
        String method = req.length > 0 ? req[0] : "GET";
        String path = req.length > 1 ? req[1] : "/";
        int contentLength = 0;
        for (String l : lines) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).equalsIgnoreCase("Content-Length")) {
                try { contentLength = Integer.parseInt(l.substring(i + 1).trim()); } catch (Throwable ignored) { }
            }
        }
        String body = contentLength > 0 ? readBody(in, contentLength) : "";

        String json;
        int code = 200;
        try {
            if (path.startsWith("/status")) {
                json = handler.statusJson();
            } else if (path.startsWith("/help")) {
                json = Json.obj("ok", Boolean.TRUE, "endpoints", "/status, /help, /command",
                        "actions", Json.raw(Json.arr(java.util.Arrays.asList("status", "setParam", "addNote",
                                "deleteNote", "clearParam", "play", "stop", "save", "newProject",
                                "setLyrics", "aiTune", "aiApply", "aiConfig"))));
            } else if (path.startsWith("/command")) {
                Map<String, Object> root = Json.asMap(Json.parse(body));
                if (root == null) throw new IllegalArgumentException("body must be a JSON object");
                String action = Json.str(root, "action", "status");
                Map<String, Object> args = new LinkedHashMap<String, Object>(root);
                args.remove("action");
                args.put("__raw", body);   // some commands re-parse the original JSON
                json = handler.command(action, args);
            } else {
                code = 404;
                json = Json.obj("ok", Boolean.FALSE, "error", "unknown path");
            }
        } catch (Throwable t) {
            code = 400;
            String m = t.getMessage();
            json = Json.obj("ok", Boolean.FALSE, "error", m == null ? t.toString() : m);
        }
        byte[] payload = json.getBytes("UTF-8");
        String status = (code == 200) ? "200 OK" : (code == 404 ? "404 Not Found" : "400 Bad Request");
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(payload);
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        int n;
        while ((n = in.read()) >= 0) {
            b.write(n);
            if (n == '\r') { if (state == 0 || state == 2) state++; else state = 0; }
            else if (n == '\n') { if (state == 1) state = 2; else if (state == 3) break; else state = 0; }
            else state = 0;
            if (b.size() > 16384) break;
        }
        return b.size() == 0 ? null : new String(b.toByteArray(), "UTF-8");
    }

    private static String readBody(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int got = 0;
        while (got < len) {
            int n = in.read(buf, got, len - got);
            if (n <= 0) break;
            got += n;
        }
        return new String(buf, 0, got, "UTF-8");
    }
}