package com.dsh.v4tune;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.v4tune.audio.AudioEngine;
import com.dsh.v4tune.audio.Demo;
import com.dsh.v4tune.audio.Mixer;
import com.dsh.v4tune.audio.Plugin;
import com.dsh.v4tune.audio.PluginChain;
import com.dsh.v4tune.i18n.L;
import com.dsh.v4tune.io.LyricFill;
import com.dsh.v4tune.io.ProjectIO;
import com.dsh.v4tune.io.Storage;
import com.dsh.v4tune.io.WavReader;
import com.dsh.v4tune.model.AudioClip;
import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.NoteCheck;

import java.util.ArrayList;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.TimeUtil;
import com.dsh.v4tune.phoneme.ChineseG2P;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.SingerLibrary;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import com.dsh.v4tune.ui.ParamView;
import com.dsh.v4tune.ui.PianoRollView;
import com.dsh.v4tune.ui.Theme;

import java.io.File;
import java.util.ArrayDeque;

public class MainActivity extends Activity implements PianoRollView.Listener {

    private static final int REQ_OPEN = 1001, REQ_SAVE = 1002, REQ_PACK = 1003, REQ_PACK_DIR = 1004,
            REQ_WAV = 1005;
    private com.dsh.v4tune.io.PackStore packStore;

