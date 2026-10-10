# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "49", "--version-name", "6.8",', '"--version-code", "50", "--version-name", "6.9",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="49"', 'android:versionCode="50"').replace('android:versionName="6.8"', 'android:versionName="6.9"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：6.8（versionCode 49）', '版本：6.9（versionCode 50）')
entry = '\n'.join([
  '### 本版（v6.9）：声库 id 与名称对齐真实 V4',
  '',
  '继续排查“能读但没数据”，发现 `<vVoice>` 的 id 是编造的：',
  '',
  '| 字段 | 真实 V4 | 修复前 | 现在 |',
  '|---|---|---|---|',
  '| `vVoice/id` | `BK8H76TAEHXWSKDB` | `BCXNNPJJQZRQZ8B5`（编造） | `BK8H76TAEHXWSKDB` |',
  '| `vVoice/name` | `Luotianyi_CHN_Meng` | `洛天依 BV`（显示名） | `Luotianyi_CHN_Meng` |',
  '| `<p>` 音素 | 多符号（`k_h a_n`） | v6.7 误改为只写最后一个 | 完整序列 |',
  '| `Demo` 里的 id | …WSKDB | …WS**Z**DB（打错一个字母） | …WSKDB |',
  '',
  '验证：**用真实工程做往返**（魔术之心 602 音符），输出与原件在结构与全部受检字段上**完全一致**。',
  '工具：`tools/format_selftest/RoundTrip.java`。',
])
if '本版（v6.9）' not in d:
    d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry + '\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）', 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('6.9 configured')