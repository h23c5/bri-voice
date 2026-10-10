# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：3.1（versionCode 19）', '版本：3.2（versionCode 20）')
d = d.replace('1686A522F5847B972E7594E8F14918829E85022EC7294D4BB73A5ECAF78BA1FF', 'PENDING')
entry = "### 本版（v3.2）：画笔 / 直线 / 选择 三个工具（悬浮在参数区右上角）\n\n参数区右上角多了一条悬浮工具条：**画笔 | 直线 | 选择**，当前工具高亮，不占用额外行高。\n\n| 工具 | 行为 |\n|---|---|\n| 画笔 | 自由绘制（沿用 v3.0/v3.1 的去抖动：邻近点合并 + RDP 简化） |\n| 直线 | 按下定起点、拖动预览、松手落笔；在两点间按固定间隔采样成一条**笔直的**线段，不再受手指抖动影响 |\n| 选择 | 不产生任何新点；点中 16dp 内的控制点会显示选中环，拖动可移动该点 |\n\n实测（真机）：直线工具从 (600,900) 拖到 (1100,840) 得到一条干净直线折角；选择工具点中起点后出现粉色选中环。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')