    private Song song;
    private Uri currentUri;
    private String projectName = "未命名";
    private int trackIndex = 0;
    /** true while the background pack load runs */
    private volatile boolean packsLoading;
    /** the user asked to play before the load finished */
    private boolean pendingPlay;
    /** the workspace shortcut, only visible while the AI is on */
    private Button aiBtn;
    /** keeps a note phoneme when its lyric is edited; off unless enabled in settings */
    private boolean phonemeLock;
    /** ms the cursor is pulled back so it matches the sound the user hears */
    private int cursorOffsetMs;
    /** experimental AI + external control settings (both off by default) */
    private com.dsh.v4tune.ai.AiConfig aiCfg;
    private com.dsh.v4tune.ai.ExtApi extApi;
    private volatile boolean aiBusy;
    private String aiLastError;
    private String aiLastReply;
    private int aiLastApplied;
    private final com.dsh.v4tune.ai.ExtApi.Handler extHandler = new com.dsh.v4tune.ai.ExtApi.Handler() {
        public String statusJson() {
            try {
                return onUi(new java.util.concurrent.Callable<String>() {
                    public String call() { return extStatus(); }
                }, 8000);
            } catch (Throwable t) {
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.FALSE, "error", String.valueOf(t.getMessage()));
            }
        }
        public String command(final String action, final java.util.Map<String, Object> args) throws Exception {
            return onUi(new java.util.concurrent.Callable<String>() {
                public String call() { return extCommand(action, args); }
            }, 20000);
        }
    };
    private Button trackBtn = null;
    private final Mixer mixer = new Mixer();
    private final PluginChain pluginChain = new PluginChain();
    private final java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>> clips
            = new java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>>();
    private VoiceModel currentVoice = null;      // BriVoice ships no voice: only installed voicebanks
    private int snapTicks = 240;
    private SliderBox snapBox, tempoBox, zoomBox;
    /** zoom steps as a percentage of the 0.25 px/tick reference */
    private static final int[] ZOOM_LEVELS = {25, 50, 75, 100, 150, 200, 300, 400};
    private static final float ZOOM_REF_PX_PER_TICK = 0.25f;
    /** snap choices offered by the slider: 1/16, 1/8, 1/4, 1/2, 1/1, off */
    private static final int[] SNAP_CHOICES = {120, 240, 480, 960, 1920, 0};
    private static final int SNAP_STEPS = SNAP_CHOICES.length;
    private static final int TEMPO_MIN = 40, TEMPO_MAX = 240;

    private PianoRollView roll;
    private ParamView paramView;
    private TextView posText, statusText;
    private Button playBtn;
    private final AudioEngine engine = new AudioEngine();
    private AudioEngine.Rendered rendered;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ArrayDeque<Song> undo = new ArrayDeque<Song>();
    private final ArrayDeque<Song> redo = new ArrayDeque<Song>();
    /** state as of the last committed edit - what undo has to restore */
    private Song baseline;
    /** playback cursor (tick) - draggable on the ruler / playhead */
    private long startTick = 0L;
    private long lastAuditionAt = 0L;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadDictionary();
        migrateOldPacks();
        packStore = new com.dsh.v4tune.io.PackStore(com.dsh.v4tune.io.PackStore.defaultDir(this));
        aiCfg = com.dsh.v4tune.ai.AiConfig.load(this);
        phonemeLock = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("phonemeLock", false);
        cursorOffsetMs = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("cursorOffsetMs", 0);
        Renderer.measuredNoise = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("mnoise", false);
        Renderer.noiseGain = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("mnoiseGain", 100) / 100f;
        Renderer.frameSequence = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("frameseq", false);
        engine.cursorOffsetMs = cursorOffsetMs;
        reloadPacksAsync(false);
        applyExtServer();          // only starts anything when the user has enabled it
        song = Demo.build();
        baseline = song.copy();
        setContentView(buildUi());
        refreshAll();
        scrollToContent();
        updateAiButton();          // the workspace shortcut only exists once the UI is built
        if (SingerLibrary.isEmpty()) {
            status("BriVoice · 尚未安装声库 —— 点「声库 → 导入声库文件」载入你自己的 .v4voice 声库");
        } else {
            status("BriVoice · 已安装 " + SingerLibrary.userPackCount() + " 个声库：" + singerName(currentVoice));
        }
        L.setLang(getSharedPreferences("brivoice", MODE_PRIVATE).getInt("lang", 0));
        boolean rootOk = Storage.ensureAll(this);
        boolean asked = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("rootAsked", false);
        if (!rootOk && !asked) {
            getSharedPreferences("brivoice", MODE_PRIVATE).edit().putBoolean("rootAsked", true).apply();
            // no "all files access" yet: offer the one-time grant, everything still works through
            // the app's own external folder in the meantime
            new AlertDialog.Builder(this)
                    .setTitle("让 BriVoice 使用 " + Storage.ROOT_NAME + " 文件夹")
                    .setMessage("工程、声库和导出的 WAV 会统一放在：\n\n"
                            + Storage.publicRoot().getAbsolutePath() + "\n\n"
                            + "这需要「所有文件访问」权限。未授权时文件会先放在应用自己的目录里"
                            + "（" + Storage.root(this).getAbsolutePath() + "）。")
                    .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { askAllFilesAccess(); }
                    })
                    .setNegativeButton("以后再说", null)
                    .show();
        }
        handleViewIntent(getIntent());
    }

    private void loadDictionary() {
        try {
            ChineseG2P.init(getAssets().open("dict/pinyin.txt.gz"));
            status("拼音词典已载入");
        } catch (Throwable t) {
            // dictionary is optional: pinyin lyrics still work, Hanzi falls back to "a"
        }
    }

    // ------------------------------------------------------------------ UI

    /** true when the device is held sideways (the layout arranges itself accordingly) */
    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        // the manifest handles orientation changes itself, so the song stays in memory; only the
        // arrangement is rebuilt and the view state (cursor, selection, scroll) is restored
        long cursor = startTick;
        setContentView(buildUi());
        refreshAll();
        if (cursor > 0) {
            roll.setPlayheadTick(cursor);
            paramView.setPlayheadTick(cursor);
        }
        status(isLandscape() ? "已切换为横屏布局" : "已切换为竖屏布局");
    }

    private View buildUi() {
        boolean land = isLandscape();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG_DEEP);

        // In landscape the window is short and wide, so the controls move into a side column and the
        // piano roll gets the full height; in portrait they stay as a top strip.
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(land ? LinearLayout.VERTICAL : LinearLayout.VERTICAL);
        if (!land) root.addView(toolbar(false));
        if (land) {
            android.widget.ScrollView side = new android.widget.ScrollView(this);
            side.setBackgroundColor(Color.parseColor("#12141A"));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(toolbar(true));
            col.addView(transportBar(true));
            side.addView(col);
            root.addView(side, new LinearLayout.LayoutParams(dp(228), ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // portrait: the row now carries sliders too, so let it scroll instead of clipping
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setBackgroundColor(Theme.BG_DEEP);
            hs.addView(transportBar(false));
            root.addView(hs, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        roll = new PianoRollView(this);
        roll.setListener(this);
        roll.setSnapTicks(snapTicks);
        right.addView(roll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (zoomBox != null) zoomBox.bar.setProgress(zoomIndex());
        roll.setZoomListener(new PianoRollView.ZoomListener() {
            public void onZoomChanged(float pxPerTick) {
                // pinch / double-tap zoom keeps the slider and its readout in step
                if (zoomBox == null) return;
                zoomBox.bar.setProgress(zoomIndex());
                zoomBox.setValue(zoomText());
            }
        });

        paramView = new ParamView(this);
        paramView.setLabels(new String[]{
                L.t("param.dyn"), L.t("param.bre"), L.t("param.bri"), L.t("param.cle"),
                L.t("param.ope"), L.t("param.gen"), L.t("param.por"), L.t("param.pit"),
                L.t("param.pbs"), L.t("param.vib"), L.t("param.xsy"), L.t("param.gwl")});
        paramView.setListener(new ParamView.Listener() {
            public void onTickRangeChanged(float from, float to) {
                // keep the piano roll on the same window while the tuning lane is dragged
                if (roll != null && to > from) roll.setVisibleTickRange((long) from, (long) to);
            }
            public void onCurveEdited(String id) { commitEdit(); refreshAll(); }
            public void onParamSelected(String id) { status(L.t("status.param", id)); }
        });
        right.addView(paramView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(land ? 130 : 196)));

        statusText = new TextView(this);
        statusText.setTextColor(Theme.TEXT_DIM);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusText.setPadding(dp(12), dp(7), dp(12), dp(7));
        statusText.setBackground(Theme.card(this, Theme.PANEL));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(6), dp(3), dp(6), dp(6));
        right.addView(statusText, slp);

        // The weight axis follows the root orientation: in a VERTICAL layout the weight consumes
        // leftover HEIGHT (width must be MATCH_PARENT), in a HORIZONTAL one it consumes WIDTH.
        // Passing width=0 in portrait made the whole roll + parameter area zero pixels wide, which
        // showed up as a black bottom half with working buttons.
        root.addView(right, land
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View transportBar(boolean vertical) {
        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        transport.setBackgroundColor(Theme.BG_DEEP);
        transport.setPadding(dp(6), dp(4), dp(6), dp(4));

        playBtn = smallButton(L.t("transport.play"), new View.OnClickListener() {
            public void onClick(View v) { if (engine.isPlaying()) stopPlay(); else playSong(); }
        }, true);
        transport.addView(playBtn);
        transport.addView(smallButton(L.t("transport.fromstart"), new View.OnClickListener() {
            public void onClick(View v) { playFromStart(); }
        }));
        aiBtn = smallButton(L.t("transport.ai"), new View.OnClickListener() {
            public void onClick(View v) { aiAskDialog(); }
        }, true);
        aiBtn.setVisibility(View.GONE);
        transport.addView(aiBtn);
        transport.addView(smallButton(L.t("transport.preview.part"), new View.OnClickListener() {
            public void onClick(View v) { previewPart(); }
        }));
        transport.addView(smallButton(L.t("transport.singer"), new View.OnClickListener() {
            public void onClick(View v) { pickSinger(); }
        }));
        // snap / tempo / zoom are sliders; each one shows its own value and is the size of a button
        snapBox = new SliderBox(L.t("transport.snap.short"), snapValueText(), SNAP_STEPS - 1,
                snapIndex(snapTicks), new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                        if (!fromUser) return;
                        snapTicks = snapTicksAt(pr);
                        roll.setSnapTicks(snapTicks);
                        snapBox.setValue(snapValueText());
                        status(snapTicks == 0 ? L.t("status.snap.off") : L.t("status.snap", 1920 / snapTicks));
                    }
                    public void onStartTrackingTouch(SeekBar sb) { }
                    public void onStopTrackingTouch(SeekBar sb) { }
                }, new View.OnClickListener() {
                    public void onClick(View v) {
                        cycleSnap(null);
                        snapBox.bar.setProgress(snapIndex(snapTicks));
                    }
                });
        transport.addView(snapBox);

        tempoBox = new SliderBox(L.t("transport.tempo"), tempoText(), (TEMPO_MAX - TEMPO_MIN) * 100,
                tempoProgress(), new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                        if (!fromUser) return;
                        setTempo(TEMPO_MIN + pr / 100.0);
                        tempoBox.setValue(tempoText());
                    }
                    public void onStartTrackingTouch(SeekBar sb) { }
                    public void onStopTrackingTouch(SeekBar sb) { }
                }, new View.OnClickListener() {
                    public void onClick(View v) { editTempo(); }        // exact value by keyboard
                });
        transport.addView(tempoBox);

        zoomBox = new SliderBox(L.t("transport.zoom.short"), zoomText(),
                ZOOM_LEVELS.length - 1, zoomIndex(), new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                        if (!fromUser) return;
                        applyZoomLevel(pr);
                    }
                    public void onStartTrackingTouch(SeekBar sb) { }
                    public void onStopTrackingTouch(SeekBar sb) { }
                }, new View.OnClickListener() {
                    public void onClick(View v) { fitZoom(); zoomBox.bar.setProgress(zoomIndex()); }
                });
        transport.addView(zoomBox);

        transport.addView(smallButton(L.t("transport.lyric"), new View.OnClickListener() {
            public void onClick(View v) { editLyric(roll.getSelectedNote()); }
        }));
        trackBtn = smallButton("音轨 1", new View.OnClickListener() {
            public void onClick(View v) { trackMenu(); }
        });
        transport.addView(trackBtn);
        transport.addView(smallButton(L.t("menu.tools"), new View.OnClickListener() {
            public void onClick(View v) { toolsMenu(); }
        }));
        posText = new TextView(this);
        posText.setTextColor(Color.parseColor("#9FB0C8"));
        posText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        posText.setPadding(dp(8), 0, 0, 0);
        transport.addView(posText);
        return transport;
    }

    private View toolbar(boolean vertical) {
        if (vertical) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(6), dp(6), dp(6), dp(6));
            toolbarInto(col, true);
            return col;
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(Theme.BG_DEEP);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        toolbarInto(bar, false);
        hs.addView(bar);
        return hs;
    }

    /** fills one container with the toolbar buttons (horizontal strip or vertical column) */
    private void toolbarInto(LinearLayout bar, boolean vertical) {
        if (vertical) {
            bar.setBackgroundColor(Theme.BG_DEEP);
        }
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));

        TextView title = new TextView(this);
        title.setText("BriVoice");
        title.setTextColor(Theme.ACCENT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        title.setPadding(dp(4), 0, dp(12), 0);
        try {                                   // the app icon doubles as a logo mark
            int id = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
            if (id != 0) {
                android.graphics.drawable.Drawable ic = getResources().getDrawable(id);
                int sz = dp(26);
                ic.setBounds(0, 0, sz, sz);
                title.setCompoundDrawables(ic, null, null, null);
                title.setCompoundDrawablePadding(dp(7));
            }
        } catch (Throwable ignored) {
        }
        bar.addView(title);

        bar.addView(smallButton(L.t("toolbar.new"), new View.OnClickListener() {
            public void onClick(View v) { newProject(); }
        }));
        bar.addView(smallButton(L.t("toolbar.open"), new View.OnClickListener() {
            public void onClick(View v) { openProject(); }
        }));
        bar.addView(smallButton(L.t("toolbar.save"), new View.OnClickListener() {
            public void onClick(View v) { saveProject(false); }
        }));
        bar.addView(smallButton(L.t("toolbar.save.as"), new View.OnClickListener() {
            public void onClick(View v) { saveProject(true); }
        }));
        bar.addView(smallButton(L.t("toolbar.export.wav"), new View.OnClickListener() {
            public void onClick(View v) { exportWav(); }
        }));
        bar.addView(smallButton(L.t("toolbar.undo"), new View.OnClickListener() {
            public void onClick(View v) { doUndo(); }
        }));
        bar.addView(smallButton(L.t("toolbar.redo"), new View.OnClickListener() {
            public void onClick(View v) { doRedo(); }
        }));
        bar.addView(smallButton(L.t("toolbar.demo"), new View.OnClickListener() {
            public void onClick(View v) { beginEdit(); song = Demo.twinkle(); commitEdit(); refreshAll(); scrollToContent(); }
        }));
        bar.addView(smallButton(L.t("toolbar.voicebank"), new View.OnClickListener() {
            public void onClick(View v) { managePacks(); }
        }));
        bar.addView(smallButton(L.t("toolbar.about"), new View.OnClickListener() {
            public void onClick(View v) { about(); }
        }));
        if (vertical) {
            for (int i = 0; i < bar.getChildCount(); i++) {      // stretch the column buttons
                View c = bar.getChildAt(i);
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (lp instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) lp).width = ViewGroup.LayoutParams.MATCH_PARENT;
                    ((LinearLayout.LayoutParams) lp).setMargins(0, dp(2), 0, dp(2));
                    c.setLayoutParams(lp);
                }
            }
        }
    }

    /** width shared by every toolbar / transport button */
    private int uniformButtonWidth() {
        return dp(96);
    }

    private Button smallButton(String text, View.OnClickListener l) {
        return smallButton(text, l, false);
    }

    /** @param primary true for the one main action of a bar (filled with the accent colour) */
    private Button smallButton(String text, View.OnClickListener l, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        b.setTextColor(primary ? Theme.ON_PRIMARY : Theme.TEXT);
        if (primary) b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setBackground(Theme.button(this, primary));
        b.setPadding(dp(12), dp(5), dp(12), dp(5));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);          // no drop shadow: flat night look
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setGravity(Gravity.CENTER);
        // every button gets the same width so the bars read as a grid; the widest label of the app
        // ("导出WAV", "另存为") still fits comfortably
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(uniformButtonWidth(), dp(38));
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------- listeners

    public void onSelectionChanged(Note n) {
        if (n != null) status("音符 " + n.lyric + "  key=" + n.key + "  pos=" + n.pos + "  dur=" + n.dur);
    }

    public void onNoteEdited(Note n, String what) {
        commitEdit();
        roll.refresh();
        status("编辑: " + what + (n != null ? "  " + n.lyric : ""));
    }

    public void onRequestLyricEdit(Note n) {
        editLyric(n);
    }

    /** piano key pressed: audition a short "a" at that pitch */
    public void onKeyAudition(final int key) {
        if (currentVoice == null) return;      // no voicebank installed -> nothing to audition
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastAuditionAt < 70L) return;
        lastAuditionAt = now;
        final VoiceModel vm = currentVoice;
        new Thread(new Runnable() {
            public void run() {
                try {
                    engine.audition(AudioEngine.renderKey(vm, key), AudioEngine.SR);
                } catch (Throwable ignored) {
                }
            }
        }, "v4tune-audition").start();
    }

    /** playback cursor dragged */
    public void onPlayheadMoved(long tick) {
        startTick = Math.max(0L, tick);
        posText.setText(formatTick(startTick));
        paramView.setPlayheadTick(startTick);
        status("播放位置: " + formatTick(startTick));
    }

    // ---------------------------------------------------------------- actions

    private void refreshAll() {
        roll.setSongKeepView(song);      // keep the view still while editing
        roll.setTrackIndex(trackIndex);
        if (roll != null) roll.setClips(clipsFor(trackIndex));
        if (trackBtn != null) {
            String tn = (song.tracks != null && trackIndex < song.tracks.size()) ? song.tracks.get(trackIndex).name : null;
            trackBtn.setText(L.t("track.name", trackIndex + 1) + (tn == null || tn.isEmpty() ? "" : (" " + tn)));
        }
        roll.setSnapTicks(snapTicks);
        Part p = currentPart();
        paramView.setSong(song);
        paramView.setPart(p);
        roll.refresh();
        paramView.refresh();
    }

    /** jump the roll and the parameter view to the first note of the song */
    private void scrollToContent() {
        long first = Long.MAX_VALUE, last = 0;
        for (com.dsh.v4tune.model.Track t : song.tracks) {
            for (Part p : t.parts) {
                for (Note n : p.notes) {
                    if (n.pos < first) first = n.pos;
                    if (n.end() > last) last = n.end();
                }
            }
        }
        if (first == Long.MAX_VALUE) { first = 0; last = 1920 * 4; }
        long pad = Math.max(960, (last - first) / 8);
        roll.setPlayheadTick(-1);
        paramView.setPlayheadTick(-1);
        roll.setVisibleTickRange(Math.max(0, first - pad), last + pad);
        paramView.setTickRange(Math.max(0, first - pad), last + pad);
        posText.setText(formatTick(first));
    }

    /** the clips of the current track, as JSON */
    private String clipJson() {
        java.util.List<AudioClip> cs = clipsFor(trackIndex);
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < cs.size(); i++) {
            AudioClip c = cs.get(i);
            if (i > 0) b.append(',');
            b.append(com.dsh.v4tune.ai.Json.obj("index", Integer.valueOf(i),
                    "name", c.name == null ? "" : c.name,
                    "startTick", Long.valueOf(c.startTick),
                    "frames", Integer.valueOf(c.data == null ? 0 : c.data.frames)));
        }
        return b.append(']').toString();
    }
    // --------------------------------------------------- streaming (render while playing)

    /** the producer of the current streaming playback, or null */
    private StreamProducer streamProducer;

    /** asks for the next slice: renders every audible track into it, then post-processes the new range */
    private final class StreamProducer implements com.dsh.v4tune.audio.AudioEngine.StreamSource {
        private final Song sng;
        private final Renderer.Cfg cfg;
        private final VoiceModel fallback;
        private final int total;
        private final float[] bufL, bufR;
        private volatile int ready;
        private volatile boolean finished, cancelled;

        StreamProducer(Song s, VoiceModel fb) {
            sng = s;
            fallback = fb;
            cfg = new Renderer.Cfg();
            total = Math.max(1, com.dsh.v4tune.audio.AudioEngine.trackFrames(s, cfg));
            bufL = new float[total];
            bufR = new float[total];
        }

        void start() {
            Thread t = new Thread(new Runnable() { public void run() { produce(); } }, "brivoice-render");
            t.setDaemon(true);
            t.start();
        }

        void cancel() { cancelled = true; }

        public Song song() { return sng; }
        public int ready() { return ready; }
        public int total() { return total; }
        public boolean finished() { return finished; }
        public float[] left() { return bufL; }
        public float[] right() { return bufR; }

        /** one second first, then double: the total work stays about twice a single render */
        private void produce() {
            try {
                int limit = Math.min(total, Math.max(1024, cfg.sampleRate));
                while (!cancelled) {
                    float[] mixL = new float[limit];
                    float[] mixR = new float[limit];
                    boolean anySolo = false;
                    for (com.dsh.v4tune.model.Track t : sng.tracks) if (t.solo) anySolo = true;
                    int trackNo = -1;
                    for (com.dsh.v4tune.model.Track t : sng.tracks) {
                        trackNo++;
                        if (t.mute) continue;
                        if (anySolo && !t.solo) continue;
                        double vol = Math.pow(10, (t.vol / 100.0) / 20.0);
                        double pan = (t.pan - 64) / 64.0;
                        if (mixer != null && trackNo < mixer.trackCount()) {
                            if (!mixer.isAudible(trackNo)) continue;
                            vol = mixer.getGain(trackNo);
                            pan = mixer.getPan(trackNo);
                        }
                        double gl = vol * Math.min(1.0, 1.0 - pan);
                        double gr = vol * Math.min(1.0, 1.0 + pan);
                        for (com.dsh.v4tune.model.Part p : t.parts) {
                            if (cancelled) return;
                            VoiceModel vm2 = com.dsh.v4tune.audio.AudioEngine.modelFor(sng.voice(p.bs, p.pc), fallback);
                            if (vm2 == null) vm2 = fallback;
                            int at = (int) Math.round(TimeUtil.tickToSec(sng, p.pos) * cfg.sampleRate);
                            int room = limit - at;
                            if (room <= 0) continue;
                            float[] mono = Renderer.renderPart(sng, p, vm2, cfg, room);
                            int n = Math.min(mono.length, room);
                            for (int i = 0; i < n; i++) {
                                mixL[at + i] += (float) (mono[i] * gl);
                                mixR[at + i] += (float) (mono[i] * gr);
                            }
                        }
                    }
                    System.arraycopy(mixL, 0, bufL, 0, limit);
                    System.arraycopy(mixR, 0, bufR, 0, limit);
                    com.dsh.v4tune.audio.AudioEngine.Rendered rr = new com.dsh.v4tune.audio.AudioEngine.Rendered();
                    rr.left = bufL; rr.right = bufR; rr.sampleRate = cfg.sampleRate; rr.song = sng;
                    applyPostDspRange(rr, Math.max(0, ready), limit);
                    ready = limit;
                    if (limit >= total) { finished = true; return; }
                    limit = Math.min(total, limit * 2);
                }
            } catch (Throwable t) {
                final String m = String.valueOf(t);
                ui.post(new Runnable() { public void run() { status(L.t("status.stream.fail", m)); } });
            } finally {
                finished = true;
            }
        }
    }

    /** clips and the plugin chain for one freshly rendered slice */
    private void applyPostDspRange(com.dsh.v4tune.audio.AudioEngine.Rendered r, int from, int to) {
        if (r == null || r.left == null || to <= from) return;
        if (to > r.left.length) to = r.left.length;
        for (int ti = 0; ti < clips.size() && ti < song.tracks.size(); ti++) {
            if (mixer.trackCount() > ti && !mixer.isAudible(ti)) continue;
            float g = (mixer.trackCount() > ti) ? mixer.getGain(ti) : 1f;
            float pn = (mixer.trackCount() > ti) ? mixer.getPan(ti) : 0f;
            double gl = Mixer.panLeftGain(pn) * g, gr = Mixer.panRightGain(pn) * g;
            for (AudioClip c : clips.get(ti)) {
                if (c == null || c.data == null) continue;
                int at = c.startFrame(song);
                int s0 = Math.max(from, at);
                int s1 = Math.min(to, at + c.data.frames);
                int len = s1 - s0;
                if (len <= 0) continue;
                float[] tl = new float[len], tr = new float[len];
                c.mixInto(tl, tr, -(s0 - at));
                for (int i = 0; i < len; i++) {
                    r.left[s0 + i] += (float) (tl[i] * gl);
                    if (r.right != null) r.right[s0 + i] += (float) (tr[i] * gr);
                }
            }
        }
        if (pluginChain.size() > 0) {
            float[] l = java.util.Arrays.copyOfRange(r.left, from, to);
            float[] rr2 = (r.right == null) ? null : java.util.Arrays.copyOfRange(r.right, from, to);
            pluginChain.prepareAll(r.sampleRate, l.length);
            pluginChain.process(l, rr2, l.length);
            for (int i = 0; i < l.length; i++) {
                r.left[from + i] = Mixer.softLimit(l[i]);
                if (r.right != null && rr2 != null) r.right[from + i] = Mixer.softLimit(rr2[i]);
            }
        }
    }
    /** renders the song (with clips and plugins) straight to a file: used by the external API */
    private java.io.File exportWavTo(java.io.File f) throws Exception {
        ensureMixerTracks();
        engine.mixer = mixer;
        com.dsh.v4tune.audio.AudioEngine.Rendered r =
                engine.render(song, currentVoice, new Renderer.Cfg(), null);
        applyPostDsp(r);
        java.io.File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        com.dsh.v4tune.audio.AudioEngine.writeWav(f, r);
        return f;
    }
    /** the clip list of a track, created on demand */
    private java.util.List<AudioClip> clipsFor(int ti) {
        while (clips.size() <= ti) clips.add(new java.util.ArrayList<AudioClip>());
        return clips.get(ti);
    }

    /** a clip drag started: snapshot so it can be undone */
    public void onClipDragStart() { beginEdit(); }

    /** a clip was dragged: commit and report */
    public void onClipEdited(AudioClip c, long oldTick) {
        if (c == null) return;
        commitEdit();
        refreshAll();
        status(L.t("status.clip.moved", c.name == null ? "audio" : c.name, formatTick(c.startTick)));
    }

    private Part currentPart() {
        if (song == null || song.tracks.isEmpty()) return null;
        if (trackIndex >= song.tracks.size()) trackIndex = 0;
        if (song.tracks.get(trackIndex).parts.isEmpty()) {
            song.tracks.get(trackIndex).parts.add(new Part());
        }
        return song.tracks.get(trackIndex).parts.get(0);
    }

    /** call BEFORE we change the model ourselves (dialogs, tempo, singer, open file) */
    private void beginEdit() {
        if (song != null) baseline = song.copy();
    }

    /**
     * Commit an edit. Works for both orders: our own dialogs (beginEdit ... mutate ... commitEdit)
     * and view-driven edits (the view mutated the model before notifying us), because the snapshot
     * pushed is always the state as of the previous commit.
     */
    private void commitEdit() {
        if (song == null) return;
        if (baseline != null) {
            undo.push(baseline);
            while (undo.size() > 24) undo.removeLast();
            redo.clear();
        }
        baseline = song.copy();
    }

    private void doUndo() {
        if (undo.isEmpty()) { status("没有可撤销的操作"); return; }
        redo.push(song.copy());
        song = undo.pop();
        baseline = song.copy();
        refreshAll();
        status("已撤销（可重做 " + redo.size() + " 步）");
    }

    private void doRedo() {
        if (redo.isEmpty()) { status("没有可重做的操作"); return; }
        undo.push(song.copy());
        while (undo.size() > 24) undo.removeLast();
        song = redo.pop();
        baseline = song.copy();
        refreshAll();
        status("已重做（可撤销 " + undo.size() + " 步）");
    }

    private void cycleSnap(Button b) {
        if (snapTicks == 480) snapTicks = 240;
        else if (snapTicks == 240) snapTicks = 120;
        else if (snapTicks == 120) snapTicks = 60;
        else if (snapTicks == 60) snapTicks = 0;
        else snapTicks = 480;
        roll.setSnapTicks(snapTicks);
        if (b != null) b.setText(snapText());
        status(snapTicks == 0 ? "网格吸附已关闭" : ("网格吸附: " + (1920 / snapTicks) + " 分音符"));
    }

    private void editLyric(final Note n) {
        if (n == null) { status("请先选中一个音符"); return; }
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(n.lyric);
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.lyric.title"))
                .setView(et)
                .setPositiveButton(L.t("dlg.lyric.ok"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        beginEdit();
                        n.lyric = et.getText().toString().trim();
                        boolean kept = false;
                        if (phonemeLock && n.phoneme != null && n.phoneme.trim().length() > 0) {
                            n.lockPhoneme = true;      // keep the phonemes this note already sings
                            kept = true;
                        } else {
                            n.phoneme = "";           // default: regenerate from the new lyric
                            n.lockPhoneme = false;
                        }
                        commitEdit();
                        roll.refresh();
                        if (kept) {
                            status(L.t("status.lyric.locked", n.lyric));
                        } else {
                            String ph = com.dsh.v4tune.synth.Renderer.resolvePhonemes(n, currentVoice).toString();
                            status(L.t("status.lyric.set", n.lyric, ph));
                        }
                    }
                })
                .setNeutralButton(L.t("dlg.lyric.clear.lock"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { n.phoneme = ""; n.lockPhoneme = false; roll.refresh(); status(L.t("set.phoneme.state.off")); }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    /** pick a sensible active voicebank after a load/delete */
    /**
     * Voicebank name in the current UI language. Packs carry a Chinese, English and Japanese name;
     * the "my voicebank" tag is appended here instead of being baked into the pack.
     */
    /**
     * BriVoice naming: the singers the app knows about are always shown as "<singer> BV", no matter
     * what the pack file carries in its name field. That way renamed packs - and packs converted
     * before this version - show up consistently without having to be imported again.
     */
    private String bvName(String raw) {
        if (raw == null) return null;
        String low = raw.toLowerCase();
        if (low.contains("miku") || raw.contains("初音") || raw.contains("ミク")) return "Miku BV";
        if (low.contains("tianyi") || low.contains("luo") || raw.contains("洛天依")) return "洛天依 BV";
        return raw;
    }

    private String singerName(VoiceModel v) {
        if (v == null) return "";
        String n = bvName(v.nameFor(L.lang()));
        if (SingerLibrary.isBuiltin(v)) {
            return n + " " + L.t("singer.tag.builtin");
        }
        if (SingerLibrary.isUserPack(v)) {
            // packs made before v3.0 baked the "my voicebank" tag into their own name; drop it and
            // append the localised one instead, so the tag follows the UI language
            n = n.replace("（我的声库）", "").replace("(installed)", "").trim();
            n = n + " " + L.t("singer.tag.mine");
        }
        return n;
    }

    private void chooseInitialVoice() {
        java.util.List<VoiceModel> all = SingerLibrary.all();
        if (all.isEmpty()) { currentVoice = null; return; }
        for (VoiceModel v : all) {
            if (currentVoice != null && v.id != null && v.id.equals(currentVoice.id)) return;
        }
        currentVoice = all.get(0);
        Part p = currentPart();
        if (p != null) applyVoiceToPart(currentVoice);
    }

    /** refuse to render without a voicebank, with a clear instruction */
    private boolean requireVoice() {
        if (currentVoice != null) return true;
        if (packsLoading) {
            // the voicebanks are parsed in the background; the user can easily hit play first
            pendingPlay = true;
            status(L.t("status.packs.loading"));
            return false;
        }
        new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.no.voice.title"))
                .setMessage(L.t("dlg.no.voice.msg"))
                .setPositiveButton("去导入", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { importPack(); }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
        status("请先安装声库");
        return false;
    }

    /**
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
            int nl = label.indexOf('\n');
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

    private void pickSinger() {
        final java.util.List<VoiceModel> all = SingerLibrary.all();
        java.util.List<com.dsh.v4tune.ui.Dialogs.Row> rows = com.dsh.v4tune.ui.Dialogs.rows();
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
                L.t("dlg.singer.sub", rows.size()), rows);
    }

    private void applyVoiceToPart(VoiceModel vm) {
        if (vm == null) return;
        Part p = currentPart();
        if (p == null) return;
        com.dsh.v4tune.model.Voice v = null;
        for (com.dsh.v4tune.model.Voice x : song.voices) {
            boolean sameName = x.name != null && x.name.equals(vm.name);
            boolean sameId = x.compID != null && x.compID.equals(vm.compID);
            if (sameName && sameId) { v = x; break; }   // an imported pack may share the official compID
        }
        if (v == null) {
            v = new com.dsh.v4tune.model.Voice();
            int maxBs = 0;
            for (com.dsh.v4tune.model.Voice x : song.voices) maxBs = Math.max(maxBs, x.bs);
            v.bs = maxBs + 4;
            v.pc = 0;
            v.compID = vm.compID;
            v.name = vm.name;
            v.lang = vm.lang;
            song.voices.add(v);
        }
        p.bs = v.bs;
        p.pc = v.pc;
    }

    // ------------------------------------------------------------- voice packs

    /** voicebanks used to live in the internal data dir; move them to external storage once */
    private void migrateOldPacks() {
        try {
            java.io.File old = new java.io.File(getFilesDir(), "singers");
            java.io.File now = com.dsh.v4tune.io.PackStore.defaultDir(this);
            if (!old.isDirectory()) return;
            java.io.File[] fs = old.listFiles();
            if (fs == null) return;
            int moved = 0;
            for (java.io.File f : fs) {
                if (!f.getName().endsWith(".v4voice")) continue;
                java.io.File dst = new java.io.File(now, f.getName());
                if (dst.exists() && dst.length() >= f.length()) continue;
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                in.close();
                out.close();
                moved++;
            }
            if (moved > 0) status("已把 " + moved + " 个旧声库迁移到 " + now.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Voicebanks are parsed OFF the main thread: a 130-190 KB pack costs ~150 ms on a desktop JVM
     * but around five seconds per pack on a phone, and doing that in onCreate kept the splash
     * screen up for ten seconds. The UI now comes up immediately and fills in when the parse ends.
     */
    /**
     * Voicebanks shipped inside the APK (app/assets/singers) are copied into the voicebank folder
     * on first run - once. The flag is remembered, so deleting a bundled voicebank in the UI keeps
     * it deleted instead of having it silently come back on the next start.
     */
    /**
     * Signature of a bundled voicebank: its length plus a CRC of its bytes.
     *
     * The installer used to remember only THAT a bundled bank had been installed, so a rebuilt bank
     * (v6.3 halves the vowel formant deviation) never reached a device that had installed the old one.
     * Storing the signature re-installs only when the shipped file actually changed; a user who deletes
     * the bank keeps it deleted, because the stored signature stays put.
     */
    private String bundledSignature(String name) {
        java.io.InputStream in = null;
        try {
            in = getAssets().open("singers/" + name);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
                total += n;
            }
            return total + ":" + crc.getValue();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }
    private void installBundledVoicebanks() {
        try {
            String[] names = getAssets().list("singers");
            if (names == null || names.length == 0) return;
            java.util.HashSet<String> ids = new java.util.HashSet<String>(
                    getSharedPreferences("brivoice", MODE_PRIVATE).getStringSet("builtinIds",
                            new java.util.HashSet<String>()));
            for (String n : names) {
                if (n == null || !n.toLowerCase(java.util.Locale.US).endsWith(".v4voice")) continue;
                String flag = "builtin_sig_" + n;
                String sig = bundledSignature(n);
                String storedSig = getSharedPreferences("brivoice", MODE_PRIVATE).getString(flag, null);
                if (sig != null && sig.equals(storedSig)) continue;
                java.io.InputStream in = null;
                VoicePack.Result res = null;
                try {
                    in = getAssets().open("singers/" + n);
                    res = packStore.importStream(in, n, true);
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                }
                if (sig != null) {
                    getSharedPreferences("brivoice", MODE_PRIVATE).edit().putString(flag, sig).apply();
                }
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
                    rs = new java.util.ArrayList<VoicePack.Result>(packStore.loadAll());
                    // also pick up packs the user simply dropped into BriVoice_root/voicebanks
                    File extraDir = Storage.voicebanks(MainActivity.this);
                    if (!extraDir.equals(packStore.directory())) {
                        rs.addAll(new com.dsh.v4tune.io.PackStore(extraDir).loadAll());
                    }
                } catch (Throwable t) {          // OutOfMemoryError included: never lose the thread
                    err = String.valueOf(t);
                }
                final java.util.List<VoicePack.Result> rsF = rs;
                final String errF = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (rsF == null) {
                            status("声库载入失败: " + errF);
                            return;
                        }
                        applyPacks(rsF, announce);
                    }
                });
            }
        }, "pack-load").start();
    }

    private void applyPacks(java.util.List<VoicePack.Result> rs, boolean announce) {
        if (rs.isEmpty()) {
            File d1 = packStore.directory();
            File d2 = Storage.voicebanks(this);
            android.util.Log.w("V4Tune", "no pack loaded; store=" + d1 + " [" + listPacks(d1) + "]"
                    + " root=" + d2 + " [" + listPacks(d2) + "] canWriteRoot=" + Storage.canWriteRoot(this));
        }
        java.util.ArrayList<VoiceModel> ms = new java.util.ArrayList<VoiceModel>();
        StringBuilder bad = new StringBuilder();
        java.util.HashSet<String> seenId = new java.util.HashSet<String>();
        for (VoicePack.Result r : rs) {
            if (!r.ok()) {
                bad.append(r.error).append("\n");
                continue;
            }
            // the same pack can sit in both the installed folder and BriVoice_root/voicebanks;
            // count it once (first hit wins)
            String key = (r.model.id == null || r.model.id.isEmpty())
                    ? String.valueOf(r.model.name) : r.model.id;
            if (seenId.add(key)) ms.add(r.model);
        }
        SingerLibrary.setUserPacks(ms);
        packsLoading = false;
        chooseInitialVoice();
        refreshAll();
        if (pendingPlay && currentVoice != null) {     // the user was waiting for this
            pendingPlay = false;
            playSong();
            return;
        }
        if (bad.length() > 0 && announce) toast("部分声库无法载入：\n" + bad);
        // the packs finish loading after the UI is already up, so the status line must be updated
        // here too - otherwise the app still claims "no voicebank installed" while it has some
        if (ms.isEmpty()) {
            status(L.t("status.no.voice"));
        } else {
            status("BriVoice · 已安装 " + ms.size() + " 个声库："
                    + (currentVoice != null ? singerName(currentVoice) : ""));
        }
    }

    private static String listPacks(File d) {
        try {
            String[] n = d.list();
            if (n == null) return "no dir";
            StringBuilder b = new StringBuilder();
            for (String s : n) {
                if (s.endsWith(".v4voice")) b.append(b.length() > 0 ? "," : "").append(s);
            }
            return b.length() == 0 ? "empty" : b.toString();
        } catch (Throwable t) {
            return "err " + t;
        }
    }

    private void reloadPacks(boolean announce) {
        java.util.List<VoicePack.Result> rs = packStore.loadAll();
        java.util.ArrayList<VoiceModel> ms = new java.util.ArrayList<VoiceModel>();
        StringBuilder bad = new StringBuilder();
        for (VoicePack.Result r : rs) {
            if (r.ok()) ms.add(r.model);
            else bad.append(r.error).append("\n");
        }
        SingerLibrary.setUserPacks(ms);
        if (bad.length() > 0 && announce) toast("部分声库无法载入：\n" + bad);
        if (announce) status("已载入 " + ms.size() + " 个自装声库，共 " + SingerLibrary.all().size() + " 位歌姬");
    }


    // ------------------------------------------------------------- tools

    /** 语言 / Language / 言語 */
    private void languageMenu() {
        final String[] names = L.languageNames();
        menu(L.t("menu.tools.lang"), null, names, -1, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        L.setLang(which);
                        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                                .putInt("lang", which).apply();
                        setContentView(buildUi());
                        refreshAll();
                        status(L.t("status.lang.switched"));
                    }
                });
    }


    // ------------------------------------------------------------- audio clips

    private void importWav() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/wav", "audio/x-wav", "audio/wave",
                "audio/vnd.wave", "audio/*", "application/octet-stream"});
        startActivityForResult(i, REQ_WAV);
    }

    private void addWavFrom(Uri uri) {
        try {
            java.io.InputStream in = ProjectIO.openAny(this, uri);
            WavReader.AudioData d = WavReader.read(in, 44100 * 180);       // up to 3 minutes (memory)
            try { in.close(); } catch (Throwable ignored) { }
            if (d == null || !d.ok()) {
                status(L.t("status.wav.fail", d == null ? "?" : d.error));
                new AlertDialog.Builder(this).setTitle(L.t("dlg.wav.fail.title"))
                        .setMessage(d == null ? "?" : d.error).setPositiveButton(L.t("dlg.ok"), null).show();
                return;
            }
            String nm = uri.getLastPathSegment();
            // A clip dropped past the end of the song would never be mixed in: the render buffer
            // stops at the last note. Fall back to the start instead of a silent "imported".
            long songEnd = song.maxTick();
            long at = Math.max(0L, startTick);
            boolean moved = false;
            if (songEnd > 0 && at >= songEnd) { at = 0L; moved = true; }
            AudioClip c = AudioClip.fromWav(d, at, nm == null ? "audio" : nm);
            if (c == null) { status(L.t("status.wav.fail", "?")); return; }
            while (clips.size() <= trackIndex) clips.add(new java.util.ArrayList<AudioClip>());
            clips.get(trackIndex).add(c);
            status(moved ? L.t("status.wav.added.atstart", nm, d.frames / 44100.0)
                           : L.t("status.wav.added", nm, d.frames / 44100.0));
            toast(L.t("status.wav.added", nm, d.frames / 44100.0));
        } catch (Throwable t) {
            status(L.t("status.wav.fail", String.valueOf(t.getMessage())));
        }
    }

    private void clipMenu() {
        while (clips.size() <= trackIndex) clips.add(new java.util.ArrayList<AudioClip>());
        final java.util.ArrayList<AudioClip> list = clips.get(trackIndex);
        if (list.isEmpty()) { status(L.t("status.no.clips")); return; }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            AudioClip c = list.get(i);
            items[i] = c.name + String.format("  @%d  %.1fs", c.startTick,
                    c.data == null ? 0 : c.data.frames / 44100.0);
        }
        menu(L.t("dlg.clips.title"), null, items, -1, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, final int which) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(list.get(which).name)
                        .setMessage(L.t("clip.remove"))
                        .setPositiveButton(L.t("clip.remove"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d2, int w) {
                                list.remove(which);
                                status(L.t("status.clip.removed"));
                            }
                        })
                        .setNegativeButton(L.t("dlg.cancel"), null)
                        .show();
            }
        });
    }

    /** mix the audio clips of every track into the rendered buffer, then run the plugin chain */
    private void applyPostDsp(final com.dsh.v4tune.audio.AudioEngine.Rendered r) {
        if (r == null || r.left == null) return;
        int scratch = 0;
        for (int ti = 0; ti < clips.size() && ti < song.tracks.size(); ti++) {
            for (AudioClip c : clips.get(ti)) {
                if (c.data != null && c.data.frames > scratch) scratch = c.data.frames;
            }
        }
        if (scratch == 0) {
            runPlugins(r);
            return;
        }
        // a clip may reach past the last note: grow the rendered buffers so nothing is cut off
        int need = 0;
        for (int ti = 0; ti < clips.size(); ti++) {
            for (AudioClip c : clips.get(ti)) {
                if (c == null || c.data == null) continue;
                need = Math.max(need, c.startFrame(song) + c.data.frames);
            }
        }
        if (need > r.left.length) {
            r.left = java.util.Arrays.copyOf(r.left, need);
            if (r.right != null) r.right = java.util.Arrays.copyOf(r.right, need);
        }
        float[] tl = new float[scratch], tr = new float[scratch];
        for (int ti = 0; ti < clips.size() && ti < song.tracks.size(); ti++) {
            if (mixer.trackCount() > ti && !mixer.isAudible(ti)) continue;
            float g = (mixer.trackCount() > ti) ? mixer.getGain(ti) : 1f;
            float p = (mixer.trackCount() > ti) ? mixer.getPan(ti) : 0f;
            double gl = Mixer.panLeftGain(p) * g, gr = Mixer.panRightGain(p) * g;
            for (AudioClip c : clips.get(ti)) {
                if (c.data == null) continue;
                java.util.Arrays.fill(tl, 0f);
                java.util.Arrays.fill(tr, 0f);
                int n = c.mixInto(tl, tr, 0);        // handles mono->stereo, gain and mute
                int at = c.startFrame(song);
                for (int i = 0; i < n; i++) {
                    int k = at + i;
                    if (k < 0 || k >= r.left.length) continue;
                    r.left[k] += (float) (tl[i] * gl);
                    r.right[k] += (float) (tr[i] * gr);
                }
            }
        }
        runPlugins(r);
    }

    /** the plugin chain runs on the summed bus, before the master limiter */
    private void runPlugins(final com.dsh.v4tune.audio.AudioEngine.Rendered r) {
        if (pluginChain.size() == 0) return;
        pluginChain.prepareAll(r.sampleRate, r.left.length);
        pluginChain.process(r.left, r.right, r.left.length);
        for (int i = 0; i < r.left.length; i++) {
            r.left[i] = Mixer.softLimit(r.left[i]);
            r.right[i] = Mixer.softLimit(r.right[i]);
        }
    }
    // ------------------------------------------------------------- mixer

    private void showMixer() {
        if (mixer == null) { status(L.t("status.mixer.unavailable")); return; }
        ensureMixerTracks();
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(8), dp(12), dp(8));
        for (int i = 0; i < song.tracks.size(); i++) {
            final int ti = i;
            String nm = song.tracks.get(i).name;
            TextView h = new TextView(this);
            h.setText((i == trackIndex ? "▶ " : "   ") + (nm == null ? ("音轨 " + (i + 1)) : nm));
            h.setTextColor(Color.parseColor("#E8F1FF"));
            h.setPadding(0, dp(8), 0, dp(2));
            col.addView(h);
            col.addView(sliderRow(L.t("mixer.volume"), 0, 200, (int) (mixer.getGain(i) * 100),
                    new android.widget.SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                            mixer.setGain(ti, p / 100f);
                        }
                        public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                        public void onStopTrackingTouch(android.widget.SeekBar sb) { }
                    }));
            col.addView(sliderRow(L.t("mixer.pan"), 0, 200, (int) ((mixer.getPan(i) + 1) * 100),
                    new android.widget.SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                            mixer.setPan(ti, p / 100f - 1f);
                        }
                        public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                        public void onStopTrackingTouch(android.widget.SeekBar sb) { }
                    }));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            android.widget.CheckBox mute = new android.widget.CheckBox(this);
            mute.setText(L.t("mixer.mute"));
            mute.setChecked(mixer.isMuted(i));
            mute.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean v) { mixer.setMute(ti, v); }
            });
            android.widget.CheckBox solo = new android.widget.CheckBox(this);
            solo.setText(L.t("mixer.solo"));
            solo.setChecked(mixer.isSolo(i));
            solo.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean v) { mixer.setSolo(ti, v); }
            });
            row.addView(mute);
            row.addView(solo);
            col.addView(row);
        }
        col.addView(sliderRow(L.t("mixer.master"), 0, 200, (int) (mixer.getMasterGain() * 100),
                new android.widget.SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                        mixer.setMasterGain(p / 100f);
                    }
                    public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                    public void onStopTrackingTouch(android.widget.SeekBar sb) { }
                }));
        sc.addView(col);
        new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.mixer.title"))
                .setView(sc)
                .setPositiveButton(L.t("dlg.ok"), null)
                .setNeutralButton(L.t("mixer.reset"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        for (int i = 0; i < song.tracks.size(); i++) {
                            mixer.setGain(i, 1f);
                            mixer.setPan(i, 0f);
                            mixer.setMute(i, false);
                            mixer.setSolo(i, false);
                        }
                        mixer.setMasterGain(1f);
                        status(L.t("status.mixer.reset"));
                    }
                })
                .show();
    }

    private View sliderRow(String label, int min, int max, int value,
                           android.widget.SeekBar.OnSeekBarChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.parseColor("#9FB0C8"));
        t.setWidth(dp(96));
        row.addView(t);
        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        sb.setOnSeekBarChangeListener(l);
        row.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void ensureMixerTracks() {
        if (mixer == null) return;
        if (mixer.trackCount() != song.tracks.size()) mixer.setTrackCount(song.tracks.size());
    }

    // ------------------------------------------------------------- plugins

    private void showPlugins() {
        final java.util.ArrayList<String> labels = new java.util.ArrayList<String>();
        for (int i = 0; i < pluginChain.size(); i++) {
            Plugin p = pluginChain.get(i);
            labels.add((pluginChain.isBypassed(i) ? "⏸ " : "▶ ") + p.name());
        }
        labels.add("＋ " + L.t("plug.add"));
        menu(L.t("dlg.plugin.title"), null, labels.toArray(new String[0]), -1, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == pluginChain.size()) { addPluginMenu(); return; }
                        pluginMenu(which);
                    }
                });
    }

    private void addPluginMenu() {
        final Plugin[] all = { new com.dsh.v4tune.audio.plugins.ReverbPlugin(),
                new com.dsh.v4tune.audio.plugins.ChorusPlugin(),
                new com.dsh.v4tune.audio.plugins.CompressorPlugin() };
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++) names[i] = all[i].name();
        menu(L.t("plug.add"), null, names, -1, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Plugin p = all[which];
                        p.prepare(44100, 4096);
                        pluginChain.add(p);
                        status(L.t("status.plugin.added", p.name()));
                    }
                });
    }

    private void pluginMenu(final int idx) {
        final Plugin p = pluginChain.get(idx);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(8), dp(12), dp(8));
        for (int i = 0; i < p.paramCount(); i++) {
            final int pi = i;
            double lo = p.paramMin(i), hi = p.paramMax(i);
            double frac = (p.getParam(i) - lo) / Math.max(1e-9, hi - lo);
            col.addView(sliderRow(p.paramName(i), 0, 100, (int) Math.round(frac * 100),
                    new android.widget.SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(android.widget.SeekBar sb, int pr, boolean u) {
                            Plugin q = pluginChain.get(idx);
                            double lo2 = q.paramMin(pi), hi2 = q.paramMax(pi);
                            q.setParam(pi, lo2 + (hi2 - lo2) * pr / 100.0);
                        }
                        public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                        public void onStopTrackingTouch(android.widget.SeekBar sb) { }
                    }));
        }
        sc.addView(col);
        new AlertDialog.Builder(this)
                .setTitle(p.name())
                .setView(sc)
                .setPositiveButton(L.t("dlg.ok"), null)
                .setNeutralButton(pluginChain.isBypassed(idx) ? L.t("plug.enable") : L.t("plug.bypass"),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                pluginChain.setBypass(idx, !pluginChain.isBypassed(idx));
                            }
                        })
                .setNegativeButton(L.t("plug.remove"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { pluginChain.remove(idx); }
                })
                .show();
    }
    // ------------------------------------------------------------- tracks

    private void trackMenu() {
        final String[] items = new String[song.tracks.size() + 3];
        for (int i = 0; i < song.tracks.size(); i++) {
            String nm = song.tracks.get(i).name;
            if (nm == null || nm.trim().isEmpty()) nm = "音轨 " + (i + 1);
            items[i] = (i == trackIndex ? "● " : "○ ") + nm;
        }
        items[song.tracks.size()] = "＋ 新建音轨";
        items[song.tracks.size() + 1] = "重命名当前音轨";
        items[song.tracks.size() + 2] = "删除当前音轨";
        menu("音轨（共 " + song.tracks.size() + " 轨）", null, items, -1, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which < song.tracks.size()) {
                            beginEdit();
                            trackIndex = which;
                            commitEdit();
                            refreshAll();
                            scrollToContent();
                            status("当前音轨: " + song.tracks.get(trackIndex).name);
                        } else if (which == song.tracks.size()) {
                            beginEdit();
                            com.dsh.v4tune.model.Track t = new com.dsh.v4tune.model.Track();
                            t.no = song.tracks.size() + 1;
                            t.name = "音轨 " + (song.tracks.size() + 1);
                            t.parts.add(new Part());
                            song.tracks.add(t);
                            trackIndex = song.tracks.size() - 1;
                            commitEdit();
                            refreshAll();
                            scrollToContent();
                            status("已新建 " + t.name);
                        } else if (which == song.tracks.size() + 1) {
                            renameTrack();
                        } else {
                            if (song.tracks.size() <= 1) { status("至少要保留一条音轨"); return; }
                            beginEdit();
                            song.tracks.remove(trackIndex);
                            if (trackIndex >= song.tracks.size()) trackIndex = song.tracks.size() - 1;
                            commitEdit();
                            refreshAll();
                            status("已删除音轨，当前: " + song.tracks.get(trackIndex).name);
                        }
                    }
                });
    }

    private void renameTrack() {
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setText(song.tracks.get(trackIndex).name);
        new AlertDialog.Builder(this)
                .setTitle("重命名音轨")
                .setView(e)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        beginEdit();
                        song.tracks.get(trackIndex).name = e.getText().toString().trim();
                        commitEdit();
                        refreshAll();
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    /** everything that is not on the main toolbar lives here */
    private void toolsMenu() {
        final String[] items = {
                L.t("menu.tools.lang"),
                L.t("menu.tools.mixer"),
                L.t("menu.tools.plugins"),
                L.t("menu.tools.import.wav"),
                L.t("menu.tools.lyrics"),
                L.t("menu.tools.overlap"),
                L.t("menu.tools.device"),
                L.t("menu.tools.clips"),
                L.t("menu.tools.orientation"),
                L.t("menu.tools.ai"),
                L.t("menu.tools.settings")
        };
        menu(L.t("menu.tools"), null, items, -1, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which == 0) languageMenu();
                else if (which == 1) showMixer();
                else if (which == 2) showPlugins();
                else if (which == 3) importWav();
                else if (which == 4) lyricFillDialog();
                else if (which == 5) checkOverlaps();
                else if (which == 6) outputDeviceDialog();
                else if (which == 7) clipMenu();
                else if (which == 8) toggleOrientation();
                else if (which == 9) aiDialog();
                else settingsDialog();
            }
        });
    }

    private void toggleOrientation() {
        boolean land = isLandscape();
        setRequestedOrientation(land ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        status(land ? "切换为竖屏" : "切换为横屏");
    }

    private void managePacks() {
        final String[] items = {
                "导入声库文件（.v4voice）",
                "从文件夹批量导入（推荐，真机更稳）",
                "打开 " + Storage.ROOT_NAME + " 文件夹说明",
                "删除已导入的声库（" + SingerLibrary.userPackCount() + "）",
                "声库说明"
        };
        menu(L.t("menu.pack.title"), L.t("menu.pack.sub", SingerLibrary.userPackCount()), items, -1,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) importPack();
                        else if (which == 1) importPackFolder();
                        else if (which == 2) showRootHelp();
                        else if (which == 3) deletePack();
                        else packHelp();
                    }
                });
    }

    /** where every file goes, and how to get the folder writable */
    private void showRootHelp() {
        boolean ok = Storage.canWriteRoot(this);
        String msg = "BriVoice 的所有文件都放在这一个文件夹里：\n\n"
                + Storage.root(this).getAbsolutePath() + "\n"
                + "  projects/   工程（.vsqx）\n"
                + "  voicebanks/ 声库（.v4voice，可以直接从电脑拷进来）\n"
                + "  export/     导出的 WAV\n\n"
                + (ok ? "当前状态：有「所有文件访问」权限，保存会直接写进这个文件夹。"
                      : "当前状态：还没有「所有文件访问」权限，文件暂时放在应用自己的目录；"
                        + "授权后就会用 " + Storage.publicRoot().getAbsolutePath() + "。\n\n"
                        + "说明：Android 不允许应用给「软件本体的安装目录」改名，"
                        + "安装位置由系统管理；这个文件夹是 BriVoice 在存储根目录下自己的根目录。");
        new AlertDialog.Builder(this)
                .setTitle(Storage.ROOT_NAME)
                .setMessage(msg)
                .setPositiveButton(ok ? "好" : "去授权", ok ? null : new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { askAllFilesAccess(); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void importPack() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_PACK);
        } catch (Throwable t) {
            toast("无法打开文件选择器: " + t.getMessage());
        }
    }

    private void deletePack() {
        final java.util.List<VoiceModel> packs = SingerLibrary.all();
        final java.util.ArrayList<VoiceModel> mine = new java.util.ArrayList<VoiceModel>();
        for (VoiceModel m : packs) if (SingerLibrary.isUserPack(m)) mine.add(m);
        if (mine.isEmpty()) { toast("还没有导入任何声库"); return; }
        String[] names = new String[mine.size()];
        for (int i = 0; i < mine.size(); i++) names[i] = singerName(mine.get(i));
        menu(L.t("dlg.pack.delete.title"), null, names, -1, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        final VoiceModel m = mine.get(which);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(L.t("dlg.pack.del.confirm.title"))
                                .setMessage(L.t("dlg.pack.del.confirm.msg", singerName(m)))
                                .setPositiveButton(L.t("dlg.delete"), new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dd, int w) {
                                        boolean ok = packStore.delete(m.id);
                                        reloadPacks(false);
                                        if (currentVoice != null && currentVoice.id != null
                                                && currentVoice.id.equals(m.id)) {
                                            currentVoice = null;
                                            chooseInitialVoice();
                                        }
                                        refreshAll();
                                        status(ok ? L.t("status.pack.deleted", singerName(m)) : L.t("status.pack.del.fail"));
                                    }
                                })
                                .setNegativeButton(L.t("dlg.cancel"), null)
                                .show();
                    }
                });
    }

    private void packHelp() {
        new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.pack.help.title"))
                .setMessage(L.t("dlg.pack.help.msg"))
                .setPositiveButton(L.t("dlg.ok"), null)
                .show();
    }

    private void editTempo() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText(String.format(java.util.Locale.US, "%.2f", song.tempoAt(0)));
        new AlertDialog.Builder(this)
                .setTitle("速度 (BPM)")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            double bpm = Double.parseDouble(et.getText().toString().trim());
                            if (bpm < 20 || bpm > 400) throw new NumberFormatException();
                            beginEdit();
                            song.tempos.clear();
                            song.tempos.add(new long[]{0, Math.round(bpm * 100)});
                            commitEdit();
                            refreshAll();
                            if (tempoBox != null) {
                                tempoBox.bar.setProgress(tempoProgress());
                                tempoBox.setValue(tempoText());
                            }
                            status(L.t("status.tempo", tempoText()));
                        } catch (Exception e) {
                            toast("请输入 20-400 之间的数字");
                        }
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }


    // ------------------------------------------------------------- small widgets

    /**
     * One slider control. It is exactly the same size as a button (see {@link #uniformButtonWidth()})
     * and carries its own readout: the name sits on the left of the top line, the current value on
     * the right, and the slider fills the second line. Tapping the name still opens an exact editor.
     */
    private final class SliderBox extends LinearLayout {
        final TextView name;
        final TextView value;
        final SeekBar bar;

        SliderBox(String label, String initial, int max, int progress,
                  SeekBar.OnSeekBarChangeListener l, View.OnClickListener onName) {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setBackground(Theme.button(MainActivity.this, false));
            setPadding(dp(7), dp(2), dp(7), dp(2));
            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            name = new TextView(MainActivity.this);
            name.setText(label);
            name.setTextColor(Theme.TEXT_DIM);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (onName != null) {
                name.setOnClickListener(onName);
                name.setPaintFlags(name.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            }
            value = new TextView(MainActivity.this);
            value.setText(initial);
            value.setTextColor(Theme.ACCENT);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            value.setSingleLine(true);
            value.setGravity(Gravity.END);
            top.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(value, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            bar = new SeekBar(MainActivity.this);
            bar.setMax(max);
            bar.setProgress(progress);
            bar.setMinimumHeight(0);
            bar.setPadding(0, 0, 0, 0);
            try {
                bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Theme.ACCENT));
                bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.ACCENT));
                bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2A3244));
            } catch (Throwable ignored) { }
            bar.setOnSeekBarChangeListener(l);
            addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    uniformButtonWidth(), dp(38));
            lp.setMargins(dp(3), dp(2), dp(3), dp(2));
            setLayoutParams(lp);
        }

        void setValue(String v) {
            value.setText(v);
        }
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

    /** the full phrase, used in the status line */
    private String snapText() {
        return snapTicks == 0 ? L.t("transport.snap.off") : L.t("transport.snap", 1920 / snapTicks);
    }

    /** just the value, used inside the slider widget where the name is already on the left */
    private String snapValueText() {
        return snapTicks == 0 ? L.t("transport.snap.off.short") : ("1/" + (1920 / snapTicks));
    }

    /** stores the tempo in centi-BPM, so 120.37 is kept exactly (no rounding to whole BPM) */
    private void setTempo(double bpm) {
        double v = Math.max(TEMPO_MIN, Math.min(TEMPO_MAX, bpm));
        v = Math.round(v * 100) / 100.0;
        beginEdit();
        song.tempos.clear();
        song.tempos.add(new long[]{0, Math.round(v * 100)});
        commitEdit();
        refreshAll();
        status(L.t("status.tempo", tempoText()));
    }

    /** tempo readout with two decimals, the same resolution the slider and the dialog use */
    private String tempoText() {
        return String.format(java.util.Locale.US, "%.2f", song.tempoAt(0));
    }

    /** slider position for the current tempo (0.01 BPM steps) */
    private int tempoProgress() {
        return (int) Math.round((song.tempoAt(0) - TEMPO_MIN) * 100);
    }

    private String zoomText() {
        if (roll == null) return "100%";        // the transport is built before the roll
        return Math.round(roll.getPxPerTick() / ZOOM_REF_PX_PER_TICK * 100) + "%";
    }

    private int zoomIndex() {
        if (roll == null) return 3;             // default step = 100%
        int pct = Math.round(roll.getPxPerTick() / ZOOM_REF_PX_PER_TICK * 100);
        int best = 3;
        int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < ZOOM_LEVELS.length; i++) {
            int d = Math.abs(ZOOM_LEVELS[i] - pct);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void applyZoomLevel(int index) {
        if (roll == null) return;
        int pct = ZOOM_LEVELS[Math.max(0, Math.min(ZOOM_LEVELS.length - 1, index))];
        roll.setZoomX(ZOOM_REF_PX_PER_TICK * pct / 100f);
        if (zoomBox != null) zoomBox.setValue(zoomText());
        status(L.t("status.zoom", zoomText()));
    }

    private void fitZoom() {
        long end = Math.max(1920L, song.maxTick() + 960L);
        roll.setVisibleTickRange(0L, end);
        status(L.t("status.zoom.fit"));
    }

    // ------------------------------------------------------------- transport

    /**
     * Renders while playing: the first slice is heard after about a second instead of after the
     * whole song, and the rest is produced in the background as it is needed.
     */
    private void playSong() {
        if (engine.isPlaying()) { stopPlay(); return; }
        if (!requireVoice()) return;
        if (!checkBeforePlay()) return;        // overlapping notes: ask before making a sound
        ensureMixerTracks();
        engine.mixer = mixer;
        final VoiceModel vm = currentVoice;
        if (streamProducer != null) { streamProducer.cancel(); streamProducer = null; }
        streamProducer = new StreamProducer(song, vm);
        final long startFrame = (long) (TimeUtil.tickToSec(song, startTick) * 44100);
        status(L.t("status.stream.start"));
        playBtn.setText(L.t("transport.stop"));
        streamProducer.start();
        engine.playStream(streamProducer, startFrame, new AudioEngine.Callback() {
            public void onProgress(final long frame) {
                ui.post(new Runnable() {
                    public void run() {
                        long tick = TimeUtil.secToTick(song, frame / 44100.0);
                        roll.setPlayheadTick(tick);
                        roll.setVisibleTickRange(tick - 1920, tick + 1920 * 3);
                        paramView.setPlayheadTick(tick);
                        posText.setText(formatTick(tick));
                    }
                });
            }
            public void onDone() {
                ui.post(new Runnable() {
                    public void run() {
                        playBtn.setText(L.t("transport.play"));
                        startTick = 0L;
                        roll.setPlayheadTick(0L);
                        if (paramView != null) paramView.setPlayheadTick(0L);
                        posText.setText(formatTick(0L));
                        status(L.t("status.play.done"));
                    }
                });
            }
            public void onError(final String m) {
                ui.post(new Runnable() { public void run() { status(L.t("status.stream.fail", m)); } });
            }
        });
    }

    private void previewPart() {
        final Part p = currentPart();
        if (p == null || p.notes.isEmpty()) { status("当前片段没有音符"); return; }
        if (!requireVoice()) return;
        status("渲染当前片段…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final float[] mono = engine.renderPart(song, p, currentVoice, new Renderer.Cfg());
                    final AudioEngine.Rendered r = new AudioEngine.Rendered();
                    r.left = mono;
                    r.right = mono;
                    r.song = song;
                    applyPostDsp(r);
                    rendered = r;
                    ui.post(new Runnable() {
                        public void run() {
                            playBtn.setText(L.t("transport.stop"));
                            engine.play(r, new AudioEngine.Callback() {
                                public void onProgress(long tick) { }
                                public void onDone() {
                                    ui.post(new Runnable() {
                                        public void run() {
                                            playBtn.setText(L.t("transport.play"));
                                            status("试听结束");
                                        }
                                    });
                                }
                                public void onError(String m) { status("播放错误: " + m); }
                            });
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() { public void run() { status("渲染失败: " + t); } });
                }
            }
        }, "v4tune-preview").start();
    }

    /** jump the cursor to the very beginning and start playing from there */
    private void playFromStart() {
        boolean wasPlaying = engine.isPlaying();
        if (wasPlaying) engine.stop();                 // silent stop: do not keep the old cursor
        startTick = 0L;
        roll.setPlayheadTick(0L);
        if (paramView != null) paramView.setPlayheadTick(0L);
        posText.setText(formatTick(0L));
        playBtn.setText(L.t("transport.play"));
        if (wasPlaying) ui.postDelayed(new Runnable() { public void run() { playSong(); } }, 150L);
        else playSong();
    }

    private void stopPlay() {
        if (streamProducer != null) { streamProducer.cancel(); streamProducer = null; }
        long at = engine.playTick();
        engine.stop();
        playBtn.setText(L.t("transport.play"));
        // leave the cursor where playback stopped so it can be resumed or dragged
        if (at > 0) startTick = at;
        roll.setPlayheadTick(startTick);
        paramView.setPlayheadTick(startTick);
        posText.setText(formatTick(startTick));
        status("已停止 · 光标停在 " + formatTick(startTick));
    }

    private String formatTick(long tick) {
        double sec = TimeUtil.tickToSec(song, tick);
        int bar = (int) (tick / 1920) + 1;
        int beat = (int) ((tick % 1920) / 480) + 1;
        return String.format(java.util.Locale.US, "%03d:%d  %05.2fs", bar, beat, sec);
    }

    // ------------------------------------------------------------------ files

    /** write the project into BriVoice_root/projects (no picker, no permissions dialog) */
    private void saveIntoRoot(String name) {
        try {
            File dir = Storage.projects(this);
            File f = new File(dir, ProjectIO.sanitize(name) + ".vsqx");
            ProjectIO.savePath(f, song);
            currentUri = null;
            projectName = ProjectIO.sanitize(name);
            status("已保存到 " + f.getAbsolutePath());
            toast("已保存到 " + Storage.ROOT_NAME + "/projects");
        } catch (Exception e) {
            status("保存失败: " + e.getMessage());
            new AlertDialog.Builder(this).setTitle("保存失败")
                    .setMessage(String.valueOf(e.getMessage())).setPositiveButton("好", null).show();
        }
    }

    /** start from an empty project (one track, one part) */
    private void newProject() {
        new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.new.title"))
                .setMessage(L.t("dlg.new.msg"))
                .setPositiveButton(L.t("dlg.new.ok"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        beginEdit();
                        song = Song.empty();
                        song.tracks.get(0).name = "音轨 1";
                        trackIndex = 0;
                        currentUri = null;
                        projectName = "未命名";
                        startTick = 0;
                        clips.clear();
                        commitEdit();
                        if (roll != null) {
                            roll.setSong(song);
                            roll.setPlayheadTick(0L);
                        }
                        if (paramView != null) paramView.setPlayheadTick(0L);
                        chooseInitialVoice();
                        refreshAll();
                        scrollToContent();
                        status(L.t("status.new.done"));
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    // ---------------------------------------------------------------- batch lyrics

    /** paste a whole line of lyrics and hand one syllable to each note */
    private void lyricFillDialog() {
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setMinLines(4);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setHint(L.t("dlg.lyrics.hint"));
        new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.lyrics.title"))
                .setView(e)
                .setPositiveButton(L.t("dlg.lyrics.ok"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { applyLyrics(e.getText().toString()); }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    private void applyLyrics(String text) {
        final java.util.List<String> syl = LyricFill.split(text, new LyricFill.Hanzi() {
            public String convert(char c) {
                try {
                    String py = ChineseG2P.hanziToPinyin(c);
                    return py;
                } catch (Throwable t) {
                    return null;
                }
            }
        });
        if (syl.isEmpty()) { status(L.t("status.lyrics.empty")); return; }
        final Part p = currentPart();
        if (p == null || p.notes.isEmpty()) { status("当前片段没有音符"); return; }
        ArrayList<Note> notes = new ArrayList<Note>(p.notes);
        java.util.Collections.sort(notes, new java.util.Comparator<Note>() {
            public int compare(Note a, Note b) { return a.pos < b.pos ? -1 : (a.pos > b.pos ? 1 : 0); }
        });
        int start = 0;
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).pos >= startTick) { start = i; break; }
        }
        beginEdit();
        int n = 0;
        for (int i = start; i < notes.size() && n < syl.size(); i++, n++) notes.get(i).lyric = syl.get(n);
        commitEdit();
        refreshAll();
        String msg = L.t("status.lyrics.done", n);
        if (n < syl.size()) msg += " " + L.t("status.lyrics.left", syl.size() - n);
        status(msg);
        toast(msg);
    }

    // ---------------------------------------------------------------- output device

    private static String deviceTypeName(int t) {
        switch (t) {
            case android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "扬声器";
            case android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "听筒";
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "有线耳机";
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET: return "有线耳麦";
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "蓝牙音频";
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "蓝牙通话";
            case android.media.AudioDeviceInfo.TYPE_USB_DEVICE: return "USB 音频";
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET: return "USB 耳麦";
            case android.media.AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            default: return "设备 " + t;
        }
    }

    /** pick which output the transport and the keyboard audition play through */
    private void outputDeviceDialog() {
        final ArrayList<android.media.AudioDeviceInfo> devs = new ArrayList<android.media.AudioDeviceInfo>();
        ArrayList<String> labels = new ArrayList<String>();
        labels.add(L.t("dlg.device.default"));
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d == null || !d.isSink()) continue;
                    devs.add(d);
                    String nm = String.valueOf(d.getProductName());
                    labels.add(deviceTypeName(d.getType()) + (nm.isEmpty() ? "" : (" · " + nm)));
                }
            }
        } catch (Throwable t) {
            status(L.t("status.device.fail", t.getMessage()));
        }
        android.media.AudioDeviceInfo cur = engine.getPreferredOutput();
        int curIdx = 0;
        if (cur != null) {
            for (int i = 0; i < devs.size(); i++) {
                if (devs.get(i).getId() == cur.getId()) { curIdx = i + 1; break; }
            }
        }
        final String[] items = labels.toArray(new String[0]);
        menu(L.t("dlg.device.title", items[curIdx]), null, items, -1, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            engine.setPreferredOutput(null);
                            status(L.t("status.device.default"));
                        } else {
                            engine.setPreferredOutput(devs.get(which - 1));
                            status(L.t("status.device.set", items[which]));
                        }
                    }
                });
    }

    // ---------------------------------------------------------------- note overlap

    private java.util.List<NoteCheck.Issue> allIssues() {
        ArrayList<NoteCheck.Issue> all = new ArrayList<NoteCheck.Issue>();
        for (com.dsh.v4tune.model.Track t : song.tracks) {
            for (Part p : t.parts) all.addAll(NoteCheck.issues(p));
        }
        return all;
    }

    /** centre the editor on the offending note and tell the user what is wrong */
    private void jumpToIssue(NoteCheck.Issue is) {
        if (is == null) return;
        startTick = is.at;
        roll.focusNote(is.second);
        roll.setPlayheadTick(is.at);
        paramView.setPlayheadTick(is.at);
        status(L.t("status.overlap.jump", is.describe()));
    }

    /** manual check from the tools menu */
    private void checkOverlaps() {
        final java.util.List<NoteCheck.Issue> is = allIssues();
        if (is.isEmpty()) {
            status(L.t("status.overlap.none"));
            toast("音符没有重叠");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < is.size() && i < 8; i++) sb.append("· ").append(is.get(i).describe()).append("\n");
        if (is.size() > 8) sb.append("… 共 ").append(is.size()).append(" 处\n");
        new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.overlap.title", is.size()))
                .setMessage(sb + "\n重叠会让两个音同时发声（歌手只有一个）。\n"
                        + L.t("dlg.overlap.why"))
                .setPositiveButton(L.t("dlg.overlap.fix"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { normalizeOverlaps(); }
                })
                .setNeutralButton(L.t("dlg.overlap.jump"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { jumpToIssue(is.get(0)); }
                })
                .show();
    }

    private void normalizeOverlaps() {
        beginEdit();
        int n = NoteCheck.normalize(song, null);
        commitEdit();
        refreshAll();
        status(n == 0 ? L.t("status.overlap.nothing") : L.t("status.overlap.fixed", n));
    }

    /**
     * Runs before every playback: a monophonic part can never sound two notes at once, so the user
     * is told about it and offered the fix (or a jump to the offending note) instead of silently
     * hearing two voices fight.
     *
     * @return true when playback may start right away
     */
    private boolean checkBeforePlay() {
        final java.util.List<NoteCheck.Issue> is = allIssues();
        if (is.isEmpty()) return true;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < is.size() && i < 6; i++) sb.append("· ").append(is.get(i).describe()).append("\n");
        if (is.size() > 6) sb.append("… 共 ").append(is.size()).append(" 处\n");
        new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.overlap.scan", is.size()))
                .setMessage(sb)
                .setPositiveButton(L.t("dlg.overlap.fixplay"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        normalizeOverlaps();
                        playSong();
                    }
                })
                .setNeutralButton("跳转到第一处", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { jumpToIssue(is.get(0)); }
                })
                .setNegativeButton(L.t("dlg.overlap.playany"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { playSong(); }
                })
                .show();
        return false;
    }

    // ---------------------------------------------------------------- batch lyrics end

    private void openProject() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/zip",
                "text/xml", "audio/midi", "audio/x-midi", "application/x-midi", "*/*"});
        try {
            Uri start = android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:" + Storage.ROOT_NAME + "/projects");
            i.putExtra("android.provider.extra.INITIAL_URI", start);
        } catch (Throwable ignored) {
        }
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (Throwable t) {
            toast("无法打开文件选择器: " + t.getMessage());
        }
    }

    private void saveProject(boolean as) {
        if (!as && currentUri != null) {
            try {
                ProjectIO.save(this, currentUri, song);
                status("已保存到原文件");
                toast("已保存");
            } catch (Exception e) {
                status("保存失败: " + e.getMessage());
            }
            return;
        }
        // direct save into BriVoice_root/projects - that is what the user asked for, and it works
        // without any picker once "all files access" is granted
        if (Storage.canWriteRoot(this)) {
            if (as) {
                final android.widget.EditText e = new android.widget.EditText(this);
                e.setText(ProjectIO.sanitize(projectName));
                new AlertDialog.Builder(this)
                        .setTitle("另存为（保存到 " + Storage.ROOT_NAME + "/projects）")
                        .setView(e)
                        .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                saveIntoRoot(e.getText().toString());
                            }
                        })
                        .setNegativeButton(L.t("dlg.cancel"), null)
                        .show();
                return;
            }
            saveIntoRoot(projectName);
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, ProjectIO.sanitize(projectName) + ".vsqx");
        try {
            startActivityForResult(i, REQ_SAVE);
        } catch (Throwable t) {
            // fall back to the private folder
            try {
                File f = ProjectIO.autosaveFile(this, projectName);
                ProjectIO.savePath(f, song);
                status("已保存到 " + f.getAbsolutePath());
            } catch (Exception e2) {
                status("保存失败: " + e2.getMessage());
            }
        }
    }

    /** optional "all files access" grant, for file managers that hand us plain file:// paths */
    private void askAllFilesAccess() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            status("请打开「允许管理所有文件」，然后重新从文件管理器打开该文件");
        } catch (Throwable t) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable t2) {
                toast("无法打开系统设置：" + t2.getMessage());
            }
        }
    }

    /** ask for the media read permission the first time a plain file:// path arrives */
    private void ensureReadPermission(Uri uri) {
        try {
            if (uri == null || !"file".equals(uri.getScheme())) return;
            if (android.os.Build.VERSION.SDK_INT < 23) return;
            String perm = android.os.Build.VERSION.SDK_INT >= 33
                    ? "android.permission.READ_MEDIA_AUDIO"
                    : android.Manifest.permission.READ_EXTERNAL_STORAGE;
            if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            requestPermissions(new String[]{perm}, 2001);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    /** "用 BriVoice 打开" from a file manager: route .v4voice to the voicebank store, everything else to the editor */
    private void handleViewIntent(Intent it) {
        if (it == null || !Intent.ACTION_VIEW.equals(it.getAction())) return;
        Uri uri = it.getData();
        if (uri == null) return;
        String name = null;
        try { name = uri.getLastPathSegment(); } catch (Throwable ignored) {}
        if (ProjectIO.isVoicePackName(name)) {
            importPackFrom(uri);
            return;
        }
        ensureReadPermission(uri);
        try {
            beginEdit();
            song = ProjectIO.open(this, uri);       // must land in the field, not a local
            commitEdit();
            currentUri = uri;
            projectName = (name == null) ? "未命名" : name.replaceAll("\\.[A-Za-z0-9]+$", "");
            trackIndex = 0;
            chooseInitialVoice();
            refreshAll();
            scrollToContent();
            status("已打开 " + name + "（" + song.tracks.size() + " 轨 / " + countNotes() + " 音符）");
            toast("已导入工程：" + name);
        } catch (Exception e) {
            android.util.Log.e("V4Tune", "open via VIEW failed: " + uri, e);
            status("打开失败: " + e.getMessage());
            final boolean canAskAllFiles = android.os.Build.VERSION.SDK_INT >= 30
                    && uri != null && "file".equals(uri.getScheme())
                    && !android.os.Environment.isExternalStorageManager();
            new AlertDialog.Builder(this)
                    .setTitle("无法打开这个文件")
                    .setMessage(String.valueOf(e.getMessage()) + "\n\n支持：.vsqx / .vsq 工程、.mid 导入、.v4voice 声库")
                    .setPositiveButton(canAskAllFiles ? "授予文件访问权限" : "好",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    if (canAskAllFiles) askAllFilesAccess();
                                }
                            })
                    .setNegativeButton(canAskAllFiles ? "取消" : null, null)
                    .show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_OPEN) {
            try {
                beginEdit();
                song = ProjectIO.open(this, uri);
                commitEdit();
                currentUri = uri;
                String last = uri.getLastPathSegment();
                projectName = last == null ? "未命名" : last.replace(".vsqx", "");
                trackIndex = 0;
                refreshAll();
                scrollToContent();
                status("已打开 " + last + "（" + song.tracks.size() + " 轨 / " + countNotes() + " 音符）");
            } catch (Exception e) {
                status("打开失败: " + e.getMessage());
                toast("打开失败: " + e.getMessage());
            }
        } else if (req == REQ_SAVE) {
            try {
                ProjectIO.save(this, uri, song);
                currentUri = uri;
                status("已保存");
                toast("已保存");
            } catch (Exception e) {
                status("保存失败: " + e.getMessage());
            }
        } else if (req == REQ_PACK) {
            importPackFrom(uri);
        } else if (req == REQ_PACK_DIR && data != null && data.getData() != null) {
            importTree(data.getData());
        } else if (req == REQ_WAV && data != null && data.getData() != null) {
            addWavFrom(data.getData());
        }
    }

    /** Pick a FOLDER and import every .v4voice inside it (the reliable path on real phones). */
    private void importPackFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_PACK_DIR);
        } catch (Throwable t) {
            status("无法打开文件夹选择器: " + t.getMessage());
        }
    }

    /** walk the granted tree and import every pack found (documents provider, no androidx needed) */
    private void importTree(final Uri tree) {
        if (tree == null) return;
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在扫描并导入声库…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final String[] res = importTreeWorker(tree);   // {ok, bad, errors}
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        reloadPacksAsync(false);
                        int ok = Integer.parseInt(res[0]), bad = Integer.parseInt(res[1]);
                        String m = "文件夹导入完成：成功 " + ok + " 个" + (bad > 0 ? ("，失败 " + bad + " 个") : "");
                        status(m);
                        if (bad > 0) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("部分声库导入失败").setMessage(m + "\n\n" + res[2])
                                    .setPositiveButton("好", null).show();
                        } else {
                            toast(m);
                        }
                    }
                });
            }
        }, "pack-import-dir").start();
    }

    /** @return {succeeded, failed, errorText} - never throws */
    private String[] importTreeWorker(Uri tree) {
        int ok = 0, bad = 0;
        StringBuilder errs = new StringBuilder();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        try {
            Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, android.provider.DocumentsContract.getTreeDocumentId(tree));
            android.database.Cursor c = getContentResolver().query(children, new String[]{
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null);
            if (c == null) return new String[]{"0", "0", "读不到这个文件夹"};
            while (c.moveToNext()) {
                String name = c.getString(0);
                String docId = c.getString(1);
                if (name == null || !name.toLowerCase(java.util.Locale.US).endsWith(".v4voice")) continue;
                Uri file = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                VoicePack.Result r = importPackUri(file, name);
                if (r != null && r.ok()) ok++;
                else {
                    bad++;
                    if (errs.length() < 300) errs.append(errs.length() > 0 ? "\n" : "").append(name).append(": ")
                            .append(r == null ? "?" : r.error);
                }
            }
            c.close();
        } catch (Throwable t) {
            return new String[]{"0", "0", "文件夹导入出错: " + t};
        }
        return new String[]{String.valueOf(ok), String.valueOf(bad), errs.toString()};
    }

    /** import one pack, returning the parse result (null when the stream could not be opened) */
    private VoicePack.Result importPackUri(Uri uri, String name) {
        java.io.InputStream in = null;
        try {
            in = ProjectIO.openAny(this, uri);
            return packStore.importStream(in, name, true);
        } catch (Throwable t) {
            VoicePack.Result r = new VoicePack.Result();
            r.error = String.valueOf(t.getMessage());
            return r;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Import runs OFF the main thread: parsing one pack costs seconds on a phone, and doing it on
     * the UI thread both froze the app and made Android kill it (ANR). A modal progress dialog
     * keeps the user informed, and every failure - including OutOfMemoryError - is reported.
     */
    private void importPackFrom(final Uri uri) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在导入声库…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final VoicePack.Result r = importPackUri(uri, uri.getLastPathSegment());
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (r != null && r.ok()) {
                            status("已导入声库: " + singerName(r.model));
                            toast("已导入声库: " + singerName(r.model));
                        } else {
                            final String err = (r == null) ? "未知错误" : r.error;
                            status("声库导入失败: " + err);
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("导入失败")
                                    .setMessage(err + "\n\n如果文件本身没问题，多半是系统给的文件流不完整。"
                                            + "请改用「声库 → 从文件夹批量导入」，把 .v4voice 放进一个文件夹再选那个文件夹。")
                                    .setPositiveButton("从文件夹导入", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) { importPackFolder(); }
                                    })
                                    .setNegativeButton(L.t("dlg.cancel"), null)
                                    .show();
                        }
                    }
                });
            }
        }, "pack-import").start();
    }

    private int countNotes() {
        int n = 0;
        for (com.dsh.v4tune.model.Track t : song.tracks) for (Part p : t.parts) n += p.notes.size();
        return n;
    }

    private void exportWav() {
        status("正在渲染音频…");
        final VoiceModel vm = currentVoice;
        new Thread(new Runnable() {
            public void run() {
                try {
                    ensureMixerTracks();
                    engine.mixer = mixer;
                    final AudioEngine.Rendered r = engine.render(song, vm, new Renderer.Cfg(), null);
                    applyPostDsp(r);        // always re-apply: clips/plugins may have changed since playback
                    final File f = ProjectIO.exportWav(MainActivity.this, projectName, r);
                    ui.post(new Runnable() {
                        public void run() {
                            status("已导出 " + f.getAbsolutePath());
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("导出成功")
                                    .setMessage("文件: " + f.getAbsolutePath() + "\n\n时长 " +
                                            String.format(java.util.Locale.US, "%.1f", r.left.length / (double) r.sampleRate) + " 秒")
                                    .setPositiveButton("分享", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) {
                                            ProjectIO.share(MainActivity.this, f, ProjectIO.MIME_WAV);
                                        }
                                    })
                                    .setNegativeButton("关闭", null)
                                    .show();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() { public void run() { status("导出失败: " + t); } });
                }
            }
        }, "v4tune-wav").start();
    }


    // ------------------------------------------------- AI / external control (experimental)

    /** runs work on the UI thread and waits for it, so an HTTP request can answer */
    private String onUi(final java.util.concurrent.Callable<String> work, long timeoutMs) throws Exception {
        java.util.concurrent.FutureTask<String> ft = new java.util.concurrent.FutureTask<String>(work);
        runOnUiThread(ft);
        return ft.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** the state an external script can read */
    private String extStatus() {
        Part p = currentPart();
        String singer = currentVoice == null ? "" : singerName(currentVoice);
        return com.dsh.v4tune.ai.Json.obj(
                "ok", Boolean.TRUE,
                "app", "BriVoice",
                "version", "4.2",
                "playing", Boolean.valueOf(engine.isPlaying()),
                "playTick", Long.valueOf(engine.playTick()),
                "startTick", Long.valueOf(startTick),
                "tracks", Integer.valueOf(song.tracks.size()),
                "trackIndex", Integer.valueOf(trackIndex),
                "partPos", Long.valueOf(p == null ? 0 : p.pos),
                "partDur", Long.valueOf(p == null ? 0 : p.dur),
                "notes", Integer.valueOf(p == null ? 0 : p.notes.size()),
                "singer", singer,
                "clips", com.dsh.v4tune.ai.Json.raw(clipJson()),
                "paramIds", com.dsh.v4tune.ai.Json.raw(com.dsh.v4tune.ai.Json.arr(java.util.Arrays.asList(com.dsh.v4tune.ai.Params.IDS))),
                "ai", com.dsh.v4tune.ai.Json.raw(com.dsh.v4tune.ai.Json.obj(
                        "enabled", Boolean.valueOf(aiCfg.aiEnabled),
                        "busy", Boolean.valueOf(aiBusy),
                        "model", aiCfg.model,
                        "lastError", aiLastError == null ? "" : aiLastError,
                        "lastApplied", Integer.valueOf(aiLastApplied))),
                "ext", com.dsh.v4tune.ai.Json.raw(com.dsh.v4tune.ai.Json.obj(
                        "enabled", Boolean.valueOf(aiCfg.extEnabled),
                        "port", Integer.valueOf(extApi != null ? extApi.port() : 0))));
    }

    /** every command an external script can send; always called on the UI thread */
    private String extCommand(String action, java.util.Map<String, Object> args) {
        try {
            Part p = currentPart();
            if (p == null) throw new IllegalStateException("no active part");
            if ("status".equals(action)) return extStatus();

            if ("setParam".equals(action) || "aiApply".equals(action)) {
                String pid = com.dsh.v4tune.ai.Json.str(args, "param", null);
                java.util.Map<String, double[][]> curves = new java.util.LinkedHashMap<String, double[][]>();
                if (pid != null && args.get("points") != null) {
                    curves.put(pid, pointsOf(args.get("points")));
                } else if (args.get("__raw") != null) {
                    // a full tuning object: curves + notes + tempo + track
                    com.dsh.v4tune.ai.Tuning t = com.dsh.v4tune.ai.AiTuner.parseTuning(
                            String.valueOf(args.get("__raw")), p.dur + p.pos);
                    int n = applyTuning(t, 1.0f);
                    return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "applied", Integer.valueOf(n),
                            "summary", t.summary());
                } else if (args.get("curves") != null) {
                    String raw = com.dsh.v4tune.ai.Json.obj("curves", com.dsh.v4tune.ai.Json.raw(String.valueOf(args.get("curves"))));
                    curves = com.dsh.v4tune.ai.AiTuner.parseCurves(raw, p.dur + p.pos);
                } else {
                    throw new IllegalArgumentException("need param+points or curves");
                }
                boolean replace = "replace".equals(com.dsh.v4tune.ai.Json.str(args, "mode", "replace"));
                int n = applyCurves(curves, !replace, 1.0f);
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "points", Integer.valueOf(n),
                        "params", Integer.valueOf(curves.size()));
            }

            if ("clearParam".equals(action)) {
                int idx = com.dsh.v4tune.ai.Params.indexOf(com.dsh.v4tune.ai.Json.str(args, "param", "DYN"));
                if (idx < 0) throw new IllegalArgumentException("unknown param");
                beginEdit();
                com.dsh.v4tune.model.ControlCurve c = p.ctrlOrNull(com.dsh.v4tune.ai.Params.IDS[idx]);
                if (c != null) c.pts.clear();
                commitEdit();
                refreshAll();
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "cleared", com.dsh.v4tune.ai.Params.IDS[idx]);
            }

            if ("addNote".equals(action)) {
                beginEdit();
                Note n = new Note();
                n.pos = (long) com.dsh.v4tune.ai.Json.num(args, "pos", 0);
                n.dur = (long) com.dsh.v4tune.ai.Json.num(args, "dur", 480);
                if (n.dur < 60) n.dur = 60;
                n.key = (int) Math.round(com.dsh.v4tune.ai.Json.num(args, "key", 60));
                n.vel = (int) Math.round(com.dsh.v4tune.ai.Json.num(args, "vel", 64));
                n.lyric = com.dsh.v4tune.ai.Json.str(args, "lyric", "la");
                String ph = com.dsh.v4tune.ai.Json.str(args, "phoneme", null);
                if (ph != null && ph.length() > 0) n.phoneme = ph;
                p.notes.add(n);
                p.sortNotes();
                long end = p.pos + 480L;
                for (Note x : p.notes) end = Math.max(end, x.pos + x.dur);
                if (end - p.pos > p.dur) p.dur = end - p.pos;
                commitEdit();
                refreshAll();
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "notes", Integer.valueOf(p.notes.size()),
                        "pos", Long.valueOf(n.pos), "key", Integer.valueOf(n.key));
            }

            if ("deleteNote".equals(action)) {
                long pos = (long) com.dsh.v4tune.ai.Json.num(args, "pos", -1);
                int key = (int) Math.round(com.dsh.v4tune.ai.Json.num(args, "key", -1));
                beginEdit();
                int removed = 0;
                for (int i = p.notes.size() - 1; i >= 0; i--) {
                    Note n = p.notes.get(i);
                    if (n.pos == pos && (key < 0 || n.key == key)) { p.notes.remove(i); removed++; }
                }
                commitEdit();
                refreshAll();
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "removed", Integer.valueOf(removed));
            }

            if ("setLyrics".equals(action)) {
                String text = com.dsh.v4tune.ai.Json.str(args, "text", "");
                java.util.List<String> parts = com.dsh.v4tune.io.LyricFill.split(text, null);
                beginEdit();
                p.sortNotes();
                int n = Math.min(parts.size(), p.notes.size());
                for (int i = 0; i < n; i++) p.notes.get(i).lyric = parts.get(i);
                commitEdit();
                refreshAll();
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "filled", Integer.valueOf(n),
                        "syllables", Integer.valueOf(parts.size()));
            }

            if ("play".equals(action)) {
                double from = com.dsh.v4tune.ai.Json.num(args, "fromTick", -1);
                if (from >= 0) {
                    startTick = (long) from;
                    roll.setPlayheadTick(startTick);
                    if (paramView != null) paramView.setPlayheadTick(startTick);
                }
                if (!engine.isPlaying()) playSong();
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "playing", Boolean.TRUE, "from", Long.valueOf(startTick));
            }
            if ("stop".equals(action)) {
                if (engine.isPlaying()) stopPlay();
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "playing", Boolean.FALSE);
            }
            if ("save".equals(action)) {
                saveProject(false);
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "saved", Boolean.TRUE);
            }
            if ("newProject".equals(action)) {
                newProject();
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "tracks", Integer.valueOf(song.tracks.size()));
            }
            if ("importWav".equals(action)) {
                String path = com.dsh.v4tune.ai.Json.str(args, "path", "");
                if (path.length() == 0) throw new IllegalArgumentException("need path");
                java.io.File f = new java.io.File(path);
                if (!f.isFile()) throw new IllegalArgumentException("no such file: " + path);
                com.dsh.v4tune.io.WavReader.AudioData d =
                        com.dsh.v4tune.io.WavReader.read(new java.io.FileInputStream(f), 44100 * 180);
                if (d == null || !d.ok()) throw new IllegalStateException("wav: " + (d == null ? "?" : d.error));
                long songEnd = song.maxTick();
                long at = (long) com.dsh.v4tune.ai.Json.num(args, "at tick", Math.max(0L, startTick));
                if (songEnd > 0 && at >= songEnd) at = 0L;
                AudioClip c = AudioClip.fromWav(d, at, f.getName());
                if (c == null) throw new IllegalStateException("clip build failed");
                beginEdit();
                clipsFor(trackIndex).add(c);
                commitEdit();
                refreshAll();
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "name", c.name,
                        "seconds", Double.valueOf(d.frames / 44100.0), "startTick", Long.valueOf(c.startTick),
                        "clips", Integer.valueOf(clipsFor(trackIndex).size()));
            }
            if ("exportWav".equals(action)) {
                String path = com.dsh.v4tune.ai.Json.str(args, "path", "");
                if (path.length() == 0) throw new IllegalArgumentException("need path");
                final String fp = path;
                exportWavTo(new java.io.File(fp));
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "path", fp);
            }
            if ("moveClip".equals(action)) {
                int idx = (int) Math.round(com.dsh.v4tune.ai.Json.num(args, "index", 0));
                long tick = (long) com.dsh.v4tune.ai.Json.num(args, "startTick", 0);
                java.util.List<AudioClip> cs = clipsFor(trackIndex);
                if (idx < 0 || idx >= cs.size()) throw new IllegalArgumentException("no clip " + idx);
                beginEdit();
                cs.get(idx).startTick = Math.max(0L, tick);
                commitEdit();
                refreshAll();
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "startTick", Long.valueOf(cs.get(idx).startTick));
            }
            if ("aiConfig".equals(action)) {
                // lets a script point the assistant at another endpoint (a local model, a proxy)
                if (args.containsKey("enabled")) {
                    Object en = args.get("enabled");
                    aiCfg.aiEnabled = Boolean.TRUE.equals(en) || "true".equalsIgnoreCase(String.valueOf(en));
                }
                if (args.containsKey("baseUrl")) aiCfg.baseUrl = String.valueOf(args.get("baseUrl"));
                if (args.containsKey("baseurl")) aiCfg.baseUrl = String.valueOf(args.get("baseurl"));
                if (args.containsKey("model")) aiCfg.model = String.valueOf(args.get("model"));
                if (args.containsKey("apiKey")) aiCfg.apiKey = String.valueOf(args.get("apiKey"));
                if (args.containsKey("strength")) aiCfg.strength = (float) num(args.get("strength"));
                aiCfg.save(MainActivity.this);
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "enabled", Boolean.valueOf(aiCfg.aiEnabled),
                        "baseUrl", aiCfg.baseUrl, "model", aiCfg.model);
            }
            if ("aiTune".equals(action)) {
                if (!aiCfg.ready()) throw new IllegalStateException("AI is off - enable it in Tools > AI (experimental)");
                runAiTune(null);
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "started", Boolean.TRUE,
                        "hint", "poll /status until ai.busy is false");
            }
            throw new IllegalArgumentException("unknown action: " + action);
        } catch (Throwable t) {
            String m = t.getMessage();
            return com.dsh.v4tune.ai.Json.obj("ok", Boolean.FALSE, "error", m == null ? t.toString() : m);
        }
    }

    /** [[tick,value],...] out of a parsed JSON list */
    private double[][] pointsOf(Object o) {
        java.util.List<Object> pts = com.dsh.v4tune.ai.Json.asList(o);
        if (pts == null) throw new IllegalArgumentException("points must be a list");
        java.util.ArrayList<double[]> keep = new java.util.ArrayList<double[]>();
        for (Object it : pts) {
            java.util.List<Object> pair = com.dsh.v4tune.ai.Json.asList(it);
            if (pair == null || pair.size() < 2) continue;
            keep.add(new double[]{num(pair.get(0)), num(pair.get(1))});
        }
        return keep.toArray(new double[keep.size()][]);
    }

    private static double num(Object o) {
        if (o instanceof Double) return (Double) o;
        try { return Double.parseDouble(String.valueOf(o)); } catch (Throwable t) { return 0; }
    }

    /**
     * Writes curves into the current part.
     *
     * @param merge true to blend with what is already there, false to replace the points
     * @param bias  0..1, how far to move towards the suggested value
     * @return the number of points written
     */
    private int applyCurves(java.util.Map<String, double[][]> curves, boolean merge, float bias) {
        Part p = currentPart();
        if (p == null || curves == null || curves.isEmpty()) return 0;
        beginEdit();
        int written = 0;
        for (java.util.Map.Entry<String, double[][]> e : curves.entrySet()) {
            int idx = com.dsh.v4tune.ai.Params.indexOf(e.getKey());
            if (idx < 0) continue;
            com.dsh.v4tune.model.ControlCurve c = p.ctrl(com.dsh.v4tune.ai.Params.IDS[idx]);
            if (!merge) c.pts.clear();
            for (double[] pt : e.getValue()) {
                double target = com.dsh.v4tune.ai.Params.clamp(idx, pt[1]);
                double v = target;
                if (merge && !c.pts.isEmpty()) {
                    double old = com.dsh.v4tune.ai.Params.clamp(idx, c.valueAt((long) pt[0]));
                    v = old + (target - old) * Math.max(0f, Math.min(1f, bias));
                }
                c.putRaw((long) pt[0], com.dsh.v4tune.ai.Params.clamp(idx, v));
                written++;
            }
            c.sort();
            c.compact((com.dsh.v4tune.ai.Params.MAX[idx] - com.dsh.v4tune.ai.Params.MIN[idx]) / 300.0);
        }
        commitEdit();
        refreshAll();
        return written;
    }

    /** shows the AI shortcut only while the experimental assistant is switched on */
    private void updateAiButton() {
        if (aiBtn == null) return;
        aiBtn.setVisibility(aiCfg != null && aiCfg.aiEnabled ? View.VISIBLE : View.GONE);
    }

    /** the quick-tune sheet: describe what you want, the assistant edits the part */
    private void aiAskDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));

        TextView hint = new TextView(this);
        hint.setText(L.t("ai.ask.hint"));
        hint.setTextColor(Theme.TEXT_DIM);
        hint.setTextSize(12f);
        box.addView(hint);

        final EditText input = new EditText(this);
        input.setHint(L.t("ai.ask.placeholder"));
        input.setTextColor(Theme.TEXT);
        input.setHintTextColor(Theme.TEXT_DIM);
        input.setTextSize(15f);
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP);
        input.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(input);

        // a few one-tap starting points
        final String[] examples = {L.t("ai.ask.ex1"), L.t("ai.ask.ex2"), L.t("ai.ask.ex3"), L.t("ai.ask.ex4")};
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        for (final String ex : examples) {
            TextView c = new TextView(this);
            c.setText("·  " + ex);
            c.setTextColor(Theme.ACCENT);
            c.setTextSize(12.5f);
            c.setPadding(0, dp(7), 0, 0);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { input.setText(ex); input.setSelection(ex.length()); }
            });
            chips.addView(c);
        }
        box.addView(chips);

        TextView scope = new TextView(this);
        scope.setText(L.t("ai.ask.scope"));
        scope.setTextColor(Theme.TEXT_DIM);
        scope.setTextSize(11.5f);
        scope.setPadding(0, dp(10), 0, 0);
        box.addView(scope);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        com.dsh.v4tune.ui.Dialogs.builder(this)
                .setTitle(L.t("ai.ask.title"))
                .setView(sc)
                .setPositiveButton(L.t("ai.ask.go"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String req = input.getText().toString().trim();
                        if (req.length() == 0) req = examples[0];
                        runAiTune(req);
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    /** applies everything a model may change, as one undoable edit */
    private int applyTuning(com.dsh.v4tune.ai.Tuning t, float bias) {
        Part p = currentPart();
        if (p == null || t == null || t.isEmpty()) return 0;
        beginEdit();
        int changed = 0;

        // 1) control curves
        for (java.util.Map.Entry<String, double[][]> e : t.curves.entrySet()) {
            int idx = com.dsh.v4tune.ai.Params.indexOf(e.getKey());
            if (idx < 0) continue;
            com.dsh.v4tune.model.ControlCurve c = p.ctrl(com.dsh.v4tune.ai.Params.IDS[idx]);
            for (double[] pt : e.getValue()) {
                double target = com.dsh.v4tune.ai.Params.clamp(idx, pt[1]);
                double v = target;
                if (!c.pts.isEmpty()) {
                    double old = com.dsh.v4tune.ai.Params.clamp(idx, c.valueAt((long) pt[0]));
                    v = old + (target - old) * Math.max(0f, Math.min(1f, bias));
                }
                c.putRaw((long) pt[0], com.dsh.v4tune.ai.Params.clamp(idx, v));
                changed++;
            }
            c.sort();
            c.compact((com.dsh.v4tune.ai.Params.MAX[idx] - com.dsh.v4tune.ai.Params.MIN[idx]) / 300.0);
        }

        // 2) notes: match by pos, only touch the fields that were sent
        for (com.dsh.v4tune.ai.Tuning.NoteEdit e : t.notes) {
            Note hit = null;
            for (Note n : p.notes) {
                if (n.pos == e.pos) { hit = n; break; }
            }
            if (hit == null) continue;                       // never invent notes from a tuning reply
            if (e.key != null) hit.key = e.key.intValue();
            if (e.vel != null) hit.vel = e.vel.intValue();
            if (e.dur != null) hit.dur = e.dur.longValue();
            if (e.newPos != null) hit.pos = e.newPos.longValue();
            if (e.lyric != null) hit.lyric = e.lyric;
            if (e.phoneme != null) hit.phoneme = e.phoneme;
            changed++;
        }
        if (!t.notes.isEmpty()) {
            p.sortNotes();
            long end = p.pos + 480L;
            for (Note n : p.notes) end = Math.max(end, n.pos + n.dur);
            if (end - p.pos > p.dur) p.dur = end - p.pos;
        }

        // 3) tempo
        if (t.tempo != null) {
            double bpm = Math.max(20.0, Math.min(400.0, t.tempo.doubleValue()));
            song.tempos.clear();
            song.tempos.add(new long[]{0, Math.round(bpm * 100)});
            changed++;
        }

        // 4) the track itself
        com.dsh.v4tune.model.Track tr = song.tracks.get(Math.max(0, Math.min(trackIndex, song.tracks.size() - 1)));
        if (t.trackVolume != null) { tr.vol = t.trackVolume.intValue(); changed++; }
        if (t.trackPan != null) { tr.pan = t.trackPan.intValue(); changed++; }

        commitEdit();
        refreshAll();
        if (t.tempo != null && tempoBox != null) {
            tempoBox.bar.setProgress(tempoProgress());
            tempoBox.setValue(tempoText());
        }
        return changed;
    }

    /** asks the model with the user's own words, then applies the whole answer */
    private void runAiTune(final String request) {
        if (aiCfg == null || !aiCfg.ready()) { status(L.t("ai.off")); return; }
        final Part p = currentPart();
        if (p == null) return;
        if (aiBusy) { status(L.t("ai.busy")); return; }
        aiBusy = true;
        aiLastError = null;
        status(L.t("ai.asking"));
        final String payload = com.dsh.v4tune.ai.AiTuner.partJson(song, p, trackIndex)
                + (request == null || request.trim().length() == 0 ? "" : (", \"request\": " + com.dsh.v4tune.ai.Json.quote(request.trim())));
        final float bias = aiCfg.strength;
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                com.dsh.v4tune.ai.Tuning tuning = null;
                try {
                    String reply = com.dsh.v4tune.ai.AiTuner.chat(aiCfg, payload, 60000);
                    aiLastReply = reply == null ? "" : reply;
                    tuning = com.dsh.v4tune.ai.AiTuner.parseTuning(reply, p.dur + p.pos);
                } catch (Throwable t2) {
                    err = t2.getMessage() == null ? t2.toString() : t2.getMessage();
                }
                final String fe = err;
                final com.dsh.v4tune.ai.Tuning ft = tuning;
                ui.post(new Runnable() {
                    public void run() {
                        aiBusy = false;
                        if (fe != null) {
                            aiLastError = fe;
                            status(L.t("ai.failed", fe));
                            return;
                        }
                        int n = applyTuning(ft, bias);
                        aiLastApplied = n;
                        status(L.t("ai.applied.full", ft == null ? "" : ft.summary(), n));
                    }
                });
            }
        }, "brivoice-ai").start();
    }

    // ------------------------------------------------------------------ settings

    /** the settings sheet; the phoneme lock lives here and is OFF by default */
    private void settingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));

        final CheckBox lock = new CheckBox(this);
        lock.setText(L.t("set.phoneme.lock"));
        lock.setChecked(phonemeLock);
        box.addView(lock);

        // experimental: sing fricatives with the spectrum the voicebank actually measured
        final CheckBox mnoise = new CheckBox(this);
        mnoise.setText(L.t("set.mnoise"));
        mnoise.setChecked(Renderer.measuredNoise);
        box.addView(mnoise);
        final TextView mnGainLabel = label(L.t("set.mnoise.gain", Integer.valueOf(Math.round(Renderer.noiseGain * 100)) + "%"));
        box.addView(mnGainLabel);
        final SeekBar mnGain = new SeekBar(this);
        mnGain.setMax(200);
        mnGain.setProgress(Math.round(Renderer.noiseGain * 100));
        mnGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                mnGainLabel.setText(L.t("set.mnoise.gain", Integer.valueOf(pr) + "%"));
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(mnGain);

        final CheckBox frameSeqChk = new CheckBox(this);
        frameSeqChk.setText(L.t("set.frameseq"));
        frameSeqChk.setChecked(Renderer.frameSequence);
        box.addView(frameSeqChk);
        TextView fsDesc = new TextView(this);
        fsDesc.setText(L.t("set.frameseq.desc"));
        fsDesc.setTextColor(Theme.TEXT_DIM);
        fsDesc.setTextSize(12f);
        fsDesc.setPadding(dp(4), 0, 0, dp(6));
        box.addView(fsDesc);
        TextView mnDesc = new TextView(this);
        mnDesc.setText(L.t("set.mnoise.desc"));
        mnDesc.setTextColor(Theme.TEXT_DIM);
        mnDesc.setTextSize(12f);
        mnDesc.setPadding(dp(4), 0, 0, dp(6));
        box.addView(mnDesc);

        // cursor delay compensation: the sound a device emits lags the picture by its output buffer
        final TextView offLabel = label(L.t("set.offset", Integer.valueOf(cursorOffsetMs)));
        box.addView(offLabel);
        final SeekBar off = new SeekBar(this);
        off.setMax(5000);                       // -2500 .. +2500 ms, 2500 is zero
        off.setProgress(cursorOffsetMs + 2500);
        off.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                offLabel.setText(L.t("set.offset", Integer.valueOf(pr - 2500)));
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(off);
        TextView offDesc = new TextView(this);
        offDesc.setText(L.t("set.offset.desc"));
        offDesc.setTextColor(Theme.TEXT_DIM);
        offDesc.setTextSize(12f);
        offDesc.setPadding(dp(4), 0, 0, dp(6));
        box.addView(offDesc);

        TextView desc = new TextView(this);
        desc.setText(L.t("set.phoneme.desc"));
        desc.setTextColor(Theme.TEXT_DIM);
        desc.setTextSize(12f);
        desc.setPadding(dp(4), 0, 0, dp(8));
        box.addView(desc);

        TextView state = new TextView(this);
        state.setText(L.t(phonemeLock ? "set.phoneme.state.on" : "set.phoneme.state.off"));
        state.setTextColor(Theme.ACCENT);
        state.setTextSize(12f);
        state.setPadding(dp(4), 0, 0, 0);
        box.addView(state);
        lock.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                state.setText(L.t(on ? "set.phoneme.state.on" : "set.phoneme.state.off"));
            }
        });

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        com.dsh.v4tune.ui.Dialogs.builder(this)
                .setTitle(L.t("dlg.settings.title"))
                .setView(sc)
                .setPositiveButton(L.t("ai.save"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cursorOffsetMs = off.getProgress() - 2500;
                        engine.cursorOffsetMs = cursorOffsetMs;
                        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                                .putInt("cursorOffsetMs", cursorOffsetMs).apply();
                        Renderer.frameSequence = frameSeqChk.isChecked();
                        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                                .putBoolean("frameseq", Renderer.frameSequence).apply();
                        Renderer.measuredNoise = mnoise.isChecked();
                        Renderer.noiseGain = mnGain.getProgress() / 100f;
                        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                                .putBoolean("mnoise", Renderer.measuredNoise)
                                .putInt("mnoiseGain", mnGain.getProgress()).apply();
                        phonemeLock = lock.isChecked();
                        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                                .putBoolean("phonemeLock", phonemeLock).apply();
                        status(L.t(phonemeLock ? "set.phoneme.state.on" : "set.phoneme.state.off"));
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }
    /** the experimental panel: switches, endpoint, key, port, manual tune button */
    private void aiDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(6));

        TextView warn = new TextView(this);
        warn.setText(L.t("ai.experimental"));
        warn.setTextColor(Theme.ACCENT2);
        warn.setTextSize(12.5f);
        warn.setPadding(0, 0, 0, dp(10));
        box.addView(warn);

        final CheckBox on = new CheckBox(this);
        on.setText(L.t("ai.enable"));
        on.setChecked(aiCfg.aiEnabled);
        box.addView(on);

        box.addView(label(L.t("ai.baseurl")));
        final EditText url = field(aiCfg.baseUrl, false);
        box.addView(url);
        box.addView(label(L.t("ai.model")));
        final EditText model = field(aiCfg.model, false);
        box.addView(model);
        box.addView(label(L.t("ai.key")));
        final EditText key = field(aiCfg.apiKey, true);
        box.addView(key);

        final TextView st = new TextView(this);
        st.setText(L.t("ai.strength", Math.round(aiCfg.strength * 100) + "%"));
        st.setTextColor(Theme.TEXT_DIM);
        st.setTextSize(12f);
        box.addView(st);
        final SeekBar str = new SeekBar(this);
        str.setMax(100);
        str.setProgress(Math.round(aiCfg.strength * 100));
        str.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                st.setText(L.t("ai.strength", pr + "%"));
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(str);

        final CheckBox ext = new CheckBox(this);
        ext.setText(L.t("ai.ext.enable"));
        ext.setChecked(aiCfg.extEnabled);
        box.addView(ext);
        box.addView(label(L.t("ai.ext.port")));
        final EditText port = field(String.valueOf(aiCfg.extPort), false);
        box.addView(port);

        final TextView state = new TextView(this);
        state.setTextColor(Theme.TEXT_DIM);
        state.setTextSize(12f);
        state.setPadding(0, dp(8), 0, 0);
        state.setText(extStateText());
        box.addView(state);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        final AlertDialog d = com.dsh.v4tune.ui.Dialogs.builder(this).setTitle(L.t("ai.title")).setView(sc)
                .setPositiveButton(L.t("ai.save"), null)
                .setNeutralButton(L.t("ai.tune"), null)
                .setNegativeButton(L.t("dlg.close"), null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                aiCfg.aiEnabled = on.isChecked();
                aiCfg.baseUrl = url.getText().toString().trim();
                aiCfg.model = model.getText().toString().trim();
                aiCfg.apiKey = key.getText().toString().trim();
                aiCfg.strength = str.getProgress() / 100f;
                aiCfg.extEnabled = ext.isChecked();
                try { aiCfg.extPort = Integer.parseInt(port.getText().toString().trim()); } catch (Throwable ignored) { }
                aiCfg.save(MainActivity.this);
                applyExtServer();
                state.setText(extStateText());
                status(L.t("ai.saved"));
            }
        });
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                aiCfg.aiEnabled = on.isChecked();
                aiCfg.baseUrl = url.getText().toString().trim();
                aiCfg.model = model.getText().toString().trim();
                aiCfg.apiKey = key.getText().toString().trim();
                aiCfg.strength = str.getProgress() / 100f;
                aiCfg.save(MainActivity.this);
                runAiTune(null);
                d.dismiss();
            }
        });
    }

    private String extStateText() {
        if (extApi != null && extApi.isRunning()) return L.t("ai.ext.running", Integer.valueOf(extApi.port()));
        return L.t("ai.ext.stopped");
    }

    /** starts or stops the local control server according to the settings */
    private void applyExtServer() {
        if (aiCfg == null) return;
        if (aiCfg.extEnabled) {
            if (extApi != null && extApi.isRunning() && extApi.port() == aiCfg.extPort) return;
            if (extApi != null) { extApi.stop(); extApi = null; }
            try {
                extApi = com.dsh.v4tune.ai.ExtApi.start(aiCfg.extPort, extHandler);
                status(L.t("ai.ext.running", Integer.valueOf(extApi.port())));
            } catch (Throwable t) {
                extApi = null;
                status(L.t("ai.ext.failed", String.valueOf(t.getMessage())));
            }
        } else if (extApi != null) {
            extApi.stop();
            extApi = null;
            status(L.t("ai.ext.stopped"));
        }
        updateAiButton();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Theme.TEXT_DIM);
        t.setTextSize(12f);
        t.setPadding(0, dp(8), 0, dp(2));
        return t;
    }

    private EditText field(String v, boolean secret) {
        EditText e = new EditText(this);
        e.setText(v == null ? "" : v);
        e.setTextColor(Theme.TEXT);
        e.setTextSize(14f);
        e.setSingleLine(true);
        if (secret) e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }
    private void about() {
        StringBuilder sb = new StringBuilder();
        sb.append(L.t("about.line.app")).append("\n\n");
        sb.append(L.t("about.line.formats")).append("\n");
        sb.append(L.t("about.line.fmt4")).append("\n");
        sb.append(L.t("about.line.fmt3")).append("\n");
        sb.append(L.t("about.line.fmt2")).append("\n");
        sb.append(L.t("about.line.wav")).append("\n\n");
        sb.append(L.t("about.line.packs")).append("\n");
        if (SingerLibrary.isEmpty()) sb.append(L.t("about.line.no.pack")).append("\n");
        for (VoiceModel v : SingerLibrary.all()) {
            int bands = 0;
            for (java.util.Map.Entry<String, VoiceModel.HarmonicBand[]> e2 : v.harmonics.entrySet()) {
                bands += e2.getValue().length;
            }
            if (bands > 0) {
                sb.append(L.t("about.line.pack", singerName(v), v.harmonics.size(), bands)).append("\n");
            } else {
                sb.append(L.t("about.line.pack.legacy", singerName(v))).append("\n");
            }
        }
        sb.append("\n").append(L.t("about.line.dir", Storage.voicebanks(this).getAbsolutePath())).append("\n");
        sb.append("\n").append(L.t("about.line.tip1")).append("\n");
        sb.append(L.t("about.line.tip2")).append("\n");
        sb.append("\n").append(L.t("about.line.author")).append("\n");
        sb.append("\n").append(L.t("about.line.disclaimer")).append("\n");
        new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.about.title"))
                .setMessage(sb.toString())
                .setPositiveButton(L.t("dlg.ok"), null)
                .show();
    }

    private void status(String s) {
        if (statusText != null) statusText.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        engine.stop();
    }

    @Override
    public void onBackPressed() {
        if (engine.isPlaying()) { stopPlay(); return; }
        super.onBackPressed();
    }
}
