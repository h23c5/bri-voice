# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(m, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)
if 'mnoise' not in s:
    rep('        cursorOffsetMs = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("cursorOffsetMs", 0);',
        '        cursorOffsetMs = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("cursorOffsetMs", 0);' + chr(10) +
        '        Renderer.measuredNoise = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("mnoise", false);',
        'load flag')
    rep('        box.addView(lock);', "        box.addView(lock);\n\n        // experimental: sing fricatives with the spectrum the voicebank actually measured\n        final CheckBox mnoise = new CheckBox(this);\n        mnoise.setText(L.t(\"set.mnoise\"));\n        mnoise.setChecked(Renderer.measuredNoise);\n        box.addView(mnoise);\n        TextView mnDesc = new TextView(this);\n        mnDesc.setText(L.t(\"set.mnoise.desc\"));\n        mnDesc.setTextColor(Theme.TEXT_DIM);\n        mnDesc.setTextSize(12f);\n        mnDesc.setPadding(dp(4), 0, 0, dp(6));\n        box.addView(mnDesc);", 'toggle')
    rep('                        phonemeLock = lock.isChecked();', "                        Renderer.measuredNoise = mnoise.isChecked();\n                        getSharedPreferences(\"brivoice\", MODE_PRIVATE).edit()\n                                .putBoolean(\"mnoise\", Renderer.measuredNoise).apply();\n                        phonemeLock = lock.isChecked();", 'save toggle')
    io.open(m, 'w', encoding='utf-8', newline='').write(s)
Lf = root + r'\app\src\com\dsh\v4tune\i18n\L.java'
u = io.open(Lf, encoding='utf-8').read()
rows = [('set.mnoise', '辅音实测谱（实验）', 'Measured consonant spectra (experimental)', '子音の実測スペクトル（実験的）'),
        ('set.mnoise.desc', '用声库实测到的辅音频谱合成擦音（s/x/f 等），听感可能更接近原声但不一定更清楚，默认关闭。', 'Sing fricatives with the bank\'s own measured spectrum. Closer to the source timbre, not necessarily clearer; off by default.', 'バンクの実測スペクトルで摩擦音を合成。音色は近くなりますが、必ずしも聞きやすくはなりません。')]
add = []
for r in rows:
    if ('"' + r[0] + '"') in u: continue
    add.append('        {"' + r[0] + '", ' + ', '.join('"' + v + '"' for v in r[1:]) + '},')
if add:
    k = u.find('{"set.offset"')
    j = u.rfind(chr(10), 0, k) + 1
    u = u[:j] + chr(10).join(add) + chr(10) + u[j:]
    io.open(Lf, 'w', encoding='utf-8', newline='').write(u)
print('keys added:', len(add))