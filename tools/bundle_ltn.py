# -*- coding: utf-8 -*-
"""[v3.6] the voicebank shipped in assets/ is installed on first run, exactly once."""
import io
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"

# --- SingerLibrary: remember which packs came with the app ---------------------
p = root + r"\app\src\com\dsh\v4tune\synth\SingerLibrary.java"
s = io.open(p, encoding="utf-8").read()
if "public static void setBuiltinIds(" not in s:
    anchor = "    public static boolean isEmpty() {"
    s = s.replace(anchor, '''    /** ids of the voicebanks that ship inside the APK (tagged in the UI) */
    private static final java.util.HashSet<String> BUILTIN_IDS = new java.util.HashSet<String>();

    public static void setBuiltinIds(java.util.Collection<String> ids) {
        BUILTIN_IDS.clear();
        if (ids != null) {
            for (String id : ids) if (id != null && !id.isEmpty()) BUILTIN_IDS.add(id);
        }
    }

    public static boolean isBuiltin(VoiceModel m) {
        return m != null && m.id != null && BUILTIN_IDS.contains(m.id);
    }

''' + anchor, 1)
    io.open(p, "w", encoding="utf-8", newline="").write(s)
    print("SingerLibrary tagged")

# --- MainActivity: install the bundled pack once, before the first load --------
m = root + r"\app\src\com\dsh\v4tune\MainActivity.java"
t = io.open(m, encoding="utf-8").read()

old = '''    private void reloadPacksAsync(final boolean announce) {
        packsLoading = true;
        new Thread(new Runnable() {
            public void run() {
                java.util.List<VoicePack.Result> rs = null;
                String err = null;
                try {
                    rs = new java.util.ArrayList<VoicePack.Result>(packStore.loadAll());'''
new = '''    /**
     * Voicebanks shipped inside the APK (app/assets/singers) are copied into the voicebank folder
     * on first run - once. The flag is remembered, so deleting a bundled voicebank in the UI keeps
     * it deleted instead of having it silently come back on the next start.
     */
    private void installBundledVoicebanks() {
        try {
            String[] names = getAssets().list("singers");
            if (names == null || names.length == 0) return;
            java.util.HashSet<String> ids = new java.util.HashSet<String>(
                    getSharedPreferences("brivoice", MODE_PRIVATE).getStringSet("builtinIds",
                            new java.util.HashSet<String>()));
            for (String n : names) {
                if (n == null || !n.toLowerCase(java.util.Locale.US).endsWith(".v4voice")) continue;
                String flag = "builtin_" + n;
                if (getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean(flag, false)) continue;
                java.io.InputStream in = null;
                VoicePack.Result res = null;
                try {
                    in = getAssets().open("singers/" + n);
                    res = packStore.importStream(in, n, true);
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                }
                getSharedPreferences("brivoice", MODE_PRIVATE).edit().putBoolean(flag, true).apply();
                if (res != null && res.ok() && res.model != null && res.model.id != null) {
                    ids.add(res.model.id);
                    getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                            .putStringSet("builtinIds", ids).apply();
                }
            }
            SingerLibrary.setBuiltinIds(ids);
        } catch (Throwable t) {
            android.util.Log.w("V4Tune", "bundled voicebank install failed: " + t);
        }
    }

    private void reloadPacksAsync(final boolean announce) {
        packsLoading = true;
        new Thread(new Runnable() {
            public void run() {
                java.util.List<VoicePack.Result> rs = null;
                String err = null;
                try {
                    installBundledVoicebanks();
                    rs = new java.util.ArrayList<VoicePack.Result>(packStore.loadAll());'''
assert old in t, "reloadPacksAsync"
t = t.replace(old, new, 1)

# UI tag: bundled packs say so instead of "my voicebank"
old_name = '''        String n = v.nameFor(L.lang());
        if (SingerLibrary.isUserPack(v)) {'''
new_name = '''        String n = v.nameFor(L.lang());
        if (SingerLibrary.isBuiltin(v)) {
            return n + " " + L.t("singer.tag.builtin");
        }
        if (SingerLibrary.isUserPack(v)) {'''
assert old_name in t, "singerName"
t = t.replace(old_name, new_name, 1)
io.open(m, "w", encoding="utf-8", newline="").write(t)
print("install-on-first-run wired")

# --- i18n ---------------------------------------------------------------------
l = root + r"\app\src\com\dsh\v4tune\i18n\L.java"
u = io.open(l, encoding="utf-8").read()
if '"singer.tag.builtin"' not in u:
    marker = "\n    };\n"
    i = u.find("private static final String[][] TABLE = {")
    j = u.find(marker, i)
    row = '        {"singer.tag.builtin", "\uff08\u5185\u7f6e\uff09", "(bundled)", "\uff08\u540c\u68b1\uff09"},\n'
    u = u[:j] + "\n" + row.rstrip("\n") + u[j:]
    io.open(l, "w", encoding="utf-8", newline="").write(u)
    print("i18n key added")
