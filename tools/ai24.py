# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
def cut_method(src, sig):
    i = src.find(sig)
    if i < 0: return src, False
    # include the javadoc directly above
    j = src.rfind(chr(10), 0, i)
    k = src.rfind('/**', max(0, i - 400), i)
    start = i if k < 0 else src.rfind(chr(10), 0, k) + 1
    depth = 0
    p = src.find('{', i)
    q = p
    while q < len(src):
        if src[q] == '{': depth += 1
        elif src[q] == '}':
            depth -= 1
            if depth == 0: break
        q += 1
    end = src.find(chr(10), q) + 1
    return src[:start] + src[end:], True

t = root + r'\app\src\com\dsh\v4tune\ai\AiTuner.java'
s = io.open(t, encoding='utf-8').read()
s, a = cut_method(s, 'public static Map<String, double[][]> parseCurves(String reply, long maxTick) {')
s, b = cut_method(s, 'private static double toD(Object o) {')
io.open(t, 'w', encoding='utf-8', newline='').write(s)
print('removed old parseCurves:', a, 'old toD:', b)
print('parseCurves now:', s.count('parseCurves('), 'toD now:', s.count('private static double toD'))

m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
u = io.open(m, encoding='utf-8').read()
if 'private void updateAiButton()' not in u:
    anchor = '    /** the quick-tune sheet: describe what you want, the assistant edits the part */'
    add = ('    /** shows the AI shortcut only while the experimental assistant is switched on */' + chr(10) +
           '    private void updateAiButton() {' + chr(10) +
           '        if (aiBtn == null) return;' + chr(10) +
           '        aiBtn.setVisibility(aiCfg != null && aiCfg.aiEnabled ? View.VISIBLE : View.GONE);' + chr(10) +
           '    }' + chr(10) + chr(10))
    if anchor not in u: raise SystemExit('anchor missing')
    u = u.replace(anchor, add + anchor, 1)
    io.open(m, 'w', encoding='utf-8', newline='').write(u)
    print('updateAiButton added')
else:
    print('updateAiButton already present')