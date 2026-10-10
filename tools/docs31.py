# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：3.0（versionCode 18）', '版本：3.1（versionCode 19）')
d = d.replace('57928F4D53344539DDFFB81E5FB672105F37756E846913991BC4CA6AC6B5C6E3', 'PENDING')
entry = "### 本版（v3.1）：再削掉参数曲线的小突起\n\nv3.0 之后还会看到零星小突起。原因是 compact 只按数值差合并，而抖动属于「值变了、但仍贴着同一条直线」，\n差值超过阈值就留下了。\n\n这一版在笔画结束时再加一道 Ramer-Douglas-Peucker 简化：逐个点量它到相邻保留点连线的垂直距离，\n小于阈值（0..127 量程上约 1 个单位，屏幕上约 1 像素）就删除。抖动被抹平，真正的折点和尖峰保留。\n\n实测：带 ±1 抖动的直线从 21 个点压到 2 个点；40 单位尖峰的峰值精确保留；三角波保留 3-5 个点。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')