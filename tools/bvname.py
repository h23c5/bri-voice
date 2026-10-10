# -*- coding: utf-8 -*-
"""[v3.7] show every known singer under its BriVoice name, whatever the pack file says."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()
if "private String bvName(" not in s:
    anchor = "    private String singerName(VoiceModel v) {"
    s = s.replace(anchor, '''    /**
     * BriVoice naming: the singers the app knows about are always shown as "<singer> BV", no matter
     * what the pack file carries in its name field. That way renamed packs - and packs converted
     * before this version - show up consistently without having to be imported again.
     */
    private String bvName(String raw) {
        if (raw == null) return null;
        String low = raw.toLowerCase();
        if (low.contains("miku") || raw.contains("\u521d\u97f3") || raw.contains("\u30df\u30af")) return "Miku BV";
        if (low.contains("tianyi") || low.contains("luo") || raw.contains("\u6d1b\u5929\u4f9d")) return "\u6d1b\u5929\u4f9d BV";
        return raw;
    }

''' + anchor, 1)

    old = '''        String n = v.nameFor(L.lang());
        if (SingerLibrary.isBuiltin(v)) {'''
    new = '''        String n = bvName(v.nameFor(L.lang()));
        if (SingerLibrary.isBuiltin(v)) {'''
    assert old in s, "singerName body"
    s = s.replace(old, new, 1)
    io.open(p, "w", encoding="utf-8", newline="").write(s)
    print("display normalisation added")
