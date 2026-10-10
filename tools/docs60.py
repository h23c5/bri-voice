# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "40", "--version-name", "5.2",', '"--version-code", "41", "--version-name", "6.0",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="40"', 'android:versionCode="41"').replace('android:versionName="5.2"', 'android:versionName="6.0"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：5.2（versionCode 40）', '版本：6.0（versionCode 41）')
d = d.replace('74254922387E7F1B95073B525DAEA826B51C4188C34781D7EFC49161F836CBB1', 'PENDING')
entry = "### 本版（v6.0）：帧序列拼接合成（新引擎，默认开启）\n\n你要求走帧序列拼接。**前提要说清**：包里存的是**频谱帧**（64 段 dB 包络，每单元 12 帧连续），没有波形数据，\n所以「帧拼接」实现为**逐帧驱动谐波振荡器组**：每 5.8 ms 用一帧真实测量谱决定每个谐波的振幅，\n沿音符走完整个帧序列，振幅在跳内线性过渡，每个谐波保持独立相位（接缝无爆音）。\n\n新增 `synth/FrameVoice.java`；`Renderer.frameSequence` 开关（设置里可 A/B），默认开启。\n没有实测数据的音素（u / sh / h / p / p_h）自动回退到原共振峰路径。\n\n**实现过程中踩的三个坑（都已修）**：\n1. 把谐波放到 FFT 频点上 → 每个谐波最多失谐半个频点（低次谐波达四分之一音），与实测包络相关度只有 **0.60**；\n2. 用递归振荡器（`2cosω·y1 − y2`）→ 数值漂移，且把增益烘进了状态里，导致**电平呈平方关系**；\n3. 无实测数据的音素没有回退 → 帧路径会继续唱上一个音素的帧，保真回归 2/7 失败。\n   改用**共享正弦表 + 单位幅度相位累加器**后：相关度 0.60 → **0.986**，电平线性可控，回归全绿。\n\n**实测（长音 a4）**：\n| 指标 | 帧序列拼接 | 旧的单周期波表 |\n|---|---|---|\n| 与声库实测包络的相关度 | **0.986** | 0.984 |\n| 电平 | +0.7 dB（已对齐） | 基准 |\n| 逐跳频谱变化量（dB，越高＝原唱微变化越多） | 1.789 | 1.852 |\n\n回归：渲染保真 7/7、DYN 音色 2/2、片段演化 2/2、乐句 4/4、时长 3/3（全部通过）。\n\n**说明**：逐跳变化量两者接近，说明差异主要体现在**帧序列本身的轨迹**与相位连续性上，而不是幅度起伏；\n这一点我无法用指标证明「更好听」——需要你的耳朵定论。设置里有开关可以随时切回旧引擎对比。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')