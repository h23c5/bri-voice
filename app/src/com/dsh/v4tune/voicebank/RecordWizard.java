package com.dsh.v4tune.voicebank;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Phone-first recording wizard for custom voicebanks (v11.0.1).
 *
 * <p>Three steps, mirroring how a VOCALOID/UTAU bank is recorded but without a PC, an audio
 * interface or a list of oto.ini values:</p>
 * <ol>
 *   <li>setup - name, language, take level and the reference pitch;</li>
 *   <li>takes - one big syllable at a time, a reference tone, a plain record button, automatic
 *       silence trimming and an immediate quality verdict (clipped / too quiet / too short);</li>
 *   <li>review - the measured takes, then hand over to the normal pack dialog for artwork and
 *       training.</li>
 * </ol>
 */
public final class RecordWizard {

    /** result of a finished session */
    public interface Host {
        void onRecordingFinished(String name, int lang, List<CustomBankBuilder.Sample> samples, String recordJson);

        /** open a WAV picker for the current take (creators who recorded on another device) */
        void onPickTakeFile();
    }

    public static final int REQ_MIC = 2002;

    private final Activity act;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final MicRecorder mic = new MicRecorder();
    private AlertDialog dlg;
    private LinearLayout page;

    private String name = "";
    private int lang = 2;
    private int level = RecordGuide.LEVEL_QUICK;
    private int pitchIdx = 1;
    private List<RecordGuide.Item> items;
    private int pos;
    private final ArrayList<RecordGuide.TakeInfo> infos = new ArrayList<RecordGuide.TakeInfo>();
    private final ArrayList<CustomBankBuilder.Sample> samples = new ArrayList<CustomBankBuilder.Sample>();
    private boolean recording;
    private boolean pendingPermission;
    private TextView statusView;
    private TextView header;
    private Button recBtn;

    public RecordWizard(Activity activity, Host host) {
        this.act = activity;
        this.host = host;
        this.items = RecordGuide.items(lang, level);
    }

