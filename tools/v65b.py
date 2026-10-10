# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
Lf = root + r'\app\src\com\dsh\v4tune\i18n\L.java'
u = io.open(Lf, encoding='utf-8').read()
old = [l for l in u.split(chr(10)) if l.strip().startswith('{"set.mnoise.gain"')]
if old:
    row = '        {"set.mnoise.gain", "实测谱强度：%s", "Measured spectrum level: %s", "実測スペクトル強度：%s"},'
    u = u.replace(old[0], row, 1)
    io.open(Lf, 'w', encoding='utf-8', newline='').write(u)
    print('key switched to %s')
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
t = io.open(m, encoding='utf-8').read()
t = t.replace('L.t("set.mnoise.gain", Integer.valueOf(Math.round(Renderer.noiseGain * 100)))',
              'L.t("set.mnoise.gain", Integer.valueOf(Math.round(Renderer.noiseGain * 100)) + "%")', 1)
t = t.replace('mnGainLabel.setText(L.t("set.mnoise.gain", Integer.valueOf(pr)));',
              'mnGainLabel.setText(L.t("set.mnoise.gain", Integer.valueOf(pr) + "%"));', 1)
io.open(m, 'w', encoding='utf-8', newline='').write(t)
print('callers updated')