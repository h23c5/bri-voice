# -*- coding: utf-8 -*-
"""[i18n] wire the new-feature UI to the catalogue and show voicebank names per language."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()
subs = [
    ('smallButton("\u65b0\u5efa"', 'smallButton(L.t("toolbar.new")'),
    ('.setTitle("\u65b0\u5efa\u5de5\u7a0b")', '.setTitle(L.t("dlg.new.title"))'),
    ('.setMessage("\u5f53\u524d\u5de5\u7a0b\u4f1a\u88ab\u6e05\u7a7a\uff08\u53ef\u4ee5\u7528\u300c\u64a4\u9500\u300d\u627e\u56de\uff09\u3002")', '.setMessage(L.t("dlg.new.msg"))'),
    ('.setPositiveButton("\u65b0\u5efa", new DialogInterface.OnClickListener() {\n                    public void onClick(DialogInterface d, int w) {\n                        beginEdit();', '.setPositiveButton(L.t("dlg.new.ok"), new DialogInterface.OnClickListener() {\n                    public void onClick(DialogInterface d, int w) {\n                        beginEdit();'),
    ('status("\u5df2\u65b0\u5efa\u7a7a\u5de5\u7a0b")', 'status(L.t("status.new.done"))'),
    ('"\u6279\u91cf\u586b\u5165\u6b4c\u8bcd\uff08\u7c98\u8d34\u4e00\u6574\u884c\uff09"', 'L.t("menu.tools.lyrics")'),
    ('"\u68c0\u67e5 / \u4fee\u590d\u97f3\u7b26\u91cd\u53e0"', 'L.t("menu.tools.overlap")'),
    ('"\u64ad\u653e\u8bbe\u5907"', 'L.t("menu.tools.device")'),
    ('.setTitle("\u6279\u91cf\u586b\u5165\u6b4c\u8bcd")', '.setTitle(L.t("dlg.lyrics.title"))'),
    ('.setHint("\u7c98\u8d34\u6b4c\u8bcd\uff0c\u4f8b\u5982\uff1a\\nluo tian yi la la\\n\u6216\u8005\u76f4\u63a5\u5199\u4e2d\u6587\uff1a\\n\u6d1b\u5929\u4f9d\u5566\u5566");', '.setHint(L.t("dlg.lyrics.hint"));'),
    ('.setPositiveButton("\u586b\u5165", new DialogInterface.OnClickListener() {\n                    public void onClick(DialogInterface d, int w) { applyLyrics(e.getText().toString()); }',
     '.setPositiveButton(L.t("dlg.lyrics.ok"), new DialogInterface.OnClickListener() {\n                    public void onClick(DialogInterface d, int w) { applyLyrics(e.getText().toString()); }'),
    ('status("\u6ca1\u6709\u89e3\u6790\u51fa\u6b4c\u8bcd")', 'status(L.t("status.lyrics.empty"))'),
    ('String msg = "\u5df2\u586b\u5165 " + n + " \u4e2a\u97f3\u7b26";', 'String msg = L.t("status.lyrics.done", n);'),
    ('if (n < syl.size()) msg += "\uff08\u8fd8\u6709 " + (syl.size() - n) + " \u4e2a\u97f3\u8282\u6ca1\u653e\u4e0b\uff09";', 'if (n < syl.size()) msg += " " + L.t("status.lyrics.left", syl.size() - n);'),
    ('labels.add("\u7cfb\u7edf\u9ed8\u8ba4");', 'labels.add(L.t("dlg.device.default"));'),
    ('status("\u8bfb\u53d6\u64ad\u653e\u8bbe\u5907\u5931\u8d25: " + t.getMessage());', 'status(L.t("status.device.fail", t.getMessage()));'),
    ('.setTitle("\u64ad\u653e\u8bbe\u5907\uff08\u5f53\u524d\uff1a" + items[curIdx] + "\uff09")', '.setTitle(L.t("dlg.device.title", items[curIdx]))'),
    ('status("\u5df2\u5207\u56de\u7cfb\u7edf\u9ed8\u8ba4\u64ad\u653e\u8bbe\u5907");', 'status(L.t("status.device.default"));'),
    ('status("\u64ad\u653e\u8bbe\u5907: " + items[which]);', 'status(L.t("status.device.set", items[which]));'),
    ('.setTitle("\u53d1\u73b0 " + is.size() + " \u5904\u97f3\u7b26\u91cd\u53e0")', '.setTitle(L.t("dlg.overlap.title", is.size()))'),
    ('.setTitle("\u64ad\u653e\u524d\u68c0\u67e5\uff1a\u53d1\u73b0 " + is.size() + " \u5904\u97f3\u7b26\u91cd\u53e0")', '.setTitle(L.t("dlg.overlap.scan", is.size()))'),
    ('.setPositiveButton("\u81ea\u52a8\u6807\u51c6\u5316"', '.setPositiveButton(L.t("dlg.overlap.fix")'),
    ('.setPositiveButton("\u81ea\u52a8\u4fee\u590d\u5e76\u64ad\u653e"', '.setPositiveButton(L.t("dlg.overlap.fixplay")'),
    ('.setNeutralButton("\u8df3\u8f6c\u5230\u7b2c\u4e00\u5904"', '.setNeutralButton(L.t("dlg.overlap.jump")'),
    ('.setNegativeButton("\u4ecd\u7136\u64ad\u653e"', '.setNegativeButton(L.t("dlg.overlap.playany")'),
    ('status("\u6ca1\u6709\u53d1\u73b0\u97f3\u7b26\u91cd\u53e0");', 'status(L.t("status.overlap.none"));'),
    ('status(n == 0 ? "\u6ca1\u6709\u9700\u8981\u4fee\u590d\u7684\u97f3\u7b26" : ("\u5df2\u6807\u51c6\u5316 " + n + " \u4e2a\u97f3\u7b26"));',
     'status(n == 0 ? L.t("status.overlap.nothing") : L.t("status.overlap.fixed", n));'),
    ('status("\u95ee\u9898\u97f3\u7b26\uff1a" + is.describe());', 'status(L.t("status.overlap.jump", is.describe()));'),
    ('+ "\u300c\u81ea\u52a8\u6807\u51c6\u5316\u300d\u4f1a\u628a\u524d\u4e00\u4e2a\u97f3\u7b26\u7f29\u77ed\u5230\u540e\u4e00\u4e2a\u5f00\u59cb\u5904\uff0c\u82e5\u4e24\u4e2a\u97f3\u7b26\u8d77\u70b9\u5b8c\u5168\u76f8\u540c\u5219\u4fdd\u7559\u8f83\u957f\u7684\u90a3\u4e2a\u3002")',
     '+ L.t("dlg.overlap.why"))'),
]
n = 0
missing = []
for a, b in subs:
    if a in s:
        s = s.replace(a, b, 1)
        n += 1
    else:
        missing.append(a[:48])
io.open(p, "w", encoding="utf-8", newline="").write(s)
print("replaced %d, missing %d" % (n, len(missing)))
for m in missing:
    print("  MISS:", m)
