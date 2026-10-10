# -*- coding: utf-8 -*-
import io, re
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()
def java(key, vals):
    return '        {"' + key + '", ' + ', '.join('"' + v.replace(chr(10), '\\n') + '"' for v in vals) + '},'
def setrow(key, vals):
    global s
    pat = re.compile(r'        \{"' + re.escape(key) + r'", .*?"\},', re.S)
    row = java(key, vals)
    s2, n = pat.subn(lambda m: row, s, count=1)
    if n == 0: raise SystemExit('row not found: ' + key)
    s = s2
setrow('dlg.no.voice.msg', ["BriVoice 需要一个声库才能唱歌。\n\n应用已内置「洛天依 BV」；你也可以点「声库 → 导入声库文件（.v4voice）」导入其它 .v4voice 参数包。\n声库里是谐波包络、共振峰与频段增益等参数化统计数据，不含录音采样。请只导入你自己合法持有的声库数据。","BriVoice needs a voicebank to sing.\n\nThe app bundles \"Luo Tianyi BV\"; you can also import another .v4voice parameter pack via Voicebanks -> Import Voicebank File.\nA pack holds parametric statistics such as harmonic envelopes, formants and band gains - no recorded samples. Please only import voicebank data you legally hold.","BriVoice で歌うには声庫が必要です。\n\nアプリには「洛天依 BV」が同梱されています。「声庫 → 声庫ファイルを読み込む（.v4voice）」から他の .v4voice パラメータパックも読み込めます。\nパックには倍音包絡・フォルマント・帯域ゲインなどのパラメータ統計が入っており、録音サンプルは含みません。適法に保有する声庫データのみを読み込んでください。"])
setrow('dlg.pack.help.msg', ["· 应用已内置「洛天依 BV」（参数化统计数据，不含录音采样）。\n· 「导入声库」可载入 .v4voice 参数包：包里是谐波幅度谱（按音高分 3 段）、共振峰与频段增益等统计数据，不含任何录音采样。请只使用你自己合法持有的声库数据。\n· 应用用这些谐波做加法合成，所以音色来自声库本身，而不是内置的共振峰模型。\n· 导入后会在「歌手」列表里标记来源（内置 / 我的声库），可随时删除。\n· 声库只影响音色，不影响工程文件的读写。","· The app bundles \"Luo Tianyi BV\" (parametric statistics, no recorded samples).\n· Import Voicebank loads a .v4voice parameter pack: harmonic magnitude spectra (3 pitch bands), formants and band gains - no recorded samples. Please only use voicebank data you legally hold.\n· The app sings with those harmonics, so the timbre comes from the pack itself rather than a built-in formant model.\n· Imported packs are tagged in the Singer list (bundled / mine) and can be deleted at any time.\n· A voicebank affects timbre only; project files read and write the same way.","· アプリには「洛天依 BV」が同梱されています（パラメータ統計のみ、録音サンプルなし）。\n· 「声庫を読み込む」では .v4voice パラメータパックを読み込めます。倍音振幅スペクトル（音高で 3 分割）・フォルマント・帯域ゲインなどの統計データのみで、録音サンプルは含みません。適法に保有する声庫データのみを使用してください。\n· これらの倍音で加算合成するため、音色は内蔵フォルマントモデルではなくパック由来です。\n· 読み込んだパックは歌手一覧に出所（同梱／マイ声庫）が表示され、いつでも削除できます。\n· 声庫は音色のみに影響し、プロジェクトファイルの読み書きには影響しません。"])
io.open(L, 'w', encoding='utf-8', newline='').write(s)
print('help texts cleaned')