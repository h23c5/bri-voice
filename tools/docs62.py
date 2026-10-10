# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "42", "--version-name", "6.1",', '"--version-code", "43", "--version-name", "6.2",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="42"', 'android:versionCode="43"').replace('android:versionName="6.1"', 'android:versionName="6.2"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：6.1（versionCode 42）', '版本：6.2（versionCode 43）')
d = d.replace('F0622A508F563D256B10E7D657D36E922735C7E7BA02193853989AC9E78AC663', 'PENDING')
entry = "### 本版（v6.2）：帧引擎修好（+5.4 → −32.4 dB 杂散）；元音共振峰核对发现**数据层错误**\n\n**1. 帧引擎的幅度轨迹（已完成）**\n\n按你的要求把逐跳线性斜坡改成一阶平滑（20 ms），过程中又查出两个真实 bug：\n\n- `dphi`（每谐波的频率步进）被我误放进 `restart` 分支 → **整个音符只算一次频率**，颤音/滑音时全部谐波失谐；\n- 未平滑的频率控制率阶梯。\n\n| 指标（长音 a4） | 修复前 | **修复后** | 旧波表引擎 |\n|---|---|---|---|\n| 谐波间杂散能量（越低越干净） | +5.4 dB | **−32.4 dB** | −39.7 dB |\n| 与实测包络相关度 | 0.753 | **0.975** | 0.984 |\n\n即杂散降低 **37.8 dB**，已接近旧引擎；保真回归 7/7 全过，电平已标定。\n「帧序列拼接」目前在设置里**默认关闭**——修好了但我听不到，请你在设备上打开对比后决定是否设为默认。\n\n**2. 元音共振峰核对：问题不在合成器，在声库数据**\n\n我测了实际渲染出的元音包络（直接读谐波，即耳朵拿到的共振峰），结论：\n\n- 合成器**忠实还原**了包里的曲线（相关度 0.984）；\n- 但**曲线本身把共振峰放错了位置**。例如 /i/（应有低 F1≈300、强 F2≈2400）：\n\n  `60Hz:0  112:0  208:0  248:0  386:-6  718:-20  1336:-41  2486:-37  2968:-33`\n\n  能量全在 **60–250 Hz**，F2（2.4 kHz）只有 −37 dB——**完全倒过来了**；\n- 另外包里的 **/o/ 和 /u/ 完全没有数据**。\n\n这正是「咬字不清、要脑补」的直接原因：/i/ 类音节（shi、yi、li…）拿不到该有的高频共振峰。\n\n**最可能的原因**：转换时把 FRM2 帧**归类到了错误的音素**（帧→音素的匹配环节），或音素索引表读错；\n而不是频率网格或 f0 映射的问题（转换器直接读取文件里的 k*f0 频率行，且校验过 spacing_err）。\n\n**要继续修需要你提供源声库**（.ddb / .ddi / .vvd）——工作区里已经没有了。\n拿到后我会：① 导出每个音素的帧分配与实测 F1/F2；② 与标准元音共振峰逐条比照；③ 重新打包并用 VowelFormantTest 复验。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')