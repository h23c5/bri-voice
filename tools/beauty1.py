# -*- coding: utf-8 -*-
"""[v4.0] secondary screens get the BriVoice look: badges, subtitles, tick, rounded card."""
import io, re
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"

# --- palette + auto badge in Dialogs -----------------------------------------
d = root + r"\app\src\com\dsh\v4tune\ui\Dialogs.java"
s = io.open(d, encoding="utf-8").read()
if "public static final int[] PALETTE" not in s:
    s = s.replace("    private Dialogs() { }",
'''    private Dialogs() { }

    /** badge colours, cycled per row */
    public static final int[] PALETTE = {0xFF35C8FF, 0xFFFF8A3D, 0xFF7CE38B, 0xFFC08CFF, 0xFFFFD166,
            0xFFFF5FA2, 0xFF4ADEDE};

    /**
     * A one-glyph badge taken from the label itself, so a menu needs no per-entry artwork:
     * "导入声库" gets 导, "New Track" gets N. Leading decoration (+, ●, spaces) is skipped.
     */
    public static String badgeFor(String label) {
        if (label == null) return null;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                String g = String.valueOf(c);
                return (c < 128) ? g.toUpperCase(java.util.Locale.US) : g;
            }
        }
        return null;
    }''', 1)
    io.open(d, "w", encoding="utf-8", newline="").write(s)
    print("palette + badgeFor added")

# --- the generic converter in MainActivity ------------------------------------
m = root + r"\app\src\com\dsh\v4tune\MainActivity.java"
t = io.open(m, encoding="utf-8").read()
if "private void menu(String title" not in t:
    anchor = "    private void pickSinger() {"
    t = t.replace(anchor, '''    /**
     * Shows one of the secondary menus the BriVoice way. A label may carry a subtitle after a
     * newline, exactly like the old list dialogs did.
     */
    private void menu(String title, String subtitle, String[] items, int checked,
                      final DialogInterface.OnClickListener onPick) {
        java.util.List<com.dsh.v4tune.ui.Dialogs.Row> rows = com.dsh.v4tune.ui.Dialogs.rows();
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            String label = items[i] == null ? "" : items[i];
            String sub = null;
            int nl = label.indexOf('\\n');
            if (nl >= 0) { sub = label.substring(nl + 1); label = label.substring(0, nl); }
            rows.add(com.dsh.v4tune.ui.Dialogs.row(
                    com.dsh.v4tune.ui.Dialogs.badgeFor(label),
                    com.dsh.v4tune.ui.Dialogs.PALETTE[i % com.dsh.v4tune.ui.Dialogs.PALETTE.length],
                    label, sub, i == checked, new Runnable() {
                        public void run() { onPick.onClick(null, which); }
                    }));
        }
        com.dsh.v4tune.ui.Dialogs.menu(this, title, subtitle, rows);
    }

''' + anchor, 1)
    io.open(m, "w", encoding="utf-8", newline="").write(t)
    print("menu() helper added")

# --- 1) singer picker ----------------------------------------------------------
old_pick = t[t.find("        String[] names = new String[all.size()];"):t.find('                .show();\n    }\n\n    private void applyVoiceToPart')] + '                .show();'
new_pick = '''        java.util.List<com.dsh.v4tune.ui.Dialogs.Row> rows = com.dsh.v4tune.ui.Dialogs.rows();
        for (int i = 0; i < all.size(); i++) {
            final VoiceModel v = all.get(i);
            int bands = 0;
            for (java.util.Map.Entry<String, VoiceModel.HarmonicBand[]> e2 : v.harmonics.entrySet()) {
                bands += e2.getValue().length;
            }
            String tag = (v.harmonics.size() > 0)
                    ? L.t("dlg.singer.stats", v.harmonics.size(), bands)
                    : L.t("dlg.singer.legacy");
            boolean cur = currentVoice != null && currentVoice.id != null && currentVoice.id.equals(v.id);
            rows.add(com.dsh.v4tune.ui.Dialogs.row(
                    com.dsh.v4tune.ui.Dialogs.badgeFor(singerName(v)),
                    com.dsh.v4tune.ui.Dialogs.PALETTE[i % com.dsh.v4tune.ui.Dialogs.PALETTE.length],
                    singerName(v), tag, cur, new Runnable() {
                        public void run() {
                            beginEdit();
                            currentVoice = v;
                            applyVoiceToPart(currentVoice);
                            commitEdit();
                            refreshAll();
                            status(L.t("status.singer", singerName(v)));
                        }
                    }));
        }
        if (rows.isEmpty()) {
            com.dsh.v4tune.ui.Dialogs.menu(this, L.t("dlg.singer.title"), null,
                    java.util.Collections.singletonList(
                            com.dsh.v4tune.ui.Dialogs.row("!", Theme.ACCENT2, L.t("dlg.singer.none"), null, false, null)));
            return;
        }
        com.dsh.v4tune.ui.Dialogs.menu(this, L.t("dlg.singer.title"),
                L.t("dlg.singer.sub", rows.size()), rows);'''
t2 = io.open(m, encoding="utf-8").read()
assert old_pick in t2, "picker block"
t2 = t2.replace(old_pick, new_pick, 1)

# --- 2) tools menu -------------------------------------------------------------
old_tools = '''        new AlertDialog.Builder(this)
                .setTitle("工具")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) languageMenu();
                        else if (which == 1) showMixer();
                        else if (which == 2) showPlugins();
                        else if (which == 3) importWav();
                        else if (which == 4) lyricFillDialog();
                        else if (which == 5) checkOverlaps();
                        else if (which == 6) outputDeviceDialog();
                        else if (which == 7) clipMenu();
                        else toggleOrientation();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();'''
new_tools = '''        menu(L.t("menu.tools"), null, items, -1, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which == 0) languageMenu();
                else if (which == 1) showMixer();
                else if (which == 2) showPlugins();
                else if (which == 3) importWav();
                else if (which == 4) lyricFillDialog();
                else if (which == 5) checkOverlaps();
                else if (which == 6) outputDeviceDialog();
                else if (which == 7) clipMenu();
                else toggleOrientation();
            }
        });'''
assert old_tools in t2, "tools block"
t2 = t2.replace(old_tools, new_tools, 1)

# --- 3) voicebank menu ---------------------------------------------------------
old_pack = '''        new AlertDialog.Builder(this)
                .setTitle("声库管理")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) importPack();
                        else if (which == 1) importPackFolder();
                        else if (which == 2) showRootHelp();
                        else if (which == 3) deletePack();
                        else packHelp();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();'''
new_pack = '''        menu(L.t("menu.pack.title"), L.t("menu.pack.sub", SingerLibrary.userPackCount()), items, -1,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) importPack();
                        else if (which == 1) importPackFolder();
                        else if (which == 2) showRootHelp();
                        else if (which == 3) deletePack();
                        else packHelp();
                    }
                });'''
assert old_pack in t2, "pack block"
t2 = t2.replace(old_pack, new_pack, 1)
io.open(m, "w", encoding="utf-8", newline="").write(t2)
print("picker, tools and voicebank menus converted")
