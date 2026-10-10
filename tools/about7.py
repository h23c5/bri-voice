# -*- coding: utf-8 -*-
import io
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()
for k in ('dlg.no.voice.title', 'dlg.pack.help.title', 'dlg.ok'):
    print(k, ':', ('"' + k + '"') in s)
add = []
if '"dlg.no.voice.title"' not in s:
    add.append('        {"dlg.no.voice.title", "还没有声库", "No voicebank yet", "声庫がありません"},')
if add:
    i = s.find('{"dlg.no.voice.msg"')
    j = s.rfind('\n', 0, i) + 1
    s = s[:j] + '\n'.join(add) + '\n' + s[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(s)
    print('titles added:', len(add))
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
t = io.open(p, encoding='utf-8').read()
i2 = t.find('private void packHelp() {')
j2 = t.find('.setPositiveButton("好", null)', i2)
if i2 > 0 and j2 > i2:
    t = t[:j2] + '.setPositiveButton(L.t("dlg.ok"), null)' + t[j2 + len('.setPositiveButton("好", null)'):]
    io.open(p, 'w', encoding='utf-8', newline='').write(t)
    print('packHelp button localised')