# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "34", "--version-name", "4.6",', '"--version-code", "35", "--version-name", "4.7",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="34"', 'android:versionCode="35"').replace('android:versionName="4.6"', 'android:versionName="4.7"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：4.6（versionCode 34）', '版本：4.7（versionCode 35）')
d = d.replace('D1436062F351BEA9D0BC4DE7F0833051279EAE1F9F8C62C03812C38AC6F40E5F', 'PENDING')
entry = "### 本版（v4.7）：指针跟声音对齐 + 拖动片段不再重置视图\n\n**1「指针太快、声音出来时已经到第 5 个音」**\n\n先做了实测（通过接口每 250 ms 采一次播放位置）：指针速度**是正确的**——120 BPM 下约 960 tick/秒，与节拍一致。\n所以问题不是速度，而是**指针比听到的声音早**。原因：引擎用墙钟推算位置，没有算设备输出缓冲。\n\n改动：指针改为跟随 **AudioTrack 的实际播放头位置**（`getPlaybackHeadPosition`），并以墙钟为上界（保证不会往前跳）。\n实测改后指针落后约 76 ms（= 设备缓冲）。\n\n**但如果你的设备（模拟器/蓝牙）输出延迟远大于缓冲**，应用无法自己测到那段时间——所以设置里新增\n「**指针延迟补偿**」滑块（0–1000 ms，默认 0）：听到的声音比画面慢多少，就把它拉回多少。\n\n**2「拖动 WAV 音频时会重置轨道位置」**\n\n根因找到：`PianoRollView.setSong()` 会把滚动位置归零并重设缩放，而**每次编辑后的 `refreshAll()` 都会调它**——\n所以一松手（提交拖动）视图就跳回开头。现在新增 `setSongKeepView()`（记住并恢复滚动/缩放），编辑路径改用它；\n只有真正加载工程时才重置视图。\n\n**验证（真机）**：先把视图拖到第 3–4 小节，再拖动片段 → `startTick` 由 0 变 **1680**（状态栏「移到 001:4 01.75s」），\n而**视图仍停在第 3–4 小节**（前后截图一致）✓。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs + version ok')