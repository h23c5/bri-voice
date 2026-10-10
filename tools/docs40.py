# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：3.9（versionCode 27）', '版本：4.0（versionCode 28）')
d = d.replace('0C8BBF8EA87E44B734B86FAA86BD63D166A5A7E3803987DDD07E93D638E8D83F', 'PENDING')
entry = "### 本版（v4.0）：子级界面美化\n\n以前所有二级界面都是系统默认列表对话框：一行灰字、没有层级、看不出当前选中的是哪个。\n现在新增 `ui/Dialogs.java`，统一成一套语言：\n\n- **圆角夜间卡片**（沿用 dialog_bg），标题 18.5sp 加粗 + 一行灰色副标题 + 强调色分隔线；\n- **圆形彩色徽标**：从条目文字自动取一个字（导入声库→导、混音器→混），七色循环，无需为每条目配图；\n- **当前项高亮**：强调色文字 + 半透明底 + 描边 + 右侧 ✓（歌手列表里一眼看出正在用哪个声库）；\n- 行内点击波纹、54dp 最小行高、列表区最多占屏高 62% 并可滚动，弹窗宽度固定 86%。\n\n改造范围（共 9 个二级界面）：**选择歌姬、工具菜单、声库管理、音轨菜单、音频片段、插件选择、添加插件、播放设备、语言切换、删除声库**。\n顺带把此前仍是硬编码中文的 4 处标题（选择歌姬/音轨/工具/声库管理/删除哪个声库）接入 i18n，中英日界面一致。\n\n**新增 i18n 键** 10 个（歌姬列表副标题与实测统计、声库删除确认、状态提示等），总计 243 键，自测 2538/2538 通过。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')