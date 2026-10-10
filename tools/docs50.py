# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "37", "--version-name", "4.9",', '"--version-code", "38", "--version-name", "5.0",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="37"', 'android:versionCode="38"').replace('android:versionName="4.9"', 'android:versionName="5.0"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：4.9（versionCode 37）', '版本：5.0（versionCode 38）')
d = d.replace('27235284F74BA0FAEE28E0C18218F8051E332A8262E937D7D6E88AC81379B093', 'PENDING')
entry = "### 本版（v5.0）：针对第二轮试听反馈的发声修正\n\n你的三条反馈分别对应到具体代码：\n\n**1. 「每个音前面吐气太重」——找到真凶**\n包里的 `breathSoundAmount=0.43` 会在**每个音前面的空隙**补一段噪声（`addBreathSounds`），长度最多 0.22 秒、幅度 0.06；\n另外 `breathBias=0.27` 常驻注入到每个音的激励里。两者叠加就是你听到的「吐气」。\n改动：空隙噪声**长度 0.22→0.12 秒、幅度 0.06→0.018**（约 −10.6 dB），声库偏置按 **×0.35** 折算（只留一丝空气感），\n并且仍随 BRE 参数增减。\n\n**2. 「衔接不够顺」**\n- 滑音原来只在音符间隔 ≤20 ms 时触发，现在放宽到 **≤60 ms**；\n- 上一版已把紧邻音做 legato（起音 10 ms / 释放 18 ms），这一版在此基础上减少了气声对连贯感的干扰。\n\n**3. 「快句仍模糊、咬字不清」——我上轮压过头了**\n为了让爆破音不刺耳，我把擦音/塞擦音压到 **0.25（−12 dB）**，结果辅音的辨义线索几乎消失；\n现在改为 **擦音/塞擦音 0.45、送气音 0.35、鼻音/流音/介音 0.75**（塞音保持 0.90），在「不刺耳」与「听得清」之间取平衡；\n配合元音保底预算（快音元音 ≥60%），快句的可识别度应进一步改善。\n\n**保留的默认颤音**：22 音分 / 5.3 Hz、延迟 0.22 秒淡入（VIB=0 可关）。\n\n**量化（当前值）**：颤音深度 30.8 音分（VIB=0 → 10.3 本底）；连音交界凹陷 −6.8 dB（留空隙时 −20.5 dB）；\n150 BPM 十六分音符的稳态元音占比 52%；纯元音音前的空气声与音符间隙噪声均按上述比例下调。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')