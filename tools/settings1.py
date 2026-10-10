# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
# 1) the setting field + load
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(m, encoding='utf-8').read()
if 'private boolean phonemeLock' not in s:
    s = s.replace('    private Button aiBtn;', '    private Button aiBtn;' + chr(10) + '    /** keeps a note phoneme when its lyric is edited; off unless enabled in settings */' + chr(10) + '    private boolean phonemeLock;', 1)
    s = s.replace('        aiCfg = com.dsh.v4tune.ai.AiConfig.load(this);',
                  '        aiCfg = com.dsh.v4tune.ai.AiConfig.load(this);' + chr(10) +
                  '        phonemeLock = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("phonemeLock", false);', 1)
    print('field + load added')
# 2) the methods
blk = io.open(root + r'\tools\settings_methods.txt', encoding='utf-8').read()
anchor = '    /** the experimental panel: switches, endpoint, key, port, manual tune button */'
if 'private void settingsDialog()' not in s:
    if anchor not in s: raise SystemExit('anchor missing')
    s = s.replace(anchor, blk + anchor, 1)
    print('settingsDialog inserted')
# 3) the tools menu entry + dispatch
s = s.replace('                L.t("menu.tools.ai")' + chr(10) + '        };', '                L.t("menu.tools.ai"),' + chr(10) + '                L.t("menu.tools.settings")' + chr(10) + '        };', 1)
s = s.replace('                else if (which == 8) toggleOrientation();' + chr(10) + '                else aiDialog();', '                else if (which == 8) toggleOrientation();' + chr(10) + '                else if (which == 9) aiDialog();' + chr(10) + '                else settingsDialog();', 1)
# 4) editLyric honours the setting, and the clear button really clears the lock
old = '''                        beginEdit();
                        n.lyric = et.getText().toString().trim();
                        n.phoneme = "";
                        commitEdit();
                        roll.refresh();
                        String ph = com.dsh.v4tune.synth.Renderer.resolvePhonemes(n, currentVoice).toString();
                        status("歌词 = " + n.lyric + "  \u2192  \u97f3\u7d20 " + ph);'''
new = '''                        beginEdit();
                        n.lyric = et.getText().toString().trim();
                        boolean kept = false;
                        if (phonemeLock && n.phoneme != null && n.phoneme.trim().length() > 0) {
                            n.lockPhoneme = true;      // keep the phonemes this note already sings
                            kept = true;
                        } else {
                            n.phoneme = "";           // default: regenerate from the new lyric
                            n.lockPhoneme = false;
                        }
                        commitEdit();
                        roll.refresh();
                        if (kept) {
                            status(L.t("status.lyric.locked", n.lyric));
                        } else {
                            String ph = com.dsh.v4tune.synth.Renderer.resolvePhonemes(n, currentVoice).toString();
                            status(L.t("status.lyric.set", n.lyric, ph));
                        }'''
if old in s:
    s = s.replace(old, new, 1)
    print('editLyric updated')
else:
    print('WARN editLyric block not matched')
s = s.replace('.setTitle("\u6b4c\u8bcd\uff08\u62fc\u97f3 / \u6c49\u5b57 / \u5047\u540d\uff09")', '.setTitle(L.t("dlg.lyric.title"))', 1)
s = s.replace('.setPositiveButton("\u786e\u5b9a", new DialogInterface.OnClickListener() {', '.setPositiveButton(L.t("dlg.lyric.ok"), new DialogInterface.OnClickListener() {', 1)
s = s.replace('.setNeutralButton("\u6e05\u7a7a\u97f3\u7d20\u9501\u5b9a", new DialogInterface.OnClickListener() {' + chr(10) + '                    public void onClick(DialogInterface d, int w) { n.phoneme = ""; roll.refresh(); }',
              '.setNeutralButton(L.t("dlg.lyric.clear.lock"), new DialogInterface.OnClickListener() {' + chr(10) + '                    public void onClick(DialogInterface d, int w) { n.phoneme = ""; n.lockPhoneme = false; roll.refresh(); status(L.t("set.phoneme.state.off")); }', 1)
io.open(m, 'w', encoding='utf-8', newline='').write(s)
print('MainActivity updated')
# 5) i18n
L = root + r'\app\src\com\dsh\v4tune\i18n\L.java'
u = io.open(L, encoding='utf-8').read()
rows = [["menu.tools.settings","设置（音素锁定等）","Settings (phoneme lock, ...)","設定（音素ロックなど）"],["dlg.settings.title","设置","Settings","設定"],["set.phoneme.lock","音素锁定（默认关闭）","Phoneme lock (off by default)","音素ロック（既定はオフ）"],["set.phoneme.desc","关闭时：修改歌词会重新按歌词生成音素。开启后：修改歌词会保留这个音符已有的音素，并把它标记为锁定（写进 .vsqx 的 lock=\"1\"）。","Off: editing a lyric regenerates the phonemes from the new lyric. On: editing a lyric keeps the note's existing phonemes and marks them locked (written to .vsqx as lock=\"1\").","オフ：歌詞を変更すると音素は歌詞から再生成されます。オン：歌詞を変更しても既存の音素を保持し、ロックとして .vsqx に lock=\"1\" で書き出します。"],["set.phoneme.state.on","音素锁定：已开启","Phoneme lock: on","音素ロック：オン"],["set.phoneme.state.off","音素锁定：已关闭（默认）","Phoneme lock: off (default)","音素ロック：オフ（既定）"],["dlg.lyric.title","歌词（拼音 / 汉字 / 假名）","Lyric (pinyin / Hanzi / kana)","歌詞（ピンイン／漢字／かな）"],["dlg.lyric.ok","确定","OK","OK"],["status.lyric.set","歌词 = %s  →  音素 %s","Lyric = %s  →  phonemes %s","歌詞 = %s  →  音素 %s"],["status.lyric.locked","歌词 = %s（音素已锁定，保持不变）","Lyric = %s (phonemes kept: locked)","歌詞 = %s（音素はロック済みで保持）"]]
add = []
for r in rows:
    if ('"' + r[0] + '"') in u: continue
    add.append('        {"' + r[0] + '", ' + ', '.join('"' + v + '"' for v in r[1:]) + '},')
if add:
    i = u.find('{"menu.tools.ai"')
    j = u.rfind(chr(10), 0, i) + 1
    u = u[:j] + chr(10).join(add) + chr(10) + u[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(u)
print('keys added:', len(add))