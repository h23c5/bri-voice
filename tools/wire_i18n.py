# -*- coding: utf-8 -*-
"""[i18n] replace the hard-coded UI literals with L.t() lookups (Lead wiring)."""
import io, re, sys

proj = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"
main = proj + r"\app\src\com\dsh\v4tune\MainActivity.java"
pv = proj + r"\app\src\com\dsh\v4tune\ui\ParamView.java"

BUTTONS = {
    "打开": "toolbar.open", "保存": "toolbar.save", "另存为": "toolbar.save.as",
    "导出WAV": "toolbar.export.wav", "撤销": "toolbar.undo", "重做": "toolbar.redo",
    "示例": "toolbar.demo", "声库": "toolbar.voicebank", "关于": "toolbar.about",
    "试听本段": "transport.preview.part", "歌手": "transport.singer",
    "速度": "transport.tempo", "歌词": "transport.lyric", "工具": "menu.tools",
    "播放": "transport.play", "停止": "transport.stop",
}
src = io.open(main, encoding="utf-8").read()
n = 0
for lit, key in BUTTONS.items():
    for pat in ('smallButton("%s"' % lit, 'setText("%s")' % lit, 'setText("\u25b6 %s")' % lit,
                'setText("\u25a0 %s")' % lit):
        rep = pat.replace('"%s"' % lit, 'L.t("%s")' % key).replace('"\u25b6 %s"' % lit, 'L.t("%s")' % key).replace('"\u25a0 %s"' % lit, 'L.t("%s")' % key)
        if pat in src:
            src = src.replace(pat, rep)
            n += pat.count(pat)
# snap button carries a number
src = src.replace('smallButton("\u5438\u9644 1/8"', 'smallButton(L.t("transport.snap", 8)')
# tools menu entries
tools = {"\u8bed\u8a00 / Language / \u8a00\u8a9e": "menu.tools.lang",
         "\u6df7\u97f3\u5668\uff08\u97f3\u91cf / \u58f0\u50cf / \u9759\u97f3 / \u72ec\u594f\uff09": "menu.tools.mixer",
         "\u63d2\u4ef6\uff08\u6df7\u54cd / \u5408\u5531 / \u538b\u7f29\uff09": "menu.tools.plugins",
         "\u5bfc\u5165 WAV \u97f3\u9891\u5230\u5f53\u524d\u97f3\u8f68": "menu.tools.import.wav",
         "\u97f3\u9891\u7247\u6bb5\u5217\u8868": "menu.tools.clips",
         "\u624b\u52a8\u5207\u6362\u6a2a\u7ad6\u5c4f": "menu.tools.orientation"}
for lit, key in tools.items():
    if '"%s"' % lit in src:
        src = src.replace('"%s"' % lit, 'L.t("%s")' % key)
        n += 1
io.open(main, "w", encoding="utf-8", newline="").write(src)

pvsrc = io.open(pv, encoding="utf-8").read()
if "public String[] labels" not in pvsrc:
    pvsrc = pvsrc.replace(
        'public static final String[] IDS =',
        '    /** display labels, parallel to {@link #IDS}; the app fills them with the current language */\n    public String[] labels;\n\n    public static final String[] IDS =')
    pvsrc = pvsrc.replace('public void setParamId(String id) {',
        'public void setLabels(String[] l) { labels = l; invalidate(); }\n\n    public void setParamId(String id) {')
    io.open(pv, "w", encoding="utf-8", newline="").write(pvsrc)
    print("ParamView: labels hook added")
print("MainActivity replacements:", n)
