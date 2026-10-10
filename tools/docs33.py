# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：3.2（versionCode 20）', '版本：3.3（versionCode 21）')
d = d.replace('09DE55644CC3406A5ADC5A8A7D35350224B6173169DAC7A3E172EB046844D307', 'PENDING')
entry = "### 本版（v3.3）：修「两个音轨无法同时播放 + 报错」\n\n两个独立的原因，都会让人以为「第二轨不发声」：\n\n**1. 空音色导致渲染报错（根因）**\n\n删掉内置歌姬之后，`AudioEngine.modelFor` 里还留着按名字兜底的旧代码：音色名含 tianyi/miku 时去\n`SingerLibrary.byId(...)` 取内置音色——内置已经不存在，于是返回 null，渲染器拿到空音色直接抛异常。\n任何**音色槽与已装声库不是精确匹配**的片段（例如第二轨、或别人的 .vsqx）都会触发。\n\n现在改成：在**已安装的声库**里做宽松名字匹配，匹配不到就用当前选中的声库；并且渲染前再加一道空值保护，\n最坏情况给出「没有可用的声库」的可读提示而不是崩溃。\n\n**2. 载入声库期间按播放会被误判为「没有声库」**\n\n声库是后台解析的（两个包在手机上要几秒），如果在这期间按播放，就会看到「还没有声库」——即使状态栏随后\n显示「已安装 2 个声库」。现在会提示「正在载入声库，请稍候…载入完成后会自动开始播放」，并在载入结束时自动接着播。\n\n**验证**：新增 TwoTrackMixTest，用双轨工程渲染两轨求和后做频谱检查——Track 1（240-300 Hz）能量 0.0275、\nTrack 2（320-380 Hz）能量 0.0469，两轨同时存在；真机播放 5 秒无报错。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')