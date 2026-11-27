package com.dsh.v4tune;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
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
import android.view.Window;
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
import com.dsh.v4tune.synth.NeuralSpectrum;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.SingerLibrary;
import com.dsh.v4tune.synth.VoiceModel;
import com.dsh.v4tune.synth.VoicePack;
import com.dsh.v4tune.ui.ParamView;
import com.dsh.v4tune.ui.PianoRollView;
import com.dsh.v4tune.ui.Theme;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;

public class MainActivity extends Activity implements PianoRollView.Listener {

    private static final int REQ_OPEN = 1001, REQ_SAVE = 1002, REQ_PACK = 1003, REQ_PACK_DIR = 1004,
            REQ_WAV = 1005, REQ_CHAR = 1007, REQ_TUNE = 1008, REQ_MODEL = 1009, REQ_REC_TAKE = 1012,
            REQ_CUSTOM_SAMPLES = 1010, REQ_CUSTOM_ART = 1011,
            REQ_BRI_SAVE = 1013, REQ_BRI_OPEN = 1014;
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
    /** live result line inside the AI quick sheet (so failures are visible without closing it) */
    private TextView quickAiResult;
    /** 11.0 custom voicebank: takes picked in the sheet, and the artwork bytes to embed in the pack */
    private final ArrayList<com.dsh.v4tune.voicebank.CustomBankBuilder.Sample> customSamples =
            new ArrayList<com.dsh.v4tune.voicebank.CustomBankBuilder.Sample>();
    private byte[] customArt;
    private TextView customStatus;
    /** v11.0.1 record-wizard manifest (record.json) for the pack under construction */
    private String customRecordJson;
    private com.dsh.v4tune.voicebank.RecordWizard recWizard;
    /** name/lang chosen in the record wizard, used as the defaults of the pack dialog */
    private String customName = "";
    private int customLang = 2;
    /** v12.2: build the recorded pack with the BriNeting neural engine (default) or samples only */
    private boolean customNeural = true;
    /** v12.2: which model the pending .bri save writes (null = every model) */
    private String briSaveKind;
    /** v12.2: hard ceiling for a recorded bank (800 MB); reported in the build summary */
    private static final long BANK_MAX_BYTES = 800L * 1024 * 1024;
    /** settings widgets for the experimental neural voicebank (updated after training) */
    private CheckBox neuralChk;
    private TextView neuralDesc;
    private TextView neuralDialogStatus;
    private NeuralSpectrum universalNeural;
    private boolean universalNeuralTried;
    /** the virtual BriNeting base singer shown in the singer picker */
    private VoiceModel briNetingVoice;

    /** keeps a note phoneme when its lyric is edited; off unless enabled in settings */
    private boolean phonemeLock;
    /** ms the cursor is pulled back so it matches the sound the user hears */
    private int cursorOffsetMs;
    /** measured output latency; the live sampler uses it only when timestamps are unavailable */
    private int latencyMeasuredMs = -1;
    private String latencySource = "";
    private boolean latencyAuto = true;
    private boolean latencyMeasuring;
    /** experimental AI + external control settings (both off by default) */
    private com.dsh.v4tune.ai.AiConfig aiCfg;
    private com.dsh.v4tune.ai.ExtApi extApi;
    private volatile boolean aiBusy;
    private String aiLastError;
    private String aiLastReply;
    private int aiLastApplied;
    /** BriTune-Net quick settings, persisted in the brivoice preferences */
    private int localStyle, localIntensity = 85, localStrength = 90;
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
    /** clip lists snapshotted with each edit, so undo/redo covers imported audio too */
    private final java.util.ArrayDeque<java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>>> undoClips
            = new java.util.ArrayDeque<java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>>>();
    private final java.util.ArrayDeque<java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>>> redoClips
            = new java.util.ArrayDeque<java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>>>();
    private java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>> clipsBaseline;
    private VoiceModel currentVoice = null;      // BriVoice ships no voice: only installed voicebanks
    private int snapTicks = 240;
    private SliderBox snapBox, tempoBox, zoomBox;
    /** top-bar controls that carry a state, kept so they can be refreshed after a change */
    private com.dsh.v4tune.ui.ToolIcon snapIcon;
    private TextView snapValue, meterBtn;
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
    /** v12.4 top bar pieces: rail, track lanes, BPM and total-length readout */
    private com.dsh.v4tune.ui.IconRail railView;
    private View trackBarView;
    private com.dsh.v4tune.ui.TrackStripView trackStripView;
    private TextView modeEditBtn, modeSelBtn;
    /** false = 选择模式: the strip only picks lanes and reorders them by dragging */
    private boolean stripEditMode = true;
    private ScrollView trackScroll;
    private TextView bpmText, totalText;
    private Button playBtn;
    private Button newBtn, openBtn, exportBtn, packBtn, toolsBtn;
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

