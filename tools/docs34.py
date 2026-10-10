# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：3.3（versionCode 21）', '版本：3.4（versionCode 22）')
d = d.replace('117DB761370811E4BFB014DCC4DFCBAE5E099EDF223ACAEFE5D39CB0D99F6800', 'PENDING')
entry = "### 本版（v3.4）：修双轨渲染的 ArrayIndexOutOfBoundsException\n\n报错内容：`渲染失败: java.lang.ArrayIndexOutOfBoundsException: length=99225; index=122598`。\n\n**根因**：渲染缓冲区是按片段存的 `dur` 分配的，而**音符可以落在 `dur` 之外**——新建音轨时 `Part.dur` 是默认值，\n在卷帘里画的音符不会自动把它撑大（第二轨最容易踩到；外部程序写的 .vsqx 也可能这样）。呼吸声效果按「音符间隙」\n往缓冲区写数据时，位置来自音符（122598），越过了缓冲区末尾（99225），于是抛异常。\n\n**修法**（三层）：\n1. 分配缓冲区时取 `max(片段 dur, 所有音符的结束 tick)`；\n2. 呼吸声写入加边界保护（`idx < 0 || idx >= out.length` 就跳过）；\n3. 编辑时同步维护 `Part.dur`——加音符、拖长度都会把 dur 撑到覆盖所有音符，新工程不再产生这种数据。\n\n**验证**：新增 PartDurTest——故意把 `dur` 设成 480 而音符排到 tick 21120，现在渲染出 981,225 采样（需要 ~970,200）、\n不抛异常且非静音；真机双轨工程完整播放 5 秒、无报错（logcat 干净）。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')