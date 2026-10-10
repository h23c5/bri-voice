# -*- coding: utf-8 -*-
import io
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
u = io.open(L, encoding='utf-8').read()
rows = [["status.wav.added.atstart","已导入 %s（%.1f 秒）——播放头在曲尾，已放到开头","Imported %s (%.1f s) - the cursor was past the end, so it was placed at the start","%s を読み込みました（%.1f 秒）— カーソルが末尾だったため先頭に配置しました"],["dlg.wav.position","起始位置（拍）","Start position (beats)","開始位置（拍）"],["dlg.wav.position.msg","导入的音频放在哪里？可以直接填数字，也可以在卷帘下方的音轨里拖动它。","Where should the audio start? Type a beat, or drag the block in the audio lane under the roll.","音声の開始位置を指定してください。数値を入力するか、ロール下の音声レーンでドラッグできます。"]]
add = []
for r in rows:
    if ('"' + r[0] + '"') in u: continue
    add.append('        {"' + r[0] + '", ' + ', '.join('"' + v + '"' for v in r[1:]) + '},')
if add:
    i = u.find('{"status.wav.added"')
    j = u.rfind(chr(10), 0, i) + 1
    u = u[:j] + chr(10).join(add) + chr(10) + u[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(u)
print('keys added:', len(add))