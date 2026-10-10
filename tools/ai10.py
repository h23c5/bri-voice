# -*- coding: utf-8 -*-
import io, re
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'

# 1) extractJson: first object that actually parses as a JSON map
t = root + r'\app\src\com\dsh\v4tune\ai\AiTuner.java'
s = io.open(t, encoding='utf-8').read()
start = s.find('    /** pulls the first complete JSON object')
end = s.find('    /**', start + 10)
assert start > 0 and end > start, 'extractJson block'
new = '''    /**
     * Pulls the JSON object out of a reply that may be wrapped in prose or code fences. Only an
     * object that really parses as a JSON map counts, so a stray pair of braces in the prose is
     * skipped instead of being handed on as if it were the answer.
     */
    public static String extractJson(String reply) {
        if (reply == null) return null;
        int at = reply.indexOf('{');
        while (at >= 0) {
            String cand = balanced(reply, at);
            if (cand != null && Json.asMap(Json.parse(cand)) != null) return cand;
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
                else if (c == '\\\\') esc = true;
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

'''
s = s[:start] + new + s[end:]
io.open(t, 'w', encoding='utf-8', newline='').write(s)
print('extractJson hardened')

# 2) key rename + 3) the strength string must not use %%
L = root + r'\app\src\com\dsh\v4tune\i18n\L.java'
u = io.open(L, encoding='utf-8').read()
u = u.replace('"ai.baseUrl"', '"ai.baseurl"')
u = re.sub(r'\{"ai.strength", "[^"]*", "[^"]*", "[^"]*"\},',
           '{"ai.strength", "\u8c03\u6574\u5e45\u5ea6\uff1a%s\uff08\u8d8a\u5927\u6539\u52a8\u8d8a\u660e\u663e\uff09", "Edit strength: %s (higher means bigger changes)", "\u8abf\u6574\u306e\u5f37\u3055\uff1a%s\uff08\u5927\u304d\u3044\u307b\u3069\u5909\u5316\u304c\u5927\u304d\u3044\uff09"},', u, count=1)
io.open(L, 'w', encoding='utf-8', newline='').write(u)
print('keys fixed')

# the code that uses them
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
c = io.open(m, encoding='utf-8').read()
c = c.replace('L.t("ai.baseUrl")', 'L.t("ai.baseurl")')
c = c.replace('st.setText(L.t("ai.strength", Integer.valueOf(Math.round(aiCfg.strength * 100))));',
              'st.setText(L.t("ai.strength", Math.round(aiCfg.strength * 100) + "%"));')
c = c.replace('st.setText(L.t("ai.strength", Integer.valueOf(pr)));',
              'st.setText(L.t("ai.strength", pr + "%"));')
io.open(m, 'w', encoding='utf-8', newline='').write(c)
print('call sites fixed')