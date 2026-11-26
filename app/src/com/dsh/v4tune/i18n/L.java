package com.dsh.v4tune.i18n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BriVoice trilingual string layer: Chinese (ZH), English (EN), Japanese (JA).
 *
 * <p>Pure Java on purpose: this class imports nothing outside {@code java.util}, so it
 * compiles and runs on the desktop JVM (see {@code tools/i18n_selftest/I18nSelftest.java})
 * as well as inside the APK. The UI calls {@code L.t("toolbar.open")} instead of hard
 * coded literals and {@link #setLang(int)} switches the whole app at once.
 *
 * <p>Keys are lowercase, dot separated, and grouped by the place they appear:
 * {@code app.*}, {@code toolbar.*}, {@code transport.*}, {@code param.*}, {@code status.*},
 * {@code dlg.*}, {@code about.line.*}, {@code menu.*}, {@code pack.info.*}, {@code toast.*}.
 *
 * <p>Rules kept by the whole table (enforced by the desktop self test):
 * <ul>
 *   <li>every key exists in all three languages, non-empty, no leading/trailing whitespace;</li>
 *   <li>the placeholder set of a key is identical in ZH/EN/JA;</li>
 *   <li>an unknown key returns the key itself, so a missing translation is visible in the UI
 *       instead of crashing it.</li>
 * </ul>
 *
 * <p>Language choice is not persisted here: the activity should keep it in
 * SharedPreferences and call {@code L.setLang(saved)} in {@code onCreate}.
 *
 * <p>Not thread safe by design of Android UI, but {@link #setLang(int)} publishes through a
 * volatile field so background render threads always read a consistent table.
 */
public final class L {

    /** Language ids, also the index used by {@link #languageNames()}. */
    public static final int ZH = 0, EN = 1, JA = 2;

    /** Number of supported languages. */
    private static final int LANGS = 3;

    /**
     * The whole catalog: {@code {key, ZH, EN, JA}}.
     * Placeholders are {@code %s} / {@code %d} only, and each key uses the same set in all
     * three languages.
     */
    private static final String[][] TABLE = {
        // ---------------------------------------------------------- app
        {"app.name", "BriVoice", "BriVoice", "BriVoice"},
        {"app.untitled", "未命名", "Untitled", "無題"},

        // ------------------------------------------------------ toolbar
        {"toolbar.open", "打开", "Open", "開く"},
        {"toolbar.save", "保存", "Save", "保存"},
        {"toolbar.save.as", "另存为", "Save As", "名前を付けて保存"},
        {"toolbar.export.wav", "导出WAV", "Export WAV", "WAV書き出し"},
        {"toolbar.undo", "撤销", "Undo", "元に戻す"},
        {"toolbar.redo", "重做", "Redo", "やり直す"},
        {"toolbar.demo", "示例", "Demo", "サンプル"},
        {"toolbar.voicebank", "声库", "Voicebanks", "声庫"},
        {"toolbar.about", "关于", "About", "このアプリについて"},

        // ---------------------------------------------------- transport
        {"transport.play", "▶ 播放", "▶ Play", "▶ 再生"},
        {"transport.stop", "■ 停止", "■ Stop", "■ 停止"},
        {"transport.busy", "…", "…", "…"},
        {"transport.preview.part", "试听本段", "Audition Part", "この部分を試聴"},
        {"transport.singer", "歌手", "Singer", "歌手"},
        {"transport.snap", "吸附 1/%d", "Snap 1/%d", "スナップ 1/%d"},
        {"transport.snap.off", "吸附 关", "Snap Off", "スナップ オフ"},
        {"transport.tempo", "速度", "Tempo", "テンポ"},
        {"transport.lyric", "歌词", "Lyric", "歌詞"},

        // ------------------------------------- parameters (ParamView ids)
        {"param.dyn", "力度", "Dynamics", "ダイナミクス"},
        {"param.bre", "气声", "Breathiness", "ブレシネス"},
        {"param.bri", "明亮度", "Brightness", "ブライトネス"},
        {"param.cle", "清晰度", "Clearness", "クリアネス"},
        {"param.ope", "开口度", "Openness", "オープニング"},
        {"param.gen", "性别", "Gender", "ジェンダー"},
        {"param.por", "滑音", "Portamento", "ポルタメント"},
        {"param.pit", "音高弯曲", "Pitch Bend", "ピッチベンド"},
        {"param.pbs", "弯曲灵敏度", "Pitch Bend Sensitivity", "ピッチベンド感度"},
        {"param.vib", "颤音", "Vibrato", "ビブラート"},
        {"param.xsy", "交叉合成", "Cross Synthesis", "クロスシンセシス"},
        {"param.gwl", "嘶吼", "Growl", "グロウル"},

        // ------------------------------------------------------- status
        {"status.dict.loaded", "拼音词典已载入", "Pinyin dictionary loaded", "ピンイン辞書を読み込みました"},
        {"status.rendering", "正在渲染 %s（完成后自动播放）", "Rendering %s (plays when done)", "レンダリング中 %s（完了後に再生）"},
        {"status.no.voice", "BriVoice · 尚未安装声库 —— 点「声库 → 导入声库文件」载入你自己的 .v4voice 声库", "BriVoice · No voicebank installed — tap \"Voicebanks → Import Voicebank File\" to load your own .v4voice voicebank", "BriVoice · 声庫が未インストールです —— 「声庫 → 声庫ファイルを読み込む」から .v4voice 声庫を読み込んでください"},
        {"status.packs.ready", "BriVoice · 已安装 %d 个声库：%s", "BriVoice · %d voicebanks installed: %s", "BriVoice · 声庫 %d 個をインストール済み：%s"},
        {"status.need.voice", "请先安装声库", "Install a voicebank first", "先に声庫をインストールしてください"},
        {"status.param", "参数: %s", "Parameter: %s", "パラメータ: %s"},
        {"status.note", "音符 %s  key=%d  pos=%d  dur=%d", "Note %s  key=%d  pos=%d  dur=%d", "音符 %s  key=%d  pos=%d  dur=%d"},
        {"status.edited", "编辑: %s", "Edited: %s", "編集: %s"},
        {"status.playhead", "播放位置: %s", "Playhead: %s", "再生位置: %s"},
        {"status.undo.empty", "没有可撤销的操作", "Nothing to undo", "元に戻せる操作がありません"},
        {"status.undo", "已撤销（可重做 %d 步）", "Undone (%d redo steps available)", "元に戻しました（やり直し %d 回）"},
        {"status.redo.empty", "没有可重做的操作", "Nothing to redo", "やり直せる操作がありません"},
        {"status.redo", "已重做（可撤销 %d 步）", "Redone (%d undo steps available)", "やり直しました（元に戻す %d 回）"},
        {"status.snap.off", "网格吸附已关闭", "Grid snap off", "グリッドスナップをオフにしました"},
        {"status.snap", "网格吸附: %d 分音符", "Grid snap: 1/%d note", "グリッドスナップ: %d 分音符"},
        {"status.no.note", "请先选中一个音符", "Select a note first", "先に音符を選択してください"},
        {"status.lyric", "歌词 = %s  →  音素 %s", "Lyric = %s  →  Phonemes %s", "歌詞 = %s  →  音素 %s"},
        {"status.singer", "当前歌姬: %s", "Current singer: %s", "現在の歌手: %s"},
        {"status.singer.desc", "当前歌姬: %s  ·  %s", "Current singer: %s  ·  %s", "現在の歌手: %s  ·  %s"},
        {"status.migrated", "已把 %d 个旧声库迁移到 %s", "Moved %d old voicebanks to %s", "旧声庫 %d 個を %s に移動しました"},
        {"status.packs.loaded", "已载入 %d 个自装声库，共 %d 位歌姬", "Loaded %d installed voicebanks, %d singers in total", "インストール済み声庫 %d 個を読み込みました（歌手は合計 %d 人）"},
        {"status.pack.deleted", "已删除声库: %s", "Voicebank deleted: %s", "声庫を削除しました: %s"},
        {"status.delete.failed", "删除失败", "Delete failed", "削除に失敗しました"},
        {"status.tempo", "速度 = %s BPM", "Tempo = %s BPM", "テンポ = %s BPM"},
        {"status.rendering.all", "正在渲染整首…", "Rendering the whole song…", "曲全体をレンダリング中…"},
        {"status.render.done", "渲染完成，从 %s 开始播放", "Render complete, playing from %s", "レンダリング完了、%s から再生します"},
        {"status.play.done", "播放结束", "Playback finished", "再生が終了しました"},
        {"status.play.error", "播放错误: %s", "Playback error: %s", "再生エラー: %s"},
        {"status.render.fail", "渲染失败: %s", "Render failed: %s", "レンダリング失敗: %s"},
        {"status.part.empty", "当前片段没有音符", "This part has no notes", "このパートに音符がありません"},
        {"status.rendering.part", "渲染当前片段…", "Rendering this part…", "このパートをレンダリング中…"},
        {"status.preview.done", "试听结束", "Audition finished", "試聴が終了しました"},
        {"status.stopped", "已停止 · 光标停在 %s", "Stopped · cursor at %s", "停止しました · カーソルは %s"},
        {"status.saved", "已保存", "Saved", "保存しました"},
        {"status.saved.original", "已保存到原文件", "Saved to the original file", "元のファイルに保存しました"},
        {"status.saved.to", "已保存到 %s", "Saved to %s", "%s に保存しました"},
        {"status.save.fail", "保存失败: %s", "Save failed: %s", "保存に失敗しました: %s"},
        {"status.all.files.hint", "请打开「允许管理所有文件」，然后重新从文件管理器打开该文件", "Turn on \"Allow management of all files\", then open the file again from your file manager", "「すべてのファイルの管理を許可」をオンにしてから、ファイルマネージャーでもう一度開いてください"},
        {"status.opened", "已打开 %s（%d 轨 / %d 音符）", "Opened %s (%d tracks / %d notes)", "%s を開きました（%d トラック / %d 音符）"},
        {"status.open.fail", "打开失败: %s", "Open failed: %s", "開くのに失敗しました: %s"},
        {"status.pack.import.fail", "声库导入失败: %s", "Voicebank import failed: %s", "声庫の読み込みに失敗しました: %s"},
        {"status.pack.imported", "已导入声库: %s", "Voicebank imported: %s", "声庫を読み込みました: %s"},
        {"status.rendering.audio", "正在渲染音频…", "Rendering audio…", "オーディオをレンダリング中…"},
        {"status.exported", "已导出 %s", "Exported %s", "%s に書き出しました"},
        {"status.export.fail", "导出失败: %s", "Export failed: %s", "書き出しに失敗しました: %s"},

        {"status.lang.switched", "已切换语言", "Language switched", "言語を切り替えました"},
        {"status.mixer.unavailable", "混音器不可用", "Mixer unavailable", "ミキサーを利用できません"},
        {"status.mixer.reset", "混音设置已重置", "Mixer settings reset", "ミキサー設定をリセットしました"},
        {"status.plugin.added", "已添加插件: %s", "Plugin added: %s", "プラグインを追加しました: %s"},
        {"status.track.current", "当前音轨: %s", "Current track: %s", "現在のトラック: %s"},
        {"status.track.created", "已新建 %s", "Created %s", "%s を作成しました"},
        {"status.track.keep.one", "至少要保留一条音轨", "At least one track must remain", "トラックは最低 1 つ必要です"},
        {"status.track.deleted", "已删除音轨，当前: %s", "Track deleted, current: %s", "トラックを削除しました。現在: %s"},
        {"status.orientation.landscape", "已切换为横屏布局", "Switched to landscape layout", "横向きレイアウトに切り替えました"},
        {"status.orientation.portrait", "已切换为竖屏布局", "Switched to portrait layout", "縦向きレイアウトに切り替えました"},
        {"status.orientation.to.landscape", "切换为横屏", "Switched to landscape", "横向きに切り替えました"},
        {"status.orientation.to.portrait", "切换为竖屏", "Switched to portrait", "縦向きに切り替えました"},
        {"status.folder.no.picker", "无法打开文件夹选择器: %s", "Cannot open the folder picker: %s", "フォルダ選択画面を開けません: %s"},
        {"status.folder.unreadable", "读不到这个文件夹", "Cannot read this folder", "このフォルダを読み取れません"},
        {"status.folder.error", "文件夹导入出错: %s", "Folder import error: %s", "フォルダの読み込みでエラー: %s"},
        {"status.folder.done", "文件夹导入完成：成功 %d 个", "Folder import complete: %d succeeded", "フォルダの読み込みが完了しました：成功 %d 個"},
        {"status.folder.done.bad", "，失败 %d 个", ", %d failed", "、失敗 %d 個"},
        {"status.wav.fail", "WAV 导入失败: %s", "WAV import failed: %s", "WAV の読み込みに失敗しました: %s"},
        {"status.wav.added.atstart", "已导入 %s（%.1f 秒）——播放头在曲尾，已放到开头", "Imported %s (%.1f s) - the cursor was past the end, so it was placed at the start", "%s を読み込みました（%.1f 秒）— カーソルが末尾だったため先頭に配置しました"},
        {"dlg.wav.position", "起始位置（拍）", "Start position (beats)", "開始位置（拍）"},
        {"dlg.wav.position.msg", "导入的音频放在哪里？可以直接填数字，也可以在卷帘下方的音轨里拖动它。", "Where should the audio start? Type a beat, or drag the block in the audio lane under the roll.", "音声の開始位置を指定してください。数値を入力するか、ロール下の音声レーンでドラッグできます。"},
        {"status.stream.start", "正在实时渲染并播放…", "Rendering while playing...", "実時レンダリング再生中…"},
        {"status.stream.fail", "实时渲染失败：%s", "Streaming render failed: %s", "実時レンダリングに失敗：%s"},
        {"status.wav.added", "已导入音频 %s（%.1f 秒）", "Imported audio %s (%.1f s)", "音声を読み込みました %s（%.1f 秒）"},
        {"status.no.clips", "还没有音频片段", "No audio clips yet", "オーディオクリップはまだありません"},
        {"status.clip.moved", "音频片段 %s 移到 %s", "Clip %s moved to %s", "クリップ %s を %s に移動"},
        {"clip.start.title", "起始位置（拍）", "Start position (beats)", "開始位置（拍）"},
        {"clip.align.playhead", "对齐到播放头", "Align to playhead", "カーソルに合わせる"},
        {"clip.align.first", "对齐到第一个音符", "Align to first note", "最初の音符に合わせる"},
        {"status.clip.aligned", "已对齐：%s → %s", "Aligned: %s → %s", "整列しました：%s → %s"},
        {"status.clip.removed", "已删除音频片段", "Audio clip removed", "オーディオクリップを削除しました"},

        // ---------------------------------------------------- dialog text
        {"dlg.mixer.title", "混音器（音量 / 声像 / 静音 / 独奏）", "Mixer (volume / pan / mute / solo)", "ミキサー（音量 / パン / ミュート / ソロ）"},
        {"dlg.plugin.title", "插件（混响 / 合唱 / 压缩）", "Plugins (reverb / chorus / compressor)", "プラグイン（リバーブ / コーラス / コンプレッサー）"},
        {"dlg.track.rename.title", "重命名音轨", "Rename Track", "トラック名を変更"},
        {"dlg.pack.partial.fail.title", "部分声库导入失败", "Some Voicebanks Failed to Import", "一部の声庫を読み込めませんでした"},
        {"dlg.import.fail.msg", "%s\n\n%s", "%s\n\n%s", "%s\n\n%s"},
        {"dlg.import.fail.hint", "如果文件本身没问题，多半是系统给的文件流不完整。请改用「声库 → 从文件夹批量导入」，把 .v4voice 放进一个文件夹再选那个文件夹。", "If the file itself is fine, the stream the system handed over was most likely incomplete. Use \"Voicebanks → Import from Folder\" instead: put the .v4voice files in one folder and pick that folder.", "ファイル自体に問題がなければ、システムから渡されたストリームが不完全な可能性が高いです。「声庫 → フォルダから一括読み込み」を使い、.v4voice を 1 つのフォルダに入れてそのフォルダを選んでください。"},
        {"dlg.import.fail.folder", "从文件夹导入", "Import from Folder", "フォルダから読み込む"},
        {"dlg.wav.fail.title", "WAV 导入失败", "WAV Import Failed", "WAV の読み込み失敗"},
        {"dlg.clips.title", "音频片段列表", "Audio Clip List", "オーディオクリップ一覧"},
        {"dlg.ok", "确定", "OK", "OK"},
        {"dlg.cancel", "取消", "Cancel", "キャンセル"},
        {"dlg.close", "关闭", "Close", "閉じる"},
        {"dlg.good", "好", "OK", "OK"},
        {"dlg.delete", "删除", "Delete", "削除"},
        {"dlg.share", "分享", "Share", "共有"},
        {"dlg.lang.title", "语言 / Language / 言語", "语言 / Language / 言語", "语言 / Language / 言語"},
        {"dlg.lyric.title", "歌词（拼音 / 汉字 / 假名）", "Lyric (pinyin / Hanzi / kana)", "歌詞（ピンイン / 漢字 / かな）"},
        {"dlg.lyric.clear.lock", "清空音素锁定", "Clear phoneme lock", "音素ロックを解除"},
        {"dlg.no.voice.title", "还没有声库", "No voicebank yet", "声庫がありません"},
        {"dlg.no.voice.msg", "BriVoice 需要一个声库才能唱歌。\n\n应用已内置「洛天依 BV」；你也可以点「声库 → 导入声库文件（.v4voice）」导入其它 .v4voice 参数包。\n声库里是谐波包络、共振峰与频段增益等参数化统计数据，不含录音采样。请只导入你自己合法持有的声库数据。", "BriVoice needs a voicebank to sing.\n\nThe app bundles 'Luo Tianyi BV'; you can also import another .v4voice parameter pack via Voicebanks -> Import Voicebank File.\nA pack holds parametric statistics such as harmonic envelopes, formants and band gains - no recorded samples. Please only import voicebank data you legally hold.", "BriVoice で歌うには声庫が必要です。\n\nアプリには「洛天依 BV」が同梱されています。「声庫 → 声庫ファイルを読み込む（.v4voice）」から他の .v4voice パラメータパックも読み込めます。\nパックには倍音包絡・フォルマント・帯域ゲインなどのパラメータ統計が入っており、録音サンプルは含みません。適法に保有する声庫データのみを読み込んでください。"},
        {"dlg.no.voice.import", "去导入", "Import", "読み込む"},
        {"dlg.pack.del.confirm.title", "删除声库", "Delete voicebank", "声庫を削除"},
        {"dlg.pack.del.confirm.msg", "确定删除「%s」吗？\n只会删除这个包文件，不影响内置声库。", "Delete '%s'?\nOnly this pack file is removed; bundled voicebanks are unaffected.", "「%s」を削除しますか？\nこのパックファイルのみ削除され、同梱の声庫には影響しません。"},
        {"status.pack.del.fail", "删除失败", "Delete failed", "削除に失敗しました"},
        {"dlg.pack.delete.title", "删除哪个声库？", "Delete which voicebank?", "どの声庫を削除しますか？"},
        {"dlg.singer.title", "选择歌姬", "Choose a Singer", "歌手を選択"},
        {"dlg.packs.title", "声库管理", "Voicebank Manager", "声庫の管理"},
        {"dlg.pack.help.title", "声库说明", "About Voicebanks", "声庫について"},
        {"dlg.pack.help.msg", "· 应用已内置「洛天依 BV」（参数化统计数据，不含录音采样）。\n· 「导入声库」可载入 .v4voice 参数包：包里是谐波幅度谱（按音高分 3 段）、共振峰与频段增益等统计数据，不含任何录音采样。请只使用你自己合法持有的声库数据。\n· 应用用这些谐波做加法合成，所以音色来自声库本身，而不是内置的共振峰模型。\n· 导入后会在「歌手」列表里标记来源（内置 / 我的声库），可随时删除。\n· 声库只影响音色，不影响工程文件的读写。", "· The app bundles 'Luo Tianyi BV' (parametric statistics, no recorded samples).\n· Import Voicebank loads a .v4voice parameter pack: harmonic magnitude spectra (3 pitch bands), formants and band gains - no recorded samples. Please only use voicebank data you legally hold.\n· The app sings with those harmonics, so the timbre comes from the pack itself rather than a built-in formant model.\n· Imported packs are tagged in the Singer list (bundled / mine) and can be deleted at any time.\n· A voicebank affects timbre only; project files read and write the same way.", "· アプリには「洛天依 BV」が同梱されています（パラメータ統計のみ、録音サンプルなし）。\n· 「声庫を読み込む」では .v4voice パラメータパックを読み込めます。倍音振幅スペクトル（音高で 3 分割）・フォルマント・帯域ゲインなどの統計データのみで、録音サンプルは含みません。適法に保有する声庫データのみを使用してください。\n· これらの倍音で加算合成するため、音色は内蔵フォルマントモデルではなくパック由来です。\n· 読み込んだパックは歌手一覧に出所（同梱／マイ声庫）が表示され、いつでも削除できます。\n· 声庫は音色のみに影響し、プロジェクトファイルの読み書きには影響しません。"},
        {"dlg.delete.pack.title", "删除哪个声库？", "Which voicebank should be deleted?", "どの声庫を削除しますか？"},
        {"dlg.delete.pack.confirm.title", "删除声库", "Delete Voicebank", "声庫を削除"},
        {"dlg.delete.pack.msg", "确定删除「%s」吗？\n只会删除这个包文件，不影响内置歌姬。", "Delete \"%s\"?\nOnly this pack file is removed; the built-in singers are unaffected.", "「%s」を削除しますか？\n削除されるのはこのパックファイルだけで、内蔵歌手には影響しません。"},
        {"dlg.tempo.title", "速度 (BPM)", "Tempo (BPM)", "テンポ (BPM)"},
        {"dlg.open.fail.title", "无法打开这个文件", "Cannot open this file", "このファイルを開けません"},
        {"dlg.open.fail.msg", "%s\n\n支持：.vsqx / .vsq 工程、.mid 导入、.v4voice 声库", "%s\n\nSupported: .vsqx / .vsq projects, .mid import, .v4voice voicebanks", "%s\n\n対応形式：.vsqx / .vsq プロジェクト、.mid の読み込み、.v4voice 声庫"},
        {"dlg.open.fail.grant", "授予文件访问权限", "Grant file access", "ファイルアクセスを許可"},
        {"dlg.import.fail.title", "导入失败", "Import Failed", "読み込み失敗"},
        {"dlg.export.ok.title", "导出成功", "Export Complete", "書き出し完了"},
        {"dlg.export.ok.msg", "文件: %s\n\n时长 %s 秒", "File: %s\n\nDuration: %s s", "ファイル: %s\n\n長さ %s 秒"},
        {"dlg.about.title", "关于", "About", "このアプリについて"},

        // ------------------------------------------- about dialog (lines)
        {"about.line.app", "BriVoice · Android 歌声合成 / 调音", "BriVoice · Android singing synthesis / tuning", "BriVoice · Android 歌声合成／調声"},
        {"about.line.formats", "格式兼容", "Supported formats", "対応フォーマット"},
        {"about.line.fmt4", "· 读取/写入 VOCALOID4 .vsqx（vsq4 架构）", "· Read/write VOCALOID4 .vsqx (vsq4 schema)", "· VOCALOID4 .vsqx の読み書き（vsq4 スキーマ）"},
        {"about.line.fmt3", "· 读取/写入 VOCALOID3 .vsqx（vsq3 架构）", "· Read/write VOCALOID3 .vsqx (vsq3 schema)", "· VOCALOID3 .vsqx の読み書き（vsq3 スキーマ）"},
        {"about.line.fmt2", "· 读取/写入 VOCALOID2 .vsq", "· Read/write VOCALOID2 .vsq", "· VOCALOID2 .vsq の読み書き"},
        {"about.line.wav", "· 导出 16bit 44.1kHz WAV", "· Export 16-bit 44.1 kHz WAV", "· 16bit 44.1kHz WAV の書き出し"},
        {"about.line.packs", "声库", "Voicebanks", "声庫"},
        {"about.line.no.pack", "· 尚未安装", "· None installed", "· 未インストール"},
        {"about.line.pack", "· %s（实测 %d 音素/%d 频段）", "· %s (measured: %d phonemes / %d bands)", "· %s（実測 %d 音素／%d バンド）"},
        {"about.line.pack.legacy", "· %s（旧格式）", "· %s (legacy format)", "· %s（旧形式）"},
        {"about.line.dir", "声库目录：%s", "Voicebank folder: %s", "声庫フォルダ: %s"},
        {"about.line.tip1", "提示：内置声库为参数化统计数据（谐波包络与频段增益），不含任何第三方录音采样。", "Note: the bundled voicebank is parametric statistics (harmonic envelopes and band gains); it contains no third-party recordings.", "ヒント：内蔵声庫はパラメータ統計（倍音包絡と帯域ゲイン）で、第三者の録音サンプルは含みません。"},
        {"about.line.tip2", "如需其它音色，请导入你自己合法持有的声库文件。", "For other voices, import a voicebank you legally own.", "他の音色が必要な場合は、ご自身が適法に保有する声庫を読み込んでください。"},
        {"about.line.net.title", "网络功能", "Network features", "ネットワーク機能"},
        {"about.line.net.update", "· 检查更新：启动时与手动检查会读取 gitee.com 上的更新清单", "· Update check: reads the update manifest on gitee.com at start-up and on demand", "· 更新確認：起動時と任意のタイミングで gitee.com の更新情報を読みます"},
        {"about.line.net.ai", "· AI 助手 / 外部接口（实验，默认关闭）：只有你自己开启后才会联网", "· AI helper / external API (experimental, off by default): connects only after you turn it on", "· AI アシスタント／外部インターフェース（実験的・既定でオフ）：有効にした場合のみ通信します"},
        {"about.line.net.offline", "· 除此之外本应用不联网、不上传任何数据；工程与声库均保存在本机", "· Otherwise the app never connects or uploads anything; projects and voicebanks stay on this device", "· それ以外の通信は行わず、データを送信しません。プロジェクトと声庫は端末内に保存されます"},
        {"about.line.license", "许可证：GPLv3（自 v12.0 起；11.1.2 及更早为 MIT）", "License: GPLv3 (v12.0 onward; MIT up to 11.1.2)", "ライセンス：GPLv3（v12.0 以降。11.1.2 までは MIT）"},

        // -------------------------------------------------- menu entries
        {"dlg.singer.sub", "已安装 %d 个声库 · 点击切换", "%d voicebanks installed · tap to switch", "%d 個の声庫 · タップで切り替え"},
        {"dlg.singer.stats", "实测谐波 %d 音素 / %d 频段", "measured %d phonemes / %d bands", "実測 %d 音素／%d バンド"},
        {"dlg.singer.legacy", "旧格式声库（无实测谐波）", "Legacy pack (no measured harmonics)", "旧形式の声庫（実測倍音なし）"},
        {"dlg.singer.none", "尚未安装声库，请先导入", "No voicebank installed yet", "声庫が未インストールです"},
        {"menu.pack.title", "声库管理", "Voicebanks", "声庫管理"},
        {"menu.pack.sub", "已导入 %d 个外部声库", "%d imported voicebanks", "外部声庫 %d 個"},
        {"menu.tools", "工具", "Tools", "ツール"},
        {"menu.tools.lang", "语言 / Language / 言語", "语言 / Language / 言語", "语言 / Language / 言語"},
        {"menu.tools.mixer", "混音器（音量 / 声像 / 静音 / 独奏）", "Mixer (volume / pan / mute / solo)", "ミキサー（音量 / パン / ミュート / ソロ）"},
        {"menu.tools.plugins", "插件（混响 / 合唱 / 压缩）", "Plugins (reverb / chorus / compressor)", "プラグイン（リバーブ / コーラス / コンプレッサー）"},
        {"menu.tools.import.wav", "导入 WAV 音频到当前音轨", "Import WAV Audio into the Current Track", "現在のトラックに WAV 音声を読み込む"},
        {"menu.tools.clips", "音频片段列表", "Audio Clip List", "オーディオクリップ一覧"},
        {"menu.tools.settings", "设置（音素锁定等）", "Settings (phoneme lock, ...)", "設定（音素ロックなど）"},
{"menu.tools.update", "检查更新", "Check for updates", "更新を確認"},
{"menu.tools.mkbank", "制作声库", "Make a voicebank", "声庫を作成"},
{"menu.tools.tutorial", "教程", "Tutorial", "チュートリアル"},
{"tuto.missing", "教程文件缺失", "Tutorial file missing", "チュートリアルファイルがありません"},
{"vb.title", "制作声库（从本机声库转换）", "Make a voicebank (convert a local bank)", "声庫を作成（端末内の声庫から変換）"},
{"vb.bank.dir", "声库文件夹路径", "Voicebank folder path", "声庫フォルダのパス"},
{"vb.bank.name", "声库名称（留空则用文件夹名）", "Voicebank name (blank = folder name)", "声庫名（空欄ならフォルダ名）"},
{"vb.build", "开始转换", "Start", "変換を開始"},
{"vb.browse", "浏览…", "Browse…", "参照…"},
{"vb.pick", "选择此文件夹", "Use this folder", "このフォルダを選択"},
{"vb.working", "正在分析声库并生成…", "Measuring the bank and building…", "声庫を解析して生成中…"},
{"vb.bad.dir", "找不到该文件夹", "Folder not found", "フォルダが見つかりません"},
{"vb.no.ddb", "该文件夹里没有 .ddb 文件", "No .ddb file in that folder", "そのフォルダに .ddb がありません"},
{"vb.done", "声库已生成", "Voicebank created", "声庫を作成しました"},
{"vb.failed", "生成失败", "Build failed", "生成に失敗しました"},
{"update.checking", "正在检查更新…", "Checking for updates…", "更新を確認中…"},
{"update.title", "软件更新", "Software update", "ソフトウェア更新"},
{"update.available", "发现新版本，是否下载并安装？", "A new version is available. Download and install it?", "新しいバージョンがあります。ダウンロードしてインストールしますか？"},
{"update.current", "已是最新版本", "Already up to date", "最新版です"},
{"update.notes", "更新内容", "What is new", "更新内容"},
{"update.install", "下载并安装", "Download and install", "ダウンロードしてインストール"},
{"update.later", "以后再说", "Later", "後で"},
{"update.downloading", "正在下载…", "Downloading…", "ダウンロード中…"},
{"update.downloaded", "下载完成，请在系统提示中确认安装", "Downloaded - confirm the install in the system dialog", "ダウンロード完了。システムの確認画面でインストールしてください"},
{"update.failed", "更新失败", "Update failed", "更新に失敗しました"},
{"update.no.network", "无法连接更新服务器", "Cannot reach the update server", "更新サーバーに接続できません"},
{"update.unknown.sources", "若系统提示，请允许本应用安装未知来源的应用", "If asked, allow this app to install unknown apps", "確認されたら、このアプリに提供元不明アプリのインストールを許可してください"},
        {"dlg.settings.title", "设置", "Settings", "設定"},
        {"set.frameseq", "帧序列拼接（新合成器）", "Frame-sequence synthesis (new engine)", "フレーム接続合成（新エンジン）"},
        {"set.frameseq.desc", "用声库实测的逐帧谱线控制每个谐波，保留原唱的微变化。关闭则回到旧的单周期波表。", "Each harmonic follows the bank's measured frame curves, keeping the micro-variation of the original take. Off falls back to the old single-period table.", "各谐波をバンクの実測フレームで制御し、元音声の微変化を保ちます。"},
        {"set.mnoise.gain", "实测谱强度：%s", "Measured spectrum level: %s", "実測スペクトル強度：%s"},
        {"set.mnoise", "辅音实测谱（实验）", "Measured consonant spectra (experimental)", "子音の実測スペクトル（実験的）"},
        {"set.mnoise.desc", "用声库实测到的辅音频谱合成擦音（s/x/f 等），听感可能更接近原声但不一定更清楚，默认关闭。", "Sing fricatives with the bank's own measured spectrum. Closer to the source timbre, not necessarily clearer; off by default.", "バンクの実測スペクトルで摩擦音を合成。音色は近くなりますが、必ずしも聞きやすくはなりません。"},
        {"set.offset", "指针延迟补偿：%d ms", "Cursor delay compensation: %d ms", "カーソル遅延補正：%d ms"},
        {"set.offset.desc", "听到的声音比画面慢时，把指针往后拉。模拟器和蓝牙耳机延迟较大，真机通常 0–100 ms。", "Pull the cursor back when the sound lags the picture. Emulators and Bluetooth headsets need more; a phone usually 0-100 ms.", "音が画面より遅れる場合にカーソルを後ろにずらします。エミュレータや Bluetooth では大きめに。"},
        {"set.phoneme.lock", "音素锁定（默认关闭）", "Phoneme lock (off by default)", "音素ロック（既定はオフ）"},
    {"set.accel", "渲染加速（长曲先渲完再播，未改动时直接复用）", "Faster rendering (pre-render long songs, reuse while unchanged)", "高速レンダリング"},
    {"menu.tools.guide", "新手引导（图标指引）", "Beginner guide (icon hints)", "初心者ガイド（アイコン案内）"},
    {"set.autosave", "自动保存", "Autosave", "自動保存"},
    {"set.autosave.interval", "自动保存间隔：%s 秒", "Autosave interval: %s s", "自動保存間隔：%s 秒"},
    {"set.autosave.desc", "只在上次保存之后有改动时写入；未另存的项目存为 BriVoice_root/projects 下的恢复文件。", "Writes only when something changed since the last save; unsaved projects go to a recovery file under BriVoice_root/projects.", "前回保存から変更がある場合のみ書き込みます。"},
    {"set.accel.desc", "关闭后恢复旧行为：从第 1 秒起边渲边播，每次都重新渲染", "Off restores the old behaviour: stream from the first second and re-render every time", "オフで旧動作に戻ります"},
        {"set.phoneme.desc", "关闭时：修改歌词会重新按歌词生成音素。开启后：修改歌词会保留这个音符已有的音素，并把它标记为锁定（写进 .vsqx 的 lock 属性）。", "Off: editing a lyric regenerates the phonemes from the new lyric. On: editing a lyric keeps the note's existing phonemes and marks them locked (written to the .vsqx lock attribute).", "オフ：歌詞を変更すると音素は歌詞から再生成されます。オン：歌詞を変更しても既存の音素を保持し、ロックとして .vsqx の lock 属性に書き出します。"},
        {"set.phoneme.state.on", "音素锁定：已开启", "Phoneme lock: on", "音素ロック：オン"},
        {"set.phoneme.state.off", "音素锁定：已关闭（默认）", "Phoneme lock: off (default)", "音素ロック：オフ（既定）"},
        {"dlg.lyric.ok", "确定", "OK", "OK"},
        {"status.lyric.set", "歌词 = %s  →  音素 %s", "Lyric = %s  →  phonemes %s", "歌詞 = %s  →  音素 %s"},
        {"status.lyric.locked", "歌词 = %s（音素已锁定，保持不变）", "Lyric = %s (phonemes kept: locked)", "歌詞 = %s（音素はロック済みで保持）"},
        {"menu.tools.ai", "AI 助手 / 外部接口（实验）", "AI assistant / external API (experimental)", "AI アシスタント／外部 API（実験）"},
        {"transport.ai", "AI 调音", "AI Tune", "AI 調声"},
        {"ai.ask.title", "想让 AI 怎么调？", "How should the AI tune it?", "AI にどう調整させますか？"},
        {"ai.ask.hint", "用一句话描述你的需求，AI 会按你的要求改参数（曲线 / 音符 / 速度 / 轨道）。", "Describe what you want in one sentence; the AI then edits the parameters (curves / notes / tempo / track).", "一文で要望を書いてください。AI がパラメータ（カーブ／音符／テンポ／トラック）を調整します。"},
        {"ai.ask.placeholder", "例如：主歌轻柔一点，副歌更有力，尾音加一点颤音", "e.g. softer verses, stronger chorus, a little vibrato on the last note", "例：Aメロは柔らかく、サビは力強く、最後の音にビブラート"},
        {"ai.ask.ex1", "整体更有感情：副歌力度大一些，句尾加颤音", "More expression overall: stronger chorus, vibrato at phrase ends", "全体的に表情豊かに：サビは強く、フレーズ末尾にビブラート"},
        {"ai.ask.ex2", "修正音准：把偏高的音拉回来，长音加一点自然的滑音", "Fix the pitch: pull the sharp notes back, add natural portamento to long notes", "音程を修正：高めの音を戻し、長音に自然なポルタメント"},
        {"ai.ask.ex3", "更柔和：降低力度起伏，气息声多一点", "Softer: flatter dynamics, more breathiness", "より柔らかく：強弱の起伏を抑え、息成分を増やす"},
        {"ai.ask.ex4", "更有节奏感：让每个音的起音更干脆，缩短句尾", "More rhythmic: crisper onsets, shorter phrase endings", "よりリズミカルに：アタックを鋭く、フレーズ末尾を短く"},
        {"ai.ask.scope", "AI 可以改：12 个参数曲线（含 DYN/PIT/VIB 等）、已有音符的音高/时长/力度/歌词、速度、当前音轨的音量与声像。改动可撤销。", "The AI may change: the 12 control curves (DYN/PIT/VIB ...), pitch/duration/velocity/lyrics of existing notes, the tempo, and the current track's volume and pan. Every change can be undone.", "AI が変更できるもの：12 本のパラメータカーブ（DYN/PIT/VIB など）、既存音符の音高・長さ・ベロシティ・歌詞、テンポ、現在のトラックの音量とパン。変更は元に戻せます。"},
        {"ai.ask.go", "开始调音", "Tune now", "調整を開始"},
        {"ai.applied.full", "AI 已调整：%s（共 %d 项改动）", "AI tuned %s (%d changes)", "AI が調整しました：%s（変更 %d 件）"},
        {"ai.title", "AI 助手（实验功能）", "AI assistant (experimental)", "AI アシスタント（実験機能）"},
        {"ai.experimental", "实验功能，默认关闭。开启后本机才会联网调用你填写的接口；外部控制接口只监听 127.0.0.1，不对外网开放。", "Experimental and off by default. Only after you switch it on does the app call the endpoint you enter; the external control interface listens on 127.0.0.1 only and is never exposed to the network.", "実験機能です（初期状態はオフ）。オンにした場合のみ、入力したエンドポイントへ通信します。外部制御APIは 127.0.0.1 のみで待ち受け、外部ネットワークには公開しません。"},
        {"ai.enable", "启用 AI 自动调参", "Enable AI auto-tuning", "AI 自動調整を有効にする"},
        {"ai.baseurl", "接口地址（OpenAI 兼容，如 https://api.openai.com/v1）", "Endpoint (OpenAI-compatible, e.g. https://api.openai.com/v1)", "エンドポイント（OpenAI 互換、例：https://api.openai.com/v1）"},
        {"ai.model", "模型名称", "Model name", "モデル名"},
        {"ai.key", "API Key（只保存在本机）", "API key (stored on this device only)", "API キー（端末内のみに保存）"},
        {"ai.strength", "调整幅度：%s（越大改动越明显）", "Edit strength: %s (higher means bigger changes)", "調整の強さ：%s（大きいほど変化が大きい）"},
        {"ai.ext.enable", "启用外部控制接口（实验，仅本机）", "Enable external control API (experimental, local only)", "外部制御 API を有効にする（実験・ローカルのみ）"},
        {"ai.ext.port", "端口", "Port", "ポート"},
        {"ai.ext.running", "外部控制接口已启动：http://127.0.0.1:%d", "External control API running: http://127.0.0.1:%d", "外部制御 API 起動中：http://127.0.0.1:%d"},
        {"ai.ext.stopped", "外部控制接口未启动", "External control API stopped", "外部制御 API は停止中"},
        {"ai.ext.failed", "外部控制接口启动失败：%s", "External control API failed to start: %s", "外部制御 API の起動に失敗：%s"},
        {"ai.save", "保存并应用", "Save and apply", "保存して適用"},
        {"ai.tune", "立即自动调参", "Tune now", "今すぐ自動調整"},
        {"ai.saved", "AI 设置已保存", "AI settings saved", "AI 設定を保存しました"},
        {"ai.off", "AI 调参尚未启用（工具 → AI 实验功能）", "AI tuning is off (Tools > AI, experimental)", "AI 調整は無効です（ツール → AI 実験機能）"},
        {"ai.busy", "AI 正在调参，请稍候", "AI is already tuning", "AI が調整中です"},
        {"ai.asking", "正在请求 AI 调参…", "Asking the model…", "モデルに問い合わせています…"},
        {"ai.applied", "AI 已调整 %d 个参数（共 %d 个控制点）", "AI tuned %d parameters (%d control points)", "AI が %d 個のパラメータを調整しました（制御点 %d 個）"},
        {"ai.failed", "AI 调参失败：%s", "AI tuning failed: %s", "AI 調整に失敗：%s"},
        {"menu.tools.orientation", "手动切换横竖屏", "Toggle Portrait / Landscape", "縦横を手動で切り替え"},
        {"menu.track.list.title", "音轨（共 %d 轨）", "Tracks (%d total)", "トラック（全 %d 個）"},
        {"menu.track.new", "＋ 新建音轨", "＋ New Track", "＋ 新しいトラック"},
        {"menu.track.rename", "重命名当前音轨", "Rename Current Track", "現在のトラック名を変更"},
        {"menu.track.delete", "删除当前音轨", "Delete Current Track", "現在のトラックを削除"},
        {"menu.pack.import.folder", "从文件夹批量导入（推荐，真机更稳）", "Import from Folder (recommended; more reliable on real phones)", "フォルダから一括読み込み（推奨・実機で安定）"},
        {"menu.pack.import", "导入声库文件（.v4voice）", "Import Voicebank File (.v4voice)", "声庫ファイルを読み込む（.v4voice）"},
        {"menu.pack.delete", "删除已导入的声库（%d）", "Delete an Imported Voicebank (%d)", "読み込んだ声庫を削除（%d）"},
        {"menu.pack.help", "声库说明", "About Voicebanks", "声庫について"},

        // ------------------------------------------------- voicebank manager / .bri (12.1)
        {"bank.title", "声库管理", "Voicebanks", "声庫管理"},
        {"bank.sub", "共 %d 个已导入 · 当前：%s", "%d imported · current: %s", "読み込み済み %d 個 · 現在：%s"},
        {"bank.empty", "还没有导入任何声库。点下面的「导入声库文件」载入 .v4voice / .v4neural。", "No voicebanks imported yet. Use Import Voicebank File below to load a .v4voice / .v4neural.", "まだ声庫がありません。下の「声庫ファイルを読み込む」から .v4voice / .v4neural を追加してください。"},
        {"bank.actions", "操作", "Actions", "操作"},
        {"bank.rename", "重命名", "Rename", "名前を変更"},
        {"bank.delete", "删除", "Delete", "削除"},
        {"bank.import.file", "导入声库文件", "Import Voicebank File", "声庫ファイルを読み込む"},
        {"bank.import.folder", "从文件夹导入", "Import from Folder", "フォルダから読み込む"},
        {"bank.export.bri", "导出模型备份（.bri）", "Export model bundle (.bri)", "モデルを書き出す（.bri）"},
        {"bank.import.bri", "导入模型备份（.bri）", "Import model bundle (.bri)", "モデルを読み込む（.bri）"},
        {"bank.help", "声库说明", "About Voicebanks", "声庫について"},
        {"bank.export.tune", "导出 BriTune-Net", "Export BriTune-Net", "BriTune-Net を書き出す"},
        {"bank.export.base", "导出 BriNeting 基座", "Export BriNeting base", "BriNeting 基座を書き出す"},
        {"bank.bri.replace", "替换", "Replace", "置き換え"},
        {"bank.bri.merge", "合并（各半）", "Merge (50/50)", "統合（50:50）"},
        {"bank.bri.merged", "已合并模型：%s", "Models merged: %s", "モデルを統合しました：%s"},
        {"bank.bri.mode.ask", "选择安装方式：替换现有模型，或与新模型各取一半合并（两者结构必须相同）。", "Choose how to install: replace the current models, or merge half and half with the new one (same architecture required).", "インストール方法を選択：現在のモデルを置き換えるか、新しいモデルと半分ずつ統合します（同じ構造が必要）。"},
        {"bank.tag.neural", "神经", "NEURAL", "ニューラル"},
        {"bank.tag.trad", "传统", "CLASSIC", "クラシック"},
        {"bank.rename.title", "重命名声库", "Rename voicebank", "声庫の名前を変更"},
        {"bank.rename.hint", "留空恢复原名", "Leave empty to restore the original name", "空欄にすると元の名前に戻ります"},
        {"bank.renamed", "已重命名为「%s」", "Renamed to 「%s」", "「%s」に名前を変更しました"},
        {"bank.selected", "已选用「%s」", "Now using 「%s」", "「%s」を使用します"},
        {"bank.bri.title", "模型备份（.bri）", "Model bundle (.bri)", "モデルのバックアップ（.bri）"},
        {"bank.bri.summary", "这个备份包含：\n\n%s\n\n.bri 里是模型权重本身，不含录音，也不含声库。", "This bundle contains:\n\n%s\n\nA .bri holds model weights only - no recordings, no voicebanks.", "このバックアップに含まれるもの：\n\n%s\n\n.bri はモデルの重みのみで、録音や声庫は含みません。"},
        {"bank.bri.none", "没有可导出的模型：先训练 BriNeting 基座，或下载 BriTune-Net 模型。", "Nothing to export yet: train the BriNeting base or download the BriTune-Net model first.", "書き出せるモデルがありません。先に BriNeting 基座を学習するか、BriTune-Net モデルを取得してください。"},
        {"bank.bri.exported", "已导出 %d 个模型 → %s", "Exported %d models → %s", "%d 個のモデルを書き出しました → %s"},
        {"bank.bri.failed", "导出失败：%s", "Export failed: %s", "書き出しに失敗：%s"},
        {"bank.bri.imported", "已恢复模型备份：%s", "Model bundle restored: %s", "モデルを復元しました：%s"},
        {"bank.bri.import.failed", "导入失败（原有模型未改动）：%s", "Import failed (installed models untouched): %s", "読み込みに失敗（既存モデルは変更なし）：%s"},
        {"bank.bri.import.title", "导入模型备份", "Import model bundle", "モデルを読み込む"},
        {"bank.bri.import.ask", "将从备份恢复：\n\n%s\n\n会替换当前的 BriTune-Net 权重和 BriNeting 基座（备份里没有的保持原样）。", "Will restore:\n\n%s\n\nThis replaces the current BriTune-Net weights and BriNeting base (models missing from the bundle stay as they are).", "バックアップから復元します：\n\n%s\n\n現在の BriTune-Net の重みと BriNeting 基座を置き換えます（バックアップに無いものはそのまま）。"},
        {"bank.bri.save.title", "导出模型备份", "Export model bundle", "モデルを書き出す"},

        // ------------------------------------------------ start screen (v12.3)
        {"start.version", "v12.3 · 歌声合成工作站", "v12.3 · singing synthesizer", "v12.3 · 歌声合成ワークステーション"},
        {"start.subtitle", "先选一个开始方式。工程、声库和导出的 WAV 都在本机，不会上传。", "Pick how to start. Projects, voicebanks and exported WAVs stay on this device.", "始め方を選んでください。プロジェクト・声庫・書き出した WAV はこの端末内に留まります。"},
        {"start.new", "新建工程", "New project", "新規プロジェクト"},
        {"start.new.sub", "从空白的音轨开始", "Start from an empty track", "空のトラックから始める"},
        {"start.open", "打开工程", "Open project", "プロジェクトを開く"},
        {"start.open.sub", "选一个 .vsqx / .vsq / .mid", "Pick a .vsqx / .vsq / .mid", ".vsqx / .vsq / .mid を選ぶ"},
        {"start.singers", "歌手管理器", "Singer manager", "シンガー管理"},
        {"start.singers.sub", "导入、切换、重命名声库", "Import, switch and rename voicebanks", "声庫の読み込み・切り替え・名前変更"},
        {"start.more", "更多", "More", "その他"},
        {"start.more.sub", "设置、教程、链接与关于", "Settings, tutorial, links and about", "設定・チュートリアル・リンク・情報"},
        {"start.recent", "最近工程", "Recent projects", "最近のプロジェクト"},
        {"start.empty", "还没有最近工程。新建或打开一个，它就会出现在这里（最多 5 个）。", "No recent projects yet. Create or open one and it appears here (up to 5).", "最近のプロジェクトはまだありません。新規作成か読み込みをすると、ここに最大 5 件表示されます。"},
        {"start.foot", "最近工程来自本机 BriVoice_root/projects 与打开过的文件；删掉文件后这里也会消失。", "Recent projects come from BriVoice_root/projects and the files you opened; deleting the file removes it here too.", "最近のプロジェクトは端末内の BriVoice_root/projects と開いたファイルから作られます。ファイルを消すとここからも消えます。"},
        {"start.more.sub2", "应用设置、使用说明，以及项目的两个主页。", "App settings, the how-to, and the project's two home pages.", "アプリ設定・使い方・プロジェクトの 2 つのページ。"},
        {"start.settings", "设置", "Settings", "設定"},
        {"start.settings.sub", "音素锁定、自动保存、渲染与延迟", "Phoneme lock, autosave, rendering and latency", "音素ロック・自動保存・レンダリング・遅延"},
        {"start.tutorial", "教程", "Tutorial", "チュートリアル"},
        {"start.tutorial.sub", "从画音符到导出 WAV 的分步说明", "Step by step, from drawing notes to exporting a WAV", "音符入力から WAV 書き出しまでの手順"},
        {"start.gitee", "Gitee 项目主页", "Gitee project page", "Gitee プロジェクトページ"},
        {"start.gitee.sub", "gitee.com/h23c5/bri-voice · 源码、更新与问题反馈", "gitee.com/h23c5/bri-voice · source, updates and issues", "gitee.com/h23c5/bri-voice · ソース・更新・不具合報告"},
        {"start.bili", "我的 B 站主页", "My Bilibili page", "作者の B 站ページ"},
        {"start.bili.sub", "space.bilibili.com/3461562746538484", "space.bilibili.com/3461562746538484", "space.bilibili.com/3461562746538484"},
        {"start.about", "关于", "About", "このアプリについて"},
        {"start.about.sub", "版本、声库列表、许可证与免责声明", "Version, voicebanks, licence and disclaimer", "バージョン・声庫一覧・ライセンス・免責"},
        {"start.back", "‹ 返回", "‹ Back", "‹ 戻る"},
        {"start.open.row", "打开 ›", "Open ›", "開く ›"},
        {"start.meta.saved", "保存于 %s", "Saved %s", "保存 %s"},
        {"start.meta.local", "本机工程", "On this device", "端末内のプロジェクト"},
        {"start.more.foot", "Gitee 与 B 站用系统浏览器打开；本界面不需要联网也能用。", "Gitee and Bilibili open in your browser; this screen works offline.", "Gitee と B 站はブラウザで開きます。この画面はオフラインでも使えます。"},

        // ----------------------------------------- voicebank info pieces
        {"pack.info.harmonics", "实测谐波 %d 音素/%d 频段", "Measured harmonics: %d phonemes / %d bands", "実測ハーモニクス %d 音素／%d バンド"},
        {"pack.info.overrides", "共振峰模板 %d 项", "Formant templates: %d", "フォルマントテンプレート %d 件"},
        {"pack.info.eq", "EQ %d 段", "EQ: %d bands", "EQ %d バンド"},
        {"pack.info.name.extra", "%s（%s）", "%s (%s)", "%s（%s）"},
        {"pack.info.join", "%s，%s", "%s, %s", "%s、%s"},
        {"singer.tag.legacy", "⚠ 旧格式声库（无实测谐波，音色接近内置模型）", "⚠ Legacy voicebank (no measured harmonics; timbre close to the built-in model)", "⚠ 旧形式の声庫（実測ハーモニクスなし。音色は内蔵モデルに近くなります）"},

        // ------------------------------------------- mixer / plugins / track
        {"mixer.volume", "音量", "Volume", "音量"},
        {"mixer.pan", "声像", "Pan", "パン"},
        {"mixer.mute", "静音", "Mute", "ミュート"},
        {"mixer.solo", "独奏", "Solo", "ソロ"},
        {"mixer.master", "总音量", "Master Volume", "マスター音量"},
        {"mixer.reset", "重置", "Reset", "リセット"},
        {"plug.add", "添加插件", "Add Plugin", "プラグインを追加"},
        {"plug.clear", "全部清除", "Clear All", "すべて削除"},
        {"plug.enable", "启用", "Enable", "有効"},
        {"plug.bypass", "旁通", "Bypass", "バイパス"},
        {"plug.remove", "删除", "Remove", "削除"},
        {"track.name", "音轨 %d", "Track %d", "トラック %d"},
        {"clip.remove", "删除", "Remove", "削除"},

        // -------------------------------------------------------- toasts
        {"toast.saved", "已保存", "Saved", "保存しました"},
        {"toast.imported.project", "已导入工程：%s", "Project imported: %s", "プロジェクトを読み込みました：%s"},
        {"toast.no.picker", "无法打开文件选择器: %s", "Cannot open the file picker: %s", "ファイル選択画面を開けません: %s"},
        {"toast.no.packs", "还没有导入任何声库", "No voicebanks have been imported yet", "読み込んだ声庫はまだありません"},
        {"toast.pack.load.fail", "部分声库无法载入：\n%s", "Some voicebanks could not be loaded:\n%s", "一部の声庫を読み込めませんでした：\n%s"},
        {"toast.bad.tempo", "请输入 20-400 之间的数字", "Enter a number between 20 and 400", "20〜400 の数値を入力してください"},
        {"toast.no.settings", "无法打开系统设置：%s", "Cannot open system settings: %s", "システム設定を開けません：%s"},
        {"toast.pack.imported", "已导入声库: %s", "Voicebank imported: %s", "声庫を読み込みました: %s"},

        // ------------------------------------------- 新建 / 歌词 / 设备 / 重叠
        {"toolbar.new", "新建", "New", "新規"},
        {"dlg.new.title", "新建工程", "New project", "新規プロジェクト"},
        {"dlg.new.msg", "当前工程会被清空（可以用「撤销」找回）。", "The current project will be cleared (Undo brings it back).", "現在のプロジェクトは消去されます（「元に戻す」で復元できます）。"},
        {"dlg.new.ok", "新建", "Create", "作成"},
        {"status.new.done", "已新建空工程", "New empty project", "空のプロジェクトを作成しました"},
        {"menu.tools.lyrics", "填写歌词（空格拆到音符）", "Fill lyrics (split by spaces)", "歌詞を入力（空白区切り）"},
        {"menu.tools.overlap", "检查 / 修复音符重叠", "Check note overlaps", "音符の重なりを確認"},
        {"menu.tools.device", "播放设备", "Playback device", "再生デバイス"},
        {"dlg.lyrics.title", "填写歌词", "Lyrics", "歌詞を入力"},
        {"dlg.lyrics.room", "从当前音符往后可用 %d 个音符（一句要几块就占几个音符，空格分隔）", "%d notes available from the current note on (one space-separated piece per note)", "現在の音符から %d 個の音符が使えます（空白区切りで 1 音符に 1 ブロック）"},
        {"dlg.lyrics.hint", "粘贴歌词，用空格把每个音符的歌词分开：\n一 闪 一 闪 亮 晶 晶\n↑ 这段 = 7 个音符\n\n（整句没有空格时，按单个汉字拆分）", "Paste lyrics, one piece per note, separated by spaces:\nyi shan yi shan liang\n^ that is 3 notes\n\n(With no spaces at all, CJK is split per character.)", "歌詞を貼り付け。音符ごとに空白で区切ります：\n一 闪 一 闪\n（空白が無い場合は 1 文字ずつ分割）"},
        {"dlg.lyrics.ok", "填入", "Fill", "入力"},
        {"status.lyrics.empty", "没有解析出歌词", "No lyrics found", "歌詞を解析できませんでした"},
        {"status.lyrics.done", "已填入 %d 个音符（从选中音符开始）", "Filled %d notes from the selected note", "%d 個の音符に入力しました（選択音符から）"},
        {"status.lyrics.nonotes", "这里往后没有音符：先选中一个音符，或画几个音符再填入", "No notes from here - select a note or draw some first", "ここから先に音符がありません"},
        {"status.lyrics.left", "还有 %d 个音节没放下", "%d syllables left over", "%d 音節が余りました"},
        {"status.lyrics.notenough", "音符不够：这句有 %d 段，从这里往后只有 %d 个音符，还缺 %d 个。请先补音符再填。", "Not enough notes: %d pieces but only %d notes from here (%d short). Add notes first.", "音符が足りません：%d 区切りに対し、ここから先は %d 個しかありません（%d 個不足）。"},
        {"dlg.device.title", "播放设备（当前：%s）", "Playback device (now: %s)", "再生デバイス（現在：%s）"},
        {"dlg.device.default", "系统默认", "System default", "システム既定"},
        {"status.device.set", "播放设备: %s", "Playback device: %s", "再生デバイス：%s"},
        {"status.device.default", "已切回系统默认播放设备", "Back to the system default device", "システム既定に戻しました"},
        {"status.device.fail", "读取播放设备失败: %s", "Cannot read the device list: %s", "デバイス一覧を取得できません：%s"},
        {"dlg.overlap.title", "发现 %d 处音符重叠", "%d overlapping notes found", "%d 箇所の重なりを検出"},
        {"dlg.overlap.scan", "播放前检查：发现 %d 处音符重叠", "Before playback: %d overlaps found", "再生前チェック：%d 箇所の重なり"},
        {"dlg.overlap.why", "重叠会让两个音同时发声（歌手只有一个）。「自动标准化」会把前一个音符缩短到后一个开始处；两个音符起点完全相同时保留较长的那个。", "Overlapping notes make the singer sound two notes at once. Normalising shortens the earlier note to where the next one starts; notes starting on the same tick keep the longer one.", "重なると同時に 2 音が鳴ります。「自動整形」は前の音符を次の開始位置まで短くし、同じ位置に始まる音符は長い方を残します。"},
        {"dlg.overlap.fix", "自动标准化", "Normalise", "自動整形"},
        {"dlg.overlap.fixplay", "自动修复并播放", "Fix and play", "修正して再生"},
        {"dlg.overlap.jump", "跳转到第一处", "Jump to the first", "最初へ移動"},
        {"dlg.overlap.playany", "仍然播放", "Play anyway", "そのまま再生"},
        {"status.overlap.none", "没有发现音符重叠", "No overlapping notes", "重なりはありません"},
        {"status.overlap.fixed", "已标准化 %d 个音符", "Normalised %d notes", "%d 個の音符を整形しました"},
        {"status.overlap.jump", "问题音符：%s", "Problem note: %s", "問題の音符：%s"},
        {"status.overlap.nothing", "没有需要修复的音符", "Nothing to fix", "修正するものはありません"},
        {"singer.tag.mine", "（我的声库）", "(installed)", "（インストール済み）"},

        // ------------------------------------------- sliders / zoom / disclaimer
        {"transport.snap.short", "吸附", "Snap", "スナップ"},
        {"status.zoom", "缩放 %s", "Zoom %s", "ズーム %s"},
        {"status.zoom.fit", "已缩放到适应窗口", "Zoomed to fit", "ウィンドウに合わせました"},
        {"about.line.author", "作者：Ling-tech　QQ：3361390582", "Author: Ling-tech   QQ: 3361390582", "作者：Ling-tech　QQ：3361390582"},
        {"about.line.disclaimer", "免责声明\n1. 本软件为个人独立开发的非商业项目，按「现状」提供，不附带任何明示或默示担保。\n2. 本软件与 Yamaha 及任何声库厂商均无隶属、赞助或合作关系；文中出现的产品名称与商标仅用于说明格式兼容性，权利归各自所有者。\n3. 本软件本体不包含任何第三方采样或声库文件；内置声库为参数化统计数据（谐波包络与频段增益），不含原始录音采样。\n4. 使用者自行导入的声库与音频数据，其来源合法性、授权范围及使用后果由使用者自行负责；请勿分发受版权保护的素材或由此衍生的数据。\n5. 本软件默认不联网、不收集也不上传任何个人信息；只有你主动开启「AI 实验功能」并填好接口后，它才会访问该接口，密钥只保存在本机。\n6. 在适用法律允许的最大范围内，作者不对因使用或无法使用本软件而产生的任何损失承担责任。\n7. 若不同意以上条款，请停止使用并卸载本软件。", "Disclaimer\n1. This software is an independent, non-commercial project provided as-is, without warranty of any kind, express or implied.\n2. It is not affiliated with, sponsored by or endorsed by Yamaha or any voicebank vendor. Product names and trademarks are used only to describe format compatibility and belong to their respective owners.\n3. The app itself contains no third-party samples or voicebank files. The bundled voicebank holds parametric statistics (harmonic envelopes and band gains), not original recordings.\n4. You are responsible for the legality, licensing and use of any voicebank or audio you import. Do not redistribute copyrighted material or data derived from it.\n5. The app is offline by default and collects no personal data; only after you switch on the experimental AI feature and enter an endpoint does it call that endpoint, and the key is stored on this device only.\n6. To the maximum extent permitted by law, the author is not liable for any loss arising from the use of, or inability to use, this software.\n7. If you do not accept these terms, please stop using and uninstall the app.", "免責事項\n1. 本ソフトウェアは個人開発の非営利プロジェクトであり、「現状のまま」提供され、明示・黙示を問わずいかなる保証も伴いません。\n2. 本ソフトウェアは Yamaha およびいかなる声庫メーカーとも提携・スポンサー・承認関係にありません。製品名・商標は形式の互換性を示す目的でのみ使用し、権利は各所有者に帰属します。\n3. 本体には第三者のサンプルや声庫ファイルを一切含みません。内蔵声庫はパラメータ統計（倍音包絡と帯域ゲイン）であり、原音のサンプルではありません。\n4. 利用者が読み込む声庫・音声データの入手経路、ライセンス、利用結果は利用者自身の責任です。著作権で保護された素材やそこから派生したデータを再配布しないでください。\n5. 既定では通信を行わず、個人情報も収集・送信しません。「AI 実験機能」を有効にしてエンドポイントを入力した場合にのみそのエンドポイントへ通信し、キーは端末内にのみ保存されます。\n6. 適用法が認める最大限の範囲で、作者は本ソフトウェアの使用または使用不能から生じるいかなる損害にも責任を負いません。\n7. 本条件に同意できない場合は、使用を中止しアンインストールしてください。"},
        {"transport.zoom.short", "缩放", "Zoom", "ズーム"},
        {"transport.snap.off.short", "关", "Off", "オフ"},
        {"param.tool.pencil", "画笔", "Draw", "ペン"},
        {"param.tool.line", "直线", "Line", "直線"},
        {"param.tool.select", "选择", "Select", "選択"},
        {"status.packs.loading", "正在载入声库，请稍候…载入完成后会自动开始播放", "Loading voicebanks, playback starts when ready", "声庫を読み込み中です。完了後に自動的に再生します"},
        {"singer.tag.builtin", "（内置）", "(bundled)", "（同梱）"},
        {"transport.fromstart", "从头播放", "From Start", "最初から再生"},
        {"status.fromstart", "已回到开头，从 001:1 开始播放", "Rewound to the start, playing from 001:1", "先頭に戻して再生します"},
    };

    private static final Map<String, String[]> BY_KEY;
    private static final List<String> KEYS;
    private static final String[] NAMES = {"中文", "English", "日本語"};

    static {
        Map<String, String[]> byKey = new HashMap<String, String[]>(TABLE.length * 2);
        List<String> keys = new ArrayList<String>(TABLE.length);
        for (int i = 0; i < TABLE.length; i++) {
            String[] row = TABLE[i];
            if (row == null || row.length != LANGS + 1) {
                throw new IllegalStateException("bad i18n row " + i);
            }
            if (byKey.put(row[0], row) != null) {
                throw new IllegalStateException("duplicate i18n key: " + row[0]);
            }
            keys.add(row[0]);
        }
        BY_KEY = Collections.unmodifiableMap(byKey);
        KEYS = Collections.unmodifiableList(keys);
    }

    private static volatile int lang = ZH;

    private L() {
    }

    /** Select the UI language. Out-of-range values are ignored. */
    public static void setLang(int newLang) {
        if (newLang >= ZH && newLang <= JA) lang = newLang;
    }

    /** @return the active language id ({@link #ZH}, {@link #EN} or {@link #JA}). */
    public static int lang() {
        return lang;
    }

    /**
     * Look up a key in the active language.
     *
     * @return the translation, or {@code key} itself when the key is unknown
     *         (so a missing entry shows up instead of crashing), or {@code ""} for null.
     */
    public static String t(String key) {
        if (key == null) return "";
        String[] row = BY_KEY.get(key);
        return row == null ? key : row[lang + 1];
    }

    /**
     * Look up a key and format it with {@link String#format} (US locale, so digits and
     * separators do not depend on the device locale).
     *
     * <p>An unknown key is returned unformatted. A malformed pattern or a wrong argument
     * count returns the raw pattern rather than throwing: a missing translation must never
     * take the UI down. The desktop self test asserts that this never happens for the
     * shipped catalog.
     */
    public static String t(String key, Object... args) {
        String pattern = t(key);
        if (args == null || args.length == 0 || !BY_KEY.containsKey(key)) return pattern;
        try {
            return String.format(Locale.US, pattern, args);
        } catch (RuntimeException badFormat) {
            return pattern;
        }
    }

    /** @return the three display names, index aligned with {@link #ZH}/{@link #EN}/{@link #JA}. */
    public static String[] languageNames() {
        return NAMES.clone();
    }

    /** @return every key, in catalog order (immutable). */
    public static List<String> allKeys() {
        return KEYS;
    }

    /** @return true when the key exists in the catalog. Useful for composed keys. */
    public static boolean has(String key) {
        return key != null && BY_KEY.containsKey(key);
    }
}
