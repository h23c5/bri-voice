# -*- coding: utf-8 -*-
"""[fix] pressing play while the voicebanks are still loading used to say "no voicebank"."""
import io
p = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(p, encoding="utf-8").read()

s = s.replace("    private boolean pendingPlay = false;", "", 1)      # idempotent
s = s.replace("    private int trackIndex = 0;",
              "    private int trackIndex = 0;\n    /** true while the background pack load runs */\n"
              "    private volatile boolean packsLoading;\n    /** the user asked to play before the load finished */\n"
              "    private boolean pendingPlay;", 1)

s = s.replace("    private void reloadPacksAsync(final boolean announce) {\n        new Thread(",
              "    private void reloadPacksAsync(final boolean announce) {\n        packsLoading = true;\n        new Thread(", 1)

old_req = '''    private boolean requireVoice() {
        if (currentVoice != null) return true;'''
new_req = '''    private boolean requireVoice() {
        if (currentVoice != null) return true;
        if (packsLoading) {
            // the voicebanks are parsed in the background; the user can easily hit play first
            pendingPlay = true;
            status(L.t("status.packs.loading"));
            return false;
        }'''
assert old_req in s, "requireVoice"
s = s.replace(old_req, new_req, 1)

old_apply = '''        SingerLibrary.setUserPacks(ms);
        chooseInitialVoice();
        refreshAll();'''
new_apply = '''        SingerLibrary.setUserPacks(ms);
        packsLoading = false;
        chooseInitialVoice();
        refreshAll();
        if (pendingPlay && currentVoice != null) {     // the user was waiting for this
            pendingPlay = false;
            playSong();
            return;
        }'''
assert old_apply in s, "applyPacks"
s = s.replace(old_apply, new_apply, 1)
io.open(p, "w", encoding="utf-8", newline="").write(s)
print("race fixed")

l = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java"
u = io.open(l, encoding="utf-8").read()
if '"status.packs.loading"' not in u:
    marker = "\n    };\n"
    i = u.find("private static final String[][] TABLE = {")
    j = u.find(marker, i)
    row = '        {"status.packs.loading", "\u6b63\u5728\u8f7d\u5165\u58f0\u5e93\uff0c\u8bf7\u7a0d\u5019\u2026\u8f7d\u5165\u5b8c\u6210\u540e\u4f1a\u81ea\u52a8\u5f00\u59cb\u64ad\u653e", "Loading voicebanks, playback starts when ready", "\u58f0\u5eab\u3092\u8aad\u307f\u8fbc\u307f\u4e2d\u3067\u3059\u3002\u5b8c\u4e86\u5f8c\u306b\u81ea\u52d5\u7684\u306b\u518d\u751f\u3057\u307e\u3059"},\n'
    u = u[:j] + "\n" + row.rstrip("\n") + u[j:]
    io.open(l, "w", encoding="utf-8", newline="").write(u)
    print("i18n key added")
