# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "38", "--version-name", "5.0",', '"--version-code", "39", "--version-name", "5.1",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="38"', 'android:versionCode="39"').replace('android:versionName="5.0"', 'android:versionName="5.1"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：5.0（versionCode 38）', '版本：5.1（versionCode 39）')
d = d.replace('50009CE35640DF98FBC90040D8C962796EF646A2191A7300DBDC8F6CEEEB6349', 'PENDING')
entry = "### 本版（v5.1）：用声库实测谱合成辅音——针对「听不清」\n\n前两轮把气声和衔接改好之后，你反馈**咬字仍然听不清、要脑补**。查下去发现了一个一直被浪费的数据：\n\n| 音素 | 类型 | 包里是否有实测谱 |\n|---|---|---|\n| a / i | 元音 | ✓ |\n| **s / x / f / z` / ts`** | **擦音、塞擦音** | **✓ 有（9 组实测）** |\n| sh / h / p / p_h / u | 擦音、送气、元音 | ✗ 无实测，回退通用模型 |\n\n问题在于：**谐波路径只对浊音生效**（`hTarget=0` for unvoiced），所以这些**真实辅音频谱从来没被用过**——\n所有擦音都走同一套「通用共振峰 + 噪声」，于是 s 和 x 听起来一样。\n\n**改动**：新增 `NoiseShape`（20 段对数分布的 peaking 滤波器组），非浊音且包里有实测谱时，\n**用实测谱包络来塑形噪声**；没有实测数据的音素（sh/h/p…）仍走原来的共振峰路径。\n另加一个诊断开关 `Renderer.measuredNoise`（默认 true），便于 A/B 与后续调音。\n\n**A/B 实测（渲染频谱 vs 包里实测谱）**：\n- 不同擦音之间的可区分度：**0.058 → 0.100（+72%）**；\n- 注：与实测谱的相关系数两种模式都高（0.85~0.93），因为噪声本身是宽带的，这个指标不够灵敏，\n  所以我以「可区分度」为准。\n\n**尚未解决**：sh / h / p / p_h / u 在包里**没有实测数据**（转换时未测到），仍用通用模型；\n要彻底解决需要重新转换声库、补齐这些音素的实测帧。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')