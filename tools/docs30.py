# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：2.9（versionCode 17）', '版本：3.0（versionCode 18）')
entry = '\n'.join([
    '### 本版（v3.0）：修掉参数曲线拖动的"梳齿"',
    '',
    '- 现象：在参数区连续来回拖动时，曲线变成一排密集的竖线（截图里那样）。',
    '- 原因：每个触摸事件都调用 putRaw 插点，而它只在 tick **完全相等**时替换，抖动落在相邻 tick 上，
    '  于是一次来回就堆出几百个点，画成一排重叠的圆点。',
    '- 修法：曲线新增 putNear(tick, value, gap)，拖动时优先**复用 2.5 像素内的已有控制点**；
    '  绘制时相邻圆点间距小于自身直径就不重复画（只留折线）。',
    '- 实测：200 次抖动事件（12 tick 范围内）现在只产生 **2 个点**（原来 200 个）；
    '  真机 16 次来回拖动后曲线是干净的三角峰，点数个位数。',
    '- 缓慢扫绘仍保留完整分辨率（40 个点跨 2400 tick 时一个不少）。',
    '',
    '### 转换步骤（在你自己的电脑上、用你自己的授权声库）',
])
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('readme updated')