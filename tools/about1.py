# -*- coding: utf-8 -*-
import io, re
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()

def setrow(key, vals):
    global s
    pat = re.compile(r'\{"' + re.escape(key) + r'", ".*?"\},', re.S)
    line = '{"' + key + '", ' + ', '.join('"' + v + '"' for v in vals) + '},'
    s2, n = pat.subn(lambda m: line, s, count=1)
    if n == 0:
        raise SystemExit('row not found: ' + key)
    s = s2

setrow('about.line.disclaimer', ["""免责声明\n1. 本软件为个人独立开发的非商业项目，按「现状」提供，不附带任何明示或默示担保。\n2. 本软件与 Yamaha 及任何声库厂商均无隶属、赞助或合作关系；文中出现的产品名称与商标仅用于说明格式兼容性，权利归各自所有者。\n3. 本软件本体不包含任何第三方采样或声库文件；内置声库为参数化统计数据（谐波包络与频段增益），不含原始录音采样。\n4. 使用者自行导入的声库与音频数据，其来源合法性、授权范围及使用后果由使用者自行负责；请勿分发受版权保护的素材或由此衍生的数据。\n5. 本软件不联网、不收集也不上传任何个人信息。\n6. 在适用法律允许的最大范围内，作者不对因使用或无法使用本软件而产生的任何损失承担责任。\n7. 若不同意以上条款，请停止使用并卸载本软件。""", """Disclaimer\n1. This software is an independent, non-commercial project provided as-is, without warranty of any kind, express or implied.\n2. It is not affiliated with, sponsored by or endorsed by Yamaha or any voicebank vendor. Product names and trademarks are used only to describe format compatibility and belong to their respective owners.\n3. The app itself contains no third-party samples or voicebank files. The bundled voicebank holds parametric statistics (harmonic envelopes and band gains), not original recordings.\n4. You are responsible for the legality, licensing and use of any voicebank or audio you import. Do not redistribute copyrighted material or data derived from it.\n5. The app does not use the network and collects no personal data.\n6. To the maximum extent permitted by law, the author is not liable for any loss arising from the use of, or inability to use, this software.\n7. If you do not accept these terms, please stop using and uninstall the app.""", """免責事項\n1. 本ソフトウェアは個人開発の非営利プロジェクトであり、「現状のまま」提供され、明示・黙示を問わずいかなる保証も伴いません。\n2. 本ソフトウェアは Yamaha およびいかなる声庫メーカーとも提携・スポンサー・承認関係にありません。製品名・商標は形式の互換性を示す目的でのみ使用し、権利は各所有者に帰属します。\n3. 本体には第三者のサンプルや声庫ファイルを一切含みません。内蔵声庫はパラメータ統計（倍音包絡と帯域ゲイン）であり、原音のサンプルではありません。\n4. 利用者が読み込む声庫・音声データの入手経路、ライセンス、利用結果は利用者自身の責任です。著作権で保護された素材やそこから派生したデータを再配布しないでください。\n5. 本ソフトウェアは通信を行わず、個人情報を収集・送信しません。\n6. 適用法が認める最大限の範囲で、作者は本ソフトウェアの使用または使用不能から生じるいかなる損害にも責任を負いません。\n7. 本条件に同意できない場合は、使用を中止しアンインストールしてください。"""])
setrow('about.line.tip1', ['提示：内置声库为参数化统计数据（谐波包络与频段增益），不含任何第三方录音采样。',
                          'Note: the bundled voicebank is parametric statistics (harmonic envelopes and band gains); it contains no third-party recordings.',
                          'ヒント：内蔵声庫はパラメータ統計（倍音包絡と帯域ゲイン）で、第三者の録音サンプルは含みません。'])
setrow('about.line.tip2', ['如需其它音色，请导入你自己合法持有的声库文件。',
                          'For other voices, import a voicebank you legally own.',
                          '他の音色が必要な場合は、ご自身が適法に保有する声庫を読み込んでください。'])
setrow('about.line.packs', ['声库', 'Voicebanks', '声庫'])
if '"dlg.ok"' not in s:
    i = s.find('{"dlg.about.title"')
    j = s.find('\n', i) + 1
    s = s[:j] + '        {"dlg.ok", "好", "OK", "OK"},\n' + s[j:]
io.open(L, 'w', encoding='utf-8', newline='').write(s)
print('i18n updated')