    public void show() {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(8));
        page = new LinearLayout(act);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page);
        ScrollView sc = new ScrollView(act);
        sc.addView(root);
        dlg = new AlertDialog.Builder(act)
                .setTitle("录音向导（v11.0.1）")
                .setView(sc)
                .setNegativeButton("关闭", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { abortMic(); }
                })
                .show();
        showSetup();
    }

    // ------------------------------------------------------------------ step 1

    private void showSetup() {
        page.removeAllViews();
        text("和 UTAU 一样按发音逐个录（文件名就是发音），像 VOCALOID 的录音清单一样先给参考音。"
                + "手机麦克风就够：安静房间、干声、不要伴奏，每个音平稳拖长 1–2 秒。", 12.5f, true);

        sectionTitle("声库名称");
        final EditText nm = new EditText(act);
        nm.setHint("我的声库");
        nm.setSingleLine(true);
        nm.setInputType(InputType.TYPE_CLASS_TEXT);
        nm.setText(name);
        page.addView(nm);

        sectionTitle("语言（决定发音清单）");
        LinearLayout langs = row();
        final Button[] lb = new Button[3];
        final String[] names = {"中文", "日文", "英文"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            lb[i] = small((i == lang ? "● " : "") + names[i]);
            lb[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    lang = idx;
                    for (int j = 0; j < 3; j++) lb[j].setText((j == lang ? "● " : "") + names[j]);
                    items = RecordGuide.items(lang, level);
                    showSetup();
                }
            });
            langs.addView(lb[i]);
        }
        page.addView(langs);

        sectionTitle("录音清单");
        LinearLayout levels = row();
        final Button[] vv = new Button[2];
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            vv[i] = small((i == level ? "● " : "") + RecordGuide.levelName(i));
            vv[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    level = idx;
                    items = RecordGuide.items(lang, level);
                    for (int j = 0; j < 2; j++) vv[j].setText((j == level ? "● " : "") + RecordGuide.levelName(j));
                }
            });
            levels.addView(vv[i]);
        }
        page.addView(levels);
        text("快速 5 个元音 1 分钟即可出声库；标准 16 个音节还能补上辅音噪声，吐字更像人。", 11.5f, true);

        sectionTitle("参考音高（所有音都照这个音高唱）");
        LinearLayout pitches = row();
        final Button[] pb = new Button[RecordGuide.PITCH_NAMES.length];
        for (int i = 0; i < RecordGuide.PITCH_NAMES.length; i++) {
            final int idx = i;
            pb[i] = small((i == pitchIdx ? "● " : "") + RecordGuide.PITCH_NAMES[i]);
            pb[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    pitchIdx = idx;
                    for (int j = 0; j < pb.length; j++) pb[j].setText((j == pitchIdx ? "● " : "") + RecordGuide.PITCH_NAMES[j]);
                    MicRecorder.playTone(RecordGuide.pitchHz(pitchIdx), 700);
                }
            });
            pitches.addView(pb[i]);
        }
        page.addView(pitches);
        text("点一下音高会播放参考音；录的时候可以跟着哼。", 11.5f, true);

        Button next = primary("下一步：开始录音");
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String n = nm.getText() == null ? "" : nm.getText().toString().trim();
                name = n.isEmpty() ? "我的声库" : n;
                pos = firstMissing();
                showTake();
            }
        });
        page.addView(next);
    }

    private int firstMissing() {
        for (int i = 0; i < items.size(); i++) if (findTake(items.get(i).symbol) == null) return i;
        return Math.min(items.size() - 1, 0);
    }

    // ------------------------------------------------------------------ step 2

    private void showTake() {
        page.removeAllViews();
        final RecordGuide.Item it = items.get(Math.max(0, Math.min(items.size() - 1, pos)));
        header = text("步骤 2/3 · 第 " + (pos + 1) + "/" + items.size() + " 条 · 已完成 " + doneCount() + " 条", 12f, false);
        TextView big = new TextView(act);
        big.setText(it.label);
        big.setTextSize(44f);
        big.setGravity(Gravity.CENTER);
        big.setPadding(0, dp(10), 0, dp(2));
        big.setTextColor(0xFFFFFFFF);
        page.addView(big);
        TextView hint = new TextView(act);
        hint.setText(it.hint + " · 参考音 " + RecordGuide.PITCH_NAMES[pitchIdx]);
        hint.setTextSize(13f);
        hint.setTextColor(0xFFB9C6D4);
        hint.setGravity(Gravity.CENTER);
        page.addView(hint);

        statusView = new TextView(act);
        statusView.setTextSize(12.5f);
        statusView.setTextColor(0xFFB9C6D4);
        statusView.setPadding(0, dp(8), 0, dp(8));
        RecordGuide.TakeInfo have = findTake(it.symbol);
        statusView.setText(have == null ? "还没录这一条" : ("已录 " + (have.ms / 1000.0) + " 秒 · "
                + (have.f0 > 0 ? (have.f0 + " Hz · ") : "") + have.quality));
        page.addView(statusView);

        LinearLayout r1 = row();
        Button tone = small("▶ 参考音");
        tone.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { MicRecorder.playTone(RecordGuide.pitchHz(pitchIdx), 700); }
        });
        final Button rec = small(recording ? "■ 停止并保存" : "● 开始录音");
        recBtn = rec;
        rec.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (recording) {
                    finishTake();
                } else {
                    startTake(rec);
                }
            }
        });
        r1.addView(tone);
        r1.addView(rec);
        page.addView(r1);

        LinearLayout r2 = row();
        Button fromFile = small("从文件…");
        fromFile.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { host.onPickTakeFile(); } catch (Throwable ignored) { }
            }
        });
        r2.addView(fromFile);
        Button prev = small("上一条");
        prev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (pos > 0) pos--;
                showTake();
            }
        });
        Button skipBtn = small("跳过");
        skipBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (pos + 1 < items.size()) pos++;
                showTake();
            }
        });
        Button done = small("完成录音");
        done.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showReview(); } });
        r2.addView(prev);
        r2.addView(skipBtn);
        r2.addView(done);
        page.addView(r2);

        text("录完会自动切到下一条；觉得不好就点上一条重录（同名文件会覆盖）。", 11.5f, true);
    }

    private void startTake(final Button rec) {
        if (android.os.Build.VERSION.SDK_INT >= 23 && act.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = true;
            act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!mic.isSupported()) {
            statusView.setText("这台设备不支持录音（或模拟器没有麦克风），可以先用「选择采样 WAV」导入已有录音。");
            return;
        }
        recording = true;
        final Button rb = rec != null ? rec : recBtn;
        if (rb != null) rb.setText("■ 停止并保存");
        statusView.setText("准备中…保持安静");
        ui.postDelayed(new Runnable() {
            public void run() {
                try {
                    mic.start();
                    statusView.setText("正在录音… 对着手机平稳拖长「" + items.get(pos).label + "」");
                    MicRecorder.playTone(RecordGuide.pitchHz(pitchIdx), 450);
                } catch (Throwable t) {
                    recording = false;
                    if (rb != null) rb.setText("● 开始录音");
                    statusView.setText("打不开麦克风：" + (t.getMessage() == null ? t.toString() : t.getMessage())
                            + "（模拟器请先开启麦克风）");
                }
            }
        }, 700);
    }

    /** called by the host after the mic permission answer */
    public void onPermissionResult(boolean granted) {
        pendingPermission = false;
        if (!granted) {
            if (statusView != null) statusView.setText("没有录音权限：请允许麦克风，或改用「选择采样 WAV」导入。");
            return;
        }
        if (dlg != null && dlg.isShowing()) {
            // the wizard is still on screen: restart the take the user asked for
            showTake();
            ui.postDelayed(new Runnable() {
                public void run() { startTake(null); }
            }, 400);
        }
    }

    private void finishTake() {
        if (!recording) return;
        recording = false;
        MicRecorder.Take t;
        try {
            t = mic.stop();
        } catch (Throwable e) {
            statusView.setText("录音失败：" + e);
            return;
        }
        if (!t.usable) {
            if (statusView != null) {
                statusView.setText("这一条不可用（" + t.quality + "）：再录一次，或点「从文件…」导入。");
            }
            if (recBtn != null) recBtn.setText("● 开始录音");
            return;
        }
        RecordGuide.Item item = items.get(pos);
        int n = takeCount(item.symbol) + 1;
        String file = item.symbol + "_" + n + ".wav";
        CustomBankBuilder.Sample s = new CustomBankBuilder.Sample(file, t.mono, t.sampleRate, t.wav);
        RecordGuide.TakeInfo info = new RecordGuide.TakeInfo();
        info.symbol = item.symbol;
        info.file = file;
        info.ms = t.ms;
        info.peakDb = t.peakDb;
        info.rmsDb = t.rmsDb;
        info.silenceRatio = t.silenceRatio;
        info.f0 = t.f0;
        info.quality = t.quality;
        removeTake(item.symbol);
        infos.add(info);
        samples.add(s);
        statusView.setText("✓ " + t.quality + " · " + (t.ms / 1000.0) + " 秒"
                + (t.f0 > 0 ? " · " + t.f0 + " Hz" : "") + " · 已加入");
        if (header != null) header.setText("步骤 2/3 · 第 " + (pos + 1) + "/" + items.size() + " 条 · 已完成 " + doneCount() + " 条");
        if (pos + 1 < items.size()) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    pos++;
                    showTake();
                }
            }, 900);
        } else {
            ui.postDelayed(new Runnable() {
                public void run() { showReview(); }
            }, 900);
        }
    }

    /** a WAV chosen from storage becomes the take for the current syllable (same checks as mic) */
    public void acceptImported(String fileName, float[] mono, int sampleRate, byte[] wav) {
        if (mono == null || mono.length == 0) return;
        MicRecorder.Take t = MicRecorder.analyze(mono, sampleRate);
        if (wav != null && wav.length > 0) t.wav = wav;
        RecordGuide.Item it = items.get(Math.max(0, Math.min(items.size() - 1, pos)));
        int n = takeCount(it.symbol) + 1;
        String file = it.symbol + "_" + n + ".wav";
        CustomBankBuilder.Sample s = new CustomBankBuilder.Sample(file, t.mono, t.sampleRate, t.wav);
        RecordGuide.TakeInfo info = new RecordGuide.TakeInfo();
        info.symbol = it.symbol;
        info.file = file;
        info.ms = t.ms;
        info.peakDb = t.peakDb;
        info.rmsDb = t.rmsDb;
        info.silenceRatio = t.silenceRatio;
        info.f0 = t.f0;
        info.quality = t.quality;
        removeTake(it.symbol);
        infos.add(info);
        samples.add(s);
        if (statusView != null) {
            statusView.setText("✓ 已导入 " + fileName + " · " + (t.ms / 1000.0) + " 秒"
                    + (t.f0 > 0 ? " · " + t.f0 + " Hz" : "") + " · " + t.quality);
        }
        if (header != null) header.setText("步骤 2/3 · 第 " + (pos + 1) + "/" + items.size() + " 条 · 已完成 " + doneCount() + " 条");
        if (pos + 1 < items.size()) {
            ui.postDelayed(new Runnable() { public void run() { pos++; showTake(); } }, 800);
        } else {
            ui.postDelayed(new Runnable() { public void run() { showReview(); } }, 900);
        }
    }

    // ------------------------------------------------------------------ step 3

    private void showReview() {
        page.removeAllViews();
        text("步骤 3/3 · 检查与训练", 12f, false);
        text("录音完成 " + infos.size() + "/" + items.size() + " 条。下面每条都可以点「重录」；"
                + "确认后进入声库资料（立绘、名称）和训练。", 12.5f, true);
        for (int i = 0; i < infos.size(); i++) {
            final RecordGuide.TakeInfo t = infos.get(i);
            LinearLayout r = row();
            TextView tv = new TextView(act);
            tv.setText(t.symbol + "  " + (t.ms / 1000.0) + "s" + (t.f0 > 0 ? "  " + t.f0 + "Hz" : "")
                    + "  " + t.quality);
            tv.setTextSize(12.5f);
            tv.setTextColor(t.quality.startsWith("可用") ? 0xFF9AD0A0 : 0xFFE0B57A);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(tv);
            Button re = small("重录");
            re.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    for (int k = 0; k < items.size(); k++) if (items.get(k).symbol.equals(t.symbol)) pos = k;
                    showTake();
                }
            });
            r.addView(re);
            page.addView(r);
        }
        if (infos.isEmpty()) {
            text("还没有录到任何一条。可以返回补录，或者关闭向导后用「选择采样 WAV…」导入已有录音。", 12f, true);
        }
        LinearLayout acts = row();
        Button back = small("补录");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pos = firstMissing(); showTake(); }
        });
        Button go = primary("训练并安装");
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (samples.isEmpty()) return;
                String json = RecordGuide.metaJson(name, lang, level, pitchIdx, infos);
                if (dlg != null) dlg.dismiss();
                host.onRecordingFinished(name, lang, new ArrayList<CustomBankBuilder.Sample>(samples), json);
            }
        });
        acts.addView(back);
        acts.addView(go);
        page.addView(acts);
    }

    // ------------------------------------------------------------------ helpers

    private int takeCount(String symbol) {
        int n = 0;
        for (RecordGuide.TakeInfo t : infos) if (symbol.equals(t.symbol)) n++;
        return n;
    }

    private RecordGuide.TakeInfo findTake(String symbol) {
        for (RecordGuide.TakeInfo t : infos) if (symbol.equals(t.symbol)) return t;
        return null;
    }

    private int doneCount() {
        int n = 0;
        for (RecordGuide.Item it : items) if (findTake(it.symbol) != null) n++;
        return n;
    }

    private void removeTake(String symbol) {
        for (int i = infos.size() - 1; i >= 0; i--) {
            if (symbol.equals(infos.get(i).symbol)) {
                infos.remove(i);
                if (i < samples.size()) samples.remove(i);
            }
        }
    }

    private void abortMic() {
        recording = false;
        try { mic.cancel(); } catch (Throwable ignored) { }
    }

    private TextView text(String s, float size, boolean dim) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setPadding(0, dp(6), 0, dp(6));
        if (dim) tv.setTextColor(0xFFB9C6D4);
        page.addView(tv);
        return tv;
    }

    private void sectionTitle(String s) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(12.5f);
        tv.setTextColor(0xFF8FA6BC);
        tv.setPadding(0, dp(10), 0, dp(2));
        page.addView(tv);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, dp(4));
        return r;
    }

    private Button small(String s) {
        Button b = new Button(act);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private Button primary(String s) {
        Button b = small(s);
        b.setPadding(0, dp(10), 0, dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
