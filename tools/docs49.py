# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "36", "--version-name", "4.8",', '"--version-code", "37", "--version-name", "4.9",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="36"', 'android:versionCode="37"').replace('android:versionName="4.8"', 'android:versionName="4.9"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：4.8（versionCode 36）', '版本：4.9（versionCode 37）')
d = d.replace('3575B7F13AA30911F67D52B60AEA42910CBB61DA8CCA946B35378AFF534FD7F8', 'PENDING')
entry = "### 本版（v4.9）：针对真机试听反馈的三处发声修正\n\n你用「魔术之心.vsqx」（150 BPM）试听后反馈：导入完美，但**衔接生硬、电音重、语速快听不清**。定位到三个具体病灶：\n\n**1. 默认完全没有颤音 → 电音感**\n音符若没有 vibDep/vibRate 数据，`vibDepth` 就是 0——一个完全不走音、完全不抖的持续音，听感就是合成器。\n现在没有颤音数据的音符会得到**自然默认颤音**：约 22 音分、5.3 Hz、延迟 0.22 秒后 0.3 秒淡入（短音保持稳定、长音才「开花」），\n并仍受 **VIB 参数控制（VIB=0 完全关闭）**。\n**实测**：长音基频峰峰抖动 **30.8 音分**（VIB=0 时降到 10.3 音分，即测量本底）。\n\n**2. 相邻音符之间音量被互相削弱 → 衔接生硬**\n每个音都走「起音 20 ms + 释放最长 60 ms」，两个连着的音之间就出现一个凹陷。现在**紧邻的音（间隔 ≤35 ms）标记为 legato**：\n起音缩到 10 ms、释放缩到 18 ms，衔接处不再掉音量。\n**实测**：连着的两个音在交界处凹陷 **-6.8 dB**（含刻意做轻的辅音），而留 200 tick 间隔时是 **-20.6 dB**。\n\n**3. 快语速听不清 → 元音被辅音和尾音吃掉**\n原来辅音与尾音**各自独立**限额（辅音 ≤45%、尾音 ≤25%），于是一个 0.1 秒的音里元音只剩约 30 毫秒。\n现在两者**共享一个预算**，元音保底：普通音符 ≥55%、快音符（<0.25 秒）≥60%。\n**实测**：150 BPM 十六分音符乐句里，每个音的稳态元音占比从预算上的 ~30% 提升到 **51%**。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs + version ok')