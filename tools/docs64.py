# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
# 1) enable the measured-noise path by default
r1 = root + r'\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(r1, encoding='utf-8').read()
s = s.replace('public static boolean measuredNoise = false;', 'public static boolean measuredNoise = true;', 1)
io.open(r1, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
t = io.open(m, encoding='utf-8').read()
t = t.replace('getBoolean("mnoise", false)', 'getBoolean("mnoise", true)', 1)
io.open(m, 'w', encoding='utf-8', newline='').write(t)
# 2) version 6.4
b = root + r'\tools\build_apk.py'
v = io.open(b, encoding='utf-8').read().replace('"--version-code", "44", "--version-name", "6.3",', '"--version-code", "45", "--version-name", "6.4",')
io.open(b, 'w', encoding='utf-8', newline='').write(v)
am = root + r'\app\AndroidManifest.xml'
a = io.open(am, encoding='utf-8').read().replace('android:versionCode="44"', 'android:versionCode="45"').replace('android:versionName="6.3"', 'android:versionName="6.4"')
io.open(am, 'w', encoding='utf-8', newline='').write(a)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：6.3（versionCode 44）', '版本：6.4（versionCode 45）')
d = d.replace('279116FA8F60371C08E2324B699CC455D3B46117D6E0F4CABFB6607A585E7531', 'PENDING')
entry = "### 本版（v6.4）：补上辅音的**实测数据**\n\n你反馈「辅音的实测数据缺失」。查下去发现数据一直在声库里，只是没被提取出来：\n\n- 声库有 **5195（Miku）/ 7749（洛天依）** 条 SND 采样记录；\n- 之前转换器只采样 800 条，且是把**全部**样本聚成 8 类 —— 占 9% 的噪声类样本被浊音类吸收，\n  于是「no class matched a fricative signature」，整个 noise 段被丢弃。\n\n**两处修正**：\n1. 先按平坦度**筛出噪声类样本再聚类**（否则 9% 被淹没）；\n2. 每条采样**只分析最'噪'的那个窗口**（擦音样本开头常带浊音，整条平均会把噪声稀释）。\n\n**结果**（写入包的 `harmonics.noise`，即声库自己实测的擦音频谱）：\n\n| 声库 | 识别出的擦音（实测质心） |\n|---|---|\n| 洛天依 BV | f (2185 Hz)、h (1728 Hz)、**s (5133 Hz)**、s\\\\ (3556 Hz) |\n| Miku BV | f (3279 Hz)、h (1944 Hz)、s` (2978 Hz) |\n\n**渲染端**：App 原本会解析 `noiseDb` 却**从未使用** ✗——现在擦音会用它塑形噪声（`NoiseShape` 滤波器组），\n电平跟随旧路径以保证响度平衡（上一版 v5.1 就是在这里翻过车）。设置里的「辅音实测谱」开关**默认开启**，\n只影响有实测数据的辅音，其余自动回退到共振峰路径。\n\n**同时保留** v6.3 的元音修复：逐帧按共振峰分配（元音两两可区分度 1.79/1.56 dB，共振峰偏移 0.592/0.489）。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('6.4 configured')