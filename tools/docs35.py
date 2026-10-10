# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：3.4（versionCode 22）', '版本：3.5（versionCode 23）')
d = d.replace('8D96504DB7D323E2B286E5ED709E55B8985D47BC893BB23AAF250D6846A37CED', 'PENDING')
entry = "### 本版（v3.5）：删除音符后立刻又被重建\n\n**原因**：卷帘里「在空白处轻点 = 新建音符」，而这个判定只检查了「没有移动」，**没有检查按了多久**。\n所以任何在空白处的抬手——包括长按——都会在落点新建一个音符；删除后的抬手自然就「把刚删掉的音符又建回来」。\n\n**修法**（两道闸）：\n1. 只有**短按**（时长小于长按阈值）在空白处才会新建音符，长按不再误建；\n2. 删除动作（长按删除 / 双击撤销刚建的音符 / 删除选中）都会记录时间戳，**删除后 400ms 内的新建一律忽略**。\n\n**验证**（真机）：卷帘空白处轻点 → 状态栏「编辑: add la」（仍能新建）；同一位置长按 900ms → 状态栏不变，**没有再建一个音符**\n（修复前同样操作会显示「编辑: add yi」）。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')