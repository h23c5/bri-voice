# -*- coding: utf-8 -*-
import io, re
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()
def java(vals):
    parts = []
    for lines in vals:
        parts.append('"' + '\\n'.join(lines) + '"')
    return '        {"about.line.disclaimer", ' + ', '.join(parts) + '},'
row = java([["免责声明","1. 本软件为个人独立开发的非商业项目，按「现状」提供，不附带任何明示或默示担保。","2. 本软件与 Yamaha 及任何声库厂商均无隶属、赞助或合作关系；文中出现的产品名称与商标仅用于说明格式兼容性，权利归各自所有者。","3. 本软件本体不包含任何第三方采样或声库文件；内置声库为参数化统计数据（谐波包络与频段增益），不含原始录音采样。","4. 使用者自行导入的声库与音频数据，其来源合法性、授权范围及使用后果由使用者自行负责；请勿分发受版权保护的素材或由此衍生的数据。","5. 本软件不联网、不收集也不上传任何个人信息。","6. 在适用法律允许的最大范围内，作者不对因使用或无法使用本软件而产生的任何损失承担责任。","7. 若不同意以上条款，请停止使用并卸载本软件。"], ["Disclaimer","1. This software is an independent, non-commercial project provided as-is, without warranty of any kind, express or implied.","2. It is not affiliated with, sponsored by or endorsed by Yamaha or any voicebank vendor. Product names and trademarks are used only to describe format compatibility and belong to their respective owners.","3. The app itself contains no third-party samples or voicebank files. The bundled voicebank holds parametric statistics (harmonic envelopes and band gains), not original recordings.","4. You are responsible for the legality, licensing and use of any voicebank or audio you import. Do not redistribute copyrighted material or data derived from it.","5. The app does not use the network and collects no personal data.","6. To the maximum extent permitted by law, the author is not liable for any loss arising from the use of, or inability to use, this software.","7. If you do not accept these terms, please stop using and uninstall the app."], ["免責事項","1. 本ソフトウェアは個人開発の非営利プロジェクトであり、「現状のまま」提供され、明示・黙示を問わずいかなる保証も伴いません。","2. 本ソフトウェアは Yamaha およびいかなる声庫メーカーとも提携・スポンサー・承認関係にありません。製品名・商標は形式の互換性を示す目的でのみ使用し、権利は各所有者に帰属します。","3. 本体には第三者のサンプルや声庫ファイルを一切含みません。内蔵声庫はパラメータ統計（倍音包絡と帯域ゲイン）であり、原音のサンプルではありません。","4. 利用者が読み込む声庫・音声データの入手経路、ライセンス、利用結果は利用者自身の責任です。著作権で保護された素材やそこから派生したデータを再配布しないでください。","5. 本ソフトウェアは通信を行わず、個人情報を収集・送信しません。","6. 適用法が認める最大限の範囲で、作者は本ソフトウェアの使用または使用不能から生じるいかなる損害にも責任を負いません。","7. 本条件に同意できない場合は、使用を中止しアンインストールしてください。"]])
pat = re.compile(r'        \{"about.line.disclaimer", .*?\},', re.S)
s2, n = pat.subn(lambda m: row, s, count=1)
if n == 0: raise SystemExit('disclaimer row not found')
io.open(L, 'w', encoding='utf-8', newline='').write(s2)
print('disclaimer row fixed, lines =', row.count('\\n'))