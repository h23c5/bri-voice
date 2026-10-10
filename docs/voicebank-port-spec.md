# 声库转换核心移植规格（Python → Java）

目标：把 `tools/voicebank_pack.py`（1251 行，PC 端命令行）移植进 App，供手机端「制作声库」向导调用。
本文只记录**可验证的事实**（行号指向 Python 源），实现时逐条对照，禁止凭印象写。

## 一、流水线（函数与职责）

| 阶段 | Python 函数（行） | 职责 |
|---|---|---|
| 1 元数据 | `read_vvd`(247) `read_phdc`(273) | 读 `.vvd` 取 VOICEIDSTR/VOICENAME；读 `.ddi` 取 62 符号 CHN 表 |
| 2 定位 | `ddi_directory_offsets`(290) `scan_frm2_offsets`(319) `scan_snd_offsets`(709) | 在 `.ddb` 里扫描 `FRM2` / SND 记录偏移 |
| 3 解码 | `decode_frame`(350) `frame_envelope`(396) `moving_average`(412) | 每帧谐波频率/幅度(dB)，包络平滑 |
| 4 聚类 | `kmeans`(420) `assign_clusters`(544) | k-means 分组（iters=40, seed=20261006）|
| 5 共振峰 | `pick_formants`(440) | 从质心+包络取 F1/F2/F3（带门限与间距约束）|
| 6 音素映射 | `match_phonemes`(474) | 符号↔簇**一对一**匹配（slack 1.12）|
| 7 谐波表 | `harmonic_grid`(525) `frame_harm_curve`(530) | 统一 64 点对数网格上的谐波曲线 |
| 8 序列 | `band_curve`(566) `frame_sequence`(596) `dyn_variants`(640) `pitch_bands`(680) | 帧序列与力度/音高变体 |
| 9 噪声 | `welch_psd`(741) `snd_analysis`(758) `classify_noise_classes`(792) | 实测辅音噪声谱（PSD）|
| 10 打包 | `build_pack`(840) `slugify`(830) | 写 `voice.json` + LICENSE，压成 `.v4voice`(ZIP) |

## 二、关键常量（Python 中的实测值）

```
FORMAT_TAG      = "v4voice/1"
FRM2_MAGIC      = b"FRM2"     FRM2_HEADER = 32      FRM2_ROW = 350
FRM2_MAG_ROW    = 350          MAG_FILLER  = 10000.0   HARMONIC_MAX_HZ = 22050.0
ENV_POINTS = 96   TILT_HZ = 400.0   TILT_DB_PER_OCT = 12.0   SHELF_POINTS = 7
FORMANT_BANDS = ((200,1100),(800,3000),(2300,4000))   BAND_GATE_DB = -25.0
F2_OVER_F1 = 1.12   F3_OVER_F2 = 1.03   MATCH_MAX_OCT = 0.45   MATCH_FREE_SLACK = 1.12
MIN_HARMONICS = 10   SPACING_MAX_REL = 0.08   HARM_POINTS = 64
```

## 三、输出结构（`voice.json`）

```
{ voice:{...}, shelf:[7], formants:[...], stats:{...},
  harmonics:{ freqs:[64] (60Hz..16kHz 对数网格，原样写入),
              phonemes:[...], noise?:{ <symbol>:{ freqs:[64], gains:[...] } } } }
```
打包为 ZIP：`voice.json` + `LICENSE.txt`（`FormatPack`/`PackStore` 已能读取此结构）。

## 四、验证策略（每一步都要能证伪）

1. **帧解码**：对同一 `.ddb`，Java 与 Python 解出的 (freqs, mags) 逐帧比对（容差 1e-6）。
2. **聚类**：k-means 结果允许顺序不同，比对**簇内成员集合**。
3. **音素映射**：比对 `符号 → 簇` 映射表是否完全一致。
4. **整包**：Java 产出的 `voice.json` 与 Python 产出逐字段比对（数值容差 1e-4）。
5. **可听验证**：手机装包后试听同一乐句，与 PC 转换的包对比。

## 五、分步落地

- **A**（本轮起）：`VoicebankFrames.java` — `.ddb` 定位 + FRM2 解码（阶段 2/3）+ 自检 ①
- **B**：`VoicebankCluster.java` — k-means + 音素匹配 + 共振峰（阶段 4/5/6）+ 自检 ②③
- **C**：`VoicebankPack.java` — 谐波表/序列/噪声 + ZIP 写出（阶段 7-10）+ 自检 ④
- **D**：向导 UI（选文件夹 → 检测 → 命名 → 进度 → 生成 → 试听）
- **E**：真机试听对比（自检 ⑤）

> 说明：`kmeans` 实现须与 Python 的初始化/迭代一致，否则聚类顺序与质心会有差异；
> 比对时应允许**标签置换**，但成员集合必须一致。

## 六、产物契约（从真实包 `build/packs_v64/luotianyi_chn_meng_bank.v4voice` 读出，2026-10-11）

ZIP 条目：`voice.json` + `LICENSE.txt`

