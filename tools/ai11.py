# -*- coding: utf-8 -*-
import io
t = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\ai\AiTuner.java'
s = io.open(t, encoding='utf-8').read()
old = '            if (cand != null && Json.asMap(Json.parse(cand)) != null) return cand;'
new = ('            if (cand != null) {' + chr(10) +
       '                java.util.Map<String, Object> m = Json.asMap(Json.parse(cand));' + chr(10) +
       '                if (m != null && !m.isEmpty()) return cand;   // {} is not an answer' + chr(10) +
       '            }')
if old not in s: raise SystemExit('anchor missing')
s = s.replace(old, new, 1)
io.open(t, 'w', encoding='utf-8', newline='').write(s)
print('extractJson requires a non-empty object')