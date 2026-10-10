# .v4voice 声库包格式（v1）

用户自备、可在应用内"添加声库"的歌手包。**只包含可再分发的参数与用户自己从已购声库中提取的数据；
本仓库不附带任何官方声库内容。**

## 容器
ZIP（UTF-8），必须包含 `voice.json`，可选 `LICENSE.txt`（来源与授权说明）、`README.txt`。

## voice.json

```json
{
  "format": "v4voice/1",
  "id": "luotianyi_v4_meng_bank",
  "name": "洛天依 V4 中文 · 萌（我的声库）",
  "nameEn": "Luo Tianyi V4 CHN (Meng, imported)",
  "lang": 2,
  "compID": "BK8H76TAEHXWSKDB",
  "source": {
    "kind": "vocaloid4-voicebank",
    "bank": "Luotianyi_CHN_Meng",
    "path": "E:/LuoTianyiV4_CHN/BK8H76TAEHXWSKDB",
    "extractedAt": "2026-10-06T14:30:00+08:00",
    "tool": "tools/voicebank_pack.py v1"
  },
  "voice": {
    "formantScale": 1.05, "f1Mul": 1.0, "f2Mul": 1.0, "f3Mul": 1.0,
    "tiltBias": 0.0, "breathBias": 0.05, "oqBias": 0.0,
    "vibratoRate": 5.5, "vibratoDepthCents": 0.0,
    "gain": 1.0, "consonantGain": 1.0, "breathSoundAmount": 0.3,
    "description": "从本机已购声库提取的音色参数（非采样再分发）"
  },
  "shelf": [[60, -3.0], [200, 0.0], [800, 1.5], [3000, 2.0], [6000, -1.5], [12000, -4.0]],
  "formants": { "a": [850, 1200, 2500], "i": [300, 2300, 3000], "uo": [400, 800, 2400] },
  "stats": { "frames": 20000, "f0Median": 210.0, "f0Min": 110.0, "f0Max": 900.0,
             "clusters": 16, "matchedPhonemes": 62 },
  "notes": "可选说明"
}
```

- `shelf`：全局音色 EQ，`[频率Hz, 增益dB]` 升序，2–12 个点，应用在合成输出上（双二阶 peaking 级联）。
- `formants`：音素符号 → `[F1,F2,F3]`（Hz）。键用 VOCALOID 汉语 62 音素符号（`a`、`iE_n`、`ts\`_h` …）。
  未知/缺失的音素走应用内置表；应用只覆盖给出的项。
- 所有字段可选；缺失即用内置默认。`id` 与 `name` 必填。

## v2 增补：真实谐波数据（"官方采样"路径）

`format` 仍写 `"v4voice/1"`（向后兼容），出现 `harmonics` 段时应用会启用**谐波合成**：
用真实声库 FRM2 帧测得的谐波幅度谱直接驱动一组正弦谐波，音色来自声库本身而不是共振峰模型。

```json
"harmonics": {
  "freqs": [60, 68.9, 79.1, "... 共 32..96 个对数等距点 ...", 16000],
  "source": "VOCALOID4 FRM2 frames of Luotianyi_CHN_Meng (measured harmonic magnitudes)",
  "phonemes": {
    "a":    [ {"f0": 219.0, "db": [ -38.2, -35.0, "... 与 freqs 等长 ..." ]},
              {"f0": 329.0, "db": [ ... ]},
              {"f0": 428.0, "db": [ ... ]} ],
    "iE_n": [ { "f0": 329.0, "db": [ ... ] } ]
  },
  "noise": { "s": {"freqs":[...], "db":[...]}, "sh": {...} }
}
```

- `freqs`：共享频率栅格（Hz，升序，32–96 点，对数等距，建议 60 Hz–16 kHz）。
- `phonemes`：音素符号 → 若干**频高段**（band）。每段的 `f0` 是该段帧的中位 f0，`db` 是与 `freqs` 等长的相对 dB（段内最大值归一到 0 dB）。
  应用按音符实际 f0 选最近的段；段间不插值。缺失的音素回退到内置共振峰模型。
- `noise`（可选）：清辅音（s / sh / x / f / h …）的实测噪声谱，同样格式；应用用它替代内置的 `noiseFc/noiseBw`。
- 体积：64 点 × int8(0.5 dB) 量化，62 音素 × 3 段约 12–20 KB/声库。

## 应用侧行为
- 导入：复制到 `filesDir/singers/<id>.v4voice`，与内置歌手一起出现在"歌手"列表里（标记"我的声库"）。
- 删除：仅删该文件；内置歌手不可删。
- 解析失败：不安装，提示原因。
- 优先级：包内 `formants` > 包内音色参数 > 内置表。
