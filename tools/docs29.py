# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：2.8（versionCode 16）', '版本：2.9（versionCode 17）')
entry = '\n'.join([
    '### 本版（v2.9）：缩放同步 / BPM 两位小数',
    '',
    '- **手指缩放（含双击标尺重置）时，缩放滑块与读数同步更新**：卷帘新增缩放回调，任何来源的缩放变化都会回写滑块。',
    '  实测：双击标尺让卷帘自己重置缩放 → 读数从 100% 变为 147%（真实值，滑块吸附到最近的档位）。',
    '- **BPM 支持两位小数**：滑块分辨率 0.01 BPM（40.00–240.00），读数显示如 120.00；',
    '  点「速度」名称打开的数字对话框同样保留两位小数（20–400 仍可手输）。存储一直是百分之一 BPM，没有精度损失。',
    '  实测：拖动滑块 120.00 → 240.00。',
    '',
    '### 转换步骤（在你自己的电脑上、用你自己的授权声库）',
])
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('readme updated')