# -*- coding: utf-8 -*-
"""[ui] helpers for the sliders / zoom buttons, the About disclaimer and the new i18n keys."""
import io

root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"
m = root + r"\app\src\com\dsh\v4tune\MainActivity.java"
s = io.open(m, encoding="utf-8").read()

# fields
s = s.replace('''    private int snapTicks = 240;''',
'''    private int snapTicks = 240;
    private android.widget.SeekBar snapBar, tempoBar;
    private TextView snapLabel;
    /** snap choices offered by the slider: 1/16, 1/8, 1/4, 1/2, 1/1, off */
    private static final int[] SNAP_CHOICES = {120, 240, 480, 960, 1920, 0};
    private static final int SNAP_STEPS = SNAP_CHOICES.length;
    private static final int TEMPO_MIN = 40, TEMPO_MAX = 240;''', 1)

# helper block, inserted before the transport section
helpers = '''
    // ------------------------------------------------------------- small widgets

    /** a labelled slider used for snap and tempo; the label doubles as the numeric editor */
    private View sliderGroup(String label, android.widget.SeekBar bar, View.OnClickListener onLabel) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(1), dp(6), dp(1));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Theme.TEXT_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        if (onLabel != null) {
            t.setOnClickListener(onLabel);
            t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        }
        box.addView(t);
        bar.setMax(bar.getMax());
        box.addView(bar, new LinearLayout.LayoutParams(dp(104), dp(24)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        box.setLayoutParams(lp);
        if (label.equals(L.t("transport.snap.short"))) snapLabel = t;
        return box;
    }

    /** square icon-ish button for the zoom trio */
    private Button compactButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(Theme.TEXT);
        b.setBackground(Theme.button(this, false));
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(38));
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int snapIndex(int ticks) {
        for (int i = 0; i < SNAP_STEPS; i++) if (SNAP_CHOICES[i] == ticks) return i;
        return 1;
    }

    private int snapTicksAt(int index) {
        return SNAP_CHOICES[Math.max(0, Math.min(SNAP_STEPS - 1, index))];
    }

    private String snapText() {
        return snapTicks == 0 ? L.t("transport.snap.off") : L.t("transport.snap", 1920 / snapTicks);
    }

    private void setTempo(double bpm) {
        double v = Math.max(TEMPO_MIN, Math.min(TEMPO_MAX, Math.round(bpm)));
        beginEdit();
        song.tempos.clear();
        song.tempos.add(new long[]{0, Math.round(v * 100)});
        commitEdit();
        refreshAll();
        status(L.t("status.tempo", (int) v));
    }

    private void zoomBy(float factor) {
        roll.zoomBy(factor);
        status(L.t("status.zoom", Math.round(roll.getPxPerTick() / 0.25f * 100)));
    }

    private void fitZoom() {
        long end = Math.max(1920L, song.maxTick() + 960L);
        roll.setVisibleTickRange(0L, end);
        status(L.t("status.zoom.fit"));
    }

'''
s = s.replace("    // ------------------------------------------------------------- transport", helpers + "    // ------------------------------------------------------------- transport", 1)

# cycleSnap must survive being called without a button (the label tap path)
s = s.replace('''    private void cycleSnap(Button b) {''', '''    private void cycleSnap(Button b) {''', 1)
s = s.replace('''        b.setText(snapTicks == 0 ? "吸附 关" : ("吸附 1/" + (1920 / snapTicks)));''',
              '''        if (b != null) b.setText(snapText());''', 1)

io.open(m, "w", encoding="utf-8", newline="").write(s)
print("helpers added")

# roll: expose the zoom level and a zoom-about-centre helper
pr = root + r"\app\src\com\dsh\v4tune\ui\PianoRollView.java"
t = io.open(pr, encoding="utf-8").read()
if "public void zoomBy(float" not in t:
    t = t.replace('''    public void setZoom(float pxPerTick, float pxPerSemitone) {''',
'''    /** current horizontal zoom (pixels per tick) */
    public float getPxPerTick() { return pxPerTick; }

    /** zoom in/out around the centre of the visible area (used by the +/- buttons) */
    public void zoomBy(float factor) {
        float cx = keyW + (getWidth() - keyW) * 0.5f;
        float cy = rulerH + (getHeight() - rulerH) * 0.5f;
        float tAt = tickAtX(cx), kAt = keyAtY(cy);
        pxPerTick = clampTickPx(pxPerTick * factor);
        scrollTick = tAt - (cx - keyW) / pxPerTick;
        clampScroll();
        zoomInited = true;
        invalidate();
    }

    public void setZoom(float pxPerTick, float pxPerSemitone) {''', 1)
    io.open(pr, "w", encoding="utf-8", newline="").write(t)
    print("roll zoom helpers added")

# i18n: slider labels, zoom statuses, disclaimer
l = root + r"\app\src\com\dsh\v4tune\i18n\L.java"
u = io.open(l, encoding="utf-8").read()
if '"transport.snap.short"' not in u:
    rows = [
        ("transport.snap.short", "吸附", "Snap", "スナップ"),
        ("status.zoom", "缩放 %d%%", "Zoom %d%%", "ズーム %d%%"),
        ("status.zoom.fit", "已缩放到适应窗口", "Zoomed to fit", "ウィンドウに合わせました"),
        ("about.line.author", "作者：Ling-tech　QQ：3361390582", "Author: Ling-tech   QQ: 3361390582", "作者：Ling-tech　QQ：3361390582"),
        ("about.line.disclaimer", "免责声明：本软件仅供个人学习与研究使用，不附带任何担保。所有声库数据均由使用者自行从其合法授权的 VOCALOID 产品中提取，作者不提供、不分发任何官方采样或声库文件，也不对使用者的数据来源负责。请勿将本软件用于商业用途或再分发受版权保护的素材。", "Disclaimer: this software is provided for personal study and research only, without warranty of any kind. All voicebank data is extracted by the user from their own licensed VOCALOID products; the author does not supply or distribute any official samples or voicebank files and is not responsible for how the user obtained their data. Do not use it commercially or redistribute copyrighted material.", "免責事項：本ソフトウェアは個人の学習・研究用であり、いかなる保証も伴いません。声庫データは利用者自身が正規ライセンスの VOCALOID 製品から抽出するもので、作者は公式サンプルや声庫ファイルを提供・配布せず、利用者のデータ入手方法について責任を負いません。商用利用や著作権素材の再配布はしないでください。"),
    ]
    def esc(x):
        return x.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
    lines = "".join('        {"%s", "%s", "%s", "%s"},\n' % (k, esc(zh), esc(en), esc(ja)) for k, zh, en, ja in rows)
    marker = "\n    };\n"
    i = u.find("private static final String[][] TABLE = {")
    j = u.find(marker, i)
    u = u[:j] + "\n\n        // ------------------------------------------- sliders / zoom / disclaimer\n" + lines.rstrip("\n") + u[j:]
    io.open(l, "w", encoding="utf-8", newline="").write(u)
    print("i18n keys added")
