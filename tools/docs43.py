# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "30", "--version-name", "4.2",', '"--version-code", "31", "--version-name", "4.3",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="30"', 'android:versionCode="31"').replace('android:versionName="4.2"', 'android:versionName="4.3"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：4.2（versionCode 30）', '版本：4.3（versionCode 31）')
d = d.replace('06A390828E53D2507DF81C485FA5DCC0B65F2AFD9EA470EDA798A01C1B95C622', 'PENDING')
entry = "### 本版（v4.3）：AI 快捷调音按钮 + 自然语言需求 + 改「几乎所有参数」\n\n**工作区快捷按钮**：开启 AI 后，走带栏出现「AI 调音」（关掉 AI 就自动隐藏，重启应用后按设置恢复）。\n\n**用自然语言提需求**：点按钮弹出「想让 AI 怎么调？」——一句话输入框 + 四个一键示例：\n- 整体更有感情：副歌力度大一些，句尾加颤音\n- 修正音准：把偏高的音拉回来，长音加一点自然的滑音\n- 更柔和：降低力度起伏，气息声多一点\n- 更有节奏感：让每个音的起音更干脆，缩短句尾\n\n**AI 现在能改的范围**（全部经白名单与范围校验，且**整批改动算一次撤销**）：\n\n| 类别 | 内容 |\n|---|---|\n| 参数曲线 | 全部 12 条（DYN/BRE/BRI/CLE/OPE/GEN/POR/PIT/PBS/VIB/XSY/GWL） |\n| 已有音符 | 音高 key、时长 dur、力度 vel、歌词 lyric、音素 phoneme、位置 newPos（按 pos 匹配，**不会凭空造音符**） |\n| 速度 | tempo（20–400 BPM，支持两位小数） |\n| 轨道 | 音量 trackVolume、声像 trackPan |\n\n**验证**：\n- `AiTuningTest` 14/14 —— 曲线/音符/速度/轨道的解析与裁剪（key 999→127、pan 200→127、tempo 9000 被忽略、无 pos 的音符被拒）；\n- `AiTunerTest` 13/13、`AiClientTest` 9/9 仍然通过；i18n 2859/2859（275 键）；\n- 真机：开启 AI 后**快捷按钮出现**，对话框与四个示例正常；通过接口下发一个完整 tuning（2 曲线 + 2 音符 + 速度 + 轨道）→\n  返回 `{\"ok\":true,\"applied\":8,\"summary\":\"2 params/2 notes/tempo/track\"}`，改动全部落到工程里。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs + version ok')