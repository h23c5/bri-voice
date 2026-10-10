# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "32", "--version-name", "4.4",', '"--version-code", "33", "--version-name", "4.5",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="32"', 'android:versionCode="33"').replace('android:versionName="4.4"', 'android:versionName="4.5"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：4.4（versionCode 32）', '版本：4.5（versionCode 33）')
d = d.replace('A7822692A08CCB9957BB2C12BF2CA1424DAA22C0CA2E7137D97C22BD56A4C2AC', 'PENDING')
entry = "### 本版（v4.5）：音频片段轨道（可拖动对齐）+ 修复「导入 WAV 后没有声音」\n\n**1. 修复「导入 WAV 后没有音乐」**（真因有两个，都已修）：\n\n- 导入时片段被放在**当前播放头**。播放过一遍之后播放头停在曲尾，片段就落在渲染范围之外，\n  **完全不会被混音**，但界面还报「已导入」——这就是你遇到的「没声音」。现在若播放头已在曲尾，自动改为放在开头并在状态栏说明；\n- 渲染缓冲区只按歌曲长度分配，**比歌长的 WAV 会被截断**。现在会按片段实际长度自动扩容，整段都能听到。\n\n**2. 卷帘底部新增「音频片段轨道」**：导入的音频以方块显示，与时间轴共用刻度；\n**按住方块左右拖动即可对齐**（吸附跟随「吸附」设置），拖动算一次可撤销的操作，状态栏会提示新位置。\n空白处拖动仍是滚动视图。\n\n**3. 外部接口新增三个动作**，方便脚本化：`importWav {path}`、`moveClip {index,startTick}`、`exportWav {path}`；\n`/status` 现在也会返回当前轨道的片段列表。\n\n**验证（端到端、可复现）**：\n- 造一个 4 秒 1500 Hz 测试音频 → 通过接口 `importWav` 导入 → `/status` 显示 `clips:[{startTick:0,frames:176400}]` ✓；\n- `moveClip` 移到 tick 3840（4 秒）→ `exportWav` 导出混音 → 拉回做频谱：\n  **5.0 秒处 1500 Hz 能量 0.06103**，0.2 秒处仅 0.00002，对照频段（1800 Hz）0.00000，人声频段 0.00516 —— 音频按位置混入、且不影响人声 ✓；\n- 真机截图：卷帘底部出现 `tone1500.wav` 方块，与刻度对齐 ✓；i18n 3030/3030（292 键）。\n\n**关于「选择」工具**：片段轨道的拖动是**直接触摸**的（不需要先切工具），在卷帘里任何工具下都能拖。\n如果你要的是「必须在参数区切到『选择』才能拖」，告诉我，我把门的开关接到那个工具上。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs + version ok')