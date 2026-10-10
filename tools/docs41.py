# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：4.0（versionCode 28）', '版本：4.1（versionCode 29）')
d = d.replace('54C9ACE5EC11BE1D4D74CAA94AC62910FB6483E3C56BF7211A5D46E9DDB1A360', 'PENDING')
entry = "### 本版（v4.1）：示例乐句改为「这里是BriVoice欢迎回来」\n\n九个音的旋律（C4 D4 E4 F4 G4 A4 G4 F4 E4，上行后回落），音符上直接显示这句原话。\n\n**为什么要多写一个 phoneme 字段**：实测发现\n- 汉字歌词直接交给 G2P 会退化成单个 [a]（`ChineseG2P` 只吃拼音，汉字→拼音是歌词填入那一步做的）；\n- `Bri` / `Voice` 会被猜成 [r,i] / [b,o,i,e]，唱出来完全不像。\n\n所以每个音同时带两样东西：`lyric` 放要**显示**的原字，`phoneme` 放**实测可用**的 CHN 音素符号\n（这些符号就是同一音节拼音的 G2P 输出，保证能唱）：\n\n| 显示 | 音素 | 显示 | 音素 |\n|---|---|---|---|\n| 这 | ts` 7 | Voice | w o i s |\n| 里 | l i | 欢 | x ua_n |\n| 是 | s` i` | 迎 | iN |\n| Bri | b z` aI | 回 | x uei |\n| | | 来 | l aI |\n\n**验证**：新增 DemoPhraseTest——乐句文本等于目标串、9 个音、**没有一个音退化成 [a]**、渲染 rms 0.0473（有声）；\n真机启动即看到新乐句、播放 7 秒正常结束。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')