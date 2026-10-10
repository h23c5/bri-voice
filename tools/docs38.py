# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：3.7（versionCode 25）', '版本：3.8（versionCode 26）')
d = d.replace('184FE6CF64CEDEF5309A83DB54C3E9A13A8FF1A2634B7046BFD86965D8059C3E', 'PENDING')
entry = "### 本版（v3.8）：新增「从头播放」按键\n\n播放键下方新增**从头播放**：把光标拉回第一拍并从 0 开始渲染/播放。三语标签：从头播放 / From Start / 最初から再生。\n\n行为细节：\n- 光标在曲中时点它 → 回到开头重新播放；\n- **正在播放时点它 → 先静默停止（不保留旧光标位置）再从头播放**，而不是把「停止位置」当成起点；\n- 尺寸与其它控件一致（96×38dp），沿用同一套按钮样式。\n\n**验证**（真机）：播放 6 秒后按停止（光标停在曲中），再点「从头播放」→\nlogcat `play(): session=2 frames=485100 startFrame=0`（**从第 0 帧开始**，而不是从停止处继续），\n状态栏显示「渲染完成，从 001:1 00.00s 开始播放」。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')