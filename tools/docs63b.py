# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：6.2（versionCode 43）', '版本：6.3（versionCode 44）')
d = d.replace('47FAC8C8F6DBE0FA82013351DFAA35F5EBA7E6F858B9CCA765B32F220BF49642', 'PENDING')
entry = "### 本版（v6.3）：修复声库数据层的元音混淆（真机试听反馈的根因）\n\n你反馈「咬字不清」。查到两层原因：\n\n**1. 部分元音在声库里是完全相同的频谱**\n转换器把每个音素映射到「最接近的 k-means 聚类」，共享聚类的音素拿到逐字节相同的曲线：\n\n| 元音对 | 原包 | 修复后（洛天依 / Miku） |\n|---|---|---|\n| o / ɤ(7) | 0.00 dB（相同） | 2.9 / 2.9 dB |\n| o / u | 0.00 dB | 2.0 / 1.9 dB |\n| ɤ / u | 0.00 dB | 3.0 / 3.0 dB |\n| y / i` | 0.00 dB | 2.7 / 2.7 dB |\n| 最近的一对（全体） | 0.00（无法区分） | **1.79 / 1.56 dB（可区分）** |\n\n**2. 修法**：不再依赖聚类量化，改为**逐帧按共振峰分配**——每一帧归给它最像的音素（只在该音素明显最近时才接受，\n含糊的过渡帧丢弃），再用这些帧重建该音素的曲线。\n\n同时验证了精度没有被牺牲（与参考共振峰的平均对数距离，越小越准）：\n\n| | 原包 | 修复后 |\n|---|---|---|\n| 洛天依 BV | 0.854 | **0.592** |\n| Miku BV | 0.597 | **0.489** |\n\n**已知取舍**：重建的 14 个符号只带平均曲线，**没有帧序列**（序列是同一段录音的连续帧，跨单元无法重建）；\n其余 47 个符号保留原有序列，帧演化回归（SequenceEvolutionTest）两包均通过。\nApp 在 `seqN < 2` 时会退回平均曲线，所以不会出错。\n\n**实测依据**：`VowelSeparationTest`（元音两两可区分度）、`VowelFormantDeviation`（共振峰偏移）、\n`PackVowelAudit`（共振峰位置）、pack 自检 2/2、渲染保真 7/7、乐句 4/4、DYN 2/2、时长 3/3、双轨 4/4。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs ok')