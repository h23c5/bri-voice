# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "48", "--version-name", "6.7",', '"--version-code", "49", "--version-name", "6.8",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="48"', 'android:versionCode="49"').replace('android:versionName="6.7"', 'android:versionName="6.8"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：6.7（versionCode 48）', '版本：6.8（versionCode 49）')
entry = '\n'.join([
  '### 本版（v6.8）：与真实 V4 工程**结构完全同形**',
  '',
  'v6.7 后仍“能读但没数据”。继续逐字段对比，发现三处差异：',
  '',
  '| 字段 | 真实 V4 | 修复前 | 修复后 |',
  '|---|---|---|---|',
  '| `vender` | `Yamaha corporation` | `Yamaha Corporation`（大写 C） | `Yamaha corporation` |',
  '| `vVoice/bs,pc` | 4, 1 | **0, 0** | 4, 1 |',
  '| `vsPart/singer/bs,pc` | 4, 1 | **0, 0** | 4, 1 |',
  '| `aux`（VST 块） | 有 | 缺失 | 有 |',
  '',
  '歌手 id 为 0 时片段绑不到音色，编辑器就会把序列当空——这正是“能读没数据”。',
  '',
  '验证：现在导出文件与真实 V4 工程的**元素树差异为空**（逐层子元素与数量完全一致）。',
])
if '本版（v6.8）' not in d:
    d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry + '\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）', 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('6.8 configured')