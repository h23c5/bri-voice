# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：3.5（versionCode 23）', '版本：3.6（versionCode 24）')
d = d.replace('5789169851A924F660EC2D0DF249DAC4661F3FB3C2AB590664653EB86973E6AE', 'PENDING')
entry = "### 本版（v3.6）：洛天依内置进 APK\n\nAPK 现在自带一份洛天依声库（`app/assets/singers/luotianyi_chn_meng_bank.v4voice`，72 KB）：\n\n- **首次启动自动安装**：从 assets 复制到声库目录，之后走的是和导入声库**完全相同**的那一条代码路径；\n- 安装过一次就记标记：**你在界面里把它删掉，它不会在下次启动时偷偷回来**；\n- 界面标注「（内置）」，与你自己导入的声库区分开；\n- 三语标签：内置 / (bundled) / （同梱）。\n\n**验证**（干净安装：`pm clear` + 清空两个声库目录）：启动后直接显示「已安装 1 个声库：洛天依 V4 中文·萌（内置）」，\n无需任何导入；随后播放示例曲目完整唱完 11 秒、logcat 无异常。APK 体积 0.61 MB → **0.68 MB**（710,084 字节）。\n\n**关于内容**：包里不是原始采样，而是从你有授权的声库**实测**出来的谐波包络与 12 帧频谱序列（每音素 × 音高 × 力度）。\n把 APK 发给别人等于把这些派生数据一起发出去，这一点请你自己把握。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')