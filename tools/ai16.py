# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
t = root + r'\app\src\com\dsh\v4tune\ai\AiTuner.java'
s = io.open(t, encoding='utf-8').read()
old = "                \"messages\", \"[\" + Json.obj(\"role\", \"system\", \"content\", systemPrompt()) + \",\"\n                        + Json.obj(\"role\", \"user\", \"content\", userContent) + \"]\");"
new = "                \"messages\", Json.raw(\"[\" + Json.obj(\"role\", \"system\", \"content\", systemPrompt()) + \",\"\n                        + Json.obj(\"role\", \"user\", \"content\", userContent) + \"]\"));"
if old not in s: raise SystemExit('messages anchor missing')
s = s.replace(old, new, 1)
io.open(t, 'w', encoding='utf-8', newline='').write(s)
print('messages fixed')
e = root + r'\app\src\com\dsh\v4tune\ai\ExtApi.java'
u = io.open(e, encoding='utf-8').read()
u = u.replace('"actions", Json.arr(java.util.Arrays.asList(', '"actions", Json.raw(Json.arr(java.util.Arrays.asList(')
u = u.replace('"aiTune", "aiApply")));', '"aiTune", "aiApply", "aiConfig"))));')
io.open(e, 'w', encoding='utf-8', newline='').write(u)
print('help array fixed')