`voice.json` 顶层键：
```
format(str)  id(str)  name(str)  nameEn(str)  nameJa(str)  lang(int)  compID(str)
source(dict)  voice(dict)  shelf(list)  formants(dict)  harmonics(dict)  stats(dict)  notes(str)
```
- `voice`：formantScale, f1Mul, f2Mul, f3Mul, tiltBias, breathBias, …
- `shelf`：**7** 项 `[freq, dB]`（例 `[60, -5.2]`）
- `formants`：`符号 -> [F1,F2,F3]`，另有 `符号_n` 计数键（例 `y: [311,...]`、`y{_n}: [394,...]`）
- `stats`：frames(39707) f0Median(329.0) f0Min f0Max clusters matchedPhonemes …
- `harmonics`：`freqs`(64，起 60.0)、`source`(str)、`phonemes`(dict，**49** 个符号)、`noise`(dict)
  - `harmonics.phonemes[符号]` = **band 列表**（本例 9 个），每项 `{f0, dyn, seqB64, …}`
    —— 注意：本例**没有 `db`**，正是”`seqN>=2` 就丢弃回退曲线“的效果
  - `harmonics.noise[符号]` = `{freqs:[64], db:[64]}`（本包 4 个符号：`f` `h` `s` `s\`）

Java 侧必须产出**同形**结构；聚类标签允许与 PC 版不同（见第五节），但键名、数组长度、数值范围必须一致。

### 6.1 噪声段组装（读自 build_pack 1150-1207）

```
kk     = min(NOISE_CLASSES=8, max(2, nRecords // 5))       聚类个数
kmeans 跑在【曲线】上（64 维），特征 Fc 按同一行序附在后面：
        每类的 centroid/bandwidth/flatness/level 取该类成员的【中位数】
        曲线 = 该类质心 Cn[j]
命名   = classify_noise_classes(classes) -> (named, lvl_med)
写包   （仅写被命名的符号）：
        db = round((curve - curve.max()) / HARM_DB_STEP=0.5) * 0.5
        db = clip(db, HARM_DB_FLOOR=-96, 0)；再把 -0.0 归 0
        freqs = [round(v, 4) for v in grid]        ← 4 位小数
若没有任何符号被命名 -> 整个 noise 键【不写出】
```

**同样的 4 位小数**也用于 `harmonics.freqs`（1199 行）——Java 侧目前输出全精度，需按此修正。

### 6.4 待修：符号 → 谐波帧的归属（C-4c 实测暴露）

现象：`formants=49`（与 PC 版一致 ✓）但 `totalBands=0`。

根因：谐波帧是 **64 维曲线**，而包络质心是 **96 维**；用 `assign(曲线, 包络质心)` 属于跨维度比较，
`sqdist` 只读前 64 个数 → 归属无意义 → 部分簇拿不到帧 → 被 `HARM_BAND_MIN_FRAMES=40` 全部挡掉。

修法（两条，推荐 A）：

**A. 按记录偏移配对（正确且简单）**
两套帧都来自**同一批 FRM2 记录偏移**，因此可以：
  1. 对包络做 k-means（现状 ✓）→ 得到每个**严格门限记录**的簇号；
  2. 对曲线做 k-means（64 维 ✓，同样 k）→ 得到每个**宽松门限记录**的簇号；
  3. 两个集合按 **offset 交集**统计共现矩阵 → 把每个共振峰簇配到共现最多的曲线簇；
  4. 符号拿到的就是配对后的曲线簇帧 → `pitch_bands`。

**B. 直接用多出的维度兜底**（不推荐，治标）
把 `assign` 改成按 `min(X[i].length, C[j].length)` 比较 —— 仍是跨语义比较，只是不再越界。


### 6.3 shelf 与 voice 参数（读自 957-962 与 notes 1256-1258）

```python
smooth   = moving_average(E.mean(axis=0), 5)      # E = 全部帧的 96 点包络
shelf_f  = geomspace(60, 16000, 7)
shelf_db = interp(shelf_f, ENV_F, smooth)
shelf_db = clip(shelf_db - shelf_db.max(), SHELF_MIN_DB, SHELF_MAX_DB)
shelf    = [[int(round(hz)), round(db, 1)] ...]    # 7 对，dB 保留 1 位
```

**voice 参数（取自 notes 的说明）**：
- `formantScale` = median(实测共振峰 / 内置 165 cm 参考表)
- `tiltBias`     = 0.02 × (4–10 kHz 与 200–800 Hz 的能量比，dB)
- `breathBias`   = 0.10 + 0.03 × (8–16 kHz 尾部 + 3)

> 待补：`SHELF_MIN_DB` / `SHELF_MAX_DB` 的具体数值（本轮正在取）。

### 6.2 harmonics.source 文案（1200-1203）
`VOCALOID4 FRM2 frames of <bank> (<compID>), measured harmonic magnitudes (<TOOL_TAG>): <N> curves from
<M> frames (<N> of <M> decoded) on a 64-point log grid, <P> CHN symbols in <B> pitch bands; noise: <txt>`
