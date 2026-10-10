# -*- coding: utf-8 -*-
import io, re
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(p, encoding='utf-8').read()
row = ["set.phoneme.desc","关闭时：修改歌词会重新按歌词生成音素。开启后：修改歌词会保留这个音符已有的音素，并把它标记为锁定（写进 .vsqx 的 lock 属性）。","Off: editing a lyric regenerates the phonemes from the new lyric. On: editing a lyric keeps the note's existing phonemes and marks them locked (written to the .vsqx lock attribute).","オフ：歌詞を変更すると音素は歌詞から再生成されます。オン：歌詞を変更しても既存の音素を保持し、ロックとして .vsqx の lock 属性に書き出します。"]
line = '        {"' + row[0] + '", ' + ', '.join('"' + v + '"' for v in row[1:]) + '},'
pat = re.compile(r'        \{"set\.phoneme\.desc", .*?\},')
s2, n = pat.subn(lambda m: line, s, count=1)
if n == 0: raise SystemExit('row not found')
io.open(p, 'w', encoding='utf-8', newline='').write(s2)
print('desc row fixed')
# sanity: no row may contain an unescaped quote inside its text
bad = [l for l in s2.split(chr(10)) if l.strip().startswith('{"') and l.count('"') % 2 == 1]
print('rows with odd quote count:', len(bad))