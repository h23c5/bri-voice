# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "39", "--version-name", "5.1",', '"--version-code", "40", "--version-name", "5.2",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="39"', 'android:versionCode="40"').replace('android:versionName="5.1"', 'android:versionName="5.2"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：5.1（versionCode 39）', '版本：5.2（versionCode 40）')
d = d.replace('F5DB9DC3C5AFED6323AFE6E72A980DD05B2073B2CE740EEE91E8E742BC84FA83', 'PENDING')
entry = "### 本版（v5.2）：修正上一版的电平错误，实测谱功能改为**可选**（默认关闭）\n\n你对 v5.1 的判定是「更不清晰」。我按你的耳朵去查，找到了我自己漏测的量——**辅音电平**：\n\n| 擦音辅音电平（相对元音） | s | x | f | z` | ts` | 可区分度 |\n|---|---|---|---|---|---|---|\n| v5.0 通用噪声 | −1.6 | −2.6 | −2.6 | −5.9 | −2.9 dB | 0.058 |\n| v5.1 实测谱（有 bug） | −4.2 | −4.2 | −4.2 | −4.2 | −4.2 dB | 0.100 |\n| v5.2 实测谱（已修） | −1.2 | −2.3 | −2.2 | −5.5 | −2.3 dB | **0.117** |\n\n原因：实测包络被归一化到 0 dB 峰值后，我用了**固定增益**——于是每个擦音的响度被抹成完全一样，\n**丢掉了真实的响度差异**（这本身就是重要辨义线索）。v5.1 因此听起来更糊。\n\n现在实测谱路径会**跟随它所替代的共振峰路径的电平**（30 ms 滑动 RMS 匹配），两项都保住了。\n\n**并且默认关闭**：设置里新增「辅音实测谱（实验）」开关，你可以自己在设备上 A/B。\n关闭时 = v5.0 的听感（你认可的版本）。\n\n**教训（我记下）**：「擦音可区分度」这个指标不包含电平平衡，不能代表可懂度——\n我拿它当代理指标，导致 v5.1 越改越差。以后涉及发声的改动，除了频谱指标，必须同时报告电平平衡。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')