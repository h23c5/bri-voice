# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "47", "--version-name", "6.6",', '"--version-code", "48", "--version-name", "6.7",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="47"', 'android:versionCode="48"').replace('android:versionName="6.6"', 'android:versionName="6.7"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：6.6（versionCode 47）', '版本：6.7（versionCode 48）')
entry = '\n'.join([
  '### 本版（v6.7）：修复“导入后没有序列、没有数据”',
  '',
  'v6.6 修好了 Verify error 后，VOCALOID4 编辑器能打开并识别到歌手，但片段里没有音符。',
  '与真实 V4 工程逐元素对比后发现，`vsPart` 缺两个子元素，且音素写法不符：',
  '',
  '| vsPart 子元素 | 真实 V4 | 修复前 | 修复后 |',
  '|---|---|---|---|',
  '| pStyle（7 个属性） | 有 | **缺失** | 有 |',
  '| plane | 有（在音符之后） | **缺失** | 有 |',
  '| <p> 音素 | 单个符号（uo） | `l uo`（两个） | `uo` |',
  '',
  '修正：片段始终输出 `pStyle`（accent/bendDep/bendLen/decay/fallPort/opening/risePort）与 `plane`；',
  '音素取 G2P 结果的**最后一个符号**（韵母），与 V4 自身约定一致（声母由歌词反推）。',
  '',
  '验证：结构对比已与真实 V4 工程逐子元素一致；VsqxNoteFixTest 仍 **ALL PASS (24 checks)**。',
])
if '本版（v6.7）' not in d:
    d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry + '\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）', 1)
d = d.replace('PENDING', 'PENDING')
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('6.7 configured')