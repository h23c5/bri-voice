# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "41", "--version-name", "6.0",', '"--version-code", "42", "--version-name", "6.1",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="41"', 'android:versionCode="42"').replace('android:versionName="6.0"', 'android:versionName="6.1"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：6.0（versionCode 41）', '版本：6.1（versionCode 42）')
d = d.replace('085933307C5F56C2951E224EF829A608A0DDFAA30170E2C81AAA5783E350B973', 'PENDING')
entry = "### 本版（v6.1）：帧拼接引擎默认关闭（回退旧引擎）+ 指针延迟扩到 ±2500 ms\n\n**1. 帧序列拼接引擎的杂音找到了，并且这次指标和耳朵一致**\n\n你反馈「新引擎电音杂音更多」。我先查代码，发现一个真实 bug：\n`pitchMoved` 的判定是「基频变化 > 0.05 Hz」，而**默认颤音让基频每 5 ms 都在变**——\n于是每一步都把全部谐波**相位重置为 0**、并把振幅清零重新淡入（200 Hz 的相位断裂 + 幅度门）。已修。\n\n修完再测，仍然不干净，而且这次我加的指标把问题量化出来了：\n\n| 指标（长音 a4） | 帧拼接引擎 | 旧波表引擎 |\n|---|---|---|\n| 谐波间杂散能量（越低越干净） | **+5.4 dB** | **−39.7 dB** |\n\n高出 **45 dB** —— 根因是**逐跳线性幅度斜坡**在 172 Hz 的跳频处产生边带。\n旧引擎用的是波表交叉淡化，所以没有这个问题。\n\n**处置**：`frameSequence` **默认关闭**（回到你认可的旧引擎），设置里的开关保留，供以后继续调试。\n要真正修好帧引擎，需要把幅度轨迹从「逐跳线性斜坡」改成**一阶平滑或双振荡器交叉淡化**——\n这是下一步，默认不启用前不会影响你。\n\n**2. 指针延迟补偿扩展到 −2500 … +2500 ms**\n\n滑块范围 0…1000 改为 **−2500 … +2500**（中间 2500 = 0 ms），\n负值表示「指针提前」（适合声音比画面快的设备），正值表示「指针滞后」。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')