# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
# 1) calibrate the frame path level to the table path (-7.4 dB measured -> x2.34)
p = root + r'\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
if 'FRAME_GAIN = 0.55;' in s:
    s = s.replace('FRAME_GAIN = 0.55;', 'FRAME_GAIN = 1.29;   // measured: matches the table path level', 1)
    print('ok: level calibrated')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
# 2) A/B switch in the settings screen
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
t = io.open(m, encoding='utf-8').read()
def rep(old, new, what, txt=None):
    global t
    if old not in (txt if txt is not None else t): raise SystemExit('anchor missing: ' + what)
    t = (txt if txt is not None else t).replace(old, new, 1)
    print('ok:', what)
if 'frameSeqChk' not in t:
    rep('        Renderer.measuredNoise = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("mnoise", false);',
        '        Renderer.measuredNoise = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("mnoise", false);' + chr(10) +
        '        Renderer.frameSequence = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("frameseq", true);',
        'load frame flag')
    rep('        box.addView(mnoise);', "        box.addView(mnoise);\n\n        final CheckBox frameSeqChk = new CheckBox(this);\n        frameSeqChk.setText(L.t(\"set.frameseq\"));\n        frameSeqChk.setChecked(Renderer.frameSequence);\n        box.addView(frameSeqChk);\n        TextView fsDesc = new TextView(this);\n        fsDesc.setText(L.t(\"set.frameseq.desc\"));\n        fsDesc.setTextColor(Theme.TEXT_DIM);\n        fsDesc.setTextSize(12f);\n        fsDesc.setPadding(dp(4), 0, 0, dp(6));\n        box.addView(fsDesc);", 'toggle widget')
    rep('                        Renderer.measuredNoise = mnoise.isChecked();', "                        Renderer.frameSequence = frameSeqChk.isChecked();\n                        getSharedPreferences(\"brivoice\", MODE_PRIVATE).edit()\n                                .putBoolean(\"frameseq\", Renderer.frameSequence).apply();\n                        Renderer.measuredNoise = mnoise.isChecked();", 'save frame flag')
    io.open(m, 'w', encoding='utf-8', newline='').write(t)
Lf = root + r'\app\src\com\dsh\v4tune\i18n\L.java'
u = io.open(Lf, encoding='utf-8').read()
rows = [('set.frameseq', '帧序列拼接（新合成器）', 'Frame-sequence synthesis (new engine)', 'フレーム接続合成（新エンジン）'),
        ('set.frameseq.desc', '用声库实测的逐帧谱线控制每个谐波，保留原唱的微变化。关闭则回到旧的单周期波表。', 'Each harmonic follows the bank\'s measured frame curves, keeping the micro-variation of the original take. Off falls back to the old single-period table.', '各谐波をバンクの実測フレームで制御し、元音声の微変化を保ちます。')]
add = []
for r in rows:
    if ('"' + r[0] + '"') in u: continue
    add.append('        {"' + r[0] + '", ' + ', '.join('"' + v + '"' for v in r[1:]) + '},')
if add:
    k = u.find('{"set.mnoise"')
    j = u.rfind(chr(10), 0, k) + 1
    u = u[:j] + chr(10).join(add) + chr(10) + u[j:]
    io.open(Lf, 'w', encoding='utf-8', newline='').write(u)
print('keys added:', len(add))