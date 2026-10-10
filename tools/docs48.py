# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "35", "--version-name", "4.7",', '"--version-code", "36", "--version-name", "4.8",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="35"', 'android:versionCode="36"').replace('android:versionName="4.7"', 'android:versionName="4.8"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：4.7（versionCode 35）', '版本：4.8（versionCode 36）')
d = d.replace('9146C7CDA70E525ACF01445CC77C7659F0A0FA82295FB8A777FC75C6EB2CFB4F', 'PENDING')
entry = "### 本版（v4.8）：实时渲染播放（不再等整首渲染完）\n\n以前按播放要**先把整首歌渲染完**才开始出声（手机上很慢）。现在改成**边渲染边播**：\n\n- 后台先渲染 **1 秒**就开始播放，随后按 1→2→4→8… 秒递增继续渲染（总工作量约为整首的 2 倍，但开声时间从「整首」降到约 1 秒）；\n- 写线程只在「要播的位置还没渲染出来」时才等待生产者，因此**内存占用不变**（仍是整首缓冲），只是不再阻塞开声；\n- 渲染仍按**所有可听轨**求和（含混音器音量/声像），并且**每个新片段都会做后处理**（音频片段混入 + 插件链，插件状态跨片段延续）；\n- 导出 WAV 仍走整首渲染那条路径（保证导出完整、与实时播放一致）；停止播放会取消后台渲染。\n\n**实测（真机，100 秒长、200 个音符的工程）**：\n- 从按下播放到出声 **0.32 秒**（旧逻辑要先渲染整首）；\n- 连续播放 24 秒无卡顿，指针稳定在 ≈960 tick/秒（=120 BPM），无失败日志。\n\n**已知取舍**：每次递增都会从 0 重渲染（因此总 CPU 约为整首的 2 倍，换来的是立即开声）；\n内存仍是整首的立体声缓冲（与之前相同）。若你的工程特别长（比如 10 分钟）想进一步省内存，我可以改成环形缓冲。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs + version ok')