# -*- coding: utf-8 -*-
"""[i18n] add the keys for the five new features (new project, lyrics, device, overlap, voicebank names)."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java"
s = io.open(p, encoding="utf-8").read()
if '"toolbar.new"' in s:
    print("keys already present")
    raise SystemExit(0)
rows = [
    ("toolbar.new", "新建", "New", "新規"),
    ("dlg.new.title", "新建工程", "New project", "新規プロジェクト"),
    ("dlg.new.msg", "当前工程会被清空（可以用「撤销」找回）。", "The current project will be cleared (Undo brings it back).", "現在のプロジェクトは消去されます（「元に戻す」で復元できます）。"),
    ("dlg.new.ok", "新建", "Create", "作成"),
    ("status.new.done", "已新建空工程", "New empty project", "空のプロジェクトを作成しました"),
    ("menu.tools.lyrics", "批量填入歌词（粘贴一整行）", "Fill lyrics from a block", "歌詞をまとめて入力"),
    ("menu.tools.overlap", "检查 / 修复音符重叠", "Check note overlaps", "音符の重なりを確認"),
    ("menu.tools.device", "播放设备", "Playback device", "再生デバイス"),
    ("dlg.lyrics.title", "批量填入歌词", "Fill lyrics", "歌詞をまとめて入力"),
    ("dlg.lyrics.hint", "粘贴歌词，例如：\nluo tian yi la la\n或者直接写中文：\n洛天依啦啦", "Paste lyrics, e.g.\nluo tian yi la la\nCJK characters are converted automatically", "歌詞を貼り付け（例：luo tian yi la la）\n漢字は自動でピンインに変換されます"),
    ("dlg.lyrics.ok", "填入", "Fill", "入力"),
    ("status.lyrics.empty", "没有解析出歌词", "No lyrics found", "歌詞を解析できませんでした"),
    ("status.lyrics.done", "已填入 %d 个音符", "Filled %d notes", "%d 個の音符に入力しました"),
    ("status.lyrics.left", "还有 %d 个音节没放下", "%d syllables left over", "%d 音節が余りました"),
    ("dlg.device.title", "播放设备（当前：%s）", "Playback device (now: %s)", "再生デバイス（現在：%s）"),
    ("dlg.device.default", "系统默认", "System default", "システム既定"),
    ("status.device.set", "播放设备: %s", "Playback device: %s", "再生デバイス：%s"),
    ("status.device.default", "已切回系统默认播放设备", "Back to the system default device", "システム既定に戻しました"),
    ("status.device.fail", "读取播放设备失败: %s", "Cannot read the device list: %s", "デバイス一覧を取得できません：%s"),
    ("dlg.overlap.title", "发现 %d 处音符重叠", "%d overlapping notes found", "%d 箇所の重なりを検出"),
    ("dlg.overlap.scan", "播放前检查：发现 %d 处音符重叠", "Before playback: %d overlaps found", "再生前チェック：%d 箇所の重なり"),
    ("dlg.overlap.why", "重叠会让两个音同时发声（歌手只有一个）。「自动标准化」会把前一个音符缩短到后一个开始处；两个音符起点完全相同时保留较长的那个。", "Overlapping notes make the singer sound two notes at once. Normalising shortens the earlier note to where the next one starts; notes starting on the same tick keep the longer one.", "重なると同時に 2 音が鳴ります。「自動整形」は前の音符を次の開始位置まで短くし、同じ位置に始まる音符は長い方を残します。"),
    ("dlg.overlap.fix", "自动标准化", "Normalise", "自動整形"),
    ("dlg.overlap.fixplay", "自动修复并播放", "Fix and play", "修正して再生"),
    ("dlg.overlap.jump", "跳转到第一处", "Jump to the first", "最初へ移動"),
    ("dlg.overlap.playany", "仍然播放", "Play anyway", "そのまま再生"),
    ("status.overlap.none", "没有发现音符重叠", "No overlapping notes", "重なりはありません"),
    ("status.overlap.fixed", "已标准化 %d 个音符", "Normalised %d notes", "%d 個の音符を整形しました"),
    ("status.overlap.jump", "问题音符：%s", "Problem note: %s", "問題の音符：%s"),
    ("status.overlap.nothing", "没有需要修复的音符", "Nothing to fix", "修正するものはありません"),
    ("singer.tag.mine", "（我的声库）", " (installed)", "（インストール済み）"),
]
def esc(t):
    return t.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
lines = "".join('        {"%s", "%s", "%s", "%s"},\n' % (k, esc(zh), esc(en), esc(ja)) for k, zh, en, ja in rows)
marker = "\n    };\n"
i = s.find("private static final String[][] TABLE = {")
j = s.find(marker, i)
if j < 0:
    raise SystemExit("table end not found")
s = s[:j] + "\n\n        // ------------------------------------------- 新建 / 歌词 / 设备 / 重叠\n" + lines.rstrip("\n") + s[j:]
io.open(p, "w", encoding="utf-8", newline="").write(s)
print("inserted %d keys" % len(rows))
