# -*- coding: utf-8 -*-
import io
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()
rows = [["dlg.pack.delete.title","删除哪个声库？","Delete which voicebank?","どの声庫を削除しますか？"],["dlg.close","关闭","Close","閉じる"]]
add = []
for r in rows:
    if ('"' + r[0] + '"') in s: continue
    add.append('        {"' + r[0] + '", ' + ', '.join('"' + v + '"' for v in r[1:]) + '},')
if add:
    i = s.find('{"dlg.singer.title"')
    j = s.rfind('\n', 0, i) + 1
    s = s[:j] + '\n'.join(add) + '\n' + s[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(s)
print('keys added:', len(add))