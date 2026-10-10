# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
# checkOverlaps lost its .show(); put it back
old = '''                .setNeutralButton(L.t("dlg.overlap.jump"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { jumpToIssue(is.get(0)); }
                })
                });'''
new = '''                .setNeutralButton(L.t("dlg.overlap.jump"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { jumpToIssue(is.get(0)); }
                })
                .show();'''
if old not in s: raise SystemExit('overlap block not found')
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('overlap dialog restored')
i = s.find('menu(L.t("dlg.pack.delete.title")')
print('--- delete-pack block ---')
print(s[i-40:i+900])