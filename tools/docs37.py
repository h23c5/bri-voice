# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：3.6（versionCode 24）', '版本：3.7（versionCode 25）')
d = d.replace('6E8573FD3AF4F50A57349B5901B7035B192550C14E2D06BF335FC504C2B6EB36', 'PENDING')
entry = "### 本版（v3.7）：声库统一命名「洛天依 BV」「Miku BV」\n\n命名做了**两层**，确保不论声库来自哪里都显示一致：\n\n1. **包内改名**：改写 .v4voice 里 voice.json 的 name/nameEn/nameJa（内置洛天依、初音未来两个包），\n   **id 保持不变**，所以不会在列表里多出重复条目；转换工具 voicebank_pack.py 的默认名字也同步改了。\n2. **显示名归一化**：应用在显示时把已知歌姬统一为「洛天依 BV」「Miku BV」，\n   **所以你自己设备上那些还是旧名字的外部声库，不用重新导入也会显示新名字**。\n\n另外把新工程的默认音色槽名也改成了「洛天依 BV」。\n\n**验证**（真机、干净安装）：歌手列表显示「洛天依 BV（内置）」与「Miku BV（我的声库）」；\n并专门做了对照实验——把一个内部名字仍是「初音未来 V4 中文」的包放进声库目录，界面同样显示「Miku BV」。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')