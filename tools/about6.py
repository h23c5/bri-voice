# -*- coding: utf-8 -*-
import io, re
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
# requireVoice(): drop the hardcoded duplicate, use the localised message
old = s[s.find('                .setMessage("BriVoice \u4e0d\u5185\u7f6e\u4efb\u4f55\u97f3\u8272'):]
i = s.find('.setTitle("\u8fd8\u6ca1\u6709\u58f0\u5e93")')
j = s.find('.setPositiveButton("\u53bb\u5bfc\u5165"', i)
if i > 0 and j > i:
    s = s[:i] + '.setTitle(L.t("dlg.no.voice.title"))\n                .setMessage(L.t("dlg.no.voice.msg"))\n                ' + s[j:]
# packHelp(): use the localised body
i2 = s.find('private void packHelp() {')
j2 = s.find('.setPositiveButton("\u597d", null)', i2)
if i2 > 0 and j2 > i2:
    s = s[:i2] + 'private void packHelp() {\n        new AlertDialog.Builder(this)\n                .setTitle(L.t("dlg.pack.help.title"))\n                .setMessage(L.t("dlg.pack.help.msg"))\n                ' + s[j2:]
    s = s.replace('.setPositiveButton("\u597d", null)', '.setPositiveButton(L.t("dlg.ok"), null)', 1) if False else s
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('dialogs localised')