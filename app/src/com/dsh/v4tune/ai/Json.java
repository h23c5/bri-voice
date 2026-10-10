package com.dsh.v4tune.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A very small JSON reader/writer: enough for API payloads, with no dependency. */
public final class Json {

    private Json() { }

    // ------------------------------------------------------------------ read

    public static Object parse(String s) {
        if (s == null) return null;
        P p = new P(s);
        p.ws();
        Object v = p.value();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

@SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return (o instanceof List) ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String k, String dflt) {
        if (m == null) return dflt;
        Object v = m.get(k);
        return (v instanceof String) ? (String) v : dflt;
    }

    public static double num(Map<String, Object> m, String k, double dflt) {
        if (m == null) return dflt;
        Object v = m.get(k);
        if (v instanceof Double) return (Double) v;
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (Throwable ignored) { }
        }
        return dflt;
    }

    private static final class P {
        final String s;
        int i;
        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') break;
                String k = string();
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return m;
        }

        List<Object> array() {
            ArrayList<Object> l = new ArrayList<Object>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (i < s.length()) {
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return l;
        }

        String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u':
                            if (i + 4 <= s.length()) {
                                try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (Throwable ignored) { }
                                i += 4;
                            }
                            break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
            return b.toString();
        }

        Double number() {
            int st = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) i++;
                else break;
            }
            try { return Double.valueOf(Double.parseDouble(s.substring(st, i))); } catch (Throwable t) { return Double.valueOf(0); }
        }
    }

    // ----------------------------------------------------------------- write

    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** a pre-built JSON fragment, written verbatim instead of being quoted */
    public static final class Raw {
        final String s;
        Raw(String s) { this.s = s; }
    }

    public static Raw raw(String s) { return new Raw(s == null ? "null" : s); }

    public static String obj(Object... kv) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (i > 0) b.append(',');
            b.append(quote(String.valueOf(kv[i]))).append(':');
            Object v = kv[i + 1];
            if (v == null) b.append("null");
            else if (v instanceof Raw) b.append(((Raw) v).s);
            else if (v instanceof Number || v instanceof Boolean) b.append(String.valueOf(v));
            else b.append(quote(String.valueOf(v)));
        }
        return b.append('}').toString();
    }

    public static String arr(java.util.Collection<?> items) {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Object v : items) {
            if (!first) b.append(',');
            first = false;
            if (v == null) b.append("null");
            else if (v instanceof Number || v instanceof Boolean) b.append(String.valueOf(v));
            else b.append(quote(String.valueOf(v)));
        }
        return b.append(']').toString();
    }
}