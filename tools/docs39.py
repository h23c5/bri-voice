# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：3.8（versionCode 26）', '版本：3.9（versionCode 27）')
d = d.replace('500B61A0FD1CCF3291F623B097BAB25A8F4CD59257AE2F3C9B68ABBE205BD05F', 'PENDING')
entry = "### 本版（v3.9）：免责声明改为详细条款，并删除「从 VOCALOID 提取」的表述\n\n**免责声明**改成 7 条完整条款（中/英/日三语），要点：\n1. 个人独立开发的非商业项目，按「现状」提供，不附带任何明示或默示担保；\n2. 与 Yamaha 及任何声库厂商无隶属/赞助/合作关系，产品名与商标仅用于说明格式兼容性；\n3. 本体不含任何第三方采样或声库文件，内置声库为参数化统计数据（谐波包络与频段增益）；\n4. 使用者自行导入的声库/音频，其来源合法性、授权范围与后果由使用者负责，请勿再分发；\n5. 不联网、不收集也不上传任何个人信息；\n6. 法律允许范围内作者不承担使用导致的任何损失；\n7. 不同意条款请停止使用并卸载。\n\n**删除**了此前「所有声库数据均由使用者自行从其合法授权的 VOCALOID 产品中提取」这类表述，\n涉及三处：关于对话框的免责声明、声库说明对话框、未安装声库时的提示。\n现在只保留必要的**格式兼容性**说明（读取/写入 VOCALOID4/3 .vsqx 与 VOCALOID2 .vsq）。\n\n顺带把「关于」和两个声库对话框全部改为**跟随界面语言**（原来这几处是硬编码中文，中英日界面下都显示中文）。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')