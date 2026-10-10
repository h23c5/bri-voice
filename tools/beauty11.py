# -*- coding: utf-8 -*-
import io
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()
rows = [["dlg.pack.del.confirm.title","删除声库","Delete voicebank","声庫を削除"],["dlg.pack.del.confirm.msg","确定删除「%s」吗？\n只会删除这个包文件，不影响内置声库。","Delete '%s'?\nOnly this pack file is removed; bundled voicebanks are unaffected.","「%s」を削除しますか？\nこのパックファイルのみ削除され、同梱の声庫には影響しません。"],["dlg.delete","删除","Delete","削除"],["status.pack.deleted","已删除声库：%s","Voicebank deleted: %s","声庫を削除しました：%s"],["status.pack.del.fail","删除失败","Delete failed","削除に失敗しました"]]
add = []
for r in rows:
    if ('"' + r[0] + '"') in s: continue
    add.append('        {"' + r[0] + '", ' + ', '.join('"' + v.replace(chr(10), '\\n') + '"' for v in r[1:]) + '},')
if add:
    i = s.find('{"dlg.pack.delete.title"')
    j = s.rfind('\n', 0, i) + 1
    s = s[:j] + '\n'.join(add) + '\n' + s[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(s)
print('keys added:', len(add))