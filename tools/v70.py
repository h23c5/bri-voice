# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "50", "--version-name", "6.9",', '"--version-code", "51", "--version-name", "7.0",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="50"', 'android:versionCode="51"').replace('android:versionName="6.9"', 'android:versionName="7.0"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：6.9（versionCode 50）', '版本：7.0（versionCode 51）')
entry = '\n'.join([
  '### 本版（v7.0）：新建工程也能在 V4 里显示',
  '',
  '用户做了三格对照实验：原件有数据 ✓、往返文件有数据 ✓、**新建工程没数据** ✗。',
  '说明读写正常，问题只在新建工程的默认值。对比后修正：',
  '',
  '| 字段 | 有数据的文件 | 新建工程（修前） | 现在 |',
  '|---|---|---|---|',
  '| `masterTrack/seqName` | `Untitled0` | **空** ✗ | `Untitled0` |',
  '| `masterTrack/preMeasure` | 1 | 4 | 1 |',
  '| `vsTrack/name` | `Track1` | `Track 1` | `Track1` |',
  '',
  '空的 `<seqName>` 对应就是编辑器报的“**没有序列**、没有数据”。',
  '',
  '之前试过一个猜测：把片段起始从 tick 0 移到 1920，结果把首些音符变成了**负 tick**（音符在模型里是绝对位置），',
  '已撤回。保留的修正都是安全的默认值。',
])
if '本版（v7.0）' not in d:
    d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry + '\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）', 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('7.0 configured')