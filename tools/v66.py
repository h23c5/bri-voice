# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "46", "--version-name", "6.5",', '"--version-code", "47", "--version-name", "6.6",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="46"', 'android:versionCode="47"').replace('android:versionName="6.5"', 'android:versionName="6.6"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：6.5（versionCode 46）', '版本：6.6（versionCode 47）')
d = d.replace('718B5BB8F078275AAB9C06379EBE1A9F8153C1E48B1B876905F17A72B1B738EA', 'PENDING')
entry = '\n'.join([
  '### 本版（v6.6）：修复“手机导出的工程在 VOCALOID4 编辑器打不开”',
  '',
  '用户报告：电脑 → 手机正常，手机 → 电脑时 VOCALOID Editor 报',
  '`Verify error ... CVSMusicalNote { posTick=480 durTick=440 noteNum=60 velocity=64 lyric=yl phLock=0 phoneme=a }`。',
  '',
  '**原因**（对比真实 VOCALOID4 工程得出）：我们写出的音符缺两样东西——',
  '1. `<p>`（音素）为**空**；2. 没有 `nStyle` 块。',
  '编辑器对每个音符做校验，两者任一都会报错；真实工程里每个音符都带 9 个标准属性。',
  '',
  '**修正**：`<p>` 空时用应用自己的 G2P 从歌词推出音素（与渲染用的是同一套）；`nStyle`/`noteStyle` 改为**始终输出**，',
  '并补齐 9 个标准属性（accent / bendDep / bendLen / decay / fallPort / opening / risePort / vibLen / vibType），',
  '项目自带的样式值会覆盖默认。v3 与 v4 两种格式都已修正。',
  '',
  '**验证**：新增 `tools/format_selftest/VsqxNoteFixTest.java`，从空工程写出 6 个音符并逐个断言',
  '（音素非空 + 样式块存在 + 属性 ≥ 9）：**ALL PASS (24 checks)**。',
  '',
  '注意：旧版本已导出的 .vsqx 仍然是坏的，需用 v6.6 重新打开并保存一次。',
])
if '本版（v6.6）' not in d:
    d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry + '\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）', 1)
    io.open(r, 'w', encoding='utf-8', newline='').write(d)
    print('readme updated')
print('6.6 configured')