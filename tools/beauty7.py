# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
old = '''        new AlertDialog.Builder(this)
                .setTitle("删除哪个声库？")
                .setItems(names, new DialogInterface.OnClickListener() {'''
new = '''        menu(L.t("dlg.pack.delete.title"), null, names, -1, new DialogInterface.OnClickListener() {'''
if old not in s: raise SystemExit('delete dialog anchor missing')
s = s.replace(old, new, 1)
# the block ends with the old negative button + show; drop them (menu() shows itself)
j = s.find(new)
end = s.find('.setNegativeButton("关闭", null)\n                .show();', j)
if end < 0: raise SystemExit('end anchor missing')
s = s[:end] + '});' + s[end + len('.setNegativeButton("关闭", null)\n                .show();'):]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('delete-pack menu converted')