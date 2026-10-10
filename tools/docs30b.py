# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：2.9（versionCode 17）', '版本：3.0（versionCode 18）')
d = d.replace('8F8AB11EEA6481B4183CF8AC20477555C72C70BAD680A58856935A96D817AA1B', '57928F4D53344539DDFFB81E5FB672105F37756E846913991BC4CA6AC6B5C6E3')
entry = "### 本版（v3.0）：修掉参数曲线拖动的「梳齿」\n\n- **现象**：在参数区连续来回拖动时，曲线变成一排密集的竖线（就是那张截图）。\n- **原因**：每个触摸事件都调用 `putRaw` 插点，而它只在 tick **完全相等**时才替换；手指抖动落在相邻 tick 上，\n  于是一次来回就堆出几百个点，画成一排重叠的圆点。\n- **修法**：曲线新增 `putNear(tick, value, gap)`，拖动时优先**复用 2.5 像素内的已有控制点**；\n  绘制时相邻圆点间距小于自身直径就不再重复画（只留折线）。\n- **实测**：200 次抖动事件（12 tick 范围内）现在只产生 **2 个点**（原来 200 个）；\n  真机 16 次来回拖动后曲线是干净的三角峰。缓慢扫绘仍保留完整分辨率（40 点跨 2400 tick 一个不少）。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('readme updated')