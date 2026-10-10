# -*- coding: utf-8 -*-
"""[i18n] show voicebank names in the UI language (pack carries zh/en/ja)."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()

helper = '''    /**
     * Voicebank name in the current UI language. Packs carry a Chinese, English and Japanese name;
     * the "my voicebank" tag is appended here instead of being baked into the pack.
     */
    private String singerName(VoiceModel v) {
        if (v == null) return "";
        String n = v.nameFor(L.lang());
        if (SingerLibrary.isUserPack(v)) n = n + " " + L.t("singer.tag.mine");
        return n;
    }

    private void chooseInitialVoice() {'''
s = s.replace("    private void chooseInitialVoice() {", helper, 1)

subs = [
    ('status("BriVoice \u00b7 \u5df2\u5b89\u88c5 " + SingerLibrary.userPackCount() + " \u4e2a\u58f0\u5e93\uff1a" + currentVoice.name);',
     'status("BriVoice \u00b7 \u5df2\u5b89\u88c5 " + SingerLibrary.userPackCount() + " \u4e2a\u58f0\u5e93\uff1a" + singerName(currentVoice));'),
    ('names[i] = v.name + "\\n" + tag;', 'names[i] = singerName(v) + "\\n" + tag;'),
    ('status("\u5f53\u524d\u6b4c\u59ec: " + currentVoice.name', 'status("\u5f53\u524d\u6b4c\u59ec: " + singerName(currentVoice)'),
    ('+ (currentVoice != null ? currentVoice.name : ""));', '+ (currentVoice != null ? singerName(currentVoice) : ""));'),
    ('for (int i = 0; i < mine.size(); i++) names[i] = mine.get(i).name;', 'for (int i = 0; i < mine.size(); i++) names[i] = singerName(mine.get(i));'),
    ('.setMessage("\u786e\u5b9a\u5220\u9664\u300c" + m.name + "\u300d\u5417\uff1f', '.setMessage("\u786e\u5b9a\u5220\u9664\u300c" + singerName(m) + "\u300d\u5417\uff1f'),
    ('status(ok ? ("\u5df2\u5220\u9664\u58f0\u5e93: " + m.name) : "\u5220\u9664\u5931\u8d25");', 'status(ok ? ("\u5df2\u5220\u9664\u58f0\u5e93: " + singerName(m)) : "\u5220\u9664\u5931\u8d25");'),
    ('status("\u5df2\u5bfc\u5165\u58f0\u5e93: " + r.model.name);', 'status("\u5df2\u5bfc\u5165\u58f0\u5e93: " + singerName(r.model));'),
    ('toast("\u5df2\u5bfc\u5165\u58f0\u5e93: " + r.model.name);', 'toast("\u5df2\u5bfc\u5165\u58f0\u5e93: " + singerName(r.model));'),
    ('sb.append("  \u00b7 ").append(v.name)', 'sb.append("  \u00b7 ").append(singerName(v))'),
]
n = 0
missing = []
for a, b in subs:
    if a in s:
        s = s.replace(a, b, 1)
        n += 1
    else:
        missing.append(a[:60])
io.open(p, "w", encoding="utf-8", newline="").write(s)
print("name substitutions %d, missing %d" % (n, len(missing)))
for m in missing:
    print("  MISS:", m)
