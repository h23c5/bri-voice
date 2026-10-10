# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\RELEASE.md'
d = io.open(p, encoding='utf-8').read()
old = '- 宣传片：`build/BriVoice_release.mp4`（3 分 08 秒，1280×720，含真机演示）'
new = '\n'.join([
    '- 宣传片：`build/BriVoice_release.mp4`（**1 分 49 秒**，1280×720，17.4 MB）',
    '  - 结构：开场动画 10s → 01 声库与音轨 → 02 音符与歌词 → 03 AI 调音 → 04 参数精调 → 05 试听与工具 → 片尾名单 12s → 尾帧 12s',
    '  - 配乐：《24/7》Synth（用户提供，人声之下）；音效：程序生成的升音、过场、铃声与收尾和弦',
    '  - 已剔除：空工程等待画面、黑屏等待段、静态免责声明页',
    '- 分镜图：`build/trailer/final_verify.png`（最终版验收帧）',
])
if old in d:
    d = d.replace(old, new, 1)
    d = d.replace('- 分镜图：`build/trailer/final_sheet.png`', '', 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(d)
    print('RELEASE.md updated')
else:
    print('anchor not found; appending')
    d += '\n' + new + '\n'
    io.open(p, 'w', encoding='utf-8', newline='').write(d)