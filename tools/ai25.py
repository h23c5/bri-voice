# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(m, encoding='utf-8').read()

# 1) the shortcut must be refreshed after the UI exists
old = '        if (SingerLibrary.isEmpty()) {'
new = '        updateAiButton();          // the workspace shortcut only exists once the UI is built' + chr(10) + '        if (SingerLibrary.isEmpty()) {'
if 'updateAiButton();          // the workspace shortcut' not in s:
    if old not in s: raise SystemExit('onCreate anchor missing')
    s = s.replace(old, new, 1)
    print('startup refresh added')

# 2) aiApply takes the whole tuning object
old2 = '''                } else if (args.get("curves") != null) {
                    String raw = com.dsh.v4tune.ai.Json.obj("curves", com.dsh.v4tune.ai.Json.raw(String.valueOf(args.get("curves"))));
                    curves = com.dsh.v4tune.ai.AiTuner.parseCurves(raw, p.dur + p.pos);
                } else {'''
new2 = '''                } else if (args.get("__raw") != null) {
                    // a full tuning object: curves + notes + tempo + track
                    com.dsh.v4tune.ai.Tuning t = com.dsh.v4tune.ai.AiTuner.parseTuning(
                            String.valueOf(args.get("__raw")), p.dur + p.pos);
                    int n = applyTuning(t, 1.0f);
                    return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "applied", Integer.valueOf(n),
                            "summary", t.summary());
                } else if (args.get("curves") != null) {
                    String raw = com.dsh.v4tune.ai.Json.obj("curves", com.dsh.v4tune.ai.Json.raw(String.valueOf(args.get("curves"))));
                    curves = com.dsh.v4tune.ai.AiTuner.parseCurves(raw, p.dur + p.pos);
                } else {'''
if old2 not in s: raise SystemExit('aiApply block missing')
s = s.replace(old2, new2, 1)
io.open(m, 'w', encoding='utf-8', newline='').write(s)
print('aiApply extended')

# 3) ExtApi passes the raw body through
e = root + r'\app\src\com\dsh\v4tune\ai\ExtApi.java'
u = io.open(e, encoding='utf-8').read()
oldE = '                args.remove("action");'
newE = '                args.remove("action");' + chr(10) + '                args.put("__raw", body);   // some commands re-parse the original JSON'
if '__raw' not in u:
    u = u.replace(oldE, newE, 1)
    io.open(e, 'w', encoding='utf-8', newline='').write(u)
    print('raw body passed through')