    // ---------------------------------------------------------------- autosave
    private boolean autoSaveEnabled = true;
    private int autoSaveSec = 60;
    private boolean webSynth = false;
    private int voiceColor = 0;
    private boolean followPlayhead = true;
    /** coalesced playhead refresh: the audio callback only stores the newest frame */
    private volatile long pendingPlayheadTick = -1L;
    private volatile boolean playheadRefreshPosted = false;
    private final Runnable playheadRefresh = new Runnable() {
        public void run() {
            playheadRefreshPosted = false;
            long tick = pendingPlayheadTick;
            if (tick < 0L) return;
            if (roll != null) roll.setPlayheadTick(tick, followPlayhead);
            if (paramView != null) paramView.setPlayheadTick(tick);
            if (trackStripView != null) trackStripView.setPlayhead(tick);
            String text = formatTick(tick);
            if (posText != null && !text.contentEquals(posText.getText())) posText.setText(text);
        }
    };
    /** set while the overlap dialog re-enters playback so the check does not ask again */
    private boolean skipOverlapCheck = false;
    private boolean dirty = false;
    private boolean autoSaving = false;
    /** optional character art in the lower-left corner of the piano roll */
    private android.widget.ImageView charView;
    private android.widget.FrameLayout rollFrame;
    private android.graphics.Bitmap charBitmap;
    private int charAlpha = 190;
    private float charSize = 0.42f;
    private android.os.Handler autoSaveHandler;
    private Runnable autoSaveTick;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // probe for a newer build a moment after start-up, off the cold-start path
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                if (!isFinishing()) checkUpdate(true);
            }
        }, 2500);
        loadDictionary();
        migrateOldPacks();
        packStore = new com.dsh.v4tune.io.PackStore(com.dsh.v4tune.io.PackStore.defaultDir(this));
        aiCfg = com.dsh.v4tune.ai.AiConfig.load(this);
        phonemeLock = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("phonemeLock", false);
        cursorOffsetMs = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("cursorOffsetMs", 0);
        latencyMeasuredMs = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("latencyMeasuredMs", -1);
        latencySource = getSharedPreferences("brivoice", MODE_PRIVATE).getString("latencySource", "");
        latencyAuto = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("latencyAuto", true);
        Renderer.measuredNoise = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("mnoise", false);
        Renderer.noiseGain = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("mnoiseGain", 100) / 100f;
        Renderer.frameSequence = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("frameseq", false);
        autoSaveEnabled = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("autosave", true);
        autoSaveSec = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("autosaveSec", 60);
        webSynth = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("websynth", false);
        voiceColor = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("voicecolor", 0);
        followPlayhead = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("follow", true);
        charAlpha = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("charAlpha", 190);
        charSize = getSharedPreferences("brivoice", MODE_PRIVATE).getFloat("charSize", 0.42f);
        Renderer.webSynth = webSynth;
        Renderer.voiceColor = voiceColor;
        renderAccel = getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("renderaccel", true);
        localStyle = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("localStyle", 0);
        localIntensity = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("localIntensity", 85);
        localStrength = getSharedPreferences("brivoice", MODE_PRIVATE).getInt("localStrength", 90);
        engine.cursorOffsetMs = cursorOffsetMs;
        engine.outputLatencyMs = Math.max(0, latencyMeasuredMs);
        engine.autoLatency = latencyAuto && latencyMeasuredMs >= 0;
        // Upgrade cleanup: a value left over from the old manual slider could reach +/-2500 ms and,
        // when combined with automatic measurement, put the cursor seconds behind the sound. Once
        // automatic measurement is active it owns the compensation, so drop the stale manual value.
        boolean legacyOffset = latencyAuto && latencyMeasuredMs >= 0
                ? cursorOffsetMs != 0
                : Math.abs(cursorOffsetMs) > 500;   // beyond any realistic device latency
        android.content.SharedPreferences offsetPrefs = getSharedPreferences("brivoice", MODE_PRIVATE);
        if (!offsetPrefs.getBoolean("offsetReset931", false)) {
            if (legacyOffset) {
                cursorOffsetMs = 0;
                engine.cursorOffsetMs = 0;
                offsetPrefs.edit().putInt("cursorOffsetMs", 0).apply();
            }
            offsetPrefs.edit().putBoolean("offsetReset931", true).apply();
        }
        reloadPacksAsync(false);
        applyExtServer();          // only starts anything when the user has enabled it
        song = Demo.build();
        baseline = song.copy();
        setContentView(buildUi());
        applyCharacterFromDisk();
        refreshAll();
        scrollToContent();
        updateAiButton();          // the workspace shortcut only exists once the UI is built
        scheduleAutoSave();
        if (latencyAuto) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() { maybeAutoMeasureLatency(); }
            }, 1000);
        }
        if (SingerLibrary.isEmpty()) {
            status("BriVoice · 尚未安装声库 —— 点「声库 → 导入声库文件」载入你自己的 .v4voice / .v4neural 声库");
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
        if (!getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("onboarded", false)) {
            ui.postDelayed(new Runnable() { public void run() { if (!isFinishing()) showOnboarding(); } }, 2200L);
        }
        boolean openedFromFile = getIntent() != null
                && Intent.ACTION_VIEW.equals(getIntent().getAction());
        handleViewIntent(getIntent());
        // v12.3: the start screen comes first, unless the app was opened *with* a project
        if (!openedFromFile) {
            ui.postDelayed(new Runnable() {
                public void run() { if (!isFinishing()) showStartScreen(); }
            }, 350L);
        }
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
        // the start screen is a full-screen dialog that measured itself for the previous
        // orientation; reopening it after the rebuild is what keeps it from being clipped
        boolean restartStart = startScreen != null && startScreen.isShowing();
        if (restartStart) {
            try { startScreen.dismiss(); } catch (Throwable ignored) { }
            startScreen = null;
        }
        // the hierarchy is rebuilt from scratch, so capture what the old roll was looking at
        // (zoom / horizontal scroll / top pitch) and hand it to the new one
        float[] rollView = (roll != null) ? roll.viewState() : null;
        long cursor = startTick;
        setContentView(buildUi());
        refreshAll();
        if (roll != null && rollView != null) {
            roll.restoreViewState(rollView);
            syncStripToRoll();
            if (paramView != null) {
                paramView.followHorizontal(roll.getPxPerTick(), roll.getScrollTick(), roll.keyWidth());
            }
        }
        if (cursor > 0) {
            syncCursor(cursor, true);
        }
        if (restartStart) showStartScreen();
        status(isLandscape() ? "已切换为横屏布局" : "已切换为竖屏布局");
    }

    private View buildUi() {
        boolean land = isLandscape();
        LinearLayout root = new LinearLayout(this);
        // v12.4 layout: one narrow icon rail on the left, then a vertical stack of
        // track bar -> piano roll -> parameter lane -> status. Everything else lives in "more".
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Theme.BG_DEEP);

        railView = new com.dsh.v4tune.ui.IconRail(this);
        railView.setPlaying(engine != null && engine.isPlaying());
        railView.setListener(new com.dsh.v4tune.ui.IconRail.Listener() {
            public void onRailItem(int item) {
                switch (item) {
                    case com.dsh.v4tune.ui.IconRail.ITEM_NEW: newProject(); break;
                    case com.dsh.v4tune.ui.IconRail.ITEM_OPEN: openProject(); break;
                    case com.dsh.v4tune.ui.IconRail.ITEM_SAVE: saveProject(false); break;
                    case com.dsh.v4tune.ui.IconRail.ITEM_PLAY:
                        if (engine.isPlaying()) stopPlay(); else playSong();
                        break;
                    case com.dsh.v4tune.ui.IconRail.ITEM_UNDO: doUndo(); break;
                    case com.dsh.v4tune.ui.IconRail.ITEM_REDO: doRedo(); break;
                    case com.dsh.v4tune.ui.IconRail.ITEM_AI: aiAskDialog(); break;
                    default: moreSheet(); break;
                }
            }
        });
        // portrait phones are narrow: give the notes 8dp more room and keep the captions readable
        root.addView(railView,
                new LinearLayout.LayoutParams(dp(land ? 64 : 56), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        trackBarView = buildTrackBar();
        right.addView(trackBarView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        roll = new PianoRollView(this);
        roll.setListener(this);
        roll.setSnapTicks(snapTicks);
        rollFrame = new FrameLayout(this);
        rollFrame.addView(roll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        charView = new android.widget.ImageView(this);
        charView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        charView.setAdjustViewBounds(true);
        charView.setClickable(false);
        charView.setFocusable(false);
        charView.setVisibility(View.GONE);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM | Gravity.START;
        clp.setMargins(dp(4), 0, 0, dp(4));
        rollFrame.addView(charView, clp);
        rollFrame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                updateCharacterOverlay();
            }
        });
        right.addView(rollFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (zoomBox != null) zoomBox.bar.setProgress(zoomIndex());
        roll.setZoomListener(new PianoRollView.ZoomListener() {
            public void onZoomChanged(float pxPerTick) {
                // pinch / double-tap zoom keeps the slider and its readout in step
                if (zoomBox == null) return;
                zoomBox.bar.setProgress(zoomIndex());
                zoomBox.setValue(zoomText());
            }

            @Override
            public void onViewChanged(float px, float tick, float leftInset) {
                // read the gutter fresh: it is 0 until the roll has been laid out, and a stale 0
                // left the whole tuning lane drawn 110 px to the left of the note grid
                if (paramView != null) paramView.followHorizontal(px, tick, roll.keyWidth());
                if (trackStripView != null) trackStripView.followHorizontal(px, tick, roll.keyWidth());
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(land ? 138 : 196)));

        statusText = new TextView(this);
        statusText.setTextColor(Theme.TEXT_DIM);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusText.setPadding(dp(12), dp(7), dp(12), dp(7));
        statusText.setBackground(Theme.card(this, Theme.PANEL));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(6), dp(3), dp(6), dp(6));
        right.addView(statusText, slp);

        // root stays HORIZONTAL in both orientations (rail on the left), so the weight axis is
        // WIDTH and the height must be MATCH_PARENT. Portrait used to pass height=0 with weight=1:
        // a horizontal LinearLayout spends weight on width only, so the whole right column measured
        // to zero height -- portrait showed a working rail next to a completely black editor.
        root.addView(right,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return root;
    }


    /** play/stop state lives in the rail icon, not in a text button */
    private void setPlayUi(boolean playing) {
        if (railView != null) railView.setPlaying(playing);
        if (playBtn != null) playBtn.setText(playing ? L.t("transport.stop") : L.t("transport.play"));
    }

    /** everything that used to sit on the main toolbar lives here */
    private void moreSheet() {
        final String[] items = {
                L.t("toolbar.save.as"),
                L.t("toolbar.export.wav"),
                L.t("toolbar.voicebank"),
                L.t("track.manage"),
                L.t("start.settings"),
                L.t("start.tutorial"),
                L.t("start.gitee"),
                L.t("start.bili"),
                L.t("toolbar.about"),
                L.t("toolbar.demo"),
                L.t("more.character"),
                L.t("menu.tools"),
                L.t("menu.tools.orientation")
        };
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(12));
        final Dialog dlg = new Dialog(this);
        TextView head = new TextView(this);
        head.setText(L.t("start.more"));
        head.setTextColor(Theme.ACCENT);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
        head.setPadding(dp(6), dp(2), 0, dp(8));
        box.addView(head);
        LinearLayout row = null;
        for (int i = 0; i < items.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            Button b = small(items[i]);
            final int which = i;
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dlg.dismiss();
                    switch (which) {
                        case 0: saveProject(true); break;
                        case 1: exportWav(); break;
                        case 2: managePacks(); break;
                        case 3: trackMenu(); break;
                        case 4: settingsDialog(); break;
                        case 5: showTutorial(); break;
                        case 6: openUrl("https://gitee.com/h23c5/bri-voice"); break;
                        case 7: openUrl("https://space.bilibili.com/3461562746538484"); break;
                        case 8: about(); break;
                        case 9: beginEdit(); song = Demo.twinkle(); commitEdit(); refreshAll(); scrollToContent(); status("已载入示例乐句"); break;
                        case 10: characterDialog(); break;
                        case 11: toolsMenu(); break;
                        default: toggleOrientation(); break;
                    }
                }
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            blp.setMargins(dp(4), dp(3), dp(4), dp(3));
            row.addView(b, blp);
        }
        dlg.setContentView(box);
        Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(Theme.card(this, Theme.PANEL));
            // 62% of the width reads well in landscape but leaves narrow buttons on a phone in
            // portrait, where there is no side-by-side content competing for space
            win.setLayout((int) (getResources().getDisplayMetrics().widthPixels
                            * (isLandscape() ? 0.62f : 0.88f)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    private View buildTrackBar() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Theme.PANEL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(3), dp(10), dp(3));

        TextView add = new TextView(this);
        add.setText("＋");
        add.setTextColor(Theme.ACCENT);
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        add.setGravity(Gravity.CENTER);
        add.setContentDescription(L.t("track.add"));
        add.setBackground(Theme.card(this, Theme.PANEL_DK));
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addTrackMenu(); }
        });
        bar.addView(add, new LinearLayout.LayoutParams(dp(30), dp(30)));

        modeEditBtn = modeChip("修改");
        modeEditBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setStripMode(true); }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.setMargins(dp(8), 0, 0, 0);
        bar.addView(modeEditBtn, elp);

        modeSelBtn = modeChip("选择");
        modeSelBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setStripMode(false); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(4), 0, 0, 0);
        bar.addView(modeSelBtn, slp);

        bpmText = new TextView(this);
        bpmText.setTextColor(Theme.TEXT);
        bpmText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        bpmText.setPadding(dp(8), dp(4), dp(8), dp(4));
        bpmText.setBackground(Theme.card(this, Theme.PANEL_DK));
        bpmText.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editTempo(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(6), 0, 0, 0);
        bar.addView(bpmText, blp);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // --- right side: snap (icon + value), time signature, mixer, go to start / end ---
        LinearLayout snapBox2 = new LinearLayout(this);
        snapBox2.setOrientation(LinearLayout.HORIZONTAL);
        snapBox2.setGravity(Gravity.CENTER_VERTICAL);
        snapBox2.setPadding(dp(5), dp(3), dp(8), dp(3));
        snapBox2.setBackground(Theme.card(this, Theme.PANEL_DK));
        snapBox2.setContentDescription("网格吸附");
        View.OnClickListener snapClick = new View.OnClickListener() {
            public void onClick(View v) { cycleSnap(null); }
        };
        snapIcon = new com.dsh.v4tune.ui.ToolIcon(this, com.dsh.v4tune.ui.ToolIcon.SNAP);
        snapIcon.setOnClickListener(snapClick);
        LinearLayout.LayoutParams snapIconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        snapBox2.addView(snapIcon, snapIconLp);
        snapValue = new TextView(this);
        snapValue.setTextColor(Theme.TEXT);
        snapValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        snapValue.setOnClickListener(snapClick);
        LinearLayout.LayoutParams snapValLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        snapValLp.setMargins(dp(3), 0, 0, 0);
        snapBox2.addView(snapValue, snapValLp);
        LinearLayout.LayoutParams snapBoxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        snapBoxLp.setMargins(dp(6), 0, 0, 0);
        bar.addView(snapBox2, snapBoxLp);

        meterBtn = new TextView(this);
        meterBtn.setTextColor(Theme.TEXT);
        meterBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        meterBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        meterBtn.setBackground(Theme.card(this, Theme.PANEL_DK));
        meterBtn.setContentDescription("拍号");
        meterBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cycleMeter(); }
        });
        meterBtn.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                boolean on = !engine.metronome;
                engine.metronome = on;
                status(on ? "节拍器: 开（每拍一下，小节首拍拍重）" : "节拍器: 关");
                return true;
            }
        });
        LinearLayout.LayoutParams meterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        meterLp.setMargins(dp(6), 0, 0, 0);
        bar.addView(meterBtn, meterLp);

        toolIcon(bar, com.dsh.v4tune.ui.ToolIcon.MIXER, "混音器", new View.OnClickListener() {
            public void onClick(View v) { showMixer(); }
        });
        toolIcon(bar, com.dsh.v4tune.ui.ToolIcon.TO_START, "回到开始", new View.OnClickListener() {
            public void onClick(View v) { seekTo(0); }
        });
        toolIcon(bar, com.dsh.v4tune.ui.ToolIcon.TO_END, "回到末尾", new View.OnClickListener() {
            public void onClick(View v) { seekTo(song.maxTick()); }
        });
        flushTopBar();

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.END);
        // the two clock lines are stacked; in portrait the bar is narrower and tighter, so the
        // type shrinks a notch and the block gets its own bottom padding (it used to be clipped
        // by the arrangement strip below)
        boolean portraitClock = !isLandscape();
        posText = new TextView(this);
        posText.setTextColor(Theme.TEXT);
        posText.setTextSize(TypedValue.COMPLEX_UNIT_SP, portraitClock ? 10f : 11f);
        totalText = new TextView(this);
        totalText.setTextColor(Theme.TEXT_DIM);
        totalText.setTextSize(TypedValue.COMPLEX_UNIT_SP, portraitClock ? 8f : 9f);
        clock.setPadding(0, dp(1), dp(2), portraitClock ? dp(3) : 0);
        clock.addView(posText);
        clock.addView(totalText);
        bar.addView(clock);
        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        trackStripView = new com.dsh.v4tune.ui.TrackStripView(this);
        trackStripView.setListener(new com.dsh.v4tune.ui.TrackStripView.Listener() {
            public void onLaneSelected(int i) { selectTrack(i); }
            public void onLaneLongPress(int i) { trackLongPress(i); }
            public void onLaneShift(int i, long delta) { shiftTrack(i, delta); }
            public void onLaneMute(int i, boolean mute) {
                if (i < 0 || i >= song.tracks.size()) return;
                beginEdit();
                song.tracks.get(i).mute = mute;
                commitEdit();
                refreshTrackBar();
                status((mute ? "已静音 " : "已取消静音 ") + trackLabel(i));
            }
            public void onLaneSolo(int i, boolean solo) {
                if (i < 0 || i >= song.tracks.size()) return;
                beginEdit();
                song.tracks.get(i).solo = solo;
                commitEdit();
                refreshTrackBar();
                status((solo ? "独奏 " : "取消独奏 ") + trackLabel(i));
            }
            public void onSeek(long tick) { seekTo(tick); }
            public void onLaneMove(int from, int to) {
                if (stripEditMode) return;       // 修改模式里上下拖动不改顺序
                moveTrack(from, to);
            }
        });
        trackStripView.setEditMode(stripEditMode);
        styleStripMode();
        trackScroll = new ScrollView(this);
        trackScroll.setVerticalScrollBarEnabled(false);
        trackScroll.setFillViewport(false);
        trackScroll.addView(trackStripView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(trackScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));
        refreshTrackBar();
        syncStripToRoll();
        return col;
    }

    /** hand the roll's current horizontal window to the arrangement strip (same zoom, same offset) */
    private void syncStripToRoll() {
        if (trackStripView == null || roll == null) return;
        trackStripView.followHorizontal(roll.getPxPerTick(), roll.getScrollTick(), roll.keyWidth());
    }

    /** rebuild every lane thumbnail from the song plus the in-memory audio clips */
    private void refreshTrackBar() {
        if (bpmText != null) bpmText.setText("BPM " + tempoText());
        if (posText != null) posText.setText(formatTick(startTick));
        if (trackStripView == null) return;

        java.util.ArrayList<com.dsh.v4tune.ui.TrackStripView.Lane> lanes =
                new java.util.ArrayList<com.dsh.v4tune.ui.TrackStripView.Lane>();
        long contentEnd = 0L;
        for (int i = 0; i < song.tracks.size(); i++) {
            com.dsh.v4tune.model.Track t = song.tracks.get(i);
            com.dsh.v4tune.ui.TrackStripView.Lane ln = new com.dsh.v4tune.ui.TrackStripView.Lane();
            String defName = L.t("track.name", i + 1);
            String raw = t.name == null ? "" : t.name.trim();
            String title = defName;
            if (t.audio) {
                title = raw.isEmpty() ? defName : raw;
            } else if (!raw.isEmpty() && !raw.equals(defName) && !raw.equals("音轨 " + (i + 1))) {
                title = defName + " · " + raw;
            }
            ln.name = title;
            ln.audio = t.audio;
            ln.mute = t.mute;
            ln.solo = t.solo;
            ln.color = com.dsh.v4tune.ui.TrackStripView.colorFor(i);
            for (Part part : t.parts) {
                for (Note n : part.notes) {
                    ln.notes.add(new long[]{n.pos, n.dur, n.key});
                    contentEnd = Math.max(contentEnd, n.end());
                }
            }
            for (AudioClip cl : clipsFor(i)) {
                if (cl == null) continue;
                ln.clips.add(cl);
                contentEnd = Math.max(contentEnd, cl.endTick((int) Math.round(song.tempoAt(0) * 100)));
            }
            // the second line is THIS track's singer, not the app-wide one: long press switches it
            String shortSinger = t.audio ? "" : singerName(singerOfTrack(t));
            if (shortSinger.isEmpty()) shortSinger = "未选择歌手";
            int cut = shortSinger.indexOf("（");
            if (cut > 0) shortSinger = shortSinger.substring(0, cut).trim();
            ln.sub = t.audio
                    ? (ln.clips.isEmpty() ? "音频轨（空）" : ln.clips.size() + " 个音频片段")
                    : shortSinger;
            lanes.add(ln);
        }
        long len = Math.max(song.maxTick(), contentEnd);
        if (totalText != null) totalText.setText("/ " + formatDuration(len));
        trackStripView.setSongLength(len);
        trackStripView.setTempo((int) Math.round(song.tempoAt(0) * 100));
        trackStripView.setPlayhead(startTick);
        trackStripView.setLanes(lanes, trackIndex);
        syncStripToRoll();

        int rows = Math.max(1, song.tracks.size());
        int h = dp(18) + dp(38) * rows + dp(2);
        int maxH = dp(150);
        if (trackScroll != null) {
            ViewGroup.LayoutParams lp = trackScroll.getLayoutParams();
            if (lp != null && lp.height != Math.min(h, maxH)) {
                lp.height = Math.min(h, maxH);
                trackScroll.setLayoutParams(lp);
            }
        }
    }

    private String formatDuration(long tick) {
        double sec = TimeUtil.tickToSec(song, Math.max(0L, tick));
        int m = (int) (sec / 60), s = (int) Math.round(sec - m * 60);
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    /** track name, or a positional fallback when the project stores an empty one */
    private String trackLabel(int i) {
        if (i < 0 || i >= song.tracks.size()) return "?";
        String n = song.tracks.get(i).name;
        return n == null || n.trim().isEmpty() ? ("音轨 " + (i + 1)) : n;
    }

    private void selectTrack(int idx) {
        if (idx < 0 || idx >= song.tracks.size()) return;
        String shown = trackLabel(idx);
        if (idx == trackIndex) { status("当前音轨: " + shown); return; }
        beginEdit();
        trackIndex = idx;
        commitEdit();
        refreshAll();
        roll.showKeyRange(48, 84);
        scrollToContent();
        status("当前音轨: " + shown);
    }

    /** small toggle above the arrangement strip */
    private TextView modeChip(String label) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextColor(Theme.TEXT_DIM);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(9), dp(4), dp(9), dp(4));
        return v;
    }

    /**
     * 修改 = every direct manipulation the strip always had (M/S, long press menu, drag to shift
     * the content). 选择 = none of those, so a slip of the finger cannot change the song; the strip
     * only picks a lane and moves it to another row.
     */
    private void setStripMode(boolean edit) {
        stripEditMode = edit;
        if (trackStripView != null) trackStripView.setEditMode(edit);
        styleStripMode();
        status(edit ? "音轨栏：修改模式" : "音轨栏：选择模式（长按音轨再拖动可排序）");
    }

    private void styleStripMode() {
        if (modeEditBtn != null) {
            modeEditBtn.setTextColor(stripEditMode ? Theme.ACCENT : Theme.TEXT_DIM);
            modeEditBtn.setBackground(stripEditMode ? Theme.card(this, Theme.PANEL_DK) : null);
        }
        if (modeSelBtn != null) {
            modeSelBtn.setTextColor(stripEditMode ? Theme.TEXT_DIM : Theme.ACCENT);
            modeSelBtn.setBackground(stripEditMode ? null : Theme.card(this, Theme.PANEL_DK));
        }
    }

    /** starts at the same place the old add button was: a menu instead of a guess */
    private void addTrackMenu() {
        final String[] items = {"新建歌声轨", "添加音频轨（WAV）"};
        new AlertDialog.Builder(this)
                .setTitle(L.t("track.add"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) addTrack(); else addAudioTrack();
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    private void addTrack() {
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
    }

    /** creates an empty audio track, then reuses the WAV picker so the clip lands on it */
    private void addAudioTrack() {
        beginEdit();
        com.dsh.v4tune.model.Track t = new com.dsh.v4tune.model.Track();
        t.no = song.tracks.size() + 1;
        t.audio = true;
        t.name = "音频 " + (song.tracks.size() + 1);
        song.tracks.add(t);
        trackIndex = song.tracks.size() - 1;
        commitEdit();
        refreshAll();
        status("音频轨已建好，请选择 WAV");
        importWav();
    }

    /** moves the whole content of a track along the timeline (drag on the lane / 更改 → 相对位置) */
    private void shiftTrack(int index, long deltaTicks) {
        if (index < 0 || index >= song.tracks.size() || deltaTicks == 0L) return;
        beginEdit();
        com.dsh.v4tune.model.Track t = song.tracks.get(index);
        for (Part part : t.parts) {
            part.pos = Math.max(0L, part.pos + deltaTicks);
            for (Note n : part.notes) n.pos = Math.max(0L, n.pos + deltaTicks);
        }
        for (AudioClip cl : clipsFor(index)) {
            if (cl != null) cl.startTick = Math.max(0L, cl.startTick + deltaTicks);
        }
        commitEdit();
        refreshAll();
        status("音轨内容平移 " + (deltaTicks > 0 ? "+" : "") + deltaTicks + " tick");
    }

    private long contentStart(int index) {
        long min = Long.MAX_VALUE;
        com.dsh.v4tune.model.Track t = song.tracks.get(index);
        for (Part part : t.parts) {
            if (!part.notes.isEmpty()) min = Math.min(min, part.pos);
            for (Note n : part.notes) min = Math.min(min, n.pos);
        }
        for (AudioClip cl : clipsFor(index)) if (cl != null) min = Math.min(min, cl.startTick);
        return min == Long.MAX_VALUE ? 0L : min;
    }

    /**
     * Long press on a lane shows THIS track's singer list: the names are the switcher, one tap
     * assigns that singer to that one track. The old 更改 / 删除 dialog stays reachable as the
     * last row, and audio tracks (which have no singer) still open it directly.
     */
    private void trackLongPress(final int idx) {
        if (idx < 0 || idx >= song.tracks.size()) return;
        final com.dsh.v4tune.model.Track t = song.tracks.get(idx);
        if (t.audio) {                       // an audio track has no voice: its clip is what gets edited
            audioTrackMenu(idx);
            return;
        }
        final java.util.List<VoiceModel> all = SingerLibrary.all();
        final VoiceModel cur = singerOfTrack(t);
        java.util.List<com.dsh.v4tune.ui.Dialogs.Row> rows = com.dsh.v4tune.ui.Dialogs.rows();
        for (int i = 0; i < all.size(); i++) {
            final VoiceModel v = all.get(i);
            if (v.neural == null) attachNeural(v);      // decide the engine badge accurately
            boolean on = cur != null && cur.id != null && cur.id.equals(v.id);
            String engine;
            if (v.neuralBank) {
                engine = v.neuralEnabled ? "神经声库" : "神经声库（需训练基座）";
            } else {
                engine = v.neuralEnabled ? "神经合成" : "传统合成";
            }
            rows.add(com.dsh.v4tune.ui.Dialogs.row(
                    com.dsh.v4tune.ui.Dialogs.badgeFor(singerName(v)),
                    com.dsh.v4tune.ui.Dialogs.PALETTE[i % com.dsh.v4tune.ui.Dialogs.PALETTE.length],
                    singerLabel(v), engine + (on ? " · 当前" : ""), on, new Runnable() {
                        public void run() { setTrackSinger(idx, v); }
                    }));
        }
        if (rows.isEmpty()) {
            rows.add(com.dsh.v4tune.ui.Dialogs.row("!", Theme.ACCENT2, "还没有安装声库",
                    "先在「更多 → 声库」里导入一个", false, null));
        }
        rows.add(com.dsh.v4tune.ui.Dialogs.row("…", Theme.TEXT_DIM, "更多音轨设置",
                "重命名 / 相对位置 / 上下移 / 删除", false, new Runnable() {
                    public void run() { trackActionsDialog(idx); }
                }));
        com.dsh.v4tune.ui.Dialogs.menu(this, trackLabel(idx) + " · 歌手",
                cur == null ? "点一个名字即可把这条轨切成该歌手" : ("当前：" + singerLabel(cur)),
                rows);
    }

    /** assign one singer to one track, then refresh the strip (its second line is that name) */
    private void setTrackSinger(int idx, VoiceModel vm) {
        if (idx < 0 || idx >= song.tracks.size() || vm == null) return;
        beginEdit();
        applyVoiceToTrack(song.tracks.get(idx), vm);
        if (idx == trackIndex) {             // keep the app-wide singer in step with the selected track
            currentVoice = vm;
            attachNeural(currentVoice);
        }
        cacheL = null; cacheR = null; cacheKey = 0L; cacheTotal = -1;   // the mix sounds different now
        commitEdit();
        refreshAll();
        status(trackLabel(idx) + " 歌手: " + singerLabel(vm));
    }

    /**
     * Long press on an audio track: the imported clip is the thing the user wants to change, so it
     * comes first - 更换音频 / 删除片段 - and the track-level actions stay one tap away.
     */
    private void audioTrackMenu(final int idx) {
        if (idx < 0 || idx >= song.tracks.size()) return;
        final com.dsh.v4tune.model.Track t = song.tracks.get(idx);
        final java.util.List<AudioClip> cs = clipsFor(idx);
        String line;
        if (cs.isEmpty()) {
            line = "这一轨还没有音频，点「更换音频」选一个 WAV";
        } else {
            AudioClip c0 = cs.get(0);
            line = cs.size() == 1
                    ? (c0.name == null ? "audio" : c0.name) + String.format(java.util.Locale.US, " · %.1f 秒", c0.frames() / 44100.0)
                    : (cs.size() + " 个音频片段");
        }
        java.util.List<com.dsh.v4tune.ui.Dialogs.Row> rows = com.dsh.v4tune.ui.Dialogs.rows();
        rows.add(com.dsh.v4tune.ui.Dialogs.row(
                com.dsh.v4tune.ui.Dialogs.badgeFor("更换音频"), com.dsh.v4tune.ui.Dialogs.PALETTE[0],
                "更换音频…", "重新选一个 WAV，替换这一轨的音频", false, new Runnable() {
                    public void run() { replaceAudio(idx); }
                }));
        if (!cs.isEmpty()) {
            rows.add(com.dsh.v4tune.ui.Dialogs.row(
                    com.dsh.v4tune.ui.Dialogs.badgeFor("删除音频"), com.dsh.v4tune.ui.Dialogs.PALETTE[5],
                    cs.size() > 1 ? ("删除音频片段（" + cs.size() + " 个）") : "删除音频片段",
                    "只清掉波形，音轨留着（可撤销）", false, new Runnable() {
                        public void run() { confirmRemoveClips(idx); }
                    }));
        }
        rows.add(com.dsh.v4tune.ui.Dialogs.row(
                com.dsh.v4tune.ui.Dialogs.badgeFor("重命名音轨"), com.dsh.v4tune.ui.Dialogs.PALETTE[2],
                "重命名音轨", t.name == null ? "" : t.name, false, new Runnable() {
                    public void run() { selectTrack(idx); renameTrack(); }
                }));
        rows.add(com.dsh.v4tune.ui.Dialogs.row(
                "…", Theme.TEXT_DIM, "更多音轨设置",
                "相对位置 / 上移 / 下移 / 删除音轨", false, new Runnable() {
                    public void run() { trackActionsDialog(idx); }
                }));
        com.dsh.v4tune.ui.Dialogs.menu(this, trackLabel(idx) + " · 音频", line, rows);
    }

    /** 更换音频：先清掉这一轨的旧片段，再走同一个 WAV 导入（导入会落到当前这条音频轨） */
    private void replaceAudio(final int idx) {
        if (idx < 0 || idx >= song.tracks.size()) return;
        selectTrack(idx);                    // addWavFrom() puts the clip on the current audio track
        final java.util.List<AudioClip> cs = clipsFor(idx);
        boolean had = !cs.isEmpty();
        beginEdit();
        cs.clear();
        commitEdit();
        refreshAll();
        if (had) status("已移除旧音频，请选择新的 WAV");
        importWav();
    }

    /** 删除这一轨的音频片段（音轨本身保留），可撤销 */
    private void confirmRemoveClips(final int idx) {
        if (idx < 0 || idx >= song.tracks.size()) return;
        final java.util.List<AudioClip> cs = clipsFor(idx);
        if (cs.isEmpty()) { status("这一轨没有音频片段"); return; }
        new AlertDialog.Builder(this)
                .setTitle("删除音频")
                .setMessage("删掉「" + trackLabel(idx) + "」上的 " + cs.size() + " 个音频片段？\n音轨会保留，之后可以用撤销找回。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        beginEdit();
                        int n = cs.size();
                        cs.clear();
                        // the sidecar file is kept on purpose: undo restores the clip object, and a
                        // deleted file would make the restored clip silent
                        commitEdit();
                        refreshAll();
                        status("已删除 " + n + " 个音频片段（可撤销）");
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    private void trackActionsDialog(final int idx) {
        if (idx < 0 || idx >= song.tracks.size()) return;
        final com.dsh.v4tune.model.Track t = song.tracks.get(idx);
        new AlertDialog.Builder(this)
                .setTitle(t.name)
                .setItems(new String[]{"更改", "删除"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) trackEditMenu(idx); else confirmDeleteTrack(idx);
                    }
                })
                .show();
    }

    private void trackEditMenu(final int idx) {
        final com.dsh.v4tune.model.Track t = song.tracks.get(idx);
        final String[] items = {
                "重命名",
                t.audio ? "重新选择音频" : "更换歌手",
                "调整相对位置（起始小节）",
                "上移",
                "下移"
        };
        new AlertDialog.Builder(this)
                .setTitle("更改 · " + t.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                selectTrack(idx);
                                renameTrack();
                                break;
                            case 1:
                                if (t.audio) {
                                    replaceAudio(idx);
                                } else {
                                    selectTrack(idx);
                                    pickSinger();
                                }
                                break;
                            case 2: askTrackStart(idx); break;
                            case 3: moveTrack(idx, idx - 1); break;
                            default: moveTrack(idx, idx + 1); break;
                        }
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    private void askTrackStart(final int idx) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(contentStart(idx) / 1920L + 1));
        new AlertDialog.Builder(this)
                .setTitle("起始小节（第几小节开始）")
                .setView(et)
                .setPositiveButton(L.t("dlg.ok"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        long bar;
                        try { bar = Long.parseLong(et.getText().toString().trim()); }
                        catch (Throwable x) { status("请输入小节号"); return; }
                        if (bar < 1) bar = 1;
                        long target = (bar - 1) * 1920L;
                        shiftTrack(idx, target - contentStart(idx));
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    private void moveTrack(int from, int to) {
        if (from < 0 || from >= song.tracks.size()) return;
        if (to < 0 || to >= song.tracks.size() || to == from) return;
        beginEdit();
        com.dsh.v4tune.model.Track t = song.tracks.remove(from);
        song.tracks.add(to, t);
        // the clip lists are parallel to the track list: move them together or clips will follow
        // the wrong track after the first reorder
        while (clips.size() < song.tracks.size()) clips.add(new java.util.ArrayList<AudioClip>());
        java.util.ArrayList<AudioClip> cs = clips.remove(from);
        clips.add(to, cs);
        trackIndex = to;
        for (int i = 0; i < song.tracks.size(); i++) song.tracks.get(i).no = i + 1;
        commitEdit();
        refreshAll();
        status("音轨已移动，当前: " + trackLabel(trackIndex));
    }

    private void confirmDeleteTrack(final int idx) {
        if (song.tracks.size() <= 1) { status("至少要保留一条音轨"); return; }
        final com.dsh.v4tune.model.Track t = song.tracks.get(idx);
        new AlertDialog.Builder(this)
                .setTitle("删除音轨")
                .setMessage("确定删除「" + t.name + "」及其内容？可以撤销。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        beginEdit();
                        song.tracks.remove(idx);
                        if (idx < clips.size()) clips.remove(idx);
                        if (trackIndex >= song.tracks.size()) trackIndex = song.tracks.size() - 1;
                        for (int i = 0; i < song.tracks.size(); i++) song.tracks.get(i).no = i + 1;
                        commitEdit();
                        refreshAll();
                        status("已删除音轨: " + t.name);
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    /** dedicated sheet so the character feature is not buried at the bottom of settings */
    private void characterDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));
        TextView desc = new TextView(this);
        desc.setText("选一张图片，自动去掉白底；透明 PNG 直接使用。立绘显示在钢琴卷帘左下角。");
        desc.setTextColor(Theme.TEXT_DIM);
        desc.setTextSize(12.5f);
        box.addView(desc);
        LinearLayout btns = row();
        Button pick = small("选择图片…");
        Button clear = small("清除立绘");
        btns.addView(pick); btns.addView(clear);
        box.addView(btns);
        final TextView alphaLabel = label("透明度 " + Math.round(charAlpha * 100f / 255f) + "%");
        box.addView(alphaLabel);
        final SeekBar alpha = new SeekBar(this);
        alpha.setMax(255);
        alpha.setProgress(Math.max(0, Math.min(255, charAlpha)));
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                charAlpha = pr; alphaLabel.setText("透明度 " + Math.round(charAlpha * 100f / 255f) + "%");
                updateCharacterOverlay();
                getSharedPreferences("brivoice", MODE_PRIVATE).edit().putInt("charAlpha", charAlpha).apply();
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(alpha);
        final TextView sizeLabel = label("大小 " + Math.round(charSize * 100) + "%");
        box.addView(sizeLabel);
        final SeekBar size = new SeekBar(this);
        size.setMax(100);
        size.setProgress(Math.max(0, Math.min(100, Math.round((charSize - 0.15f) / 0.60f * 100f))));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                charSize = 0.15f + pr / 100f * 0.60f;
                sizeLabel.setText("大小 " + Math.round(charSize * 100) + "%");
                updateCharacterOverlay();
                getSharedPreferences("brivoice", MODE_PRIVATE).edit().putFloat("charSize", charSize).apply();
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(size);
        pick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickCharacter(); } });
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { clearCharacter(); } });
        new AlertDialog.Builder(this).setTitle("角色立绘").setView(box)
                .setPositiveButton(L.t("dlg.ok"), null).setNegativeButton(L.t("dlg.cancel"), null).show();
    }

    /** character art stored in the app's private files, reloaded on every start */
    private void applyCharacterFromDisk() {
        try {
            java.io.File f = com.dsh.v4tune.ui.CharacterArt.savedFile(this);
            if (!f.exists()) return;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bm == null) return;
            charBitmap = bm;
            charView.setImageBitmap(bm);
            charView.setVisibility(View.VISIBLE);
            updateCharacterOverlay();
        } catch (Throwable ignored) { }
    }

    private void updateCharacterOverlay() {
        if (charView == null) return;
        if (charBitmap == null) { charView.setVisibility(View.GONE); return; }
        // a rotation rebuilds the hierarchy: the new ImageView has no drawable yet, so re-attach
        // the bitmap that is still in memory (otherwise the art silently disappears until restart)
        if (charView.getDrawable() == null) charView.setImageBitmap(charBitmap);
        charView.setAlpha(Math.max(0f, Math.min(1f, charAlpha / 255f)));
        int baseH = (rollFrame != null && rollFrame.getHeight() > 0) ? rollFrame.getHeight() : getResources().getDisplayMetrics().heightPixels;
        int h = (int) (baseH * charSize);
        h = Math.max(dp(60), Math.min(h, (int) (baseH * 0.92f)));
        float ratio = (charBitmap.getHeight() > 0) ? (float) charBitmap.getWidth() / charBitmap.getHeight() : 0.7f;
        int w = Math.max(dp(40), (int) (h * ratio));
        // portrait: the roll is tall, so a height-driven size would blow the illustration up until
        // it covered the notes; clamp by the roll's width instead (landscape keeps the old maths)
        if (!isLandscape() && rollFrame != null && rollFrame.getWidth() > 0) {
            int maxW = (int) (rollFrame.getWidth() * 0.36f);
            if (w > maxW && maxW > dp(40)) {
                w = maxW;
                h = (int) (w / Math.max(0.05f, ratio));
            }
        }
        ViewGroup.LayoutParams lp = charView.getLayoutParams();
        if (lp != null && (lp.width != w || lp.height != h)) {
            lp.width = w;
            lp.height = h;
            charView.setLayoutParams(lp);
        }
        charView.setVisibility(View.VISIBLE);
    }

    private void pickCharacter() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, REQ_CHAR);
        } catch (Throwable t) {
            status("无法打开图片选择器: " + t.getMessage());
        }
    }

    /** decode, flood-fill the white background away, keep a private PNG copy */
    private void loadCharacter(final Uri uri) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在处理立绘（自动去白底）…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                android.graphics.Bitmap bm = null;
                try {
                    bm = com.dsh.v4tune.ui.CharacterArt.fromUri(MainActivity.this, uri);
                    com.dsh.v4tune.ui.CharacterArt.savePng(MainActivity.this, bm);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final String fe = err;
                final android.graphics.Bitmap fb = bm;
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (fe != null) {
                            status("立绘处理失败: " + fe);
                            toast("立绘处理失败: " + fe);
                            return;
                        }
                        charBitmap = fb;
                        charView.setImageBitmap(fb);
                        charView.setVisibility(View.VISIBLE);
                        updateCharacterOverlay();
                        if (rollFrame != null) rollFrame.post(new Runnable() { public void run() { updateCharacterOverlay(); } });
                        status("已更新角色立绘（自动去白底）");
                        toast("角色立绘已更新");
                    }
                });
            }
        }, "brivoice-character").start();
    }

    private void clearCharacter() {
        try {
            java.io.File f = com.dsh.v4tune.ui.CharacterArt.savedFile(this);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) { }
        charBitmap = null;
        if (charView != null) {
            charView.setImageBitmap(null);
            charView.setVisibility(View.GONE);
        }
        status("已清除角色立绘");
        toast("已清除角色立绘");
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
                        syncSnapIcon();
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
        toolsBtn = smallButton(L.t("menu.tools"), new View.OnClickListener() {
            public void onClick(View v) { toolsMenu(); }
        });
        transport.addView(toolsBtn);
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

        newBtn = smallButton(L.t("toolbar.new"), new View.OnClickListener() {
            public void onClick(View v) { newProject(); }
        });
        bar.addView(newBtn);
        openBtn = smallButton(L.t("toolbar.open"), new View.OnClickListener() {
            public void onClick(View v) { openProject(); }
        });
        bar.addView(openBtn);
        bar.addView(smallButton(L.t("toolbar.save"), new View.OnClickListener() {
            public void onClick(View v) { saveProject(false); }
        }));
        bar.addView(smallButton(L.t("toolbar.save.as"), new View.OnClickListener() {
            public void onClick(View v) { saveProject(true); }
        }));
        exportBtn = smallButton(L.t("toolbar.export.wav"), new View.OnClickListener() {
            public void onClick(View v) { exportWav(); }
        });
        bar.addView(exportBtn);
        bar.addView(smallButton(L.t("toolbar.undo"), new View.OnClickListener() {
            public void onClick(View v) { doUndo(); }
        }));
        bar.addView(smallButton(L.t("toolbar.redo"), new View.OnClickListener() {
            public void onClick(View v) { doRedo(); }
        }));
        bar.addView(smallButton(L.t("toolbar.demo"), new View.OnClickListener() {
            public void onClick(View v) { beginEdit(); song = Demo.twinkle(); commitEdit(); refreshAll(); scrollToContent(); }
        }));
        packBtn = smallButton(L.t("toolbar.voicebank"), new View.OnClickListener() {
            public void onClick(View v) { managePacks(); }
        });
        bar.addView(packBtn);
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
        refreshTrackBar();          // the strip is a thumbnail of the same notes: it changes with them
        status("编辑: " + what + (n != null ? "  " + n.lyric : ""));
    }

    /** the roll is still dragging a note: follow along without snapshotting undo on every frame */
    public void onNoteChanging() {
        refreshTrackBar();
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

    /** playback cursor dragged on the roll ruler */
    public void onPlayheadMoved(long tick) {
        syncCursor(tick, false);
        status("播放位置: " + formatTick(startTick));
    }

    /**
     * One cursor, several views. Every seek path (roll ruler, arrangement ruler, AI "play",
     * stop, end of song) goes through here so the piano roll, the strip, the parameter lane
     * and the clock can never drift apart.
     */
    private void syncCursor(long tick, boolean follow) {
        if (tick < 0L) tick = 0L;
        startTick = tick;
        if (roll != null) roll.setPlayheadTick(tick, follow);
        if (paramView != null) paramView.setPlayheadTick(tick);
        if (trackStripView != null) trackStripView.setPlayhead(tick);
        if (posText != null) posText.setText(formatTick(tick));
    }

    /** arrangement ruler tap/drag: park every cursor on that tick and stop whatever was running */
    private void seekTo(long tick) {
        // A render or a playback callback would otherwise keep writing the old position over the
        // one the finger just picked, so both are cancelled before the cursor moves. Pressing play
        // afterwards starts from this tick (playSong reads startTick).
        if (engine.isPlaying()) {
            cancelPlayheadRefresh();
            engine.stop();
            setPlayUi(false);
        }
        syncCursor(tick, true);
        status("播放位置: " + formatTick(startTick));
    }

    // ---------------------------------------------------------------- actions

    private void refreshAll() {
        roll.setSongKeepView(song);      // keep the view still while editing
        roll.setTrackIndex(trackIndex);
        if (roll != null) roll.setClips(clipsFor(trackIndex));
        refreshTrackBar();
        flushTopBar();
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
        int minKey = 127, maxKey = 0;
        for (com.dsh.v4tune.model.Track t : song.tracks) {
            for (Part p : t.parts) {
                for (Note n : p.notes) {
                    if (n.pos < first) first = n.pos;
                    if (n.end() > last) last = n.end();
                    if (n.key < minKey) minKey = n.key;
                    if (n.key > maxKey) maxKey = n.key;
                }
            }
        }
        if (first == Long.MAX_VALUE) { first = 0; last = 1920 * 4; minKey = maxKey = 60; }
        long pad = Math.max(960, (last - first) / 8);
        roll.setPlayheadTick(-1);
        paramView.setPlayheadTick(-1);
        roll.showKeyRange(minKey, maxKey);
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
                    "frames", Integer.valueOf(c.frames())));
        }
        return b.append(']').toString();
    }
    // --------------------------------------------------- streaming (render while playing)

    /**
     * Last fully rendered song, reused while nothing that affects the sound has changed. The synth
     * always starts at frame 0, so any edit costs a full re-render; caching makes replay free.
     */
    private static long cacheKey = 0L;
    private static int cacheTotal = -1;
    private static float[] cacheL, cacheR;

    /** FNV-1a over everything the renderer reads: notes, parts, tempos, voices and the flags. */
    private static long songFingerprint(com.dsh.v4tune.model.Song s, int sampleRate, VoiceModel vm) {
        long h = 0xcbf29ce484222325L;
        h = (h ^ sampleRate) * 0x100000001b3L;
        h = (h ^ (Renderer.measuredNoise ? 1 : 0)) * 0x100000001b3L;
        h = (h ^ Float.floatToIntBits(Renderer.noiseGain)) * 0x100000001b3L;
        // the singer itself and the web-mode switches are inputs too: without these a cached render
        // was replayed after switching banks, which made every voicebank sound identical
        h = (h ^ (vm == null ? 0 : String.valueOf(vm.id).hashCode())) * 0x100000001b3L;
        h = (h ^ (vm == null ? 0 : String.valueOf(vm.name).hashCode())) * 0x100000001b3L;
        h = (h ^ (vm == null ? 0 : vm.harmonics.size())) * 0x100000001b3L;
        h = (h ^ (vm == null ? 0 : vm.overrides.size())) * 0x100000001b3L;
        h = (h ^ (Renderer.webSynth ? 4 : 0)) * 0x100000001b3L;
        h = (h ^ Renderer.voiceColor) * 0x100000001b3L;
        h = (h ^ (Renderer.frameSequence ? 8 : 0)) * 0x100000001b3L;
        for (com.dsh.v4tune.model.Voice v : s.voices) { h = (h ^ v.bs) * 0x100000001b3L; h = (h ^ v.pc) * 0x100000001b3L; }
        for (long[] tm : s.tempos) { for (long x : tm) h = (h ^ x) * 0x100000001b3L; }
        for (com.dsh.v4tune.model.Track t : s.tracks) {
            h = (h ^ (t.mute ? 1 : 0)) * 0x100000001b3L;
            h = (h ^ (t.solo ? 2 : 0)) * 0x100000001b3L;
            h = (h ^ t.vol) * 0x100000001b3L; h = (h ^ t.pan) * 0x100000001b3L;
            for (com.dsh.v4tune.model.Part p : t.parts) {
                h = (h ^ p.pos) * 0x100000001b3L; h = (h ^ p.dur) * 0x100000001b3L; h = (h ^ p.bs) * 0x100000001b3L; h = (h ^ p.pc) * 0x100000001b3L;
                for (com.dsh.v4tune.model.Note n : p.notes) {
                    h = (h ^ n.pos) * 0x100000001b3L; h = (h ^ n.dur) * 0x100000001b3L;
                    h = (h ^ n.key) * 0x100000001b3L; h = (h ^ n.vel) * 0x100000001b3L;
                    h = (h ^ String.valueOf(n.lyric).hashCode()) * 0x100000001b3L;
                    h = (h ^ String.valueOf(n.phoneme).hashCode()) * 0x100000001b3L;
                }
                for (java.util.Map.Entry<String, com.dsh.v4tune.model.ControlCurve> e : p.ctrls.entrySet()) {
                    h = (h ^ e.getKey().hashCode()) * 0x100000001b3L;
                    // every point, not just the count: an AI/curve edit that keeps the same number of
                    // points must still invalidate the cached render, otherwise it is silently replayed
                    for (com.dsh.v4tune.model.ControlCurve.P pt : e.getValue().pts) {
                        h = (h ^ pt.t) * 0x100000001b3L;
                        h = (h ^ Double.doubleToLongBits(pt.v)) * 0x100000001b3L;
                    }
                }
            }
        }
        return h;
    }

    /**
     * Rendering acceleration, switchable in settings: renders a long song in one pass instead of
     * doubling, and replays a fully rendered song from cache while nothing has changed. Turning it
     * off restores the old behaviour (stream from the first second, always re-render).
     */
    private boolean renderAccel = true;

    /** the producer of the current streaming playback, or null */
    private StreamProducer streamProducer;

    /** asks for the next slice: renders every audible track into it, then post-processes the new range */
    private final class StreamProducer implements com.dsh.v4tune.audio.AudioEngine.StreamSource {
        private final Song sng;
        private final Renderer.Cfg cfg;
        private final VoiceModel fallback;
        /** frames of the whole playback: the singer render length, or the end of the last clip */
        private final int total;
        /** frames the singer render fills; the clip audio is streamed past this point */
        private final int vocalTotal;
        private final float[] bufL, bufR;
        private volatile int ready;
        private volatile boolean finished, cancelled;

        StreamProducer(Song s, VoiceModel fb) {
            sng = s;
            fallback = fb;
            cfg = new Renderer.Cfg();
            vocalTotal = Math.max(1, com.dsh.v4tune.audio.AudioEngine.trackFrames(s, cfg));
            // A backing track may reach past the last note. The timeline (playhead, end detection)
            // covers the clip, but the singer buffers stay note-sized: the clip audio is read from
            // disk while writing, so a 4-minute WAV never becomes part of the song render.
            total = Math.max(vocalTotal, clipsEndFrame(s));
            bufL = new float[vocalTotal];
            bufR = new float[vocalTotal];
        }

        /** the singer part only; the engine streams the clips itself past this frame */
        public int vocalFrames() { return vocalTotal; }

        /** engine callback: overlay the audio clips for this block, straight from disk */
        public void mixExtras(int from, int n, float[] l, float[] r) {
            mixClipsInto(from, n, l, r);
        }

        void start() {
            // cache hit: nothing that affects the sound changed, so replay the stored render
            final long key = renderAccel ? songFingerprint(sng, cfg.sampleRate, fallback) : 0L;
            if (renderAccel && key != 0L && key == cacheKey && cacheTotal == vocalTotal
                    && cacheL != null && cacheR != null) {
                System.arraycopy(cacheL, 0, bufL, 0, vocalTotal);
                System.arraycopy(cacheR, 0, bufR, 0, vocalTotal);
                ready = vocalTotal;
                finished = true;
                android.util.Log.i("V4Tune", "render cache HIT: " + vocalTotal + " frames reused");
                return;
            }
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
                // A song that is going to be pre-rendered anyway is rendered in ONE pass. The synth always
                // restarts at frame 0 (the filters need their history), so the doubling schedule re-rendered
                // the whole prefix on every round and the total work came to about twice the final pass:
                // measured on a 167 s song, 94 s of rendering of which roughly 47 s was redundant. One pass
                // halves it with identical output. Short songs keep the 1 s first-chunk behaviour.
                final boolean fullRender = renderAccel && vocalTotal > com.dsh.v4tune.audio.AudioEngine.FULL_RENDER_FRAMES;
                // The full pass is CPU bound and runs before playback starts; dropping it to background
                // priority keeps touches and the "rendering" animation responsive on slower phones.
                if (fullRender) android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                int limit = fullRender ? vocalTotal : Math.min(vocalTotal, Math.max(1024, cfg.sampleRate));
                final long startedMs = System.currentTimeMillis();
                final long lookaheadMs = 4000L;   // stay at most ~4 s ahead of the audio clock
                while (!cancelled) {
                    // Rendering always restarts at frame 0 (the synth needs its filter history), so
                    // every growth step costs a full render of an ever longer prefix. Running those
                    // steps back to back saturated the CPU while the user was listening: playback
                    // crawled from the middle on and the tail was never produced in time, which is
                    // why long songs lost their last notes. Throttling the producer so it only ever
                    // stays a few seconds ahead keeps the first-sound latency and removes the burst.
                    // Full-render songs must not be throttled at all: -1 makes aheadMs negative so the
                    // wait loop below is skipped. (Using Long.MAX_VALUE here made aheadMs enormous and the
                    // producer slept for ever, which stalled the whole playback.)
                    long readyMs = vocalTotal > com.dsh.v4tune.audio.AudioEngine.FULL_RENDER_FRAMES
                            ? -1L
                            : (long) ((double) limit * 1000.0 / cfg.sampleRate);
                    long aheadMs = readyMs - lookaheadMs - (System.currentTimeMillis() - startedMs);
                    while (aheadMs > 0 && !cancelled) {
                        try { Thread.sleep(Math.min(aheadMs, 100L)); } catch (InterruptedException ignored) { }
                        aheadMs = readyMs - lookaheadMs - (System.currentTimeMillis() - startedMs);
                    }
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
                            vol = mixer.getGain(trackNo) * mixer.getTrim(trackNo);   // fader x trim
                            pan = mixer.getPan(trackNo);
                        }
                        double gl = vol * Math.min(1.0, 1.0 - pan);
                        double gr = vol * Math.min(1.0, 1.0 + pan);
                        float peak = 0f;
                        for (com.dsh.v4tune.model.Part p : t.parts) {
                            if (cancelled) return;
                            VoiceModel vm2 = com.dsh.v4tune.audio.AudioEngine.modelFor(sng.voice(p.bs, p.pc), fallback);
                            if (vm2 == null) vm2 = fallback;
                            // renderPart writes at absolute times, so the slice starts at frame 0; adding
                            // the part start here shifted late parts by part.pos seconds (the tail then
                            // fell outside the slice and was dropped, which is why long songs lost their
                            // last notes while the cursor kept running).
                            float[] mono = Renderer.renderPart(sng, p, vm2, cfg, limit);
                            int n = Math.min(mono.length, limit);
                            for (int i = 0; i < n; i++) {
                                float vl = (float) (mono[i] * gl);
                                mixL[i] += vl;
                                mixR[i] += (float) (mono[i] * gr);
                                float a = vl < 0f ? -vl : vl;
                                if (a > peak) peak = a;
                            }
                        }
                        engine.noteTrackPeak(trackNo, peak);   // mixer meters read this
                    }
                    System.arraycopy(mixL, 0, bufL, 0, limit);
                    System.arraycopy(mixR, 0, bufR, 0, limit);
                    com.dsh.v4tune.audio.AudioEngine.Rendered rr = new com.dsh.v4tune.audio.AudioEngine.Rendered();
                    rr.left = bufL; rr.right = bufR; rr.sampleRate = cfg.sampleRate; rr.song = sng;
                    applyPostDspRange(rr, Math.max(0, ready), limit);
                    ready = limit;
                    // diagnostics: one line per render round; the last one shows where the tail went
                    android.util.Log.i("V4Tune", "render round: limit=" + limit + " total=" + total
                            + " elapsed=" + (System.currentTimeMillis() - startedMs) + "ms");
                    if (total > com.dsh.v4tune.audio.AudioEngine.FULL_RENDER_FRAMES) {
                        final int pct = (int) (100L * limit / Math.max(1, vocalTotal));
                        final int secs = (int) (limit / (double) cfg.sampleRate);
                        ui.post(new Runnable() { public void run() { status(L.t("status.rendering",
                                String.valueOf(pct) + "  " + secs + "s / " + (vocalTotal / cfg.sampleRate) + "s")); } });
                    }
                    if (limit >= vocalTotal) {
                        if (!renderAccel) { finished = true; return; }
                        cacheKey = songFingerprint(sng, cfg.sampleRate, fallback);
                        cacheTotal = vocalTotal;
                        cacheL = bufL.clone();
                        cacheR = bufR.clone();
                        android.util.Log.i("V4Tune", "render cache STORE: " + vocalTotal + " frames");
                        finished = true;
                        return;
                    }
                    // grow in long arithmetic: limit * 2 used to overflow to a negative int on
                    // long songs (5+ minutes), which stalled this loop, tripped the catch below
                    // and reported 'finished' early - the player then stopped before the last
                    // notes. Doubling in a long and clamping to total always reaches the end.
                    long nextLimit = (long) limit * 2L;
                    limit = (int) Math.min((long) vocalTotal, nextLimit);
                }
            } catch (Throwable t) {
                android.util.Log.e("V4Tune", "render failed: " + t, t);
                final String m = String.valueOf(t);
                ui.post(new Runnable() { public void run() { status(L.t("status.stream.fail", m)); } });
            } finally {
                finished = true;
            }
        }
    }

    /**
     * The plugin chain for one freshly rendered slice.
     *
     * <p>Audio clips are <b>not</b> mixed here any more: they are streamed from their sidecar files
     * straight into the output by {@code StreamSource.mixExtras}, so the singer render buffer never
     * holds a backing track (that buffer is what made the 3-minute cap necessary).</p>
     */
    private void applyPostDspRange(com.dsh.v4tune.audio.AudioEngine.Rendered r, int from, int to) {
        if (r == null || r.left == null || to <= from) return;
        if (to > r.left.length) to = r.left.length;
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
    /** end frame of the last audio clip on the timeline (0 when there is none) */
    private int clipsEndFrame(com.dsh.v4tune.model.Song s) {
        if (s == null) return 0;
        long end = 0L;
        for (int ti = 0; ti < clips.size(); ti++) {
            for (AudioClip c : clips.get(ti)) {
                if (c == null || c.frames() <= 0) continue;
                long e = (long) c.startFrame(s) + c.frames();
                if (e > end) end = e;
            }
        }
        return end > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) end;
    }

    /** scratch buffers for the streamed clip mix (one writer block, reused) */
    private float[] clipMixL, clipMixR;

    /**
     * Mixes every audible audio clip into {@code [from, from+n)} by reading the sidecar files, so a
     * long backing track is never held in the heap. Called by the playback writer per block, and by
     * nothing else: the singer render must not contain clip audio (that is what made the old
     * 3-minute cap necessary).
     */
    private void mixClipsInto(int from, int n, float[] l, float[] r) {
        if (l == null || r == null || n <= 0) return;
        java.util.Arrays.fill(l, 0, n, 0f);
        java.util.Arrays.fill(r, 0, n, 0f);
        if (song == null) return;
        boolean anySolo = false;
        for (com.dsh.v4tune.model.Track t : song.tracks) if (t.solo) anySolo = true;
        if (clipMixL == null || clipMixL.length < n) {
            int cap = Math.max(n, 8192);
            clipMixL = new float[cap];
            clipMixR = new float[cap];
        }
        final int to = from + n;
        for (int ti = 0; ti < clips.size() && ti < song.tracks.size(); ti++) {
            com.dsh.v4tune.model.Track t = song.tracks.get(ti);
            if (t.mute) continue;
            if (anySolo && !t.solo) continue;
            if (mixer != null && mixer.trackCount() > ti && !mixer.isAudible(ti)) continue;
            float g = (mixer != null && mixer.trackCount() > ti) ? mixer.getGain(ti) : 1f;
            float pn = (mixer != null && mixer.trackCount() > ti) ? mixer.getPan(ti) : 0f;
            double gl = Mixer.panLeftGain(pn) * g, gr = Mixer.panRightGain(pn) * g;
            float peak = 0f;
            for (AudioClip c : clips.get(ti)) {
                if (c == null || c.frames() <= 0) continue;
                int at = c.startFrame(song);
                int s0 = Math.max(from, at);
                int s1 = Math.min(to, at + c.frames());
                int len = s1 - s0;
                if (len <= 0) continue;
                java.util.Arrays.fill(clipMixL, 0, len, 0f);
                java.util.Arrays.fill(clipMixR, 0, len, 0f);
                int mixed = Math.min(c.mixInto(clipMixL, clipMixR, -(s0 - at)), len);
                if (mixed <= 0) continue;
                int base = s0 - from;
                for (int i = 0; i < mixed; i++) {
                    l[base + i] += (float) (clipMixL[i] * gl);
                    r[base + i] += (float) (clipMixR[i] * gr);
                    float a = Math.abs(l[base + i]);
                    if (a > peak) peak = a;
                }
            }
            if (engine != null) engine.noteTrackPeak(ti, peak);   // mixer meters read this
        }
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
        clipsBaseline = copyClips();
    }

    /**
     * Commit an edit. Works for both orders: our own dialogs (beginEdit ... mutate ... commitEdit)
     * and view-driven edits (the view mutated the model before notifying us), because the snapshot
     * pushed is always the state as of the previous commit.
     */
    private void commitEdit() {
        if (song == null) return;
        dirty = true;
        if (baseline != null) {
            undo.push(baseline);
            while (undo.size() > 24) undo.removeLast();
            redo.clear();
        }
        baseline = song.copy();
        pushClips(undoClips, clipsBaseline);
        redoClips.clear();
        clipsBaseline = copyClips();
    }

    /** deep-enough copy: new lists, copied clips (the audio itself is shared) */
    private java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>> copyClips() {
        java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>> out =
                new java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>>(clips.size());
        for (int i = 0; i < clips.size(); i++) {
            java.util.ArrayList<com.dsh.v4tune.model.AudioClip> src = clips.get(i);
            java.util.ArrayList<com.dsh.v4tune.model.AudioClip> copy =
                    new java.util.ArrayList<com.dsh.v4tune.model.AudioClip>(src.size());
            for (com.dsh.v4tune.model.AudioClip c : src) copy.add(c == null ? null : c.copy());
            out.add(copy);
        }
        return out;
    }

    private void pushClips(java.util.ArrayDeque<java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>>> stack,
                           java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>> snap) {
        if (snap == null) return;
        stack.push(snap);
        while (stack.size() > 24) stack.removeLast();
    }

    private void setClips(java.util.ArrayList<java.util.ArrayList<com.dsh.v4tune.model.AudioClip>> snap) {
        if (snap == null) return;
        clips.clear();
        for (int i = 0; i < snap.size(); i++) {
            clips.add(new java.util.ArrayList<com.dsh.v4tune.model.AudioClip>(snap.get(i)));
        }
    }

    /** (re)arm the autosave timer; called on start, resume and after settings change */
    private void scheduleAutoSave() {
        if (autoSaveHandler == null) autoSaveHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        if (autoSaveTick == null) {
            autoSaveTick = new Runnable() {
                public void run() {
                    autoSaveNow();
                    if (autoSaveEnabled && autoSaveHandler != null) {
                        autoSaveHandler.postDelayed(this, Math.max(10, autoSaveSec) * 1000L);
                    }
                }
            };
        }
        autoSaveHandler.removeCallbacks(autoSaveTick);
        if (autoSaveEnabled) autoSaveHandler.postDelayed(autoSaveTick, Math.max(10, autoSaveSec) * 1000L);
    }

    /** write a recovery copy / the current file when something changed since the last save */
    private void autoSaveNow() {
        if (!autoSaveEnabled || song == null || !dirty || autoSaving || isFinishing()) return;
        final Song snapshot = song.copy();
        final Uri uri = currentUri;
        final String name = projectName;
        // An imported .ust / .ustx (or .mid / .svip3) can only be written back through an exporter that
        // has no control curves and no phonemes. Autosaving into it silently destroyed both, which is
        // exactly what happened after going back to the start screen and reopening from the recent list.
        final String uriName = uri == null ? null : String.valueOf(uri.getLastPathSegment());
        final boolean lossySource = uri != null && !ProjectIO.isNativeName(uriName);
        autoSaving = true;
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File written = null;
                try {
                    if (uri != null && !lossySource) {
                        ProjectIO.save(MainActivity.this, uri, snapshot);
                    } else if (lossySource) {
                        written = importedCopyFile(uri, name);
                        ProjectIO.saveNative(written, snapshot);
                    } else {
                        written = localProjectFile(name);
                        ProjectIO.saveNative(written, snapshot);
                    }
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final String e = err;
                final File saved = written;
                autoSaving = false;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (e == null) {
                            dirty = false;
                            if (saved != null) {
                                projectName = stripProjectExt(saved.getName());
                                if (lossySource) {
                                    // keep editing the native copy, and offer that one from now on
                                    currentUri = null;
                                    if (uri != null) {
                                        String recentKey = "file".equals(uri.getScheme()) && uri.getPath() != null
                                                ? uri.getPath() : uri.toString();
                                        forgetRecent(recentKey);
                                    }
                                }
                                noteRecent(saved);
                            }
                            String hhmm;
                            try {
                                hhmm = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
                            } catch (Throwable t) { hhmm = ""; }
                            status((lossySource ? "已转存为本地工程并自动保存 " : "已自动保存 ") + hhmm);
                        } else {
                            status("自动保存失败: " + e);
                        }
                    }
                });
            }
        }, "autosave").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleAutoSave();
    }

    @Override
    protected void onPause() {
        if (autoSaveHandler != null && autoSaveTick != null) autoSaveHandler.removeCallbacks(autoSaveTick);
        autoSaveNow();
        super.onPause();
    }

    private void doUndo() {
        if (undo.isEmpty()) { status("没有可撤销的操作"); return; }
        redo.push(song.copy());
        song = undo.pop();
        if (!undoClips.isEmpty()) {
            pushClips(redoClips, copyClips());
            setClips(undoClips.pop());
        }
        baseline = song.copy();
        clipsBaseline = copyClips();
        refreshAll();
        status("已撤销（可重做 " + redo.size() + " 步）");
    }

    private void doRedo() {
        if (redo.isEmpty()) { status("没有可重做的操作"); return; }
        undo.push(song.copy());
        while (undo.size() > 24) undo.removeLast();
        song = redo.pop();
        if (!redoClips.isEmpty()) {
            pushClips(undoClips, copyClips());
            setClips(redoClips.pop());
        }
        baseline = song.copy();
        clipsBaseline = copyClips();
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
        syncSnapIcon();
        if (b != null) b.setText(snapText());
        status(snapTicks == 0 ? "网格吸附已关闭" : ("网格吸附: " + (1920 / snapTicks) + " 分音符"));
    }

    /**
     * v12.0.2: this used to be a separate single-note editor, which is where the whole line could
     * end up inside one note ("积压成一个音符块"). Every lyric entry point now goes through
     * {@link #lyricDialog}, so a sentence is always split and spread over the notes that follow.
     */
    private void editLyric(final Note n) { lyricDialog(n); }

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
        if (low.contains("lingxi") || raw.contains("灵溪")) return "灵溪 AI";
        if (low.contains("tianyi") || low.contains("luo") || raw.contains("洛天依")) return "洛天依 BV";
        return raw;
    }

    private String singerName(VoiceModel v) {
        if (v == null) return "";
        // a renamed bank shows the alias the user typed; the pack file itself is never rewritten
        String alias = bankAlias(v);
        if (alias.length() > 0 && SingerLibrary.isUserPack(v)) {
            return alias + " " + L.t("singer.tag.mine");
        }
        // legacy packs are named after the voicebank they were converted from; fusion results
        // carry their own combined name, so do not rewrite them into a single singer's name.
        String n = isFusionBank(v) ? v.nameFor(L.lang()) : bvName(v.nameFor(L.lang()));
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

    /**
     * Name for a singer *picker*: two installed packs can carry the same display name (the same bank
     * imported twice, or two recordings exported under one name), and a list that renders them
     * identically turns the row the user taps into a coin toss. Colliding names get a short id tail;
     * unique names are left untouched.
     */
    private String singerLabel(VoiceModel v) {
        String base = singerName(v);
        if (v == null || v.id == null || v.id.trim().isEmpty()) return base;
        int same = 0;
        for (VoiceModel o : SingerLibrary.all()) {
            if (singerName(o).equals(base)) same++;
        }
        return same < 2 ? base : (base + " · " + shortId(v.id));
    }

    /** tail of a pack id, short enough to sit in a menu row: "utau-fa034bc" -> "fa034bc" */
    private static String shortId(String id) {
        String s = id == null ? "" : id.trim();
        int cut = s.lastIndexOf('-');
        if (cut >= 0 && cut + 1 < s.length()) s = s.substring(cut + 1);
        if (s.length() > 8) s = s.substring(s.length() - 8);
        return s;
    }

    private void chooseInitialVoice() {
        java.util.List<VoiceModel> all = SingerLibrary.all();
        if (all.isEmpty()) { currentVoice = null; return; }
        for (VoiceModel v : all) {
            if (currentVoice != null && v.id != null && v.id.equals(currentVoice.id)) { attachNeural(v); return; }
        }
        currentVoice = all.get(0);
        attachNeural(currentVoice);
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

    /** switch to a voicebank: engine, params, caches and character art all follow it */
    private void selectVoice(final VoiceModel v) {
        if (v == null) return;
        beginEdit();
        currentVoice = v;
        attachNeural(currentVoice);
        applyVoiceToPart(currentVoice);
        cacheL = null; cacheR = null; cacheKey = 0L; cacheTotal = -1;
        commitEdit();
        refreshAll();
        if (v.characterPng != null) applyPackCharacter(v.characterPng);
        status(v.neuralBank && currentVoice.neural == null
                ? "这是神经声库，请先训练 BriNeting 基座"
                : L.t("status.singer", singerName(v)));
        if (Renderer.webSynth) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("当前是网页快速合成")
                    .setMessage("网页快速模式不用逐帧实测谐波，只保留声库的平均频谱色彩；细节会比「声库谐波」少，但不同声库仍会有区别。\n\n想听完整细节，请切换到「声库谐波」。")
                    .setPositiveButton("切回声库谐波", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            webSynth = false;
                            Renderer.webSynth = false;
                            getSharedPreferences("brivoice", MODE_PRIVATE).edit().putBoolean("websynth", false).apply();
                            cacheL = null; cacheR = null; cacheKey = 0L; cacheTotal = -1;
                            status("已切回声库谐波，再播放即可听出音色差异");
                        }
                    })
                    .setNegativeButton("先这样", null)
                    .show();
        }
    }

    private void pickSinger() {
        final java.util.List<VoiceModel> all = SingerLibrary.all();
        java.util.List<com.dsh.v4tune.ui.Dialogs.Row> rows = com.dsh.v4tune.ui.Dialogs.rows();
        final VoiceModel baseSinger = briNetingSinger();
        boolean baseCur = currentVoice != null && "_brineting".equals(currentVoice.id);
        com.dsh.v4tune.ui.Dialogs.Row baseRow = com.dsh.v4tune.ui.Dialogs.row("神", Theme.ACCENT,
                "神经歌手",
                "BriNeting 基座 · 直出中性声线",
                baseCur, new Runnable() {
                    public void run() {
                        beginEdit();
                        currentVoice = baseSinger;
                        attachNeural(currentVoice);
                        applyVoiceToPart(currentVoice);
                        cacheL = null; cacheR = null; cacheKey = 0L; cacheTotal = -1;
                        commitEdit();
                        refreshAll();
                        status(currentVoice.neural == null
                                ? "神经歌手需要先训练 BriNeting 基座"
                                : "已选择神经歌手：BriNeting 基座");
                    }
                });
        if (baseSinger.neural != null && baseSinger.neuralEnabled) baseRow.tag("AI");
        rows.add(baseRow);
        for (int i = 0; i < all.size(); i++) {
            final VoiceModel v = all.get(i);
            if (v.neural == null) attachNeural(v);          // decide the engine badge accurately
            int bands = 0;
            for (java.util.Map.Entry<String, VoiceModel.HarmonicBand[]> e2 : v.harmonics.entrySet()) {
                bands += e2.getValue().length;
            }
            String info;
            if (isFusionBank(v) && v.neural != null) {
                info = "融合声库 · " + v.neural.bankCount + " 声库 · 参数 " + v.neural.paramCount();
            } else if (v.neuralEmbedded) {
                info = "自制声库 · " + v.sampleWavs.size() + " 个采样 · "
                        + (v.neural != null ? ("神经参数 " + v.neural.paramCount()) : "传统合成");
            } else {
                info = (v.harmonics.size() > 0)
                        ? L.t("dlg.singer.stats", v.harmonics.size(), bands)
                        : L.t("dlg.singer.legacy");
            }
            String engine;
            if (v.neuralBank) {
                engine = v.neuralEnabled ? "BriNeting 神经声库" : "BriNeting 神经声库（需训练基座）";
            } else {
                engine = v.neuralEnabled ? "BriNeting 神经合成" : "传统合成";
            }
            boolean cur = currentVoice != null && currentVoice.id != null && currentVoice.id.equals(v.id);
            com.dsh.v4tune.ui.Dialogs.Row row = com.dsh.v4tune.ui.Dialogs.row(
                    com.dsh.v4tune.ui.Dialogs.badgeFor(singerName(v)),
                    com.dsh.v4tune.ui.Dialogs.PALETTE[i % com.dsh.v4tune.ui.Dialogs.PALETTE.length],
                    singerLabel(v), info + " · " + engine, cur, new Runnable() {
                        public void run() { selectVoice(v); }
                    });
            if (v.neuralBank || (v.neural != null && v.neuralEnabled)) row.tag("AI");
            rows.add(row);
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

    /** the song's voice slot for a singer, created on first use */
    private com.dsh.v4tune.model.Voice voiceEntryFor(VoiceModel vm) {
        for (com.dsh.v4tune.model.Voice x : song.voices) {
            boolean sameName = x.name != null && x.name.equals(vm.name);
            boolean sameId = x.compID != null && x.compID.equals(vm.compID);
            if (sameName && sameId) return x;   // an imported pack may share the official compID
        }
        com.dsh.v4tune.model.Voice v = new com.dsh.v4tune.model.Voice();
        int maxBs = 0;
        for (com.dsh.v4tune.model.Voice x : song.voices) maxBs = Math.max(maxBs, x.bs);
        v.bs = maxBs + 4;
        v.pc = 0;
        v.compID = vm.compID;
        v.name = vm.name;
        v.lang = vm.lang;
        song.voices.add(v);
        return v;
    }

    /**
     * A singer belongs to a TRACK, not just to one part: every part of the track is switched
     * together, so a multi-part track never mixes two voices. (In the vsqx format the singer
     * lives on each vsPart, which is why this writes through to all of them rather than to a
     * track level field.)
     */
    private void applyVoiceToTrack(com.dsh.v4tune.model.Track t, VoiceModel vm) {
        if (t == null || vm == null) return;
        com.dsh.v4tune.model.Voice v = voiceEntryFor(vm);
        for (Part q : t.parts) { q.bs = v.bs; q.pc = v.pc; }
    }

    /** the singer a track actually renders with (audio tracks have none of their own) */
    private VoiceModel singerOfTrack(com.dsh.v4tune.model.Track t) {
        if (t == null || t.audio || t.parts.isEmpty()) return currentVoice;
        Part p = t.parts.get(0);
        return com.dsh.v4tune.audio.AudioEngine.modelFor(song.voice(p.bs, p.pc), currentVoice);
    }

    private void applyVoiceToPart(VoiceModel vm) {
        if (vm == null) return;
        Part p = currentPart();
        if (p == null) return;
        boolean done = false;
        for (com.dsh.v4tune.model.Track t : song.tracks) {
            if (t.parts.contains(p)) { applyVoiceToTrack(t, vm); done = true; break; }
        }
        if (!done) {
            com.dsh.v4tune.model.Voice v = voiceEntryFor(vm);
            p.bs = v.bs;
            p.pc = v.pc;
        }
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
            // A build may swap the bundled singer (v26H1127V3 ships 灵溪 AI instead of 洛天依 BV).
            // The old pack was already copied into the voicebank folder by an earlier version, so it
            // has to be retired explicitly - otherwise the previous bundled singer stays listed
            // forever and the swap only happens on a clean install.
            retireReplacedBundled(names, ids);
            SingerLibrary.setBuiltinIds(ids);
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

    /**
     * Asset file names that earlier builds shipped as the bundled singer. A build that swaps the
     * bundled voicebank must delete the one it replaced, otherwise the old pack stays installed
     * (and listed as 内置) forever on every device that ever ran the previous version.
     */
    private static final String[] LEGACY_BUNDLED = { "luotianyi_chn_meng_bank.v4voice" };

    /** delete bundled packs of an earlier build that this build no longer ships */
    private void retireReplacedBundled(String[] current, java.util.HashSet<String> ids) {
        android.content.SharedPreferences sp = getSharedPreferences("brivoice", MODE_PRIVATE);
        java.util.HashSet<String> now = new java.util.HashSet<String>();
        for (String n : current) if (n != null) now.add(n);
        java.util.HashSet<String> gone = new java.util.HashSet<String>();
        java.util.Set<String> before = sp.getStringSet("builtin_files", new java.util.HashSet<String>());
        for (String old : before) if (old != null && !now.contains(old)) gone.add(old);
        for (String old : LEGACY_BUNDLED) if (!now.contains(old)) gone.add(old);
        boolean idsChanged = false;
        for (String old : gone) {
            java.io.File victim = new java.io.File(packStore.directory(), old);
            if (!victim.isFile()) continue;
            try {
                VoicePack.Result r = VoicePack.load(victim);
                if (r != null && r.ok() && r.model != null && r.model.id != null
                        && ids.remove(r.model.id)) {
                    idsChanged = true;
                }
            } catch (Throwable ignored) {
            }
            if (victim.delete()) {
                sp.edit().remove("builtin_sig_" + old).apply();
                android.util.Log.i("V4Tune", "retired bundled voicebank " + old);
            }
        }
        if (idsChanged) sp.edit().putStringSet("builtinIds", ids).apply();
        sp.edit().putStringSet("builtin_files", now).apply();
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
                        java.util.List<VoicePack.Result> ex = new com.dsh.v4tune.io.PackStore(extraDir).loadAll();
                        rs.addAll(ex);
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
        if (currentVoice == null) chooseInitialVoice();
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
        // same two sources as reloadPacksAsync: the store plus packs dropped into
        // BriVoice_root/voicebanks. Reading only the store made a delete look like it had
        // swallowed every pack the user copied in by hand.
        java.util.List<VoicePack.Result> rs =
                new java.util.ArrayList<VoicePack.Result>(packStore.loadAll());
        File extraDir = Storage.voicebanks(this);
        if (!extraDir.equals(packStore.directory())) {
            try { rs.addAll(new com.dsh.v4tune.io.PackStore(extraDir).loadAll()); }
            catch (Throwable t) { android.util.Log.w("V4Tune", "extra voicebank dir: " + t); }
        }
        java.util.ArrayList<VoiceModel> ms = new java.util.ArrayList<VoiceModel>();
        StringBuilder bad = new StringBuilder();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        for (VoicePack.Result r : rs) {
            if (!r.ok() || r.model == null) {
                if (r.error != null) bad.append(r.error).append("\n");
                continue;
            }
            String key = (r.model.id == null || r.model.id.isEmpty())
                    ? String.valueOf(r.model.name) : r.model.id;
            if (seen.add(key)) ms.add(r.model);       // the same pack can sit in both folders
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

    /**
     * Frame cap handed to the WAV importer: one hour. The samples are streamed into a sidecar file
     * instead of being decoded into the heap, so this is no longer a memory bound - the old
     * 3-minute cap was there only because a 4-minute clip cost ~63 MB of float arrays.
     */
    private static final int WAV_MAX_FRAMES = 44100 * 60 * 60;

    /** folder that keeps the decoded clip audio (one .pcm per imported WAV) */
    private java.io.File clipDir() {
        java.io.File d = new java.io.File(getFilesDir(), "clips");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * The audio track an imported WAV belongs to: the current track when that one already is an
     * audio track, else an empty audio track, else a new one. A backing track is never dropped onto
     * a singer track - its waveform would share the lane with the notes and the vocal would look
     * like it "has" the accompaniment. Must be called between beginEdit() and commitEdit().
     */
    private int audioTrackForImport() {
        if (trackIndex >= 0 && trackIndex < song.tracks.size() && song.tracks.get(trackIndex).audio) {
            return trackIndex;
        }
        for (int i = 0; i < song.tracks.size(); i++) {
            if (song.tracks.get(i).audio && clipsFor(i).isEmpty()) return i;
        }
        com.dsh.v4tune.model.Track t = new com.dsh.v4tune.model.Track();
        t.no = song.tracks.size() + 1;
        t.audio = true;
        t.name = "音频 " + (song.tracks.size() + 1);
        song.tracks.add(t);
        while (clips.size() < song.tracks.size()) clips.add(new java.util.ArrayList<AudioClip>());
        return song.tracks.size() - 1;
    }

    /** imports a WAV: stream it into a sidecar file off the UI thread, then place it on an audio track */
    private void addWavFrom(final Uri uri) {
        final java.io.File pcm = com.dsh.v4tune.io.PcmFile.newFile(clipDir(), "clip");
        if (pcm == null) { status(L.t("status.wav.fail", "no storage")); return; }
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage(L.t("dlg.wav.importing"));
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                WavReader.PcmInfo info = null;
                java.io.InputStream in = null;
                try {
                    in = ProjectIO.openAny(MainActivity.this, uri);
                    info = WavReader.exportPcm(in, pcm, WAV_MAX_FRAMES);
                } catch (Throwable t) {
                    info = null;
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                }
                final WavReader.PcmInfo fi = info;
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (fi == null || !fi.ok()) {
                            com.dsh.v4tune.io.PcmFile.delete(pcm);
                            String why = (fi == null) ? "?" : fi.error;
                            status(L.t("status.wav.fail", why));
                            new AlertDialog.Builder(MainActivity.this).setTitle(L.t("dlg.wav.fail.title"))
                                    .setMessage(why).setPositiveButton(L.t("dlg.ok"), null).show();
                            return;
                        }
                        String nm = uri.getLastPathSegment();
                        if (nm == null || nm.length() == 0) nm = "audio";
                        long songEnd = song.maxTick();
                        long at = Math.max(0L, startTick);
                        boolean moved = false;
                        if (songEnd > 0 && at >= songEnd) { at = 0L; moved = true; }
                        beginEdit();
                        int ti = audioTrackForImport();
                        AudioClip c = AudioClip.fromPcm(pcm, fi.frames, at, nm);
                        if (c == null) {
                            commitEdit();
                            status(L.t("status.wav.fail", "?"));
                            return;
                        }
                        c.peaks = fi.peaks;
                        clipsFor(ti).add(c);
                        trackIndex = ti;
                        commitEdit();
                        refreshAll();
                        String msg = (moved ? L.t("status.wav.added.atstart", nm, fi.seconds())
                                            : L.t("status.wav.added", nm, fi.seconds()))
                                + " · " + trackLabel(ti);
                        status(msg);
                        toast(msg);
                        if (fi.truncated) toast("音频超过 60 分钟，已截断");
                    }
                });
            }
        }, "wav-import").start();
    }

    // ------------------------------------------- audio-driven expression (v10.1, AudioTune)

    /** Menu entry: explains the analysis, then picks a dry vocal take. */
    private void audioTuneMenu() {
        new AlertDialog.Builder(this)
                .setTitle("从音频分析表情")
                .setMessage("选一段和当前轨道对齐的干声演唱（WAV，最多 3 分钟）。\n"
                        + "本机用 YIN 追音高、RMS 追力度，把音准偏差、句首滑音、句尾释放和长音颤音写回 PIT / DYN / VIB。\n"
                        + "只读音频、不联网，写入后可撤销。")
                .setPositiveButton("选择 WAV…", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { pickTuneWav(); }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    private void pickTuneWav() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/wav", "audio/x-wav", "audio/wave",
                "audio/vnd.wave", "audio/*", "application/octet-stream"});
        startActivityForResult(i, REQ_TUNE);
    }

    /** reads the take, aligns it with the notes of the current part and previews the result */
    private void audioTuneFrom(Uri uri) {
        final Part part = currentPart();
        if (part == null || part.notes.isEmpty()) { status("当前轨道没有音符，先画几个音"); return; }
        final WavReader.AudioData d;
        try {
            java.io.InputStream in = ProjectIO.openAny(this, uri);
            d = WavReader.read(in, 44100 * 180);          // same limit as the clip import
            try { in.close(); } catch (Throwable ignored) { }
        } catch (Throwable t) {
            status("读取音频失败：" + t.getMessage());
            toast("读取音频失败：" + t.getMessage());
            return;
        }
        if (d == null || !d.ok()) {
            String m = "读取音频失败：" + (d == null ? "?" : d.error);
            status(m);
            toast(m);
            return;
        }
        final float[] mono = new float[d.frames];
        for (int i = 0; i < mono.length; i++) {
            mono[i] = d.right == null ? d.left[i] : (d.left[i] + d.right[i]) * 0.5f;
        }
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在分析音频…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final com.dsh.v4tune.ai.AudioTune.Result r =
                        com.dsh.v4tune.ai.AudioTune.analyze(song, part, mono, d.sampleRate);
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (r == null || r.error != null) {
                            String m = "音频分析：" + (r == null ? "?" : r.error);
                            status(m);
                            toast(m);
                            return;
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("音频分析完成")
                                .setMessage(r.summary + "\n\n把这些表情写进当前轨道吗？（可撤销）")
                                .setPositiveButton("写入调音", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dlg, int w) {
                                        int n = applyTuning(r.tuning, 1.0f);
                                        aiLastApplied = n;
                                        String msg = "音频分析调音：" + r.summary + " · " + n + " 处";
                                        status(msg);
                                        toast("已按音频写入 " + n + " 处表情");
                                    }
                                })
                                .setNegativeButton(L.t("dlg.cancel"), null)
                                .show();
                    }
                });
            }
        }, "audio-tune").start();
    }

    /** imports a BRTN model file, the offline path for models built outside the app */
    private void importModelFrom(Uri uri) {
        try {
            java.io.InputStream in = ProjectIO.openAny(this, uri);
            com.dsh.v4tune.ai.LocalModelStore.importFrom(this, in);
            try { in.close(); } catch (Throwable ignored) { }
            toast("模型已导入");
            status("本地调音模型已导入：" + localModelState());
            localModelDialog();
        } catch (Throwable t) {
            String m = "模型导入失败：" + t.getMessage();
            status(m);
            toast(m);
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
                    c.frames() / 44100.0);
        }
        menu(L.t("dlg.clips.title"), null, items, -1, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, final int which) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(list.get(which).name)
                        .setMessage(L.t("clip.remove"))
                        .setPositiveButton(L.t("clip.remove"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d2, int w) {
                                // keep the sidecar file: undo puts the clip object back, and a
                                // deleted file would make the restored clip silent
                                list.remove(which);
                                status(L.t("status.clip.removed"));
                                refreshAll();
                            }
                        })
                        .setNegativeButton(L.t("dlg.cancel"), null)
                        .show();
            }
        });
    }

    /**
     * Mixes the audio clips of every track into a fully rendered song (export path), then runs the
     * plugin chain. The clips are read from their sidecar files block by block: only one 5 s window
     * of float samples exists at a time, not the whole backing track.
     */
    private void applyPostDsp(final com.dsh.v4tune.audio.AudioEngine.Rendered r) {
        if (r == null || r.left == null) return;
        // a clip may reach past the last note: grow the rendered buffers so nothing is cut off
        int need = clipsEndFrame(song);
        if (need > r.left.length) {
            r.left = java.util.Arrays.copyOf(r.left, need);
            if (r.right != null) r.right = java.util.Arrays.copyOf(r.right, need);
        }
        final int block = 44100 * 5;
        final float[] tl = new float[block], tr = new float[block];
        for (int ti = 0; ti < clips.size() && ti < song.tracks.size(); ti++) {
            if (mixer.trackCount() > ti && !mixer.isAudible(ti)) continue;
            float g = (mixer.trackCount() > ti) ? mixer.getGain(ti) : 1f;
            float p = (mixer.trackCount() > ti) ? mixer.getPan(ti) : 0f;
            double gl = Mixer.panLeftGain(p) * g, gr = Mixer.panRightGain(p) * g;
            for (AudioClip c : clips.get(ti)) {
                if (c == null || c.frames() <= 0) continue;
                final int at = c.startFrame(song);
                final int total = c.frames();
                for (int off = 0; off < total; off += block) {
                    final int len = Math.min(block, total - off);
                    java.util.Arrays.fill(tl, 0, len, 0f);
                    java.util.Arrays.fill(tr, 0, len, 0f);
                    int mixed = Math.min(c.mixInto(tl, tr, -off), len);
                    if (mixed <= 0) break;
                    for (int i = 0; i < mixed; i++) {
                        int k = at + off + i;
                        if (k < 0 || k >= r.left.length) continue;
                        r.left[k] += (float) (tl[i] * gl);
                        if (r.right != null && k < r.right.length) r.right[k] += (float) (tr[i] * gr);
                    }
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
        final com.dsh.v4tune.ui.MixerPanelView[] viewRef = new com.dsh.v4tune.ui.MixerPanelView[1];
        com.dsh.v4tune.ui.MixerPanelView.Host host = new com.dsh.v4tune.ui.MixerPanelView.Host() {
            public Mixer mixer() { return mixer; }
            public int trackCount() { return song.tracks.size(); }
            public String trackName(int i) { return trackLabel(i); }
            public boolean isCurrent(int i) { return i == trackIndex; }
            public float peak(int t) {
                float[] pk = engine.trackPeaks();
                return (t >= 0 && pk != null && t < pk.length) ? pk[t] : 0f;
            }
            public float masterPeak() {
                float[] pk = engine.trackPeaks();
                if (pk == null) return 0f;
                float m = 0f;
                for (int i = 0; i < song.tracks.size() && i < pk.length; i++) m = Math.max(m, pk[i]);
                return Math.min(1f, m * mixer.getMasterGain());
            }
            public void onChanged(String what) { status(what); }
            public void onEmptyInsert(int track) {
                toast("通道插入槽还没有实现：插件挂在总线上（更多 → 工具 → 插件）");
            }
            public int insertCount() { return pluginChain == null ? 0 : pluginChain.size(); }
            public String insertName(int slot) {
                Plugin p = pluginChain == null ? null : pluginChain.get(slot);
                return p == null ? "—" : p.name();
            }
            public boolean insertBypassed(int slot) {
                return pluginChain != null && pluginChain.isBypassed(slot);
            }
            public void onToggleInsert(int slot) {
                if (pluginChain == null || slot < 0 || slot >= pluginChain.size()) return;
                boolean now = !pluginChain.isBypassed(slot);
                pluginChain.setBypass(slot, now);
                status("插入 " + (slot + 1) + (now ? " 已旁通" : " 已启用"));
                if (viewRef[0] != null) viewRef[0].invalidate();
            }
        };
        final com.dsh.v4tune.ui.MixerPanelView panel = new com.dsh.v4tune.ui.MixerPanelView(this, host);
        viewRef[0] = panel;
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setBackgroundColor(Theme.BG_DEEP);
        hs.setHorizontalScrollBarEnabled(true);
        hs.addView(panel, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hs.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(400)));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.mixer.title"))
                .setView(hs)
                .setPositiveButton(L.t("dlg.ok"), null)
                .setNeutralButton(L.t("mixer.reset"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        for (int i = 0; i < song.tracks.size(); i++) {
                            mixer.setGain(i, 1f);
                            mixer.setTrimDb(i, 0f);
                            mixer.setPan(i, 0f);
                            mixer.setMute(i, false);
                            mixer.setSolo(i, false);
                        }
                        mixer.setMasterGain(1f);
                        mixer.setMasterPan(0f);
                        status(L.t("status.mixer.reset"));
                    }
                })
                .show();
        // the meters only move while something is rendered: repaint ~12 fps until the dialog closes
        final Runnable tick = new Runnable() {
            public void run() {
                if (panel.isShown()) {
                    panel.invalidate();
                    panel.postDelayed(this, 80L);
                }
            }
        };
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) { panel.removeCallbacks(tick); }
        });
        panel.postDelayed(tick, 80L);
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

    /**
     * The plugin rack (v12.0): a physical front panel instead of a column of SeekBars.
     *
     * <p>Each parameter is a knurled knob on a bevelled plate; bypass is a hardware toggle with an
     * LED, and the plate is labelled with the module name. Knobs are 270-degree rotations with an
     * engraved scale, so the current setting is readable without parsing a number, and the whole
     * panel scrolls as one unit when a module has more parameters than fit.</p>
     */
    private void pluginMenu(final int idx) {
        final Plugin p = pluginChain.get(idx);
        if (p == null) return;

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.setBackgroundColor(0xFF0B0D13);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(10), dp(10), dp(10));

        // --- module plate: name + bypass toggle, on a bevelled panel
        FrameLayout header = new FrameLayout(this);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62));
        header.setLayoutParams(hlp);
        // the plate is a View, not a Drawable, so it has to sit behind the content as a child
        header.addView(new com.dsh.v4tune.ui.RackControls.Plate(this),
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

        TextView name = new TextView(this);
        name.setText(p.name().toUpperCase(java.util.Locale.US));
        name.setTextColor(0xFFE8ECF5);
        name.setTextSize(14f);
        name.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        name.setLetterSpacing(0.12f);
        name.setPadding(dp(18), dp(18), 0, 0);
        header.addView(name);

        final com.dsh.v4tune.ui.RackControls.Toggle bypass =
                new com.dsh.v4tune.ui.RackControls.Toggle(this);
        bypass.setOn(!pluginChain.isBypassed(idx));
        bypass.setCaption(pluginChain.isBypassed(idx) ? "BYPASS" : "ACTIVE");
        bypass.setListener(new com.dsh.v4tune.ui.RackControls.Toggle.Listener() {
            public void onToggle(boolean on) {
                pluginChain.setBypass(idx, !on);
                // the engraved label has to follow the switch, otherwise it keeps saying ACTIVE
                // while the module is bypassed
                bypass.setCaption(on ? "ACTIVE" : "BYPASS");
                status(L.t(on ? "plug.enable" : "plug.bypass", p.name()));
            }
        });
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(120), dp(44));
        tlp.gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
        tlp.rightMargin = dp(12);
        header.addView(bypass, tlp);
        col.addView(header);

        // --- knobs, two per row on a phone, all on one plate
        FrameLayout panel = new FrameLayout(this);
        panel.addView(new com.dsh.v4tune.ui.RackControls.Plate(this),
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(14), dp(18), dp(14), dp(10));
        LinearLayout row = null;
        for (int i = 0; i < p.paramCount(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 0, 0, dp(4));
                grid.addView(row);
            }
            row.addView(knobFor(idx, i), new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        panel.addView(grid);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(10);
        panel.setLayoutParams(plp);
        col.addView(panel);

        sc.addView(col);
        new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.plugin.title"))
                .setView(sc)
                .setPositiveButton(L.t("dlg.ok"), null)
                .setNegativeButton(L.t("plug.remove"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { pluginChain.remove(idx); }
                })
                .show();
    }

    /** one knob bound to plugin[idx] parameter pi, with its value formatted for the readout */
    private View knobFor(final int idx, final int pi) {
        final Plugin q = pluginChain.get(idx);
        final double lo = q.paramMin(pi), hi = q.paramMax(pi);
        final double span = Math.max(1e-9, hi - lo);
        final com.dsh.v4tune.ui.RackControls.Knob k = new com.dsh.v4tune.ui.RackControls.Knob(this);
        k.setValue((float) ((q.getParam(pi) - lo) / span));
        k.setText(shortParamName(q.paramName(pi)), formatParam(q, pi, q.getParam(pi)));
        k.setListener(new com.dsh.v4tune.ui.RackControls.Knob.Listener() {
            public void onKnob(float v) {
                Plugin cur = pluginChain.get(idx);
                if (cur == null) return;
                double nv = lo + span * v;
                cur.setParam(pi, nv);
                k.setText(shortParamName(cur.paramName(pi)), formatParam(cur, pi, cur.getParam(pi)));
            }
        });
        return k;
    }

    /** "Dry/Wet" -> "DRY/WET"; long names keep their first word so the engraved caption fits */
    private static String shortParamName(String name) {
        String s = name == null ? "" : name.trim().toUpperCase(java.util.Locale.US);
        if (s.length() <= 9) return s;
        int sp = s.indexOf(' ');
        if (sp > 0) return s.substring(0, sp);
        return s.substring(0, 9);
    }

    /** the readout above a knob: whole numbers for dB/ms-like ranges, one decimal for ratios */
    private static String formatParam(Plugin q, int pi, double v) {
        String nm = q.paramName(pi) == null ? "" : q.paramName(pi).toLowerCase(java.util.Locale.US);
        if (nm.contains("ratio")) return String.format(java.util.Locale.US, "%.1f:1", v);
        if (nm.contains("db") || nm.contains("depth") || nm.contains("rate")
                || nm.contains("threshold") || nm.contains("attack") || nm.contains("release")) {
            return String.format(java.util.Locale.US, "%.1f", v);
        }
        return String.format(java.util.Locale.US, "%.0f%%", v * 100.0);
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
                L.t("menu.tools.settings"),
                L.t("menu.tools.update"),
                L.t("menu.tools.mkbank"),
                L.t("menu.tools.tutorial"),
                L.t("menu.tools.guide"),
                "角色立绘（左下角）",
                "BriNeting 神经声学模型（v11.0）",
                "本地调音模型（BriTune-Net）",
                "从音频分析表情（WAV，实验）",
                "自制声库（用采样训练）(v12.2)"
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
                else if (which == 10) settingsDialog();
                else if (which == 11) checkUpdate(false);
                else if (which == 12) com.dsh.v4tune.voicebank.VoicebankWizard.start(MainActivity.this);
                else if (which == 13) showTutorial();
                else if (which == 14) showOnboarding();
                else if (which == 15) characterDialog();
                else if (which == 16) neuralDialog();
                else if (which == 17) localTuneDialog();
                else if (which == 18) audioTuneMenu();
                else customBankDialog();
            }
        });
    }

    /** The start-up probe passes silent = true: only a newer build speaks up. */
    private void checkUpdate(final boolean silent) {
        final android.app.ProgressDialog wait;
        if (silent) {
            wait = null;
        } else {
            wait = new android.app.ProgressDialog(this);
            wait.setMessage(L.t("update.checking"));
            wait.setCancelable(false);
            wait.show();
        }
        com.dsh.v4tune.update.Updater.check(this, new com.dsh.v4tune.update.Updater.CheckCb() {
            public void done(final com.dsh.v4tune.update.Updater.Entry e, final String error) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (wait != null) { try { wait.dismiss(); } catch (Throwable ignored) { } }
                        if (error != null) {
                            if (!silent) updateToast(L.t("update.no.network") + "  (" + error + ")");
                            return;
                        }
                        if (e == null) {
                            if (!silent) updateToast(L.t("update.current") + "  " + com.dsh.v4tune.update.Updater.installedVersionName(MainActivity.this));
                            return;
                        }
                        updateDialog(e);
                    }
                });
            }
        });
    }

    /** First-run coach marks: each step highlights the real button it is talking about. */
    private void showOnboarding() {
        if (railView == null || trackBarView == null || roll == null) {
            status("请先把窗口转成横屏或竖屏再试"); return;
        }
        com.dsh.v4tune.ui.OnboardingOverlay.Step[] steps = new com.dsh.v4tune.ui.OnboardingOverlay.Step[]{
                new com.dsh.v4tune.ui.OnboardingOverlay.Step(railView, "1. 左边图标栏", "新建 / 打开 / 保存 / 播放 / 撤销 / 重做 / AI 调音都在左边。"),
                new com.dsh.v4tune.ui.OnboardingOverlay.Step(trackBarView, "2. 音轨栏", "「＋」新建歌声轨或音频轨；点缩略图切换轨；左右拖动改内容位置；长按改设置或删除。"),
                new com.dsh.v4tune.ui.OnboardingOverlay.Step(roll, "3. 画音符", "双击空白处加音符，拖动移动，点音符改歌词；下面是参数曲线。"),
                new com.dsh.v4tune.ui.OnboardingOverlay.Step(railView, "4. 其它都在「更多」", "另存为、导出 WAV、声库、设置、教程、链接和关于都收进了左边最下面的「更多」。")
        };
        com.dsh.v4tune.ui.OnboardingOverlay.show(this, steps);
    }

    /** tutorial text + chapter offsets, loaded once from assets/tutorial */
    private String tutorialText;
    private String[] tutorialTitles;
    private int[] tutorialStarts;

    private void showTutorial() {
        if (tutorialText == null) loadTutorial();
        if (tutorialText == null || tutorialText.length() == 0) {
            updateToast(L.t("tuto.missing"));
            return;
        }
        // chapter rail on the left, page jumping and search on top of the same parsed text
        new com.dsh.v4tune.ui.TutorialScreen(this, tutorialText).show();
    }

    private void loadTutorial() {
        StringBuilder all = new StringBuilder();
        String code = L.lang() == 1 ? "en" : (L.lang() == 2 ? "ja" : "zh");
        appendAsset(all, "tutorial/manual." + code + ".txt");
        if (all.length() == 0) appendAsset(all, "tutorial/manual.zh.txt");
        int before = all.length();
        if (before > 0) all.append("\n\n========== 声库制作与导入 ==========\n\n");
        appendAsset(all, "tutorial/voicebank." + code + ".txt");
        if (all.length() == before) appendAsset(all, "tutorial/voicebank.zh.txt");
        tutorialText = all.toString();
        java.util.ArrayList<String> titles = new java.util.ArrayList<String>();
        java.util.ArrayList<Integer> starts = new java.util.ArrayList<Integer>();
        int pos = 0;
        for (String line : tutorialText.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("【") && t.indexOf("】") > 1) {
                titles.add(t);
                starts.add(Integer.valueOf(pos));
            }
            pos += line.length() + 1;
        }
        tutorialTitles = titles.toArray(new String[0]);
        tutorialStarts = new int[starts.size()];
        for (int i = 0; i < tutorialStarts.length; i++) tutorialStarts[i] = starts.get(i).intValue();
    }

    private void appendAsset(StringBuilder out, String name) {
        java.io.InputStream in = null;
        try {
            in = getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            out.append(new String(bo.toByteArray(), "UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    private void updateToast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    private void updateDialog(final com.dsh.v4tune.update.Updater.Entry e) {
        StringBuilder m = new StringBuilder();
        m.append(L.t("update.available"));
        m.append("\n\n").append(com.dsh.v4tune.update.Updater.installedVersionName(this)).append("  ->  ").append(e.versionName);
        if (e.notes != null && e.notes.length() > 0) {
            m.append("\n\n").append(L.t("update.notes")).append(":\n").append(e.notes);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.t("update.title"))
                .setMessage(m.toString())
                .setPositiveButton(L.t("update.install"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { downloadUpdate(e); }
                })
                .setNegativeButton(L.t("update.later"), null)
                .show();
    }

    private void downloadUpdate(final com.dsh.v4tune.update.Updater.Entry e) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage(L.t("update.downloading"));
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(1000);
        pd.setCancelable(false);
        pd.show();
        com.dsh.v4tune.update.Updater.download(this, e, new com.dsh.v4tune.update.Updater.DownloadCb() {
            public void progress(final int permille) {
                runOnUiThread(new Runnable() { public void run() { pd.setProgress(permille); } });
            }
            public void done(final java.io.File apk, final String error) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (error != null || apk == null) {
                            updateToast(L.t("update.failed") + ": " + error);
                            return;
                        }
                        updateToast(L.t("update.downloaded"));
                        try { com.dsh.v4tune.update.Updater.install(MainActivity.this, apk); }
                        catch (Throwable t) { updateToast(L.t("update.unknown.sources")); }
                    }
                });
            }
        });
    }

    /**
     * Flips between portrait and landscape. The SENSOR_* variants (instead of the plain
     * PORTRAIT/LANDSCAPE ones) keep the opposite direction of the same axis usable, so turning the
     * phone 180 degrees still works while editing. Reached from both 更多 and 工具 → 屏幕方向.
     */
    private void toggleOrientation() {
        boolean land = isLandscape();
        setRequestedOrientation(land
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        status(land ? "切换为竖屏" : "切换为横屏");
    }

    /** the voicebank manager: one list, rename and delete on each bank, imports and .bri below */
    private void managePacks() {
        com.dsh.v4tune.ui.VoicebankManager.Host host = new com.dsh.v4tune.ui.VoicebankManager.Host() {
            public java.util.List<VoiceModel> banks() {
                java.util.ArrayList<VoiceModel> mine = new java.util.ArrayList<VoiceModel>();
                for (VoiceModel m : SingerLibrary.all()) {
                    if (!SingerLibrary.isUserPack(m)) continue;
                    // a freshly reloaded pack carries no neural model yet; the row would then claim
                    // "needs a base" even though the shared base is trained. Attach before reading.
                    if (m.neural == null) attachNeural(m);
                    mine.add(m);
                }
                return mine;
            }
            public String currentName() { return currentVoice == null ? "未选择" : singerName(currentVoice); }
            public String name(VoiceModel v) { return singerName(v); }
            public String info(VoiceModel v) { return bankInfo(v); }
            public boolean neural(VoiceModel v) { return v.neuralBank || (v.neural != null && v.neuralEnabled); }
            public boolean isCurrent(VoiceModel v) {
                return currentVoice != null && v.id != null && v.id.equals(currentVoice.id);
            }
            public void select(VoiceModel v) {
                selectVoice(v);
                if (v.neuralBank && v.neural == null) status("这是神经声库，请先训练 BriNeting 基座");
                else status(L.t("bank.selected", singerName(v)));
            }
            public void rename(VoiceModel v) { renameBank(v); }
            public void delete(VoiceModel v) { deleteBank(v); }
            public void importFile() { importPack(); }
            public void importFolder() { importPackFolder(); }
            public void help() { packHelp(); }
            public void exportBundle() { showBundleExport(); }
            public void importBundle() { showBundleImport(); }
            public void exportTune() { showBundleExport(com.dsh.v4tune.io.BriBundle.KIND_TUNE); }
            public void exportBriNetingBase() { showBundleExport(com.dsh.v4tune.io.BriBundle.KIND_BASE); }
        };
        com.dsh.v4tune.ui.VoicebankManager.show(this, host);
    }

    /** where every file goes, and how to get the folder writable */
    private void showRootHelp() {
        boolean ok = Storage.canWriteRoot(this);
        String msg = "BriVoice 的所有文件都放在这一个文件夹里：\n\n"
                + Storage.root(this).getAbsolutePath() + "\n"
                + "  projects/   工程（.vsqx）\n"
                + "  voicebanks/ 声库（.v4voice / .v4neural，可以直接从电脑拷进来）\n"
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
                                        neuralFile(m).delete();   // fused/normalised weights, if any
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

    /** name / engine / size of one imported bank, as the manager row subtitle */
    private String bankInfo(VoiceModel v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder();
        int bands = 0;
        for (java.util.Map.Entry<String, VoiceModel.HarmonicBand[]> e : v.harmonics.entrySet()) {
            bands += e.getValue().length;
        }
        if (v.harmonics.size() > 0) {
            sb.append("谐波 ").append(v.harmonics.size()).append(" 音素 / ").append(bands).append(" 频段");
        } else {
            sb.append("旧格式声库（无实测谐波）");
        }
        sb.append(" · ");
        if (v.neuralBank) sb.append(v.neural != null ? "BriNeting 神经" : "BriNeting（需训练基座）");
        else sb.append(v.neuralEnabled ? "BriNeting 神经合成" : "传统谐波");
        try {
            File f = packStore.fileFor(v.id);
            if (f != null && f.isFile()) {
                sb.append(" · ").append(String.format(java.util.Locale.US, "%.0f KB", f.length() / 1024.0));
            }
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    /** the display alias of a renamed bank ("" when it still uses the name inside its pack) */
    private String bankAlias(VoiceModel v) {
        if (v == null || v.id == null) return "";
        try {
            return getSharedPreferences("brivoice", MODE_PRIVATE).getString("alias_" + neuralKey(v), "");
        } catch (Throwable t) {
            return "";
        }
    }

    private void setBankAlias(VoiceModel v, String alias) {
        if (v == null || v.id == null) return;
        String key = "alias_" + neuralKey(v);
        if (alias == null || alias.trim().isEmpty()) {
            getSharedPreferences("brivoice", MODE_PRIVATE).edit().remove(key).apply();
        } else {
            getSharedPreferences("brivoice", MODE_PRIVATE).edit().putString(key, alias.trim()).apply();
        }
    }

    /** rename an imported bank: only the display name changes, the pack file is left untouched */
    private void renameBank(final VoiceModel v) {
        if (v == null) return;
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(bankAlias(v));
        et.setHint(L.t("bank.rename.hint"));
        et.setTextColor(Theme.TEXT);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), 0);
        box.addView(et);
        com.dsh.v4tune.ui.Dialogs.builder(this)
                .setTitle(L.t("bank.rename.title"))
                .setView(box)
                .setPositiveButton(L.t("dlg.ok"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        setBankAlias(v, et.getText().toString());
                        refreshAll();
                        status(L.t("bank.renamed", singerName(v)));
                        managePacks();
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { managePacks(); }
                })
                .show();
    }

    /** delete one imported bank, together with the neural weights that belonged to it */
    private void deleteBank(final VoiceModel v) {
        if (v == null) return;
        com.dsh.v4tune.ui.Dialogs.builder(this)
                .setTitle(L.t("dlg.pack.del.confirm.title"))
                .setMessage(L.t("dlg.pack.del.confirm.msg", singerName(v)))
                .setPositiveButton(L.t("dlg.delete"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dd, int w) {
                        String gone = singerName(v);
                        boolean ok = packStore.delete(v.id);
                        neuralFile(v).delete();
                        setBankAlias(v, "");
                        reloadPacks(false);
                        if (currentVoice != null && currentVoice.id != null && currentVoice.id.equals(v.id)) {
                            currentVoice = null;
                            chooseInitialVoice();
                        }
                        refreshAll();
                        status(ok ? L.t("status.pack.deleted", gone) : L.t("status.pack.del.fail"));
                        managePacks();
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { managePacks(); }
                })
                .show();
    }

    // ------------------------------------------------------------- model bundle (.bri)

    /** show what a bundle would contain, then let the user pick where it goes */
    private void showBundleExport() {
        showBundleExport(null);
    }

    /**
     * v12.2: the same sheet, but limited to one model, so BriTune-Net and the BriNeting base can be
     * exported on their own (the bundle still carries one manifest entry and the same checks).
     *
     * @param kind {@code BriBundle.KIND_TUNE} / {@code KIND_BASE}, null for every model
     */
    private void showBundleExport(final String kind) {
        java.util.List<com.dsh.v4tune.io.BriBundle.Part> parts = com.dsh.v4tune.io.BriBundle.parts(this, kind);
        if (parts.isEmpty()) {
            com.dsh.v4tune.ui.Dialogs.message(this, L.t("bank.bri.title"), L.t("bank.bri.none"));
            return;
        }
        String body = L.t("bank.bri.summary", com.dsh.v4tune.io.BriBundle.summary(this));
        if (kind != null) {
            body = (com.dsh.v4tune.io.BriBundle.KIND_BASE.equals(kind) ? "BriNeting 基座" : "BriTune-Net")
                    + " · " + (parts.get(0).size / 1024) + " KB";
        }
        com.dsh.v4tune.ui.Dialogs.builder(this)
                .setTitle(kind == null ? L.t("bank.bri.save.title") : L.t("bank.bri.save.title"))
                .setMessage(body)
                .setPositiveButton(kind == null ? L.t("bank.export.bri")
                        : (com.dsh.v4tune.io.BriBundle.KIND_BASE.equals(kind)
                            ? L.t("bank.export.base") : L.t("bank.export.tune")),
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { pickBriSave(kind); }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    private void pickBriSave(final String kind) {
        briSaveKind = kind;
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            String stem = kind == null ? "BriVoice-models"
                    : (com.dsh.v4tune.io.BriBundle.KIND_BASE.equals(kind) ? "BriVoice-brineting-base" : "BriVoice-britune-net");
            i.putExtra(Intent.EXTRA_TITLE, stem + "-" + stamp() + com.dsh.v4tune.io.BriBundle.EXT);
            startActivityForResult(i, REQ_BRI_SAVE);
        } catch (Throwable t) {
            toast("无法打开保存对话框: " + msgOf(t));
        }
    }

    private String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(new java.util.Date());
    }

    /**
     * Writes the bundle, then reads it back through the importer's own checks before handing it to
     * the picked URI - so a half written or corrupt .bri is never reported as exported.
     */
    private void exportBriTo(final Uri uri) {
        File tmp = new File(getCacheDir(), "export.bri");
        try {
            java.io.OutputStream os = new java.io.FileOutputStream(tmp);
            int n = com.dsh.v4tune.io.BriBundle.export(this, os, briSaveKind);   // closes the stream
            com.dsh.v4tune.io.BriBundle.Result check = com.dsh.v4tune.io.BriBundle.verify(this, tmp);
            java.io.OutputStream dst = getContentResolver().openOutputStream(uri, "w");
            if (dst == null) throw new java.io.IOException("系统没有给出可写的输出流");
            java.io.InputStream src = new java.io.FileInputStream(tmp);
            try {
                byte[] buf = new byte[32 * 1024];
                int k;
                while ((k = src.read(buf)) > 0) dst.write(buf, 0, k);
                dst.flush();
            } finally {
                try { src.close(); } catch (Throwable ignored) { }
                try { dst.close(); } catch (Throwable ignored) { }
            }
            long size = tmp.length();
            String name = uri.getLastPathSegment();
            if (name == null || name.trim().isEmpty()) name = "模型备份";
            String details = name + " · " + (size / 1024) + " KB · " + check.text;
            status(L.t("bank.bri.exported", n, details));
            toast(L.t("bank.bri.exported", n, details));
        } catch (Throwable t) {
            status(L.t("bank.bri.failed", msgOf(t)));
            toast(L.t("bank.bri.failed", msgOf(t)));
        } finally {
            tmp.delete();
        }
    }

    private void showBundleImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/zip", "*/*"});
            startActivityForResult(i, REQ_BRI_OPEN);
        } catch (Throwable t) {
            toast("无法打开文件选择器: " + msgOf(t));
        }
    }

    /**
     * v12.2: copy the picked bundle to cache, validate it, then let the user choose between
     * replacing the installed models and merging the new one into them (half and half).
     */
    private void importBriFrom(final Uri uri) {
        final File tmp = new File(getCacheDir(), "import.bri");
        final com.dsh.v4tune.io.BriBundle.Result opened;
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new java.io.IOException("读不到这个文件");
            java.io.OutputStream os = new java.io.FileOutputStream(tmp);
            try {
                byte[] buf = new byte[32 * 1024];
                int k;
                while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
                os.flush();
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
                try { os.close(); } catch (Throwable ignored) { }
            }
            java.io.InputStream fin = new java.io.FileInputStream(tmp);
            opened = com.dsh.v4tune.io.BriBundle.open(this, fin);   // keeps parsed models, installs nothing
        } catch (Throwable t) {
            tmp.delete();
            status(L.t("bank.bri.import.failed", msgOf(t)));
            toast(L.t("bank.bri.import.failed", msgOf(t)));
            return;
        }
        tmp.delete();
        com.dsh.v4tune.ui.Dialogs.builder(this)
                .setTitle(L.t("bank.bri.import.title"))
                .setMessage(L.t("bank.bri.import.ask", opened.text) + "\n\n" + L.t("bank.bri.mode.ask"))
                .setPositiveButton(L.t("bank.bri.replace"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { applyBriReplace(opened); }
                })
                .setNeutralButton(L.t("bank.bri.merge"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { applyBriMerge(opened); }
                })
                .setNegativeButton(L.t("dlg.cancel"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { com.dsh.v4tune.io.BriBundle.discard(opened); }
                })
                .show();
    }

    private void applyBriReplace(com.dsh.v4tune.io.BriBundle.Result opened) {
        try {
            com.dsh.v4tune.io.BriBundle.applyReplace(this, opened);
            afterModelChange();
            status(L.t("bank.bri.imported", opened.text));
            toast(L.t("bank.bri.imported", opened.text));
        } catch (Throwable t) {
            com.dsh.v4tune.io.BriBundle.discard(opened);
            status(L.t("bank.bri.import.failed", msgOf(t)));
            toast(L.t("bank.bri.import.failed", msgOf(t)));
        }
    }

    private void applyBriMerge(com.dsh.v4tune.io.BriBundle.Result opened) {
        try {
            com.dsh.v4tune.io.BriBundle.applyMerge(this, opened, 0.5);
            afterModelChange();
            status(L.t("bank.bri.merged", opened.text));
            toast(L.t("bank.bri.merged", opened.text));
        } catch (Throwable t) {
            com.dsh.v4tune.io.BriBundle.discard(opened);
            status(L.t("bank.bri.import.failed", msgOf(t)));
            toast(L.t("bank.bri.import.failed", msgOf(t)));
        }
    }

    /** a replaced or merged model invalidates the cached base and the per-singer binding */
    private void afterModelChange() {
        reloadUniversalNeural();
        if (currentVoice != null) {
            currentVoice.neural = null;
            attachNeural(currentVoice);
        }
        refreshAll();
    }

    /** the bundle import may have replaced the base: drop the cached copy so the next render uses it */
    private void reloadUniversalNeural() {
        universalNeural = null;
        universalNeuralTried = false;
        if (briNetingVoice != null) {
            briNetingVoice.neural = null;
            briNetingVoice.neuralFromUniversal = false;
        }
        ensureUniversalNeural();
    }

    private String msgOf(Throwable t) {
        if (t == null) return "未知错误";
        return t.getMessage() == null ? t.toString() : t.getMessage();
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

    /** a compact canvas-drawn icon button for the top bar: no text, size fixed by the caller */
    private com.dsh.v4tune.ui.ToolIcon toolIcon(LinearLayout bar, int kind, String desc,
                                                View.OnClickListener l) {
        com.dsh.v4tune.ui.ToolIcon ic = new com.dsh.v4tune.ui.ToolIcon(this, kind);
        ic.setContentDescription(desc);
        ic.setBackground(Theme.card(this, Theme.PANEL_DK));
        ic.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
        lp.setMargins(dp(4), 0, 0, 0);
        bar.addView(ic, lp);
        return ic;
    }

    /** keeps the snap icon + its value lit/in step, wherever the value was changed */
    private void syncSnapIcon() {
        if (snapIcon != null) snapIcon.setActive(snapTicks != 0);
        if (snapValue != null) snapValue.setText(snapTicks == 0 ? "关" : ("1/" + (1920 / snapTicks)));
    }

    private String meterText() {
        int[] m = (song == null) ? new int[]{4, 4} : song.meterAt(0);
        return m[0] + "/" + m[1];
    }

    /** one bar in ticks for the current time signature (a bar used to be hard coded to 4/4) */
    private long meterBarTicks() {
        int[] m = (song == null) ? new int[]{4, 4} : song.meterAt(0);
        long bar = (long) Math.max(1, m[0]) * 4L * 480L / Math.max(1, m[1]);
        return Math.max(240L, bar);
    }

    /** refresh everything in the top bar that mirrors model state, plus the strip ruler */
    private void flushTopBar() {
        syncSnapIcon();
        if (meterBtn != null) meterBtn.setText(meterText());
        if (trackStripView != null) trackStripView.setBarTicks(meterBarTicks());
    }

    private static final int[][] METER_PRESETS = {{4, 4}, {3, 4}, {2, 4}, {6, 8}, {5, 4}, {7, 8}};

    /** tap the meter button: step through the usual signatures and re-draw the bar grid */
    private void cycleMeter() {
        int[] cur = (song == null) ? new int[]{4, 4} : song.meterAt(0);
        int idx = 0;
        for (int i = 0; i < METER_PRESETS.length; i++) {
            if (METER_PRESETS[i][0] == cur[0] && METER_PRESETS[i][1] == cur[1]) { idx = i; break; }
        }
        int[] next = METER_PRESETS[(idx + 1) % METER_PRESETS.length];
        beginEdit();
        song.timeSigs.clear();
        song.timeSigs.add(new int[]{0, next[0], next[1]});
        commitEdit();
        refreshAll();
        status("拍号: " + next[0] + "/" + next[1]);
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
        if (!skipOverlapCheck && !checkBeforePlay()) return; // overlapping notes: ask before making a sound
        ensureMixerTracks();
        engine.mixer = mixer;
        final VoiceModel vm = currentVoice;
        if (streamProducer != null) { streamProducer.cancel(); streamProducer = null; }
        streamProducer = new StreamProducer(song, vm);
        final long startFrame = (long) (TimeUtil.tickToSec(song, startTick) * 44100);
        status(L.t("status.stream.start"));
        setPlayUi(true);
        streamProducer.start();
        engine.playStream(streamProducer, startFrame, new AudioEngine.Callback() {
            public void onProgress(long frame) {
                // Keep only the newest cursor position. Posting one Runnable per audio callback (40/s)
                // let the UI queue grow faster than it could draw: the playhead stuttered and taps on
                // the parameter chips were delayed until playback ended.
                pendingPlayheadTick = TimeUtil.secToTick(song, frame / 44100.0);
                if (!playheadRefreshPosted) {
                    playheadRefreshPosted = true;
                    // One frame worth of delay: audio now reports every ~16 ms, so the cursor can
                    // move every display frame instead of stepping in 100 ms chunks.
                    ui.postDelayed(playheadRefresh, 16L);
                }
            }
            public void onDone() {
                cancelPlayheadRefresh();
                ui.post(new Runnable() {
                    public void run() {
                        setPlayUi(false);
                        syncCursor(0L, true);
                        status(L.t("status.play.done"));
                    }
                });
            }
            public void onError(final String m) {
                cancelPlayheadRefresh();
                ui.post(new Runnable() {
                    public void run() {
                        // The engine has already stopped. Without this the button stayed on "stop"
                        // and the playhead looked frozen while nothing was playing.
                        setPlayUi(false);
                        status(L.t("status.stream.fail", m));
                    }
                });
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
                    // the render starts at song tick 0, so auditioning from the part start skips the
                    // silence in front of a part that does not begin at 0
                    final long previewFrom = (long) (TimeUtil.tickToSec(song, p.pos) * 44100);
                    ui.post(new Runnable() {
                        public void run() {
                            setPlayUi(true);
                            engine.play(r, previewFrom, new AudioEngine.Callback() {
                                public void onProgress(long tick) { }
                                public void onDone() {
                                    ui.post(new Runnable() {
                                        public void run() {
                                            setPlayUi(false);
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
        syncCursor(0L, true);
        setPlayUi(false);
        if (wasPlaying) ui.postDelayed(new Runnable() { public void run() { playSong(); } }, 150L);
        else playSong();
    }

    private void stopPlay() {
        cancelPlayheadRefresh();
        if (streamProducer != null) { streamProducer.cancel(); streamProducer = null; }
        long at = engine.playTick();
        engine.stop();
        setPlayUi(false);
        // leave the cursor where playback stopped so it can be resumed or dragged
        if (at > 0) startTick = at;
        syncCursor(startTick, true);
        status("已停止 · 光标停在 " + formatTick(startTick));
    }

    private void cancelPlayheadRefresh() {
        pendingPlayheadTick = -1L;
        playheadRefreshPosted = false;
        ui.removeCallbacks(playheadRefresh);
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
            String base = ProjectIO.sanitize(stripProjectExt(name));
            File f = new File(dir, base + ".vsqx");
            ProjectIO.saveNative(f, song);
            currentUri = null;
            projectName = base;
            noteRecent(f);
            dirty = false;
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
    /** 工具菜单入口：不带具体音符，锚点交给选中音符 / 播放光标 */
    private void lyricFillDialog() { lyricDialog(null); }

    /** the part's notes in playing order, as a mutable copy */
    private ArrayList<Note> sortedNotes(Part p) {
        ArrayList<Note> notes = new ArrayList<Note>(p.notes);
        java.util.Collections.sort(notes, new java.util.Comparator<Note>() {
            public int compare(Note a, Note b) { return a.pos < b.pos ? -1 : (a.pos > b.pos ? 1 : 0); }
        });
        return notes;
    }

    /**
     * Index the fill starts at: the note that opened the dialog, else the roll selection, else the
     * first note at/after the playback cursor. -1 when the part has no notes that qualify.
     */
    private int lyricAnchorIndex(ArrayList<Note> notes, Note anchor) {
        if (notes == null || notes.isEmpty()) return -1;
        Note sel = anchor != null ? anchor : (roll == null ? null : roll.getSelectedNote());
        int selIndex = -1;
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i) == sel) { selIndex = i; break; }
        }
        long[] positions = new long[notes.size()];
        for (int i = 0; i < notes.size(); i++) positions[i] = notes.get(i).pos;
        return LyricFill.startIndex(positions, selIndex, startTick);
    }

    /** how many notes are available for a fill that starts here (0 when there is no room) */
    private int lyricRoom(Note anchor) {
        Part p = currentPart();
        if (p == null || p.notes.isEmpty()) return 0;
        ArrayList<Note> notes = sortedNotes(p);
        int start = lyricAnchorIndex(notes, anchor);
        return start < 0 ? 0 : notes.size() - start;
    }

    /**
     * The one and only lyric editor (v12.0.2).
     *
     * <p>All three entry points - the 歌词 button in the left column, double-tapping a note, and
     * 工具 &gt; 批量填入歌词 - open this dialog. They used to differ: the first two dropped the whole
     * line into a single note, which is exactly the "积压成一个音符块" that kept being reported.</p>
     *
     * <p>The dialog prints how many notes are available from the anchor to the end of the part, so
     * "this line has 14 pieces but only 13 notes are left" is visible before 填入 is pressed; the
     * fill itself refuses to write anything in that case.</p>
     */
    private void lyricDialog(final Note anchorNote) {
        final Part part = currentPart();
        if (part == null || part.notes.isEmpty()) { status(L.t("status.lyrics.nonotes")); return; }
        final ArrayList<Note> notes = sortedNotes(part);
        final int start = lyricAnchorIndex(notes, anchorNote);
        final int room = start < 0 ? 0 : notes.size() - start;
        final Note anchor = (start >= 0) ? notes.get(start) : null;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(6), dp(18), dp(2));

        TextView info = new TextView(this);
        info.setText(L.t("dlg.lyrics.room", room));
        info.setTextColor(room > 0 ? 0xFF9FB0C8 : 0xFFFF6B6B);
        info.setTextSize(13f);
        info.setPadding(0, 0, 0, dp(6));
        col.addView(info);

        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setHint(L.t("dlg.lyrics.hint"));
        if (anchor != null && anchor.lyric != null) {
            e.setText(anchor.lyric);
            e.setSelection(e.getText().length());
        }
        col.addView(e);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(L.t("dlg.lyrics.title"))
                .setView(col)
                .setPositiveButton(L.t("dlg.lyrics.ok"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        applyLyrics(e.getText().toString(), anchorNote);
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null);
        if (anchor != null) {
            b.setNeutralButton(L.t("dlg.lyric.clear.lock"), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    beginEdit();
                    anchor.phoneme = "";
                    anchor.lockPhoneme = false;
                    commitEdit();
                    if (roll != null) roll.refresh();
                    status(L.t("set.phoneme.state.off"));
                }
            });
        }
        b.show();
    }

    /**
     * Fills the picked note and the ones after it, <b>one space-separated piece per note</b>
     * (v12.0.1).
     *
     * <p>The old splitter cut every CJK character apart, so "一 闪 一 闪 …" arrived as fourteen
     * characters and "洛天依" as three - and because the user's line is usually typed with spaces,
     * the two rules fought: the whole line could land on the first note while later notes kept
     * their old text, which reads as "积压成一个音符块". Segmentation is now by whitespace first;
     * per-character splitting is only the fallback for a line with no spaces at all.</p>
     *
     * <p>When there are not enough notes left from the selection, nothing is written and the user
     * is told how many notes are missing - a partial fill silently dropping the tail of the line
     * was the other half of the complaint.</p>
     */
    private void applyLyrics(String text, Note anchorNote) {
        final java.util.List<String> syl = LyricFill.segments(text);
        if (syl.isEmpty()) { status(L.t("status.lyrics.empty")); return; }
        final Part p = currentPart();
        if (p == null || p.notes.isEmpty()) { status(L.t("status.lyrics.nonotes")); return; }
        ArrayList<Note> notes = new ArrayList<Note>(p.notes);
        java.util.Collections.sort(notes, new java.util.Comparator<Note>() {
            public int compare(Note a, Note b) { return a.pos < b.pos ? -1 : (a.pos > b.pos ? 1 : 0); }
        });
        // start at the note the user selected, then run forward; the playback cursor is only the
        // anchor when nothing is selected.
        int start = lyricAnchorIndex(notes, anchorNote);
        if (start < 0) { status(L.t("status.lyrics.nonotes")); return; }
        int room = notes.size() - start;
        if (room < syl.size()) {
            // refuse rather than fill half a line: the text would land on the wrong notes and the
            // remainder would look like it vanished
            String msg = L.t("status.lyrics.notenough", syl.size(), room, syl.size() - room);
            status(msg);
            toast(msg);
            return;
        }
        beginEdit();
        for (int n = 0; n < syl.size(); n++) {
            Note nt = notes.get(start + n);
            nt.lyric = syl.get(n);
            if (syl.size() == 1 && phonemeLock && nt.phoneme != null && nt.phoneme.trim().length() > 0) {
                // editing a single note keeps the phonemes it already sings (the old single-note
                // editor behaved this way; spreading a line over many notes never does)
                nt.lockPhoneme = true;
            } else {
                nt.phoneme = "";       // re-derive from the new lyric; a stale <p> would win otherwise
                nt.lockPhoneme = false;
            }
        }
        commitEdit();
        refreshAll();
        String msg = L.t("status.lyrics.done", syl.size());
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
                    public void onClick(DialogInterface d, int w) {
                        // playSong() checks again by default, which used to just reopen this dialog
                        skipOverlapCheck = true;
                        try { playSong(); } finally { skipOverlapCheck = false; }
                    }
                })
                .show();
        return false;
    }

    // ---------------------------------------------------------------- batch lyrics end

    // ------------------------------------------------------------- start screen (v12.3)

    private com.dsh.v4tune.ui.StartScreen startScreen;
    /** preference key: one recent project per line, tab separated path / label / time */
    private static final String RECENT_PREF = "recentProjects";
    private static final int RECENT_MAX = 5;
    private static final String IMPORT_MAP_PREF = "importMap";

    /** full-screen entry point: four ways in, plus the five most recent projects */
    private void showStartScreen() {
        if (startScreen != null && startScreen.isShowing()) return;
        startScreen = new com.dsh.v4tune.ui.StartScreen(this,
                new com.dsh.v4tune.ui.StartScreen.Host() {
            public void startNew() { newProject(); }
            public void startOpen() { openProject(); }
            public void startSingers() { managePacks(); }
            public void startSettings() { settingsDialog(); }
            public void startTutorial() { showTutorial(); }
            public void startOrientation() { toggleOrientation(); }
            public void startGitee() { openUrl("https://gitee.com/h23c5/bri-voice"); }
            public void startBilibili() { openUrl("https://space.bilibili.com/3461562746538484"); }
            public void startAbout() { about(); }

            public java.util.List<com.dsh.v4tune.ui.StartScreen.Recent> startRecents() {
                return recentProjects();
            }

            public void startRecent(com.dsh.v4tune.ui.StartScreen.Recent r) { openRecent(r); }
        });
        startScreen.show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast("没有可用的浏览器：" + msgOf(t));
        }
    }

    /** remember a project picked through the system file picker (content:// keeps its Uri) */
    private void noteRecentUri(Uri uri, String label) {
        if (uri == null) return;
        String path = uri.toString();
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            noteRecent(new File(uri.getPath()));
            return;
        }
        try {
            String old = getSharedPreferences("brivoice", MODE_PRIVATE).getString(RECENT_PREF, "");
            StringBuilder sb = new StringBuilder();
            sb.append(path).append('\t').append(label == null ? "工程" : stripProjectExt(label))
                    .append('\t').append(System.currentTimeMillis());
            int kept = 1;
            if (old != null) {
                for (String line : old.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    int t = line.indexOf('\t');
                    if (t <= 0) continue;
                    if (line.substring(0, t).equals(path)) continue;
                    if (kept >= RECENT_MAX) continue;
                    sb.append('\n').append(line);
                    kept++;
                }
            }
            getSharedPreferences("brivoice", MODE_PRIVATE).edit().putString(RECENT_PREF, sb.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** remember a saved project so the start screen can offer it again */
    private void noteRecent(File f) {
        if (f == null || !f.isFile()) return;
        try {
            String path = f.getAbsolutePath();
            String label = stripProjectExt(baseName(f.getName()));
            String old = getSharedPreferences("brivoice", MODE_PRIVATE).getString(RECENT_PREF, "");
            StringBuilder sb = new StringBuilder();
            sb.append(path).append('\t').append(label).append('\t').append(System.currentTimeMillis());
            int kept = 1;
            if (old != null) {
                for (String line : old.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    int t = line.indexOf('\t');
                    if (t <= 0) continue;
                    if (line.substring(0, t).equals(path)) continue;
                    if (kept >= RECENT_MAX) continue;
                    sb.append('\n').append(line);
                    kept++;
                }
            }
            getSharedPreferences("brivoice", MODE_PRIVATE).edit().putString(RECENT_PREF, sb.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** recent projects: the saved list first, then anything in BriVoice_root/projects, newest first */
    private java.util.List<com.dsh.v4tune.ui.StartScreen.Recent> recentProjects() {
        java.util.LinkedHashMap<String, com.dsh.v4tune.ui.StartScreen.Recent> map =
                new java.util.LinkedHashMap<String, com.dsh.v4tune.ui.StartScreen.Recent>();
        String raw = getSharedPreferences("brivoice", MODE_PRIVATE).getString(RECENT_PREF, "");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;
                String stored = parts[0];
                java.io.File f = stored.startsWith("content://") ? null : new File(stored);
                if (f != null && (!f.isFile() || f.length() == 0)) continue;  // gone or half-written: not openable
                long time = f == null ? 0L : f.lastModified();
                if (parts.length >= 3) {
                    try { time = Long.parseLong(parts[2].trim()); } catch (Throwable ignored) { }
                }
                map.put(stored, new com.dsh.v4tune.ui.StartScreen.Recent(displayName(parts[1]), stored, time,
                        f == null ? 0L : f.length()));
            }
        }
        File dir = Storage.projects(this);
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                public int compare(File x, File y) { return Long.compare(y.lastModified(), x.lastModified()); }
            });
            for (File f : files) {
                if (f == null || !f.isFile() || f.length() == 0) continue;   // empty = interrupted save, not a project
                if (!f.getName().toLowerCase(java.util.Locale.US).endsWith(".vsqx")) continue;
                String path = f.getAbsolutePath();
                if (map.containsKey(path)) continue;
                map.put(path, new com.dsh.v4tune.ui.StartScreen.Recent(
                        displayName(f.getName()), path, f.lastModified(), f.length()));
            }
        }
        java.util.ArrayList<com.dsh.v4tune.ui.StartScreen.Recent> out =
                new java.util.ArrayList<com.dsh.v4tune.ui.StartScreen.Recent>(map.values());
        java.util.Collections.sort(out, new java.util.Comparator<com.dsh.v4tune.ui.StartScreen.Recent>() {
            public int compare(com.dsh.v4tune.ui.StartScreen.Recent x,
                               com.dsh.v4tune.ui.StartScreen.Recent y) {
                return Long.compare(y.time, x.time);
            }
        });
        while (out.size() > RECENT_MAX) out.remove(out.size() - 1);
        return out;
    }

    private void openRecent(com.dsh.v4tune.ui.StartScreen.Recent r) {
        if (r == null) return;
        boolean content = r.path != null && r.path.startsWith("content://");
        java.io.File f = content ? null : new File(r.path);
        if (f != null && (!f.isFile() || f.length() == 0)) {
            toast("这个工程文件不见了（可能被移动或删掉）");
            return;
        }
        String label = content ? projectNameOf(Uri.parse(r.path)) : displayName(r.label);
        try {
            beginEdit();
            song = content ? ProjectIO.open(this, Uri.parse(r.path)) : ProjectIO.openPath(f);
            commitEdit();
            currentUri = null;
            projectName = label;
            trackIndex = 0;
            chooseInitialVoice();
            refreshAll();
            scrollToContent();
            if (f != null) noteRecent(f); else noteRecentUri(Uri.parse(r.path), label);
            status("已打开 " + label + "（" + song.tracks.size() + " 轨 / " + countNotes() + " 音符）");
        } catch (Throwable t) {
            toast("打开失败：" + msgOf(t));
            status("打开失败：" + msgOf(t));
        }
    }

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
            String last = currentUri.getLastPathSegment();
            if (ProjectIO.isNativeName(last)) {
                try {
                    ProjectIO.save(this, currentUri, song);
                    dirty = false;
                    status("已保存到原文件");
                    toast("已保存");
                } catch (Exception e) {
                    status("保存失败: " + e.getMessage());
                }
                return;
            }
            // writing the project back into a .ust/.ustx file would drop curves and phonemes
            currentUri = null;
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
            projectName = projectNameOf(uri);
            trackIndex = 0;
            noteRecentUri(uri, projectName);
            if (!ProjectIO.isNativeName(name)) {
                // the importer gave us a format we can only export to; make sure the edits survive
                status("已导入 " + name + "（保存时转为本地 .vsqx 工程）");
            }
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
                    .setMessage(String.valueOf(e.getMessage()) + "\n\n支持：.vsqx / .vsq / .svip3 工程、.mid 导入、.v4voice / .v4neural 声库")
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
        android.util.Log.i("V4Tune", "onActivityResult req=" + req + " res=" + res + " data=" + (data != null));
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_CUSTOM_SAMPLES) { addCustomSamples(data); return; }
        Uri uri = null;
        try {
            uri = data.getData();
        } catch (Throwable t) {
            android.util.Log.e("V4Tune", "getData failed", t);
        }
        android.util.Log.i("V4Tune", "onActivityResult uri=" + uri);
        if (uri == null) {
            try {
                android.util.Log.i("V4Tune", "clip=" + data.getClipData());
            } catch (Throwable t) {
                android.util.Log.e("V4Tune", "getClipData failed", t);
            }
            return;
        }
        if (req == REQ_REC_TAKE) {
            recTakeFrom(data == null ? null : data.getData());
        } else if (req == REQ_CHAR) {
            loadCharacter(uri);
        } else if (req == REQ_OPEN) {
            try {
                beginEdit();
                song = ProjectIO.open(this, uri);
                commitEdit();
                currentUri = uri;
                String last = uri.getLastPathSegment();
                projectName = projectNameOf(uri);
                trackIndex = 0;
                noteRecentUri(uri, projectName);
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
                String last = uri.getLastPathSegment();
                if (ProjectIO.isNativeName(last)) {
                    currentUri = uri;
                    projectName = projectNameOf(uri);
                    noteRecentUri(uri, projectName);
                    status("已保存");
                    toast("已保存");
                } else {
                    // exported into a format that has no curves/phonemes: keep an editable native copy
                    currentUri = null;
                    projectName = ProjectIO.sanitize(projectNameOf(uri));
                    File f = localProjectFile(projectName);
                    ProjectIO.saveNative(f, song);
                    projectName = stripProjectExt(f.getName());
                    noteRecent(f);
                    status("已导出 " + last + "，可编辑工程为 " + f.getName());
                    toast("已导出；" + Storage.ROOT_NAME + "/projects 里保留了可编辑工程");
                }
            } catch (Exception e) {
                status("保存失败: " + e.getMessage());
            }
        } else if (req == 1006) {           // VoicebankWizard.REQ_ZIP
            com.dsh.v4tune.voicebank.VoicebankWizard.importZip(this, uri);
        } else if (req == REQ_PACK) {
            importPackFrom(uri);
        } else if (req == REQ_PACK_DIR && data != null && data.getData() != null) {
            importTree(data.getData());
        } else if (req == REQ_WAV && data != null && data.getData() != null) {
            addWavFrom(data.getData());
        } else if (req == REQ_TUNE) {
            audioTuneFrom(uri);
        } else if (req == REQ_MODEL) {
            importModelFrom(uri);
        } else if (req == REQ_CUSTOM_ART) {
            loadCustomArt(uri);
        } else if (req == REQ_BRI_SAVE) {
            exportBriTo(uri);
        } else if (req == REQ_BRI_OPEN) {
            importBriFrom(uri);
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
                if (name == null || !VoicePack.isPackName(name)) continue;
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

    /** a UTAU bank zip (contains oto.ini) must go to the UTAU importer, not the .v4voice parser */
    private boolean zipHasOto(Uri uri) {
        return zipContainsName(uri, "oto.ini");
    }

    /** name scan that survives non-UTF-8 zip headers (Java's ZipInputStream throws MALFORMED[1]) */
    private boolean zipContainsName(Uri uri, String name) {
        java.io.InputStream in = null;
        try {
            in = ProjectIO.openAny(this, uri);
            if (in == null) return false;
            byte[] needle = name.toLowerCase(java.util.Locale.US).getBytes("US-ASCII");
            byte[] buf = new byte[65536];
            byte[] carry = new byte[Math.max(0, needle.length - 1)];
            int clen = 0, n;
            while ((n = in.read(buf)) > 0) {
                byte[] d = new byte[clen + n];
                System.arraycopy(carry, 0, d, 0, clen);
                System.arraycopy(buf, 0, d, clen, n);
                for (int i = 0; i + needle.length <= d.length; i++) {
                    boolean ok = true;
                    for (int j = 0; j < needle.length; j++) {
                        int c = d[i + j] & 255;
                        if (c >= 'A' && c <= 'Z') c += 32;
                        if (c != needle[j]) { ok = false; break; }
                    }
                    if (ok) return true;
                }
                clen = Math.min(carry.length, d.length);
                if (clen > 0) System.arraycopy(d, d.length - clen, carry, 0, clen);
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
        return false;
    }

    /** some users distribute a zipped voicebank; detect that before treating it as a UTAU bank */
    private boolean zipHasV4(Uri uri) {
        return zipContainsName(uri, ".v4voice") || zipContainsName(uri, ".v4neural");
    }

    /** extract the first .v4voice from a zip and import it, so a zipped pack also works */
    private VoicePack.Result importPackZip(Uri uri) {
        java.io.File dir = new java.io.File(getCacheDir(), "v4zip_" + System.currentTimeMillis());
        dir.mkdirs();
        java.io.File found = null;
        java.io.InputStream in = null;
        java.util.zip.ZipInputStream zis = null;
        try {
            in = ProjectIO.openAny(this, uri);
            if (in == null) {
                VoicePack.Result r = new VoicePack.Result(); r.error = "无法读取压缩包（系统没有给文件流）"; return r;
            }
            zis = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(in));
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null && found == null) {
                String name = e.getName();
                if (name == null) continue;
                name = name.replace('\\', '/');
                if (e.isDirectory() || !VoicePack.isPackName(name)) continue;
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.length() == 0) base = "pack.v4voice";
                java.io.File out = new java.io.File(dir, base);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                int n;
                while ((n = zis.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close();
                found = out;
            }
            if (found == null) {
                VoicePack.Result r = new VoicePack.Result(); r.error = "压缩包里没有 .v4voice / .v4neural 文件"; return r;
            }
            return importPackUri(Uri.fromFile(found), found.getName());
        } catch (Throwable t) {
            VoicePack.Result r = new VoicePack.Result(); r.error = String.valueOf(t.getMessage()); return r;
        } finally {
            try { if (zis != null) zis.close(); else if (in != null) in.close(); } catch (Throwable ignored) { }
            deleteRec(dir);
        }
    }

    private void deleteRec(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteRec(k);
        f.delete();
    }

    /** detailed pack-import failure with one-tap copy (mirrors the UTAU wizard report) */
    private void showImportFailure(String detail) {
        StringBuilder b = new StringBuilder();
        b.append(detail == null ? "未知错误" : detail).append("\n\n");
        b.append("--- 设备信息 ---\n");
        b.append("Android: ").append(android.os.Build.VERSION.RELEASE).append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        b.append("机型: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL).append("\n");
        b.append("BriVoice: 8.1.1\n");
        b.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())).append("\n");
        final String report = b.toString();
        try {
            java.io.File dir = new java.io.File(Storage.root(this), "");
            if (!dir.exists()) dir.mkdirs();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(dir, "last_pack_error.txt"));
            fo.write(report.getBytes("UTF-8")); fo.flush(); fo.close();
        } catch (Throwable ignored) { }
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextSize(12f);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("声库导入失败")
                .setView(sc)
                .setPositiveButton("复制日志", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("BriVoice pack error", report));
                            toast("日志已复制，可直接粘贴反馈");
                        } catch (Throwable t) { toast("复制失败: " + t.getMessage()); }
                    }
                })
                .setNeutralButton("从文件夹导入", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { importPackFolder(); }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
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
                if (zipHasOto(uri)) {
                    ui.post(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Throwable ignored) { }
                            com.dsh.v4tune.voicebank.VoicebankWizard.importZip(MainActivity.this, uri);
                        }
                    });
                    return;
                }
                final VoicePack.Result r = zipHasV4(uri)
                        ? importPackZip(uri)
                        : importPackUri(uri, uri.getLastPathSegment());
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (r != null && r.ok()) {
                            status("已导入声库: " + singerName(r.model));
                            toast("已导入声库: " + singerName(r.model));
                        } else {
                            final String err = (r == null) ? "未知错误" : r.error;
                            status("声库导入失败: " + err);
                            showImportFailure(err + "\n\n如果文件本身没问题，多半是系统给的文件流不完整。"
                                    + "可以改用「从文件夹导入」，把 .v4voice / .v4neural 放进一个文件夹再选那个文件夹。");
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
                                            String.format(java.util.Locale.US, "%.1f", r.left.length / (double) r.sampleRate) + " 秒" +
                                            "\n\n这次听着满意吗？满意的话把当前参数加入本地模型的自学习数据集（只存在本机）。")
                                    .setPositiveButton("分享", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) {
                                            ProjectIO.share(MainActivity.this, f, ProjectIO.MIME_WAV);
                                        }
                                    })
                                    .setNeutralButton("满意，加入训练", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) {
                                            startSelfLearn();
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
                java.util.List<String> parts = com.dsh.v4tune.io.LyricFill.segments(text);
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
                    syncCursor((long) from, true);
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
                java.io.File pcm = com.dsh.v4tune.io.PcmFile.newFile(clipDir(), "clip");
                java.io.InputStream wavIn = new java.io.FileInputStream(f);
                com.dsh.v4tune.io.WavReader.PcmInfo info;
                try {
                    info = com.dsh.v4tune.io.WavReader.exportPcm(wavIn, pcm, WAV_MAX_FRAMES);
                } finally {
                    try { wavIn.close(); } catch (Throwable ignored) { }
                }
                if (info == null || !info.ok()) {
                    throw new IllegalStateException("wav: " + (info == null ? "?" : info.error));
                }
                long songEnd = song.maxTick();
                long at = (long) com.dsh.v4tune.ai.Json.num(args, "at tick", Math.max(0L, startTick));
                if (songEnd > 0 && at >= songEnd) at = 0L;
                beginEdit();
                int apiTrack = audioTrackForImport();
                AudioClip c = AudioClip.fromPcm(pcm, info.frames, at, f.getName());
                if (c == null) throw new IllegalStateException("clip build failed");
                c.peaks = info.peaks;
                clipsFor(apiTrack).add(c);
                trackIndex = apiTrack;
                commitEdit();
                refreshAll();
                return com.dsh.v4tune.ai.Json.obj("ok", Boolean.TRUE, "name", c.name,
                        "seconds", Double.valueOf(info.seconds()), "startTick", Long.valueOf(c.startTick),
                        "track", Integer.valueOf(apiTrack),
                        "clips", Integer.valueOf(clipsFor(apiTrack).size()));
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

    /** copies the full prompt from the shortcut sheet (works without any API key) */
    private void quickCopyPrompt(String request) {
        Part p = currentPart();
        if (p == null) { toast("当前没有可调音的片段"); return; }
        try {
            String prompt = com.dsh.v4tune.ai.AiTuner.fullPrompt(song, p, trackIndex, request);
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("BriVoice AI prompt", prompt));
            status("提示词已复制，去 AI 里粘贴吧");
            toast("已复制提示词");
        } catch (Throwable t) { status("复制失败: " + t.getMessage()); }
    }

    /** paste/edit a tuning JSON straight from the shortcut sheet */
    private void aiJsonDialog() {
        final EditText input = multi(aiLastReply == null ? "" : aiLastReply, 7);
        input.setHint("{\"curves\":{\"DYN\":[[0,90]]},\"tempo\":132}");
        ScrollView sc = new ScrollView(this);
        sc.addView(input);
        final AlertDialog d = com.dsh.v4tune.ui.Dialogs.builder(this)
                .setTitle("填写 JSON")
                .setView(sc)
                .setPositiveButton("应用 JSON", null)
                .setNeutralButton("导出 JSON", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int w) { exportAiJson(input.getText().toString()); }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Part p = currentPart();
                    if (p == null) throw new IllegalArgumentException("没有可调音的片段");
                    com.dsh.v4tune.ai.Tuning t = com.dsh.v4tune.ai.AiTuner.parseTuning(input.getText().toString(), p.dur + p.pos);
                    int n = applyTuning(t, aiCfg == null ? 0.6f : aiCfg.strength);
                    aiLastReply = input.getText().toString();
                    status(L.t("ai.applied.full", t.summary(), n));
                    toast("已应用 JSON：" + n + " 处调整");
                    d.dismiss();
                } catch (Throwable t) {
                    status("应用 JSON 失败: " + t.getMessage());
                    toast("JSON 不可用: " + t.getMessage());
                }
            }
        });
    }

    /** writes a tuning reply to BriVoice_root/export/ai/ */
    private void exportAiJson(String text) {
        try {
            if (text == null || text.trim().length() == 0) throw new IllegalArgumentException("JSON 是空的");
            File dir = new File(Storage.exports(this), "ai");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
            File f = new File(dir, "ai-" + stamp + ".json");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(text.getBytes("UTF-8"));
            fo.flush(); fo.close();
            status("已导出 " + f.getAbsolutePath());
            toast("已导出 JSON 文件");
        } catch (Throwable t) { status("导出失败: " + t.getMessage()); }
    }

    /** saves the current project to the user's backend, straight from the shortcut sheet */
    private void quickCloudSave() {
        if (aiCfg == null || aiCfg.cloudUrl == null || aiCfg.cloudUrl.trim().length() == 0) {
            toast("先到「AI 设置」里填云端地址");
            aiDialog();
            return;
        }
        status("正在保存到云端...");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try { com.dsh.v4tune.ai.AiCloud.save(aiCfg.cloudUrl, aiCfg.cloudToken, projectName, song); }
                catch (Throwable t) { err = String.valueOf(t.getMessage()); }
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (fe != null) status("云端保存失败: " + fe);
                        else { status("已保存到云端：" + projectName); toast("云端保存成功"); }
                    }
                });
            }
        }, "brivoice-cloud").start();
    }

    /** reads a project from the user's backend after confirmation */
    private void quickCloudLoad() {
        if (aiCfg == null || aiCfg.cloudUrl == null || aiCfg.cloudUrl.trim().length() == 0) {
            toast("先到「AI 设置」里填云端地址");
            aiDialog();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("从云端读取")
                .setMessage("会替换当前工程（可以先撤销）。继续？")
                .setPositiveButton("读取", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        status("正在从云端读取...");
                        new Thread(new Runnable() {
                            public void run() {
                                String err = null;
                                Song loaded = null;
                                try { loaded = com.dsh.v4tune.ai.AiCloud.load(aiCfg.cloudUrl, aiCfg.cloudToken); }
                                catch (Throwable t) { err = String.valueOf(t.getMessage()); }
                                final String fe = err;
                                final Song fs = loaded;
                                ui.post(new Runnable() {
                                    public void run() {
                                        if (fe != null) { status("云端读取失败: " + fe); return; }
                                        beginEdit();
                                        song = fs;
                                        commitEdit();
                                        currentUri = null;
                                        projectName = "云端工程";
                                        trackIndex = 0;
                                        chooseInitialVoice();
                                        refreshAll();
                                        scrollToContent();
                                        status("已从云端读取 " + countNotes() + " 音符");
                                        toast("云端工程已载入");
                                    }
                                });
                            }
                        }, "brivoice-cloud").start();
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    private void setQuickAiStatus(String text) {
        if (quickAiResult != null) quickAiResult.setText(text);
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

        // services: copy the prompt / cloud save / cloud load without leaving the shortcut sheet
        LinearLayout svc1 = row();
        Button copyNow = small("复制提示词");
        Button saveCloud = small("云端保存");
        svc1.addView(copyNow);
        svc1.addView(saveCloud);
        box.addView(svc1);
        LinearLayout svc2 = row();
        Button readCloud = small("云端读取");
        Button fullPanel = small("AI 设置");
        svc2.addView(readCloud);
        svc2.addView(fullPanel);
        box.addView(svc2);
        LinearLayout svc3 = row();
        Button fillJson = small("填写 JSON");
        Button exportJson = small("导出 JSON");
        svc3.addView(fillJson);
        svc3.addView(exportJson);
        box.addView(svc3);
        LinearLayout svc4 = row();
        Button localTune = small("本地模型调音");
        Button localModel = small("本地模型…");
        svc4.addView(localTune);
        svc4.addView(localModel);
        box.addView(svc4);

        copyNow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { quickCopyPrompt(input.getText().toString()); }
        });
        saveCloud.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { quickCloudSave(); }
        });
        readCloud.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { quickCloudLoad(); }
        });
        fullPanel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { aiDialog(); }
        });
        fillJson.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { aiJsonDialog(); }
        });
        exportJson.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportAiJson(aiLastReply == null ? "" : aiLastReply); }
        });
        localTune.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { localTuneDialog(); }
        });
        localModel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { localModelDialog(); }
        });

        final TextView quickResult = new TextView(this);
        quickResult.setTextColor(Theme.TEXT_DIM);
        quickResult.setTextSize(11.5f);
        quickResult.setPadding(0, dp(8), 0, dp(2));
        quickResult.setText(aiLastError != null ? ("上次失败：" + aiLastError) : "就绪");
        box.addView(quickResult);
        quickAiResult = quickResult;

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
        setQuickAiStatus("正在请求 AI 调参…");
        StringBuilder pb = new StringBuilder(com.dsh.v4tune.ai.AiTuner.partText(song, p, trackIndex));
        if (request != null && request.trim().length() > 0) pb.append("\n【用户要求】\n").append(request.trim());
        final String payload = pb.toString();
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
                            setQuickAiStatus("失败：" + fe);
                            return;
                        }
                        int n = applyTuning(ft, bias);
                        aiLastApplied = n;
                        if (n == 0) {
                            status("AI 没有给出可应用的修改");
                            setQuickAiStatus("AI 返回了 0 处可应用调整（可能只回了 {} 或数值没变）");
                        } else {
                            status(L.t("ai.applied.full", ft == null ? "" : ft.summary(), n));
                            setQuickAiStatus("已应用 " + n + " 处调整：" + (ft == null ? "" : ft.summary()));
                        }
                    }
                });
            }
        }, "brivoice-ai").start();
    }

    // ------------------------------------------------- local tuning model (v10.0, BriTune-Net)

    private static final String[] LOCAL_STYLE_NAMES = {"流行", "抒情", "摇滚", "电子", "国风"};

    private String localModelState() {
        try {
            if (!com.dsh.v4tune.ai.LocalModelStore.installed(this))
                return "模型未下载 · 首次调音时自动下载（约 225 KB）";
            return "模型已就绪 · " + com.dsh.v4tune.ai.LocalModelStore.installedInfo(this);
        } catch (Throwable t) {
            return "模型状态未知：" + t.getMessage();
        }
    }

    private String selfLearnState() {
        try {
            com.dsh.v4tune.ai.LocalTuner.Model m = null;
            try { m = com.dsh.v4tune.ai.LocalModelStore.load(this); } catch (Throwable ignored) { }
            int n = com.dsh.v4tune.ai.SelfLearn.count(
                    com.dsh.v4tune.ai.SelfLearn.dataFile(this, m), m);
            String where = com.dsh.v4tune.ai.LocalModelStore.selfTrained(this)
                    ? "已用于当前模型" : "尚未训练成模型";
            return "自学习：数据集 " + n + " 条 · " + where;
        } catch (Throwable t) {
            return "自学习：状态未知（" + t.getMessage() + "）";
        }
    }

    /** style + intensity sheet for the on-device tuning model */
    private void localTuneDialog() {
        final Part p = currentPart();
        if (p == null) { status("请先选择一个音轨"); return; }
        if (p.notes.isEmpty()) { status("当前音轨没有音符"); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));
        box.addView(label("用本机模型按风格给出 DYN/BRE/BRI/CLE/OPE/POR/VIB/PIT/GEN 曲线，完全离线，可撤销。"));
        box.addView(label(localModelState()));

        final int[] style = {Math.max(0, Math.min(LOCAL_STYLE_NAMES.length - 1, localStyle))};
        final Button[] styleBtns = new Button[LOCAL_STYLE_NAMES.length];
        LinearLayout styleRow1 = row();
        LinearLayout styleRow2 = row();
        for (int i = 0; i < styleBtns.length; i++) {
            final int idx = i;
            styleBtns[i] = small(LOCAL_STYLE_NAMES[i]);
            styleBtns[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    style[0] = idx;
                    for (int j = 0; j < styleBtns.length; j++)
                        styleBtns[j].setText((j == idx ? "● " : "") + LOCAL_STYLE_NAMES[j]);
                }
            });
            if (i < 3) styleRow1.addView(styleBtns[i]); else styleRow2.addView(styleBtns[i]);
        }
        styleBtns[style[0]].setText("● " + LOCAL_STYLE_NAMES[style[0]]);
        box.addView(label("风格"));
        box.addView(styleRow1);
        box.addView(styleRow2);

        final int[] intensity = {Math.max(35, Math.min(100, localIntensity))};
        final TextView intLabel = label("强度 " + intensity[0] + "%");
        box.addView(intLabel);
        SeekBar intBar = new SeekBar(this);
        intBar.setMax(65);
        intBar.setProgress(intensity[0] - 35);
        intBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                intensity[0] = 35 + pr;
                intLabel.setText("强度 " + intensity[0] + "%");
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(intBar);

        final int[] strength = {Math.max(10, Math.min(150, localStrength))};
        final TextView strLabel = label("应用幅度 " + strength[0] + "%");
        box.addView(strLabel);
        SeekBar strBar = new SeekBar(this);
        strBar.setMax(140);
        strBar.setProgress(strength[0] - 10);
        strBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                strength[0] = 10 + pr;
                strLabel.setText("应用幅度 " + strength[0] + "%");
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(strBar);

        box.addView(label("输出会写成参数曲线（每音符一点，PIT 两点），保存后可随时撤销。"));

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("本地模型调音 · " + p.notes.size() + " 音符")
                .setView(sc)
                .setPositiveButton("开始调音", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        startLocalTune(style[0], intensity[0], strength[0]);
                    }
                })
                .setNeutralButton("模型管理", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { localModelDialog(); }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    /** runs the on-device model with a live progress dialog */
    private void startLocalTune(final int style, final int intensityPct, final int strengthPct) {
        final Part p = currentPart();
        if (p == null) return;
        final int total = p.notes.size();
        if (total == 0) { status("当前音轨没有音符"); return; }
        final String styleName = LOCAL_STYLE_NAMES[Math.max(0, Math.min(LOCAL_STYLE_NAMES.length - 1, style))];
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(total + 100);
        pd.setProgress(0);
        pd.setMessage("准备本地调音…");
        pd.setCancelable(false);
        pd.show();
        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                .putInt("localStyle", style)
                .putInt("localIntensity", intensityPct)
                .putInt("localStrength", strengthPct).apply();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                com.dsh.v4tune.ai.Tuning res = null;
                try {
                    com.dsh.v4tune.ai.LocalTuner.Model model =
                            com.dsh.v4tune.ai.LocalModelStore.ensureBlocking(MainActivity.this,
                                    new com.dsh.v4tune.ai.LocalModelStore.P() {
                                        public void onProgress(int pm, final String stage) {
                                            ui.post(new Runnable() {
                                                public void run() {
                                                    try {
                                                        pd.setProgress(Math.max(0, Math.min(100, pm / 10)));
                                                        pd.setMessage(stage + "…");
                                                    } catch (Throwable ignored) { }
                                                }
                                            });
                                        }
                                    });
                    res = com.dsh.v4tune.ai.LocalTuner.tune(p, song, trackIndex, model,
                            com.dsh.v4tune.ai.LocalTuner.STYLES[style],
                            intensityPct / 100f, strengthPct / 100f,
                            new com.dsh.v4tune.ai.LocalTuner.Progress() {
                                public void onProgress(final int done, final int tot, String stage) {
                                    ui.post(new Runnable() {
                                        public void run() {
                                            try {
                                                pd.setProgress(100 + done * 900 / Math.max(1, tot));
                                                pd.setMessage("本地模型推理 " + done + "/" + tot);
                                            } catch (Throwable ignored) { }
                                        }
                                    });
                                }
                            });
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final String fe = err;
                final com.dsh.v4tune.ai.Tuning ft = res;
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (fe != null) {
                            String m = "本地调音失败：" + fe;
                            status(m);
                            setQuickAiStatus(m);
                            return;
                        }
                        int n = applyTuning(ft, 1f);
                        aiLastApplied = n;
                        aiLastReply = com.dsh.v4tune.ai.LocalTuner.toJson(ft);
                        String msg = "本地调音完成（" + styleName + "）：" + ft.summary() + " · " + n + " 处";
                        status(msg);
                        setQuickAiStatus(msg);
                    }
                });
            }
        }, "britune-local").start();
    }

    /**
     * "满意，加入训练" in the export dialog: the tuning now on the curves becomes training data and
     * the network is retrained on-device; nothing leaves the phone.
     */
    private void startSelfLearn() {
        if (song == null) { status("没有可学习的工程"); return; }
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(1000);
        pd.setProgress(0);
        pd.setMessage("准备自学习…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                int added = 0, total = 0;
                try {
                    final com.dsh.v4tune.ai.LocalTuner.Model base =
                            com.dsh.v4tune.ai.LocalModelStore.ensureBlocking(MainActivity.this,
                                    new com.dsh.v4tune.ai.LocalModelStore.P() {
                                        public void onProgress(int pm, final String stage) {
                                            ui.post(new Runnable() {
                                                public void run() {
                                                    try { pd.setMessage(stage + "…"); } catch (Throwable ignored) { }
                                                }
                                            });
                                        }
                                    });
                    ui.post(new Runnable() {
                        public void run() {
                            try { pd.setProgress(60); pd.setMessage("整理本次调音数据…"); } catch (Throwable ignored) { }
                        }
                    });
                    added = com.dsh.v4tune.ai.SelfLearn.record(MainActivity.this, song, base,
                            localStyle, localIntensity);
                    if (added > 0) {
                        final java.util.List<float[]> xs = new java.util.ArrayList<float[]>();
                        final java.util.List<float[]> ys = new java.util.ArrayList<float[]>();
                        com.dsh.v4tune.ai.SelfLearn.load(
                                com.dsh.v4tune.ai.SelfLearn.dataFile(MainActivity.this, base), base, xs, ys);
                        total = xs.size();
                        com.dsh.v4tune.ai.LocalTuner.Model tuned = com.dsh.v4tune.ai.SelfLearn.fit(
                                base, xs, ys, new com.dsh.v4tune.ai.LocalTuner.Progress() {
                                    public void onProgress(final int done, final int tot, String stage) {
                                        ui.post(new Runnable() {
                                            public void run() {
                                                try {
                                                    pd.setProgress(100 + done * 880 / Math.max(1, tot));
                                                    pd.setMessage("自学习微调 " + done + "/" + tot + " 轮");
                                                } catch (Throwable ignored) { }
                                            }
                                        });
                                    }
                                });
                        com.dsh.v4tune.ai.LocalTuner.save(tuned,
                                com.dsh.v4tune.ai.SelfLearn.userModelFile(MainActivity.this));
                        com.dsh.v4tune.ai.LocalModelStore.forget();
                    }
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final String fe = err;
                final int fa = added, ft2 = total;
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        String m;
                        if (fe != null) m = "自学习失败：" + fe;
                        else if (fa == 0) m = "本次没有可学习的改动：工程参数都还是默认值";
                        else m = "已加入自学习：" + fa + " 条 · 数据集 " + ft2 + " 条 · 模型已更新";
                        status(m);
                        setQuickAiStatus(m);
                        toast(m);
                    }
                });
            }
        }, "britune-self").start();
    }

    /** where the weights live, how big they are, re-download and delete */
    private void localModelDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));
        box.addView(label("BriTune-Net v3：88-256-128-13 全连接网络（57,357 参数），纯 Java 在本机推理，不联网也能调音。"));
        box.addView(label("训练数据：按通用中文调音策略合成的参数偏移样本，按歌切分验证集；每槽 16 维音符特征（含小节拍点/强拍/反拍/同音重复）+ 8 维全局特征（速度、风格、强度、规模）。"));
        box.addView(label("v3 新增音符内的力度走向与延迟颤音（VIB 起点 0 → 目标深度），并把拍点信息喂给网络；v2 修正了音高表情单位（音分不再被当成 14-bit PIT 值）。"));
        final TextView state = label(localModelState());
        box.addView(state);
        final TextView selfLabel = label(selfLearnState());
        box.addView(selfLabel);
        box.addView(label("存放：" + com.dsh.v4tune.ai.LocalModelStore.file(this).getAbsolutePath()));
        LinearLayout selfRow = row();
        Button clearSelf = small("清空自学习数据");
        clearSelf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                com.dsh.v4tune.ai.SelfLearn.clearAll(MainActivity.this);
                com.dsh.v4tune.ai.LocalModelStore.dropUser(MainActivity.this);
                selfLabel.setText(selfLearnState());
                state.setText(localModelState());
                status("自学习数据已清空，模型回到发布版本");
                toast("自学习数据已清空");
            }
        });
        selfRow.addView(clearSelf);
        Button learnAgain = small("用全部数据重训");
        learnAgain.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startSelfLearn(); }
        });
        selfRow.addView(learnAgain);
        box.addView(selfRow);
        LinearLayout importRow = row();
        Button importModel = small("导入模型文件…");
        importModel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "*/*"});
                startActivityForResult(i, REQ_MODEL);
            }
        });
        importRow.addView(importModel);
        box.addView(importRow);
        new AlertDialog.Builder(this)
                .setTitle("本地调音模型 BriTune-Net")
                .setView(box)
                .setPositiveButton("下载更新", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { downloadLocalModel(state); }
                })
                .setNeutralButton("删除模型", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        com.dsh.v4tune.ai.LocalModelStore.delete(MainActivity.this);
                        state.setText(localModelState());
                        status("本地调音模型已删除");
                        toast("本地调音模型已删除");
                    }
                })
                .setNegativeButton(L.t("dlg.close"), null)
                .show();
    }

    private void downloadLocalModel(final TextView state) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(1000);
        pd.setProgress(0);
        pd.setMessage("准备下载模型…");
        pd.setCancelable(false);
        pd.show();
        com.dsh.v4tune.ai.LocalModelStore.ensure(this, true, new com.dsh.v4tune.ai.LocalModelStore.Cb() {
            public void progress(final int permille, final String stage) {
                ui.post(new Runnable() {
                    public void run() {
                        try {
                            pd.setProgress(Math.max(0, Math.min(1000, permille)));
                            pd.setMessage(stage);
                        } catch (Throwable ignored) { }
                    }
                });
            }
            public void done(com.dsh.v4tune.ai.LocalTuner.Model model, final String error) {
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (state != null) state.setText(localModelState());
                        if (error != null) {
                            status("模型下载失败：" + error);
                            toast("模型下载失败：" + error);
                        } else {
                            status("本地调音模型已就绪");
                            toast("本地调音模型已就绪");
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ settings

    // ------------------------------------------------------- neural acoustic model (v9.1, BriNeting)

    /** directory holding the optional .v4neural models; one universal + one per singer */
    private File neuralDir() {
        File dir = new File(getFilesDir(), "neural");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** stable, filesystem-safe id used for the optional neural model of a voicebank */
    private String neuralKey(VoiceModel vm) {
        String id = (vm == null || vm.id == null || vm.id.trim().isEmpty()) ? "voice" : vm.id.trim();
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private File neuralFile(VoiceModel vm) {
        return new File(neuralDir(), neuralKey(vm) + ".v4neural");
    }

    private File universalNeuralFile() {
        return new File(neuralDir(), "_universal.v4neural");
    }

    /** virtual base singer: neutral voice, all voiced envelopes come from BriNeting */
    private VoiceModel briNetingSinger() {
        if (briNetingVoice == null) {
            VoiceModel vm = new VoiceModel();
            vm.id = "_brineting";
            vm.name = "神经歌手";
            vm.nameEn = "BriNeting";
            vm.nameJa = "BriNeting";
            vm.lang = 2;
            vm.compID = "BriNeting";
            vm.description = "BriNeting 神经基座";
            vm.neuralBank = true;
            vm.harmonicFreqs = NeuralSpectrum.canonicalGrid();
            briNetingVoice = vm;
        }
        NeuralSpectrum u = ensureUniversalNeural();
        briNetingVoice.neural = u;
        briNetingVoice.neuralFromUniversal = true;
        briNetingVoice.neuralEnabled = u != null;
        return briNetingVoice;
    }

    /**
     * Load the shared universal prior once (it is a few dozen KB).
     *
     * <p>The base now ships inside the APK (assets/net/brineting-base.v4neural) so a fresh install
     * already has BriNeting trained with the retrained 灵溪 AI voicebank instead of asking the user
     * to train it by hand. Like the bundled voicebank, it is installed ONCE per shipped build:
     * the signature of the asset is remembered, so a rebuilt base reaches devices that already
     * installed the old one, while a user who deletes it keeps it deleted.</p>
     */
    private NeuralSpectrum ensureUniversalNeural() {
        if (!universalNeuralTried) {
            universalNeuralTried = true;
            installBundledNeuralBase();
            File f = universalNeuralFile();
            if (f.isFile()) {
                try {
                    universalNeural = NeuralSpectrum.load(f);
                } catch (Throwable t) {
                    android.util.Log.w("V4Tune", "universal neural load failed: " + t);
                }
            }
        }
        return universalNeural;
    }

    /** copy assets/net/brineting-base.v4neural into files/neural once per shipped signature */
    private void installBundledNeuralBase() {
        final String name = "brineting-base.v4neural";
        java.io.InputStream in = null;
        try {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            long total = 0;
            byte[] buf = new byte[8192];
            try {
                in = getAssets().open("net/" + name);
            } catch (Throwable missing) {
                return;                                  // no bundled base in this build
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
            int n;
            while ((n = in.read(buf)) > 0) { crc.update(buf, 0, n); bos.write(buf, 0, n); total += n; }
            in.close();
            in = null;
            String sig = total + ":" + crc.getValue();
            android.content.SharedPreferences sp = getSharedPreferences("brivoice", MODE_PRIVATE);
            if (sig.equals(sp.getString("base_sig", null))) return;
            File dst = universalNeuralFile();
            File dir = dst.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                bos.writeTo(out);
            } finally {
                out.close();
            }
            sp.edit().putString("base_sig", sig).apply();
            android.util.Log.i("V4Tune", "bundled BriNeting base installed: " + total + " bytes");
        } catch (Throwable t) {
            android.util.Log.w("V4Tune", "bundled base install failed: " + t);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    private void saveNeuralEnabled(VoiceModel vm, boolean on) {
        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                .putBoolean("neuralOn_" + neuralKey(vm), on).apply();
    }

    /** restore the user's saved neural controls for this singer (pack defaults otherwise) */
    private void applyNeuralPrefs(VoiceModel vm) {
        if (vm == null || vm.neuralParams == null) return;
        String s = getSharedPreferences("brivoice", MODE_PRIVATE)
                .getString("np_" + neuralKey(vm), null);
        if (s == null || s.trim().isEmpty()) return;
        try {
            String[] p = s.split(",");
            if (p.length >= 8) {
                VoiceModel.NeuralParams n = vm.neuralParams;
                n.breath = Double.parseDouble(p[0]);
                n.tension = Double.parseDouble(p[1]);
                n.brightness = Double.parseDouble(p[2]);
                n.nasality = Double.parseDouble(p[3]);
                n.clarity = Double.parseDouble(p[4]);
                n.vibrato = Double.parseDouble(p[5]);
                n.dynamics = Double.parseDouble(p[6]);
                n.pitch = Double.parseDouble(p[7]);
                if (p.length >= 9) n.mix = Double.parseDouble(p[8]);
                n.clampAll();
            }
        } catch (Throwable ignored) { }
    }

    private void saveNeuralPrefs(VoiceModel vm) {
        if (vm == null || vm.neuralParams == null) return;
        VoiceModel.NeuralParams n = vm.neuralParams;
        n.clampAll();
        String s = n.breath + "," + n.tension + "," + n.brightness + "," + n.nasality + ","
                + n.clarity + "," + n.vibrato + "," + n.dynamics + "," + n.pitch + "," + n.mix;
        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                .putString("np_" + neuralKey(vm), s).apply();
    }

    /** load the per-singer model, else use the universal prior, then restore the on/off state */
    private void attachNeural(VoiceModel vm) {
        if (vm == null) return;
        applyNeuralPrefs(vm);
        if (vm.neural == null) {
            File f = neuralFile(vm);
            if (f.isFile()) {
                try {
                    vm.neural = NeuralSpectrum.load(f);
                    vm.neuralFromUniversal = false;
                } catch (Throwable t) {
                    android.util.Log.w("V4Tune", "neural load failed: " + t);
                }
            }
        }
        if (vm.neural == null) {
            NeuralSpectrum u = ensureUniversalNeural();
            if (u != null) {
                vm.neural = u;
                vm.neuralFromUniversal = true;
            }
        }
        // v9.2: engine follows the pack type. A .v4neural bank is always BriNeting; a normal
        // .v4voice bank uses the traditional engine unless the user explicitly turned neural on.
        // v11.0: a self-made bank ships its own neural link weights, so BriNeting is its default
        // engine. The user can still switch it off explicitly (the pref then wins).
        android.content.SharedPreferences pref = getSharedPreferences("brivoice", MODE_PRIVATE);
        String nk = "neuralOn_" + neuralKey(vm);
        boolean on = pref.getBoolean(nk, false);
        if (!on && vm.neuralEmbedded && !pref.contains(nk)) on = true;
        vm.neuralEnabled = vm.neural != null && (vm.neuralBank || on);
    }

    /** every installed voicebank that actually carries measured harmonic frames */
    private java.util.List<VoiceModel> neuralBanks() {
        java.util.ArrayList<VoiceModel> out = new java.util.ArrayList<VoiceModel>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        for (VoiceModel vm : SingerLibrary.all()) {
            if (vm == null || vm.harmonicFreqs == null || vm.harmonicFreqs.length < 4
                    || vm.harmonics == null || vm.harmonics.isEmpty()) continue;
            String key = (vm.id == null || vm.id.isEmpty()) ? vm.name : vm.id;
            if (key == null || !seen.add(key)) continue;
            out.add(vm);
        }
        return out;
    }

    private String neuralStatusText() {
        StringBuilder b = new StringBuilder();
        NeuralSpectrum u = ensureUniversalNeural();
        b.append("BriNeting 基座：");
        if (u == null) b.append("未训练");
        else b.append(u.describe());
        b.append('\n').append("当前歌手：");
        if (currentVoice == null) {
            b.append("未选择");
        } else if (currentVoice.neural == null) {
            b.append("未训练");
        } else if ("_brineting".equals(currentVoice.id)) {
            b.append("神经歌手（基座直出）");
        } else if (isFusionBank(currentVoice)) {
            b.append("融合声库 · ").append(currentVoice.neural.describe());
        } else {
            b.append(currentVoice.neuralBank ? "神经声库" : (currentVoice.neuralEmbedded ? "自制声库" : "传统声库"));
            b.append(" · ").append(currentVoice.neuralFromUniversal ? "BriNeting 基座（尚未微调）" : "已微调");
            b.append(" · ").append(currentVoice.neural.describe());
        }
        return b.toString();
    }

    private void refreshNeuralWidgets() {
        String text = neuralStatusText();
        if (neuralDesc != null) neuralDesc.setText(text);
        if (neuralDialogStatus != null) neuralDialogStatus.setText(text);
        if (neuralChk != null) {
            boolean usable = currentVoice != null && currentVoice.neural != null;
            if (currentVoice != null && currentVoice.neuralBank) {
                neuralChk.setText("此声库固定使用 BriNeting 神经合成");
                neuralChk.setEnabled(false);
                neuralChk.setChecked(usable);
            } else {
                neuralChk.setText("启用神经合成（当前歌手）");
                neuralChk.setEnabled(usable);
                neuralChk.setChecked(usable && currentVoice != null && currentVoice.neuralEnabled);
            }
        }
    }

    private android.app.ProgressDialog neuralProgress(String title) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle(title);
        pd.setMessage("准备频谱数据…");
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        return pd;
    }

    private void clearRenderCache() {
        cacheL = null; cacheR = null; cacheKey = 0L; cacheTotal = -1;
    }

    /** Step 1: learn the shared spectral rules of every installed measured voicebank. */
    private void trainUniversalNeural() {
        final android.app.ProgressDialog pd = neuralProgress("训练 BriNeting 基座");
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.util.List<VoiceModel> banks = neuralBanks();
                    if (banks.isEmpty()) throw new java.io.IOException("没有可用的实测声库");
                    final NeuralSpectrum model = NeuralSpectrum.trainUniversal(banks,
                            new NeuralSpectrum.Progress() {
                                public void onProgress(final int percent, final String message) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            pd.setProgress(percent);
                                            pd.setMessage(message);
                                        }
                                    });
                                }
                            });
                    final File f = universalNeuralFile();
                    model.save(f);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            pd.dismiss();
                            universalNeural = model;
                            universalNeuralTried = true;
                            // The base is shared, but the engine of a normal .v4voice singer stays
                            // traditional unless the user already opted that singer in. .v4neural
                            // packs are always driven by the base.
                            for (VoiceModel m : SingerLibrary.all()) {
                                if (m.neural == null || m.neuralFromUniversal) {
                                    m.neural = model;
                                    m.neuralFromUniversal = true;
                                }
                                boolean on = m.neural != null && (m.neuralBank
                                        || getSharedPreferences("brivoice", MODE_PRIVATE)
                                                .getBoolean("neuralOn_" + neuralKey(m), false));
                                m.neuralEnabled = on;
                                saveNeuralEnabled(m, on);
                            }
                            if (briNetingVoice != null) {
                                briNetingVoice.neural = model;
                                briNetingVoice.neuralFromUniversal = true;
                                briNetingVoice.neuralEnabled = true;
                                saveNeuralEnabled(briNetingVoice, true);
                            }
                            if (currentVoice != null && currentVoice.neural == null) {
                                currentVoice.neural = model;
                                currentVoice.neuralFromUniversal = true;
                                currentVoice.neuralEnabled = currentVoice.neuralBank;
                                saveNeuralEnabled(currentVoice, currentVoice.neuralEnabled);
                            }
                            clearRenderCache();
                            refreshNeuralWidgets();
                            toast("BriNeting 基座已训练：" + model.describe());
                            status("BriNeting 基座已训练：神经声库将基于它合成，传统声库保持原引擎");
                        }
                    });
                } catch (final Throwable t) {
                    android.util.Log.e("V4Tune", "universal neural train failed", t);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            pd.dismiss();
                            toast("BriNeting 基座训练失败：" + t.getMessage());
                            status("BriNeting 基座训练失败：" + t.getMessage());
                        }
                    });
                }
            }
        }, "neural-universal").start();
    }

    /**
     * Step 2: fine-tune the universal prior on the current singer. If no prior exists yet it is
     * trained first, so the button always produces a usable per-singer model.
     */
    private void fineTuneNeural() {
        final VoiceModel vm = currentVoice;
        if (vm == null) { toast("先安装并选择一个声库"); return; }
        if ("_brineting".equals(vm.id)) {
            toast("神经歌手直接使用 BriNeting 基座，不需要微调");
            return;
        }
        final android.app.ProgressDialog pd = neuralProgress("微调歌手神经模型");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final NeuralSpectrum prior;
                    NeuralSpectrum existing = ensureUniversalNeural();
                    if (existing == null) {
                        java.util.List<VoiceModel> banks = neuralBanks();
                        if (banks.isEmpty()) throw new java.io.IOException("没有可用的实测声库");
                        existing = NeuralSpectrum.trainUniversal(banks, new NeuralSpectrum.Progress() {
                            public void onProgress(final int percent, final String message) {
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        pd.setProgress(Math.max(1, percent / 3));
                                        pd.setMessage("① BriNeting 基座 · " + message);
                                    }
                                });
                            }
                        });
                        existing.save(universalNeuralFile());
                    }
                    prior = existing;
                    final NeuralSpectrum tuned = NeuralSpectrum.fineTune(prior, vm,
                            new NeuralSpectrum.Progress() {
                                public void onProgress(final int percent, final String message) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            pd.setProgress(35 + Math.max(1, percent * 63 / 100));
                                            pd.setMessage("② 微调 " + vm.name + " · " + message);
                                        }
                                    });
                                }
                            });
                    final File f = neuralFile(vm);
                    tuned.save(f);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            pd.dismiss();
                            universalNeural = prior;
                            universalNeuralTried = true;
                            for (VoiceModel m : SingerLibrary.all()) {
                                if (m.neural == null || m.neuralFromUniversal) {
                                    m.neural = prior;
                                    m.neuralFromUniversal = true;
                                }
                            }
                            if (briNetingVoice != null) {
                                briNetingVoice.neural = prior;
                                briNetingVoice.neuralFromUniversal = true;
                                briNetingVoice.neuralEnabled = true;
                                saveNeuralEnabled(briNetingVoice, true);
                            }
                            vm.neural = tuned;
                            vm.neuralFromUniversal = false;
                            vm.neuralEnabled = true;
                            saveNeuralEnabled(vm, true);
                            clearRenderCache();
                            refreshNeuralWidgets();
                            toast("微调完成：" + f.getName() + "（" + Math.max(1, f.length() / 1024) + " KB）");
                            status("已基于 BriNeting 微调并启用：" + vm.name + " · " + tuned.describe());
                        }
                    });
                } catch (final Throwable t) {
                    android.util.Log.e("V4Tune", "neural fine-tune failed", t);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            pd.dismiss();
                            toast("微调失败：" + t.getMessage());
                            status("歌手神经模型微调失败：" + t.getMessage());
                        }
                    });
                }
            }
        }, "neural-finetune").start();
    }

    private void deleteCurrentNeural() {
        if (currentVoice == null) return;
        if ("_brineting".equals(currentVoice.id)) { deleteUniversalNeural(); return; }
        File nf = neuralFile(currentVoice);
        if (nf.isFile()) nf.delete();
        currentVoice.neural = null;
        currentVoice.neuralFromUniversal = false;
        currentVoice.neuralEnabled = false;
        saveNeuralEnabled(currentVoice, false);
        clearRenderCache();
        refreshNeuralWidgets();
        toast("已删除当前歌手的神经模型");
        status("已删除当前歌手的神经模型");
    }

    private void deleteUniversalNeural() {
        File f = universalNeuralFile();
        if (f.isFile()) f.delete();
        universalNeural = null;
        universalNeuralTried = true;
        for (VoiceModel m : SingerLibrary.all()) {
            if (m.neuralFromUniversal) {
                m.neural = null;
                m.neuralFromUniversal = false;
                m.neuralEnabled = false;
                saveNeuralEnabled(m, false);
            }
        }
        if (briNetingVoice != null) {
            briNetingVoice.neural = null;
            briNetingVoice.neuralFromUniversal = false;
            briNetingVoice.neuralEnabled = false;
            saveNeuralEnabled(briNetingVoice, false);
        }
        clearRenderCache();
        refreshNeuralWidgets();
        toast("已删除 BriNeting 基座");
        status("已删除 BriNeting 基座，所有歌手回到实测频谱合成");
    }

    /** dedicated sheet: train the BriNeting base, fine-tune the current singer, then enable it */
    // ---------------------------------------------- multi-bank fusion (v9.4)

    /** true for a singer produced by multi-bank fusion (a .v4neural pack with a fusion_ id) */
    static boolean isFusionBank(VoiceModel v) {
        return v != null && v.id != null && v.id.startsWith("fusion_");
    }

    /** display name of one source bank without the local "my voicebank" suffix */
    private static String shortBankName(VoiceModel vm) {
        if (vm == null) return "声库";
        String n = vm.nameFor(L.lang());
        if (n == null || n.trim().isEmpty()) n = vm.name;
        if (n == null) return "声库";
        return n.replace("（我的声库）", "").replace("(installed)", "")
                .replace("（内置）", "").replace("(built-in)", "").trim();
    }

    private static String fusionSourceText(java.util.List<VoiceModel> picks) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < picks.size(); i++) {
            if (i > 0) b.append("、");
            b.append(shortBankName(picks.get(i)));
        }
        return b.toString();
    }

    private static String defaultFusionName(java.util.List<VoiceModel> picks) {
        StringBuilder b = new StringBuilder("融合·");
        for (int i = 0; i < picks.size() && i < 2; i++) {
            if (i > 0) b.append(" + ");
            b.append(shortBankName(picks.get(i)));
        }
        if (picks.size() > 2) b.append(" 等").append(picks.size()).append("库");
        return b.toString();
    }

    /**
     * Multi-bank fusion: pick 2+ installed voicebanks, blend their measured harmonic frames into a
     * wider neural model (112x72, about twice the base parameters) and install the result as a new
     * {@code .v4neural} singer, so it can be selected, compared and deleted like any imported pack.
     */
    private void fusionDialog() {
        final java.util.List<VoiceModel> banks = neuralBanks();
        if (banks.size() < 2) {
            toast("至少要有 2 个带实测谐波的声库才能融合（当前 " + banks.size() + " 个）");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(4));

        TextView help = new TextView(this);
        help.setText("勾选 2 个以上声库，把它们的实测谐波一起训练成一个更宽的神经声库"
                + "（112×72，约单库模型的 2 倍参数）。\n"
                + "融合结果会作为新的 .v4neural 声库出现在「歌手」列表，可随时切换、删除，"
                + "不会改动原来的声库；每个声库参与训练的比例相同。");
        help.setTextColor(Theme.TEXT_DIM);
        help.setTextSize(11.5f);
        box.addView(help);

        final java.util.List<CheckBox> checks = new java.util.ArrayList<CheckBox>();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < banks.size(); i++) {
            final VoiceModel vm = banks.get(i);
            int bands = 0;
            for (java.util.Map.Entry<String, VoiceModel.HarmonicBand[]> e : vm.harmonics.entrySet()) {
                bands += e.getValue().length;
            }
            CheckBox cb = new CheckBox(this);
            cb.setText(singerName(vm) + " · " + vm.harmonics.size() + " 音素 / " + bands + " 频段");
            cb.setTextColor(Theme.TEXT);
            cb.setTextSize(12.5f);
            cb.setChecked(i < 2);
            checks.add(cb);
            list.addView(cb);
        }
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(list);
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

        TextView nameLbl = new TextView(this);
        nameLbl.setText("融合声库名称（留空自动命名）");
        nameLbl.setTextColor(Theme.TEXT_DIM);
        nameLbl.setTextSize(12f);
        nameLbl.setPadding(0, dp(6), 0, 0);
        box.addView(nameLbl);
        final EditText name = field("", false);
        name.setHint("例如：融合·洛天依 + Miku");
        box.addView(name);

        LinearLayout actions = row();
        Button cancel = small("取消");
        Button start = small("开始融合");
        actions.addView(cancel);
        actions.addView(start);
        box.addView(actions);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("多声库融合（BriNeting）")
                .setView(box)
                .create();
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                java.util.ArrayList<VoiceModel> picks = new java.util.ArrayList<VoiceModel>();
                for (int i = 0; i < checks.size(); i++) {
                    if (checks.get(i).isChecked()) picks.add(banks.get(i));
                }
                if (picks.size() < 2) { toast("请至少勾选 2 个声库"); return; }
                String nm = name.getText().toString().trim();
                if (nm.isEmpty()) nm = defaultFusionName(picks);
                dlg.dismiss();
                trainFusion(picks, nm);
            }
        });
        dlg.show();
    }

    /** trains the fused model off the UI thread, then installs it as a .v4neural voicebank */
    private void trainFusion(final java.util.List<VoiceModel> picks, final String requestedName) {
        final android.app.ProgressDialog pd = neuralProgress("多声库融合训练");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final long t0 = System.currentTimeMillis();
                    final NeuralSpectrum model = NeuralSpectrum.trainFusion(picks,
                            new NeuralSpectrum.Progress() {
                                public void onProgress(final int percent, final String message) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            pd.setProgress(percent);
                                            pd.setMessage(message);
                                        }
                                    });
                                }
                            });
                    final String name = uniqueFusionName(requestedName);
                    final VoiceModel fused = buildFusionVoice(name, picks, model);
                    model.save(neuralFile(fused));
                    File pack = new File(packStore.directory(), fused.id + VoicePack.EXT_NEURAL);
                    File dir = pack.getParentFile();
                    if (dir != null && !dir.isDirectory()) dir.mkdirs();
                    VoicePack.write(pack, fused, fusionLicense(picks));
                    final VoicePack.Result back = VoicePack.load(pack);
                    final int seconds = (int) Math.max(1, (System.currentTimeMillis() - t0) / 1000);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            pd.dismiss();
                            if (back == null || !back.ok() || back.model == null) {
                                toast("融合声库写入失败：" + (back == null ? "未知错误" : back.error));
                                return;
                            }
                            VoiceModel v = back.model;
                            v.neural = model;
                            v.neuralFromUniversal = false;
                            v.neuralEnabled = true;
                            saveNeuralEnabled(v, true);
                            SingerLibrary.upsertUserPack(v);
                            beginEdit();
                            currentVoice = v;
                            applyVoiceToPart(v);
                            clearRenderCache();
                            commitEdit();
                            refreshAll();
                            refreshNeuralWidgets();
                            toast("融合完成：" + v.name + " · " + model.paramCount() + " 参数");
                            status("已安装融合声库 " + v.name + "（" + picks.size() + " 个声库 · "
                                    + seconds + " 秒）");
                        }
                    });
                } catch (final Throwable t) {
                    android.util.Log.e("V4Tune", "neural fusion failed", t);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            pd.dismiss();
                            toast("融合训练失败：" + t.getMessage());
                            status("融合训练失败：" + t.getMessage());
                        }
                    });
                }
            }
        }, "neural-fusion").start();
    }

    private String uniqueFusionName(String wanted) {
        String base = wanted == null ? "" : wanted.trim();
        if (base.isEmpty()) base = "融合声库";
        if (base.length() > 36) base = base.substring(0, 36);
        String name = base;
        int n = 2;
        boolean clash = true;
        while (clash) {
            clash = false;
            for (VoiceModel m : SingerLibrary.all()) {
                if (m != null && name.equalsIgnoreCase(m.nameFor(L.lang()))) { clash = true; break; }
            }
            if (clash) name = base + " · " + (n++);
        }
        return name;
    }

    /** builds the singer record: averaged acoustic metadata + averaged perceptual controls */
    private VoiceModel buildFusionVoice(String name, java.util.List<VoiceModel> picks,
                                        NeuralSpectrum model) {
        VoiceModel v = new VoiceModel();
        v.id = "fusion_" + Long.toString(System.currentTimeMillis(), 36)
                + "_" + Integer.toHexString((name + picks.size()).hashCode());
        v.name = name;
        v.nameEn = "Fusion · " + shortBankName(picks.get(0));
        v.nameJa = name;
        v.compID = "BriNetingFusion";
        int[] langCount = new int[8];
        for (VoiceModel p : picks) {
            if (p.lang >= 0 && p.lang < langCount.length) langCount[p.lang]++;
        }
        int best = 0;
        for (int i = 1; i < langCount.length; i++) if (langCount[i] > langCount[best]) best = i;
        v.lang = best;

        VoiceModel first = picks.get(0);
        v.formantScale = first.formantScale;
        v.f1Mul = first.f1Mul;
        v.f2Mul = first.f2Mul;
        v.f3Mul = first.f3Mul;
        v.f4Mul = first.f4Mul;
        v.f5Mul = first.f5Mul;
        v.tiltBias = first.tiltBias;
        v.breathBias = first.breathBias;
        v.oqBias = first.oqBias;
        v.vibratoRate = first.vibratoRate;
        v.vibratoDepthCents = first.vibratoDepthCents;
        v.vibratoDelayMs = first.vibratoDelayMs;
        v.gain = first.gain;
        v.consonantGain = first.consonantGain;

        if (v.neuralParams == null) v.neuralParams = new VoiceModel.NeuralParams();
        double[] avg = new double[9];
        for (VoiceModel p : picks) {
            VoiceModel.NeuralParams q = p.neuralParams;
            if (q == null) q = new VoiceModel.NeuralParams();
            avg[0] += q.breath;
            avg[1] += q.tension;
            avg[2] += q.brightness;
            avg[3] += q.nasality;
            avg[4] += q.clarity;
            avg[5] += q.vibrato;
            avg[6] += q.dynamics;
            avg[7] += q.pitch;
            avg[8] += q.mix;
        }
        for (int i = 0; i < avg.length; i++) avg[i] /= picks.size();
        v.neuralParams.breath = avg[0];
        v.neuralParams.tension = avg[1];
        v.neuralParams.brightness = avg[2];
        v.neuralParams.nasality = avg[3];
        v.neuralParams.clarity = avg[4];
        v.neuralParams.vibrato = avg[5];
        v.neuralParams.dynamics = avg[6];
        v.neuralParams.pitch = avg[7];
        v.neuralParams.mix = avg[8];
        v.neuralParams.clampAll();

        v.neuralBank = true;
        v.neural = model;
        v.neuralEnabled = true;
        v.neuralFromUniversal = false;
        v.harmonicFreqs = NeuralSpectrum.canonicalGrid();
        v.description = "多声库融合：" + fusionSourceText(picks);
        return v;
    }

    private static String fusionLicense(java.util.List<VoiceModel> picks) {
        return "BriVoice multi-bank fusion model\n"
                + "Sources: " + fusionSourceText(picks) + "\n"
                + "Trained on this device from the harmonic spectra already measured into each "
                + "voicebank; no audio samples were decoded.\n"
                + "Original voicebanks remain the property of their respective rights holders.\n";
    }

    // -------------------------------------------------- custom voicebank (v11.0)

    /** sheet: pick takes + artwork, then train everything on the phone */
    private void customBankDialog() { customBankDialog(true); }

    private void customBankDialog(boolean reset) {
        if (reset) {
            customSamples.clear();
            customArt = null;
            customRecordJson = null;
            customName = "";
            customLang = 2;
            customNeural = true;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));
        TextView desc = new TextView(this);
        desc.setText("造库流程按 VOCALOID / UTAU 的习惯来：先定发音清单和参考音高，再一条一条录（UTAU 的文件名就是发音，"
                + "VOCALOID 的录音清单就是参考音），手机直接录、自动修剪和质检；也可以导入已有 WAV。"
                + "BriVoice 只在本机测量谐波、帧序列和首辅音噪声，再训练神经连接模型。包内会带上立绘、采样、神经参数和 record.json 录音记录。");
        desc.setTextColor(Theme.TEXT_DIM);
        desc.setTextSize(12.5f);
        box.addView(desc);

        Button guide = new Button(this);
        guide.setText("🎙 录音向导（推荐 · 手机直接录）");
        guide.setAllCaps(false);
        guide.setTextSize(14f);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.setMargins(0, dp(8), 0, dp(4));
        guide.setLayoutParams(glp);
        guide.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openRecordWizard(); }
        });
        box.addView(guide);

        final EditText name = new EditText(this);
        name.setHint("声库名称（默认：我的声库）");
        name.setSingleLine(true);
        name.setSelectAllOnFocus(true);
        name.setText(customName);
        box.addView(name);

        final int[] lang = {customLang};
        final String[] langNames = {"中文", "日文", "英文"};
        final Button[] langBtns = new Button[3];
        LinearLayout langs = row();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            langBtns[i] = small(langNames[i]);
            langBtns[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    lang[0] = idx;
                    for (int j = 0; j < 3; j++) langBtns[j].setText((j == idx ? "● " : "") + langNames[j]);
                }
            });
            langs.addView(langBtns[i]);
        }
        for (int i = 0; i < 3; i++) {
            langBtns[i].setText((i == customLang ? "● " : "") + langNames[i]);
        }
        box.addView(langs);

        TextView engineTitle = new TextView(this);
        engineTitle.setText("合成方式");
        engineTitle.setTextColor(Theme.TEXT_DIM);
        engineTitle.setTextSize(11.5f);
        engineTitle.setPadding(0, dp(8), 0, dp(2));
        box.addView(engineTitle);
        final Button[] engBtns = new Button[2];
        final String[] engNames = {"神经合成（BriNeting）", "采样合成（传统）"};
        LinearLayout engines = row();
        for (int i = 0; i < 2; i++) {
            final boolean neural = (i == 0);
            engBtns[i] = small("");
            engBtns[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    customNeural = neural;
                    engBtns[0].setText((customNeural ? "● " : "") + engNames[0]);
                    engBtns[1].setText((customNeural ? "" : "● ") + engNames[1]);
                }
            });
            engines.addView(engBtns[i]);
        }
        engBtns[0].setText((customNeural ? "● " : "") + engNames[0]);
        engBtns[1].setText((customNeural ? "" : "● ") + engNames[1]);
        box.addView(engines);
        TextView engTip = new TextView(this);
        engTip.setText("神经合成会额外训练一层声学模型并嵌进包里（默认，音色更连贯）；"
                + "采样合成只保留测量出的谐波与辅音谱，包更小、训练更快。两种都不超过 800 MB。");
        engTip.setTextColor(Theme.TEXT_DIM);
        engTip.setTextSize(11.5f);
        box.addView(engTip);

        customStatus = label("已选采样：" + customSamples.size() + " 个"
                + (customArt != null ? " · 立绘：已选择" : " · 立绘：未选择")
                + (customRecordJson != null ? " · 录音记录：有" : ""));
        box.addView(customStatus);

        LinearLayout btns = row();
        Button picks = small("选择采样 WAV…");
        Button picka = small("选择立绘…");
        btns.addView(picks);
        btns.addView(picka);
        box.addView(btns);
        picks.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickCustomSamples(); }
        });
        picka.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickCustomArt(); }
        });

        TextView tip = new TextView(this);
        tip.setText("建议：每个发音 1 秒以上、干声、不要伴奏；音高尽量覆盖你要唱的音域。"
                + "训练完成后会自动把新声库并入 BriNeting 基座。");
        tip.setTextColor(Theme.TEXT_DIM);
        tip.setTextSize(12f);
        box.addView(tip);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("自制声库（v12.2）")
                .setView(sc)
                .setPositiveButton("开始训练", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { startCustomTraining(name.getText().toString(), lang[0], customNeural); }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }

    /** v11.0.1: phone-first guided recording, then hand the takes to the pack sheet */
    private void openRecordWizard() {
        recWizard = new com.dsh.v4tune.voicebank.RecordWizard(this,
                new com.dsh.v4tune.voicebank.RecordWizard.Host() {
                    public void onRecordingFinished(String name, int lang,
                            java.util.List<com.dsh.v4tune.voicebank.CustomBankBuilder.Sample> samples,
                            String recordJson) {
                        recWizard = null;
                        customSamples.clear();
                        customSamples.addAll(samples);
                        customRecordJson = recordJson;
                        customName = name;
                        customLang = lang;
                        customBankDialog(false);
                    }

                    public void onPickTakeFile() { pickRecTake(); }
                });
        recWizard.show();
    }

    private void pickRecTake() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/wav", "audio/x-wav", "audio/wave",
                "audio/vnd.wave", "audio/*", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_REC_TAKE);
        } catch (Throwable t) {
            toast("无法打开文件选择器：" + t.getMessage());
        }
    }

    /** v11.0.1: a picked WAV becomes the current wizard take */
    private void recTakeFrom(Uri uri) {
        android.util.Log.i("V4Tune", "recTakeFrom uri=" + uri + " wizard=" + (recWizard != null));
        if (recWizard == null || uri == null) return;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            byte[] bytes = readAllStream(in, 32 * 1024 * 1024);
            WavReader.AudioData ad = WavReader.read(new ByteArrayInputStream(bytes), WavReader.OUT_RATE * 60);
            if (ad == null || ad.error != null) {
                toast("读不出这个 WAV：" + (ad == null ? "未知" : ad.error));
                return;
            }
            float[] mono = new float[ad.left.length];
            for (int k = 0; k < mono.length; k++) {
                mono[k] = ad.right == null ? ad.left[k] : (ad.left[k] + ad.right[k]) * 0.5f;
            }
            android.util.Log.i("V4Tune", "recTakeFrom bytes=" + bytes.length + " mono=" + mono.length + " sr=" + ad.sampleRate);
            recWizard.acceptImported(baseName(uri), mono, WavReader.OUT_RATE, bytes);
        } catch (Throwable t) {
            android.util.Log.e("V4Tune", "recTakeFrom failed", t);
            toast("导入失败：" + t.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == com.dsh.v4tune.voicebank.RecordWizard.REQ_MIC) {
            boolean granted = grantResults != null && grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (recWizard != null) recWizard.onPermissionResult(granted);
            else if (!granted) toast("没有麦克风权限，可以改用「选择采样 WAV…」导入");
        }
    }

    private void pickCustomSamples() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/wav", "audio/x-wav", "audio/wave",
                "audio/vnd.wave", "audio/*", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_CUSTOM_SAMPLES);
        } catch (Throwable t) {
            status("无法打开文件选择器: " + t.getMessage());
        }
    }

    private void pickCustomArt() {
        // DocumentsUI (not the photo picker) so a PNG copied into Download is selectable too
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_CUSTOM_ART);
        } catch (Throwable t) {
            status("无法打开图片选择器: " + t.getMessage());
        }
    }

    /** read every picked WAV once: bytes stay for the pack, float mono is used for measuring */
    private void addCustomSamples(Intent data) {
        final ArrayList<Uri> uris = new ArrayList<Uri>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在读取采样…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                int added = 0;
                StringBuilder bad = new StringBuilder();
                long total = 0;
                for (Uri u : uris) {
                    if (customSamples.size() >= 64) break;
                    try {
                        InputStream in = getContentResolver().openInputStream(u);
                        byte[] bytes = readAllStream(in, 48 * 1024 * 1024);
                        total += bytes.length;
                        if (total > 160L * 1024 * 1024) { bad.append("、总大小超过 160MB"); break; }
                        WavReader.AudioData ad = WavReader.read(new ByteArrayInputStream(bytes), WavReader.OUT_RATE * 60);
                        if (ad == null || ad.error != null) {
                            if (bad.length() < 200) bad.append(bad.length() > 0 ? "、" : "").append(baseName(u)).append("（").append(ad == null ? "读不出" : ad.error).append("）");
                            continue;
                        }
                        float[] mono = new float[ad.left.length];
                        for (int i = 0; i < mono.length; i++) {
                            mono[i] = ad.right == null ? ad.left[i] : (ad.left[i] + ad.right[i]) * 0.5f;
                        }
                        customSamples.add(new com.dsh.v4tune.voicebank.CustomBankBuilder.Sample(
                                baseName(u), mono, WavReader.OUT_RATE, bytes));
                        added++;
                    } catch (Throwable t) {
                        if (bad.length() < 200) bad.append(bad.length() > 0 ? "、" : "").append(baseName(u));
                    }
                }
                final int fadd = added;
                final String fbad = bad.toString();
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        updateCustomStatus();
                        toast(fadd > 0 ? ("已加入 " + fadd + " 个采样") : "没有读到可用的 WAV 采样");
                        if (fbad.length() > 0) status("部分采样未加入：" + fbad);
                    }
                });
            }
        }, "custom-samples").start();
    }

    private void loadCustomArt(final Uri uri) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在处理立绘（自动去白底）…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                byte[] png = null;
                try {
                    android.graphics.Bitmap bm = com.dsh.v4tune.ui.CharacterArt.fromUri(MainActivity.this, uri);
                    if (bm == null) throw new IllegalStateException("图片解码失败");
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo);
                    png = bo.toByteArray();
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final String fe = err;
                final byte[] fp = png;
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (fe != null) { toast("立绘处理失败：" + fe); return; }
                        customArt = fp;
                        updateCustomStatus();
                        toast("立绘已加入声库（会自动去白底）");
                    }
                });
            }
        }, "custom-art").start();
    }

    private void updateCustomStatus() {
        if (customStatus == null) return;
        customStatus.setText("已选采样：" + customSamples.size() + " 个"
                + (customArt != null ? " · 立绘：已选择" : " · 立绘：未选择")
                + (customRecordJson != null ? " · 录音记录：有" : ""));
    }

    /**
     * measure + (optionally) train + write the pack + register it; heavy work on a background thread.
     *
     * <p>v12.2: {@code neural} picks the synthesis mode chosen in the sheet. Neural also trains the
     * BriNeting acoustic layer and embeds it; sample mode keeps the measured harmonics only, which
     * makes a much smaller pack and skips the training wait.</p>
     */
    private void startCustomTraining(final String rawName, final int lang, final boolean neural) {
        if (customSamples.isEmpty()) {
            toast("请先选择至少一个 WAV 采样");
            return;
        }
        final String nm = (rawName == null || rawName.trim().isEmpty()) ? "我的声库" : rawName.trim();
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setMessage("正在测量采样…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                String detail = "";
                com.dsh.v4tune.synth.VoiceModel result = null;
                try {
                    final com.dsh.v4tune.voicebank.CustomBankBuilder.Report rep =
                            new com.dsh.v4tune.voicebank.CustomBankBuilder.Report();
                    pd.setMessage(neural ? "正在用采样训练神经声学模型…" : "正在测量采样（采样合成）…");
                    final com.dsh.v4tune.voicebank.VoicebankWriter w =
                            com.dsh.v4tune.voicebank.CustomBankBuilder.build(
                                    new ArrayList<com.dsh.v4tune.voicebank.CustomBankBuilder.Sample>(customSamples),
                                    nm, lang, rep, neural ? baseSampleNet() : null);
                    pd.setMessage("正在写入声库包…");
                    File tmp = new File(getCacheDir(), "custom_" + System.currentTimeMillis() + VoicePack.EXT);
                    w.writeZip(tmp, null);
                    VoicePack.Result pr = VoicePack.load(tmp);
                    if (pr.error != null || pr.model == null) throw new IllegalStateException(pr.error == null ? "声库包读不回来" : pr.error);
                    final com.dsh.v4tune.synth.VoiceModel vm = pr.model;
                    if (customRecordJson != null && customRecordJson.trim().length() > 0) {
                        vm.recordJson = customRecordJson;
                    }
                    vm.sampleWavs.clear();
                    for (com.dsh.v4tune.voicebank.CustomBankBuilder.Sample s : customSamples) {
                        vm.sampleWavs.put(baseName(s.fileName), s.wavBytes);
                    }
                    vm.characterPng = customArt;
                    // the sampled model eats the raw takes frame by frame: this is the engine that
                    // actually sings with the singer's own spectrum (v11.1)
                    if (rep.sampleNet != null) {
                        vm.sampleNet = rep.sampleNet;
                        vm.sampleNetBytes = rep.sampleNetBytes;
                    }

                    // 1) the samples get their own neural link model (falls back to fine-tuning
                    //    the shared base when there are too few frames). Sample mode skips it.
                    pd.setMessage(neural ? "正在训练采样神经参数…" : "采样合成：跳过神经训练");
                    NeuralSpectrum model = null;
                    if (neural) try {
                        model = NeuralSpectrum.train(vm, new NeuralSpectrum.Progress() {
                            public void onProgress(final int percent, final String message) {
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        pd.setProgress(Math.max(2, Math.min(80, percent * 8 / 10)));
                                        pd.setMessage("训练采样神经参数 · " + message);
                                    }
                                });
                            }
                        });
                    } catch (Throwable first) {
                        NeuralSpectrum prior = null;
                        try { prior = NeuralSpectrum.load(universalNeuralFile()); } catch (Throwable ignored) { }
                        if (prior != null) {
                            final NeuralSpectrum pr2 = prior;
                            model = NeuralSpectrum.fineTune(pr2, vm, new NeuralSpectrum.Progress() {
                                public void onProgress(final int percent, final String message) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            pd.setProgress(Math.max(20, Math.min(80, 20 + percent * 6 / 10)));
                                            pd.setMessage("用 BriNeting 基座微调采样 · " + message);
                                        }
                                    });
                                }
                            });
                        }
                        if (model == null) throw first;
                    }
                    pd.setProgress(84);
                    pd.setMessage(neural ? "正在写神经模型与采样…" : "正在写采样声库…");
                    if (neural && model != null) {
                        model.save(neuralFile(vm));
                        vm.neural = model;
                        vm.neuralEmbedded = true;
                        vm.neuralFromUniversal = false;
                        vm.neuralEnabled = true;
                    } else {
                        vm.neural = null;
                        vm.neuralEmbedded = false;
                        vm.neuralFromUniversal = false;
                        vm.neuralEnabled = false;
                        vm.neuralBank = false;
                    }

                    // 2) rewrite the pack with art + raw takes + neural weights embedded
                    File dir = packStore.directory();
                    if (!dir.isDirectory()) dir.mkdirs();
                    File target = new File(dir, vm.id + VoicePack.EXT);
                    VoicePack.write(target, vm, "本声库的采样与立绘由用户提供，版权归用户所有；"
                            + "BriVoice 只在本机分析与训练，不上传任何音频。");
                    if (target.length() > BANK_MAX_BYTES) {
                        long mb = target.length() / (1024L * 1024L);
                        target.delete();
                        throw new IllegalStateException("声库超过 800 MB 上限（" + mb
                                + " MB）：减少采样数量，或改用采样合成");
                    }
                    VoicePack.Result check = VoicePack.load(target);
                    if (check.error != null || check.model == null) throw new IllegalStateException(check.error == null ? "声库包校验失败" : check.error);
                    result = check.model;
                    pd.setProgress(94);
                    detail = "采样 " + rep.takes + " 个 · 符号 " + rep.symbols + " 个 · 频段 " + rep.bands + " 条"
                            + (rep.f0Max > 0 ? " · 音域 " + Math.round(rep.f0Min) + "–" + Math.round(rep.f0Max) + " Hz" : "")
                            + " · 合成 " + (neural ? "神经" : "采样")
                            + (model != null ? " · 参数 " + model.paramCount() : "")
                            + " · " + (target.length() / 1024) + " KB"
                            + (rep.sampleNet != null
                                ? "\n采样神经模型：" + rep.sampleNetFrames + " 帧 · "
                                  + rep.sampleNetParams + " 参数（基座 + 你的采样微调）"
                                : (rep.sampleNetError != null ? "\n采样神经模型：训练失败（" + rep.sampleNetError + "）" : ""));
                    if (rep.skipped.length() > 0) detail += "\n跳过的文件：" + rep.skipped;
                    if (tmp.exists()) tmp.delete();
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                    android.util.Log.e("V4Tune", "custom voicebank failed", t);
                }
                final String fe = err;
                final String fd = detail;
                final com.dsh.v4tune.synth.VoiceModel fv = result;
                ui.post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (fe != null || fv == null) {
                            toast("自制声库失败：" + fe);
                            status("自制声库失败：" + fe);
                            return;
                        }
                        SingerLibrary.upsertUserPack(fv);
                        saveNeuralEnabled(fv, fv.neuralEnabled);
                        beginEdit();
                        currentVoice = fv;
                        attachNeural(currentVoice);
                        applyVoiceToPart(currentVoice);
                        cacheL = null; cacheR = null; cacheKey = 0L; cacheTotal = -1;
                        commitEdit();
                        refreshAll();
                        if (fv.characterPng != null) applyPackCharacter(fv.characterPng);
                        toast("自制声库完成：" + fd);
                        status("已安装自制声库 " + fv.name + " · " + fd);
                        AlertDialog.Builder fin = new AlertDialog.Builder(MainActivity.this)
                                .setTitle("自制声库已生成")
                                .setMessage(fd + "\n\n已包含：立绘" + (fv.characterPng != null ? "✓" : "—")
                                        + " · 采样 " + fv.sampleWavs.size() + " 个 · 包内神经参数"
                                        + (fv.neuralEmbedded ? "✓" : "—")
                                        + " · 采样神经模型" + (fv.sampleNet != null ? "（" + fv.sampleNet.paramCount() + " 参数）" : "—")
                                        + " · 录音记录" + (fv.recordJson != null ? "✓" : "—")
                                        + (fv.neuralEnabled
                                            ? "\n\n下一步可以把这个声库并入 BriNeting 基座训练（会重新训练共享基座，约几分钟）。"
                                            : "\n\n采样合成不训练神经参数，随时可以在管理页换成神经合成重建。"))
                                .setNegativeButton(fv.neuralEnabled ? "稍后" : "好", null);
                        if (fv.neuralEnabled) {
                            fin.setPositiveButton("并入基座训练", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { trainUniversalNeural(); }
                            });
                        }
                        fin.show();
                    }
                });
            }
        }, "custom-voicebank").start();
    }

    /** write the artwork embedded in a voicebank into the private slot and show it */
    private void applyPackCharacter(byte[] png) {
        if (png == null || png.length == 0) return;
        try {
            File f = com.dsh.v4tune.ui.CharacterArt.savedFile(this);
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(png);
            fo.flush();
            fo.close();
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bm == null) return;
            charBitmap = bm;
            charView.setImageBitmap(bm);
            charView.setVisibility(View.VISIBLE);
            updateCharacterOverlay();
        } catch (Throwable t) {
            android.util.Log.w("V4Tune", "pack character apply failed: " + t);
        }
    }

    private static byte[] readAllStream(InputStream in, int limit) throws java.io.IOException {
        if (in == null) throw new java.io.IOException("读不到文件");
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) throw new java.io.IOException("文件太大");
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** every extension the reader understands; used to keep project names free of them */
    private static final String[] PROJECT_EXTS = {".vsqx", ".vsq", ".ustx", ".ust", ".mid", ".midi", ".svip3"};

    /** "primary:BriVoice_root/projects/song.vsqx" -> "song": old builds stored SAF document ids as names */
    private static String displayName(String raw) {
        String s = raw == null ? "" : raw.trim();
        int k = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        if (k >= 0) s = s.substring(k + 1);
        return stripProjectExt(s);
    }

    /** a clean project name from any uri (SAF document ids are "primary:folder/name.vsqx") */
    private static String projectNameOf(Uri uri) {
        if (uri == null) return "未命名";
        String s = null;
        try { s = uri.getLastPathSegment(); } catch (Throwable ignored) { }
        if (s == null) return "未命名";
        try { s = Uri.decode(s); } catch (Throwable ignored) { }
        return displayName(s);
    }

    /** "song.vsqx.vsqx" -> "song": older builds appended .vsqx to a name that already had one */
    private static String stripProjectExt(String name) {
        String n = name == null ? "" : name.trim();
        boolean cut = true;
        while (cut) {
            cut = false;
            String low = n.toLowerCase(java.util.Locale.US);
            for (String ext : PROJECT_EXTS) {
                if (low.endsWith(ext) && n.length() > ext.length()) {
                    n = n.substring(0, n.length() - ext.length());
                    cut = true;
                    break;
                }
            }
        }
        n = n.trim();
        return n.isEmpty() ? "未命名" : n;
    }

    /** the native project file for this name, inside BriVoice_root/projects */
    private File localProjectFile(String base) {
        File dir = Storage.projects(this);
        if (dir != null && !dir.exists()) dir.mkdirs();
        return new File(dir, ProjectIO.sanitize(stripProjectExt(base)) + ".vsqx");
    }

    private String adoptedPathFor(String sourceKey) {
        if (sourceKey == null || sourceKey.isEmpty()) return null;
        try {
            String raw = getSharedPreferences("brivoice", MODE_PRIVATE).getString(IMPORT_MAP_PREF, "");
            if (raw == null) return null;
            for (String line : raw.split("\n")) {
                int t = line.indexOf('\t');
                if (t > 0 && line.substring(0, t).equals(sourceKey)) return line.substring(t + 1);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private void rememberAdopted(String sourceKey, File f) {
        if (sourceKey == null || sourceKey.isEmpty() || f == null) return;
        try {
            String old = getSharedPreferences("brivoice", MODE_PRIVATE).getString(IMPORT_MAP_PREF, "");
            StringBuilder sb = new StringBuilder();
            sb.append(sourceKey).append('\t').append(f.getAbsolutePath());
            int kept = 1;
            if (old != null) {
                for (String line : old.split("\n")) {
                    int t = line.indexOf('\t');
                    if (t <= 0) continue;
                    if (line.substring(0, t).equals(sourceKey)) continue;
                    if (kept >= 12) continue;
                    sb.append('\n').append(line);
                    kept++;
                }
            }
            getSharedPreferences("brivoice", MODE_PRIVATE).edit().putString(IMPORT_MAP_PREF, sb.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** the local .vsqx that an imported (lossy-format) source is copied into */
    private File importedCopyFile(Uri source, String base) {
        String key = source == null ? "" : source.toString();
        if (!key.isEmpty()) {
            String known = adoptedPathFor(key);
            if (known != null) {
                File k = new File(known);
                if (k.isFile()) return k;
            }
        }
        File dir = Storage.projects(this);
        if (dir != null && !dir.exists()) dir.mkdirs();
        String stemName = ProjectIO.sanitize(stripProjectExt(base));
        File f = new File(dir, stemName + ".vsqx");
        if (f.exists()) {
            int n = 2;
            do { f = new File(dir, stemName + "（导入" + n + "）.vsqx"); n++; } while (f.exists() && n < 1000);
        }
        if (!key.isEmpty()) rememberAdopted(key, f);
        return f;
    }

    /** drop one entry from the recent list (the imported source is no longer the thing to reopen) */
    private void forgetRecent(String pathOrUri) {
        if (pathOrUri == null) return;
        try {
            String old = getSharedPreferences("brivoice", MODE_PRIVATE).getString(RECENT_PREF, "");
            if (old == null) return;
            StringBuilder sb = new StringBuilder();
            for (String line : old.split("\n")) {
                if (line.trim().isEmpty()) continue;
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                if (line.substring(0, t).equals(pathOrUri)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            getSharedPreferences("brivoice", MODE_PRIVATE).edit().putString(RECENT_PREF, sb.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** last path segment, cleaned up, used both as the pack entry name and the phoneme hint */
    private static String baseName(Uri uri) {
        return baseName(uri == null ? "" : String.valueOf(uri.getLastPathSegment()));
    }

    private static String baseName(String path) {
        String n = path == null ? "" : path;
        int k = Math.max(n.lastIndexOf('/'), n.lastIndexOf(':'));
        if (k >= 0) n = n.substring(k + 1);
        if (n.trim().isEmpty()) n = "sample.wav";
        return n;
    }

    /** shared sampled model trained on a free UTAU bank; fine-tuned on the user's takes */
    private static com.dsh.v4tune.synth.SampleNet.Model baseSampleNetCache;
    private static boolean baseSampleNetTried;

    private com.dsh.v4tune.synth.SampleNet.Model baseSampleNet() {
        if (!baseSampleNetTried) {
            baseSampleNetTried = true;
            try {
                java.io.InputStream in = getAssets().open("net/base-samplenet.bin");
                try { baseSampleNetCache = com.dsh.v4tune.synth.SampleNet.read(in); }
                finally { in.close(); }
            } catch (Throwable t) {
                baseSampleNetCache = null;
            }
        }
        return baseSampleNetCache;
    }

    private void neuralDialog() {
        ensureUniversalNeural();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(6));

        TextView help = new TextView(this);
        help.setText("① BriNeting 基座：用本机声库已测量的谐波学习共享频谱规律（不读取音频采样）\n"
                + "② 声库类型决定引擎：.v4voice 走传统合成，.v4neural 固定走 BriNeting；传统声库也可以手动启用\n"
                + "③ 神经声库可以调声线参数（气息、张力、明亮、鼻音、吐字、颤音、DYN/PIT 响应）。\n"
                + "④ 多声库融合：勾选 2 个以上声库，训练一个更宽的神经声库（约 2 倍参数）并安装成新声库。\n"
                + "⑤ 自制声库：用你自己的 WAV 采样训练专属声库（工具 → 自制声库），包内带立绘/采样/神经参数，训练完可一键并入基座。");
        help.setTextColor(Theme.TEXT_DIM);
        help.setTextSize(12f);
        box.addView(help);

        neuralDialogStatus = new TextView(this);
        neuralDialogStatus.setTextSize(13f);
        neuralDialogStatus.setPadding(dp(2), dp(8), dp(2), dp(8));
        box.addView(neuralDialogStatus);

        LinearLayout row1 = row();
        Button bUniv = small("训练 BriNeting 基座");
        Button bFine = small("微调当前歌手");
        row1.addView(bUniv);
        row1.addView(bFine);
        box.addView(row1);

        LinearLayout rowF = row();
        Button bFuse = small("多声库融合（2 个以上声库）");
        rowF.addView(bFuse);
        box.addView(rowF);

        LinearLayout row2 = row();
        Button bDelCur = small("删除当前歌手模型");
        Button bDelUniv = small("删除 BriNeting 基座");
        row2.addView(bDelCur);
        row2.addView(bDelUniv);
        box.addView(row2);

        LinearLayout row3 = row();
        Button bTune = small("声线参数…");
        row3.addView(bTune);
        box.addView(row3);

        final CheckBox enable = new CheckBox(this);
        neuralChk = enable;
        enable.setText("启用神经合成（当前歌手）");
        enable.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (currentVoice == null) return;
                if (on && currentVoice.neural == null) {
                    NeuralSpectrum u = ensureUniversalNeural();
                    if (u != null) { currentVoice.neural = u; currentVoice.neuralFromUniversal = true; }
                }
                currentVoice.neuralEnabled = on && currentVoice.neural != null;
                saveNeuralEnabled(currentVoice, currentVoice.neuralEnabled);
                clearRenderCache();
            }
        });
        box.addView(enable);

        bUniv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { trainUniversalNeural(); }
        });
        bFine.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { fineTuneNeural(); }
        });
        bFuse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { fusionDialog(); }
        });
        bDelCur.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { deleteCurrentNeural(); }
        });
        bDelUniv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { deleteUniversalNeural(); }
        });
        bTune.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { neuralParamsDialog(); }
        });

        refreshNeuralWidgets();
        new AlertDialog.Builder(this)
                .setTitle("BriNeting 神经声学模型（v11.0）")
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    /** XStudio-style neural voicebank controls; saved per singer and applied at render time */
    private void neuralParamsDialog() {
        final VoiceModel vm = currentVoice;
        if (vm == null) { toast("先选择一个声库"); return; }
        if (vm.neuralParams == null) vm.neuralParams = new VoiceModel.NeuralParams();
        final VoiceModel.NeuralParams np = vm.neuralParams;
        final double[] tmp = {np.breath, np.tension, np.brightness, np.nasality,
                np.clarity, np.vibrato, np.dynamics, np.pitch, np.mix};
        final String[] names = {"气息", "张力", "明亮", "鼻音", "吐字清晰度", "颤音深度",
                "DYN 响应", "PIT/PBS 响应", "神经混合（其余用声库实测包络）"};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(4));
        TextView tip = new TextView(this);
        tip.setText("这些参数在 BriNeting 引擎里额外强化对应表现；0.5 为中性。");
        tip.setTextColor(Theme.TEXT_DIM);
        tip.setTextSize(11.5f);
        box.addView(tip);

        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setPadding(0, dp(6), 0, 0);
            TextView name = new TextView(this);
            name.setText(names[i]);
            name.setTextColor(Theme.TEXT);
            name.setTextSize(13.5f);
            head.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final TextView val = new TextView(this);
            val.setText(String.format(java.util.Locale.US, "%.0f%%", tmp[i] * 100));
            val.setTextColor(Theme.ACCENT);
            val.setTextSize(12.5f);
            head.addView(val);
            box.addView(head);
            android.widget.SeekBar bar = new android.widget.SeekBar(this);
            bar.setMax(100);
            bar.setProgress((int) Math.round(tmp[i] * 100));
            bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                    tmp[idx] = progress / 100.0;
                    val.setText(progress + "%");
                }
                public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                public void onStopTrackingTouch(android.widget.SeekBar sb) { }
            });
            box.addView(bar);
        }

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("声线参数 · " + singerName(vm))
                .setView(sc)
                .setPositiveButton("保存并应用", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        np.breath = tmp[0]; np.tension = tmp[1]; np.brightness = tmp[2];
                        np.nasality = tmp[3]; np.clarity = tmp[4]; np.vibrato = tmp[5];
                        np.dynamics = tmp[6]; np.pitch = tmp[7]; np.mix = tmp[8];
                        np.clampAll();
                        saveNeuralPrefs(vm);
                        clearRenderCache();
                        refreshNeuralWidgets();
                        toast("声线参数已应用");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String latencyInfoText() {
        if (latencyMeasuredMs >= 0) {
            String src = (latencySource == null || latencySource.isEmpty()) ? "自动测量" : latencySource;
            return "输出延迟：" + latencyMeasuredMs + " ms（" + src + "）。\n"
                    + "播放指针会按实时时间戳继续校正；换耳机或蓝牙后可在下面重新测量。";
        }
        return "尚未测得输出延迟。播放指针会尝试用实时时间戳校正；"
                + "点下面的按钮会播放约 2 秒接近无声的测试音，自动测算并应用。";
    }

    private void saveLatency(int ms, String source) {
        // No consumer device has seconds of output latency; a larger reading means the timestamp
        // pair was unreliable and must not be allowed to push the cursor that far behind.
        latencyMeasuredMs = Math.min(600, Math.max(0, ms));
        latencySource = source == null ? "" : source;
        engine.outputLatencyMs = latencyMeasuredMs;
        engine.autoLatency = latencyAuto && latencyMeasuredMs >= 0;
        android.content.SharedPreferences p = getSharedPreferences("brivoice", MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = p.edit()
                .putInt("latencyMeasuredMs", latencyMeasuredMs)
                .putString("latencySource", latencySource)
                .putLong("latencyMeasuredAt", System.currentTimeMillis());
        // Automatic measurement now owns the compensation: keep any manual value out of the sum,
        // otherwise the two corrections stack and the cursor can trail by seconds.
        if (latencyAuto && cursorOffsetMs != 0) {
            cursorOffsetMs = 0;
            engine.cursorOffsetMs = 0;
            ed.putInt("cursorOffsetMs", 0);
        }
        ed.putBoolean("latencyAutoMigrated", true);
        ed.apply();
    }

    /** quiet 2-second probe at start-up; result is only used when live timestamps are unavailable */
    private void maybeAutoMeasureLatency() {
        if (!latencyAuto || latencyMeasuring || isFinishing() || engine.isPlaying()) return;
        latencyMeasuring = true;
        new Thread(new Runnable() {
            public void run() {
                final com.dsh.v4tune.audio.LatencyCalibrator.Result res =
                        com.dsh.v4tune.audio.LatencyCalibrator.measure(MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        latencyMeasuring = false;
                        if (isFinishing()) return;
                        if (res == null || !res.reliable) {
                            maybeShowLatencyGuide();
                            return;
                        }
                        saveLatency(res.ms, res.source);
                        if (!getSharedPreferences("brivoice", MODE_PRIVATE).getBoolean("latencyTold", false)) {
                            getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                                    .putBoolean("latencyTold", true).apply();
                            toast("已自动测量音频延迟：" + res.ms + " ms");
                        }
                    }
                });
            }
        }, "latency-measure").start();
    }

    /** one-time fallback guide for devices that cannot report an AudioTrack presentation time */
    private void maybeShowLatencyGuide() {
        if (getSharedPreferences("brivoice", MODE_PRIVATE)
                .getBoolean("latencyGuideTold", false)) return;
        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                .putBoolean("latencyGuideTold", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("音频延迟校准")
                .setMessage("这台设备没有提供可用的音频时间戳，暂时无法自动测量。\n\n"
                        + "可以点「播放」听示例，然后在设置里拖动「指针延迟补偿」，"
                        + "直到指针和听到的声音对齐。模拟器和蓝牙耳机通常需要更大的补偿值。")
                .setPositiveButton("打开设置", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { settingsDialog(); }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /** same probe, but visible and re-applied while the settings dialog is open */
    private void measureLatencyNow(final TextView info) {
        if (engine.isPlaying()) {
            toast("请先停止播放再测量");
            return;
        }
        if (latencyMeasuring) return;
        latencyMeasuring = true;
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在测量音频输出延迟…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final com.dsh.v4tune.audio.LatencyCalibrator.Result res =
                        com.dsh.v4tune.audio.LatencyCalibrator.measure(MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        latencyMeasuring = false;
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (res != null && res.reliable) {
                            saveLatency(res.ms, res.source);
                            if (info != null) info.setText(latencyInfoText());
                            toast("测得输出延迟：" + res.ms + " ms，已应用");
                        } else {
                            toast("当前设备不支持自动测量，请用下面的滑块手动微调");
                        }
                    }
                });
            }
        }, "latency-measure-now").start();
    }

    private void settingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));

        final CheckBox lock = new CheckBox(this);
        lock.setText(L.t("set.phoneme.lock"));
        lock.setChecked(phonemeLock);
        box.addView(lock);

        // web-style synthesis method + colour presets
        box.addView(label("合成方式"));
        final android.widget.Spinner synth = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> synthAd = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"声库谐波（按声库实测音色，默认）", "网页快速（保留声库频谱色彩）"});
        synthAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        synth.setAdapter(synthAd);
        synth.setSelection(webSynth ? 1 : 0);
        box.addView(synth);

        box.addView(label("音色（网页预设）"));
        final android.widget.Spinner color = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> colorAd = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"跟随声库", "明亮", "柔和", "低语"});
        colorAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        color.setAdapter(colorAd);
        color.setSelection(Math.max(0, Math.min(3, voiceColor)));
        box.addView(color);

        final CheckBox follow = new CheckBox(this);
        follow.setText("播放时滚动跟随（同步）");
        follow.setChecked(followPlayhead);
        box.addView(follow);

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

        final CheckBox accel = new CheckBox(this);
        accel.setText(L.t("set.accel"));
        accel.setChecked(renderAccel);
        accel.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                renderAccel = on;
                cacheL = null; cacheR = null; cacheKey = 0L; cacheTotal = -1;
                getSharedPreferences("brivoice", MODE_PRIVATE).edit().putBoolean("renderaccel", on).apply();
            }
        });
        box.addView(accel);
        TextView accelDesc = new TextView(this);
        accelDesc.setText(L.t("set.accel.desc"));
        accelDesc.setTextColor(Theme.TEXT_DIM);
        accelDesc.setTextSize(12f);
        accelDesc.setPadding(dp(4), 0, 0, dp(6));
        box.addView(accelDesc);

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

        // autosave: keep a recovery copy while editing
        final CheckBox autoChk = new CheckBox(this);
        autoChk.setText(L.t("set.autosave"));
        autoChk.setChecked(autoSaveEnabled);
        box.addView(autoChk);
        final int[] autoSteps = {15, 30, 60, 120, 300};
        int autoIdx = 2;
        for (int i = 0; i < autoSteps.length; i++) if (autoSteps[i] == autoSaveSec) autoIdx = i;
        final TextView autoLabel = label(L.t("set.autosave.interval", Integer.valueOf(autoSaveSec)));
        box.addView(autoLabel);
        final SeekBar autoBar = new SeekBar(this);
        autoBar.setMax(autoSteps.length - 1);
        autoBar.setProgress(autoIdx);
        autoBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                autoLabel.setText(L.t("set.autosave.interval", Integer.valueOf(autoSteps[Math.max(0, Math.min(autoSteps.length - 1, pr))])));
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(autoBar);
        TextView autoDesc = new TextView(this);
        autoDesc.setText(L.t("set.autosave.desc"));
        autoDesc.setTextColor(Theme.TEXT_DIM);
        autoDesc.setTextSize(12f);
        autoDesc.setPadding(dp(4), 0, 0, dp(6));
        box.addView(autoDesc);

        // optional character artwork in the lower-left corner
        box.addView(label("角色立绘（左下角）"));
        TextView charDesc = new TextView(this);
        charDesc.setText("选一张图片，自动去掉白底；透明 PNG 直接使用。");
        charDesc.setTextColor(Theme.TEXT_DIM);
        charDesc.setTextSize(12f);
        charDesc.setPadding(dp(4), 0, 0, dp(6));
        box.addView(charDesc);
        LinearLayout charBtns = row();
        Button pickChar = small("选择图片…");
        Button clearChar = small("清除立绘");
        charBtns.addView(pickChar);
        charBtns.addView(clearChar);
        box.addView(charBtns);
        final TextView charAlphaLabel = label("透明度 " + Math.round(charAlpha * 100f / 255f) + "%");
        box.addView(charAlphaLabel);
        final SeekBar charAlphaBar = new SeekBar(this);
        charAlphaBar.setMax(255);
        charAlphaBar.setProgress(Math.max(0, Math.min(255, charAlpha)));
        charAlphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                charAlpha = pr;
                charAlphaLabel.setText("透明度 " + Math.round(charAlpha * 100f / 255f) + "%");
                updateCharacterOverlay();
                getSharedPreferences("brivoice", MODE_PRIVATE).edit().putInt("charAlpha", charAlpha).apply();
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(charAlphaBar);
        final TextView charSizeLabel = label("大小 " + Math.round(charSize * 100) + "%");
        box.addView(charSizeLabel);
        final SeekBar charSizeBar = new SeekBar(this);
        charSizeBar.setMax(100);
        charSizeBar.setProgress(Math.max(0, Math.min(100, Math.round((charSize - 0.15f) / 0.60f * 100f))));
        charSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                charSize = 0.15f + pr / 100f * 0.60f;
                charSizeLabel.setText("大小 " + Math.round(charSize * 100) + "%");
                updateCharacterOverlay();
                getSharedPreferences("brivoice", MODE_PRIVATE).edit().putFloat("charSize", charSize).apply();
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(charSizeBar);
        pickChar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickCharacter(); }
        });
        clearChar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearCharacter(); }
        });

        // neural acoustic model: universal prior + optional per-singer fine-tune
        box.addView(label("BriNeting 神经声学模型（v11.0）"));
        neuralChk = null;
        TextView nnDesc = new TextView(this);
        neuralDesc = nnDesc;
        nnDesc.setText(neuralStatusText());
        nnDesc.setTextColor(Theme.TEXT_DIM);
        nnDesc.setTextSize(12f);
        nnDesc.setPadding(dp(4), 0, 0, dp(6));
        box.addView(nnDesc);
        Button nnOpen = small("打开神经声学模型设置…");
        nnOpen.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { neuralDialog(); }
        });
        box.addView(nnOpen);

        // cursor delay compensation: the sound a device emits lags the picture by its output buffer
        final boolean autoOffset = latencyAuto && latencyMeasuredMs >= 0;
        final TextView offLabel = label(L.t("set.offset", Integer.valueOf(cursorOffsetMs)));
        box.addView(offLabel);
        final SeekBar off = new SeekBar(this);
        off.setMax(5000);                       // -2500 .. +2500 ms, 2500 is zero
        off.setProgress(cursorOffsetMs + 2500);
        off.setEnabled(!autoOffset);
        off.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean u) {
                offLabel.setText(L.t("set.offset", Integer.valueOf(pr - 2500)));
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(off);
        TextView offDesc = new TextView(this);
        offDesc.setText(autoOffset
                ? L.t("set.offset.desc") + "\n自动测量已启用：此滑块不会叠加使用，需要手动微调请先取消上面的勾选。"
                : L.t("set.offset.desc"));
        offDesc.setTextColor(Theme.TEXT_DIM);
        offDesc.setTextSize(12f);
        offDesc.setPadding(dp(4), 0, 0, dp(6));
        box.addView(offDesc);

        // v9.3: output latency is measured on start-up and corrected again live from AudioTrack
        // timestamps, so this slider is only a manual trim on top of that.
        final TextView latInfo = new TextView(this);
        latInfo.setText(latencyInfoText());
        latInfo.setTextColor(Theme.TEXT_DIM);
        latInfo.setTextSize(12f);
        latInfo.setPadding(dp(4), dp(4), 0, dp(4));
        box.addView(latInfo);
        final CheckBox latAuto = new CheckBox(this);
        latAuto.setText("启动时自动测量输出延迟");
        latAuto.setChecked(latencyAuto);
        latAuto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                boolean useAuto = on && latencyMeasuredMs >= 0;
                off.setEnabled(!useAuto);
                offDesc.setText(useAuto
                        ? L.t("set.offset.desc") + "\n自动测量已启用：此滑块不会叠加使用。"
                        : L.t("set.offset.desc"));
            }
        });
        box.addView(latAuto);
        Button remeasure = smallButton("重新测量输出延迟…", new View.OnClickListener() {
            public void onClick(View v) { measureLatencyNow(latInfo); }
        });
        LinearLayout.LayoutParams remeasureLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        remeasureLp.setMargins(0, dp(2), 0, dp(8));
        remeasure.setLayoutParams(remeasureLp);
        box.addView(remeasure);

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
                        latencyAuto = latAuto.isChecked();
                        boolean autoUse = latencyAuto && latencyMeasuredMs >= 0;
                        cursorOffsetMs = autoUse ? 0 : off.getProgress() - 2500;
                        engine.cursorOffsetMs = cursorOffsetMs;
                        engine.autoLatency = autoUse;
                        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                                .putInt("cursorOffsetMs", cursorOffsetMs)
                                .putBoolean("latencyAuto", latencyAuto).apply();
                        Renderer.frameSequence = frameSeqChk.isChecked();
                        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                                .putBoolean("frameseq", Renderer.frameSequence).apply();
                        Renderer.measuredNoise = mnoise.isChecked();
                        Renderer.noiseGain = mnGain.getProgress() / 100f;
                        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                                .putBoolean("mnoise", Renderer.measuredNoise)
                                .putInt("mnoiseGain", mnGain.getProgress()).apply();
                        phonemeLock = lock.isChecked();
                        autoSaveEnabled = autoChk.isChecked();
                        autoSaveSec = autoSteps[Math.max(0, Math.min(autoSteps.length - 1, autoBar.getProgress()))];
                        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                                .putBoolean("phonemeLock", phonemeLock)
                                .putBoolean("autosave", autoSaveEnabled)
                                .putInt("autosaveSec", autoSaveSec).apply();
                        scheduleAutoSave();
                        webSynth = synth.getSelectedItemPosition() == 1;
                        voiceColor = Math.max(0, Math.min(3, color.getSelectedItemPosition()));
                        followPlayhead = follow.isChecked();
                        Renderer.webSynth = webSynth;
                        Renderer.voiceColor = voiceColor;
                        getSharedPreferences("brivoice", MODE_PRIVATE).edit()
                                .putBoolean("websynth", webSynth)
                                .putInt("voicecolor", voiceColor)
                                .putBoolean("follow", followPlayhead)
                                .putInt("charAlpha", charAlpha)
                                .putFloat("charSize", charSize).apply();
                        cacheL = null; cacheR = null; cacheKey = 0L; cacheTotal = -1;
                        status(autoSaveEnabled ? ("自动保存已开启，间隔 " + autoSaveSec + " 秒") : "自动保存已关闭");
                        status(webSynth ? "合成方式：网页快速（保留声库频谱色彩）" : "合成方式：声库谐波（按声库实测音色）");
                    }
                })
                .setNegativeButton(L.t("dlg.cancel"), null)
                .show();
    }
    /** the AI panel: endpoint + copyable prompt + reply JSON + optional user-provided cloud save */
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
        url.setSelectAllOnFocus(true);
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
            public void onProgressChanged(SeekBar sb, int pr, boolean u) { st.setText(L.t("ai.strength", pr + "%")); }
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

        // ---- copyable prompt (web-version workflow: use any chat UI) ----
        box.addView(label("要求（自然语言，可选；会写进提示词）"));
        final EditText req = field("", false);
        box.addView(req);
        box.addView(label("提示词（点「复制提示词」，粘贴到任意 AI 对话框）"));
        final EditText promptBox = multi("", 6);
        promptBox.setFocusable(false);
        promptBox.setClickable(false);
        box.addView(promptBox);
        LinearLayout promptRow = row();
        Button copyBtn = small("复制提示词");
        copyBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("BriVoice AI prompt", promptBox.getText().toString()));
                    status("提示词已复制，去 AI 里粘贴吧");
                    toast("已复制提示词");
                } catch (Throwable t) { status("复制失败: " + t.getMessage()); }
            }
        });
        Button refreshBtn = small("刷新提示词");
        promptRow.addView(copyBtn);
        promptRow.addView(refreshBtn);
        box.addView(promptRow);

        // ---- reply JSON / file export ----
        box.addView(label("输出 JSON（把 AI 的答复贴到这里，或直接编辑）"));
        final EditText out = multi(aiLastReply == null ? "" : aiLastReply, 7);
        box.addView(out);
        LinearLayout jsonRow = row();
        Button applyBtn = small("应用 JSON");
        Button exportBtn = small("导出 JSON 文件");
        jsonRow.addView(applyBtn);
        jsonRow.addView(exportBtn);
        box.addView(jsonRow);

        // ---- cloud save against the user's own backend ----
        box.addView(label("云端保存（你自己的后端：POST 保存 / GET 读取，Token 可选）"));
        final EditText cloudUrl = field(aiCfg.cloudUrl, false);
        final EditText cloudToken = field(aiCfg.cloudToken, true);
        cloudUrl.setSelectAllOnFocus(true);
        cloudToken.setSelectAllOnFocus(true);
        box.addView(label("云端地址（完整 URL）"));
        box.addView(cloudUrl);
        box.addView(label("云端 Token（可选，Bearer）"));
        box.addView(cloudToken);
        LinearLayout cloudRow = row();
        Button cloudSave = small("保存到云端");
        Button cloudLoad = small("从云端读取");
        cloudRow.addView(cloudSave);
        cloudRow.addView(cloudLoad);
        box.addView(cloudRow);
        final TextView cloudState = new TextView(this);
        cloudState.setTextColor(Theme.TEXT_DIM);
        cloudState.setTextSize(12f);
        cloudState.setPadding(0, dp(4), 0, dp(4));
        cloudState.setText("云端未连接");
        box.addView(cloudState);

        final Runnable fillPrompt = new Runnable() {
            public void run() {
                Part p = currentPart();
                if (p == null) { promptBox.setText("（当前没有可调音的片段）"); return; }
                promptBox.setText(com.dsh.v4tune.ai.AiTuner.fullPrompt(song, p, trackIndex, req.getText().toString()));
            }
        };
        fillPrompt.run();
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { fillPrompt.run(); toast("提示词已刷新"); }
        });

        copyBtn.setTag(Boolean.TRUE);
        applyBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Part p = currentPart();
                    if (p == null) throw new IllegalArgumentException("没有可调音的片段");
                    com.dsh.v4tune.ai.Tuning t = com.dsh.v4tune.ai.AiTuner.parseTuning(out.getText().toString(), p.dur + p.pos);
                    int n = applyTuning(t, str.getProgress() / 100f);
                    aiLastReply = out.getText().toString();
                    status(L.t("ai.applied.full", t.summary(), n));
                    toast("已应用 JSON：" + n + " 处调整");
                } catch (Throwable t) {
                    status("应用 JSON 失败: " + t.getMessage());
                    toast("JSON 不可用: " + t.getMessage());
                }
            }
        });
        exportBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    File dir = new File(Storage.exports(MainActivity.this), "ai");
                    if (!dir.exists()) dir.mkdirs();
                    String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
                    File f = new File(dir, "ai-" + stamp + ".json");
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    fo.write(out.getText().toString().getBytes("UTF-8"));
                    fo.flush(); fo.close();
                    status("已导出 " + f.getAbsolutePath());
                    toast("已导出 JSON 文件");
                } catch (Throwable t) { status("导出失败: " + t.getMessage()); }
            }
        });

        cloudSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String cu = cloudUrl.getText().toString().trim();
                final String ct = cloudToken.getText().toString().trim();
                aiCfg.cloudUrl = cu; aiCfg.cloudToken = ct; aiCfg.save(MainActivity.this);
                status("正在保存到云端...");
                cloudState.setText("正在保存...");
                new Thread(new Runnable() {
                    public void run() {
                        String err = null;
                        try { com.dsh.v4tune.ai.AiCloud.save(cu, ct, projectName, song); }
                        catch (Throwable t) { err = String.valueOf(t.getMessage()); }
                        final String fe = err;
                        ui.post(new Runnable() {
                            public void run() {
                                if (fe != null) { status("云端保存失败: " + fe); cloudState.setText("保存失败: " + fe); }
                                else { status("已保存到云端：" + projectName); cloudState.setText("已保存：" + projectName); toast("云端保存成功"); }
                            }
                        });
                    }
                }, "brivoice-cloud").start();
            }
        });
        cloudLoad.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String cu = cloudUrl.getText().toString().trim();
                final String ct = cloudToken.getText().toString().trim();
                aiCfg.cloudUrl = cu; aiCfg.cloudToken = ct; aiCfg.save(MainActivity.this);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("从云端读取")
                        .setMessage("会替换当前工程（可以先撤销）。继续？")
                        .setPositiveButton("读取", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                status("正在从云端读取...");
                                new Thread(new Runnable() {
                                    public void run() {
                                        String err = null;
                                        Song loaded = null;
                                        try { loaded = com.dsh.v4tune.ai.AiCloud.load(cu, ct); }
                                        catch (Throwable t) { err = String.valueOf(t.getMessage()); }
                                        final String fe = err;
                                        final Song fs = loaded;
                                        ui.post(new Runnable() {
                                            public void run() {
                                                if (fe != null) { status("云端读取失败: " + fe); cloudState.setText("读取失败: " + fe); return; }
                                                beginEdit();
                                                song = fs;
                                                commitEdit();
                                                currentUri = null;
                                                projectName = "云端工程";
                                                trackIndex = 0;
                                                chooseInitialVoice();
                                                refreshAll();
                                                scrollToContent();
                                                status("已从云端读取 " + countNotes() + " 音符");
                                                cloudState.setText("已读取：" + countNotes() + " 音符");
                                                toast("云端工程已载入");
                                            }
                                        });
                                    }
                                }, "brivoice-cloud").start();
                            }
                        })
                        .setNegativeButton(L.t("dlg.cancel"), null)
                        .show();
            }
        });

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
                aiCfg.cloudUrl = cloudUrl.getText().toString().trim();
                aiCfg.cloudToken = cloudToken.getText().toString().trim();
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
                aiCfg.cloudUrl = cloudUrl.getText().toString().trim();
                aiCfg.cloudToken = cloudToken.getText().toString().trim();
                aiCfg.save(MainActivity.this);
                runAiTune(req.getText().toString());
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

    private EditText multi(String v, int lines) {
        EditText e = new EditText(this);
        e.setText(v == null ? "" : v);
        e.setTextColor(Theme.TEXT);
        e.setTextSize(12.5f);
        e.setSingleLine(false);
        e.setMinLines(lines);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        return e;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, dp(2));
        return r;
    }

    private Button small(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12f);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
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
        sb.append("\n").append(L.t("about.line.net.title")).append("\n");
        sb.append(L.t("about.line.net.update")).append("\n");
        sb.append(L.t("about.line.net.ai")).append("\n");
        sb.append(L.t("about.line.net.offline")).append("\n");
        sb.append("\n").append(L.t("about.line.license")).append("\n");
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
        // inside a project, "back" means back to the title screen, not out of the app
        if (startScreen == null || !startScreen.isShowing()) { showStartScreen(); return; }
        super.onBackPressed();
    }
}
