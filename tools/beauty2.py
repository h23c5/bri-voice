# -*- coding: utf-8 -*-
import io, re
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()
rows = [["dlg.singer.title","选择歌姬","Choose a Singer","歌手を選択"],["dlg.singer.sub","已安装 %d 个声库 · 点击切换","%d voicebanks installed · tap to switch","%d 個の声庫 · タップで切り替え"],["dlg.singer.stats","实测谐波 %d 音素 / %d 频段","measured %d phonemes / %d bands","実測 %d 音素／%d バンド"],["dlg.singer.legacy","旧格式声库（无实测谐波）","Legacy pack (no measured harmonics)","旧形式の声庫（実測倍音なし）"],["dlg.singer.none","尚未安装声库，请先导入","No voicebank installed yet","声庫が未インストールです"],["status.singer","当前歌姬：%s","Current singer: %s","現在の歌手：%s"],["menu.pack.title","声库管理","Voicebanks","声庫管理"],["menu.pack.sub","已导入 %d 个外部声库","%d imported voicebanks","外部声庫 %d 個"]]
add = []
for r in rows:
    if ('"' + r[0] + '"') in s:
        continue
    add.append('        {"' + r[0] + '", ' + ', '.join('"' + v + '"' for v in r[1:]) + '},')
if add:
    i = s.find('{"menu.tools"')
    if i < 0: raise SystemExit('anchor missing')
    j = s.rfind('\n', 0, i) + 1
    s = s[:j] + '\n'.join(add) + '\n' + s[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(s)
print('keys added:', len(add))