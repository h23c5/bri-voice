# BriVoice · Android 歌声合成 / 调音

以本机安装的 **VOCALOID4 Editor** 为蓝本实现的安卓歌声合成与调音软件。本体不内置任何音色，所有音色来自外部声库（.v4voice）。

纯 Java 8 + 原生 Android 框架编写，不依赖 Gradle / AndroidX / 任何第三方库，直接调用 aapt2 / javac / d8 / zipalign / apksigner 打包成 APK。

---

## 〇、教程

**手机端制作声库**（最常问的）—— 应用内：工具菜单 →「教程」；仓库里：[docs/教程-声库制作.md](docs/教程-声库制作.md)

三语版本随应用内置：`app/assets/tutorial/voicebank.zh.txt` / `.en.txt` / `.ja.txt`（按当前界面语言自动选择）。

要点速览：

1. 声库是**文件夹**（含 `.ddb` / `.ddi` / `.vvd`），整个拷到手机，如 `/sdcard/VoiceDB/BK8H76TAEHXWSKDB/`
2. 工具菜单 →「制作声库」→ 填**文件夹路径** + 名称 → 开始转换（PC 约 20 秒，手机几分钟）
3. 产出在 `/sdcard/BriVoice_root/voicebanks/<名称>.v4voice`，应用自动识别，可直接试听
4. 包内只有**测量统计量**（谐波包络 / 共振峰 / 辅音噪声谱 / 帧序列），**不含任何原始录音**，全程离线
5. 测不到的就不写（不编造）：帧数不足 40 的音素不生成曲线；摩擦音素材少的声库只会命名 1~2 个辅音

---
## 一、功能

**编辑功能**
- 钢琴卷帘：128 键、可横向/纵向滚动、双指缩放、小节/拍网格、音名与歌词显示
- 音符编辑：新建、拖动改音高/位置、右边缘拖拽改时长、左边缘拖拽改力度、长按删除
- 歌词支持：拼音（luo）、汉字（内置 26709 字拼音表）、假名/罗马字
- 控制参数曲线：DYN、BRE、BRI、CLE、OPE、GEN、POR、PIT、PBS、VIB、XSY、GWL
- 试音：左侧琴键即发出该音高的 "a" 音
- 撤销/重做（24 步）、速度（BPM）、网格吸附
- 多轨工程：读取多轨 VSQX，逐轨渲染混音

**播放与输出**
- 播放：边渲染边播放，支持播放头拖动定位
- 导出 WAV：44.1 kHz / 16 bit / 立体声
- 打开 / 保存 / 另存为：支持 .vsqx / .vsq / .mid 格式

---

## 二、格式兼容性

| 格式 | 读 | 写 |
|---|---|---|
| VOCALOID4 工程 `.vsqx` | ✅ | ✅ |
| VOCALOID3 工程 `.vsqx` | ✅ | ✅ |
| VOCALOID2 工程 `.vsq` | ✅ | ✅ |
| MIDI `.mid` | ✅ | — |
| WAV 导出 | — | ✅ |

---

## 三、安装

```powershell
adb install -r dist/BriVoice.apk
```
或把 APK 拷进手机点击安装（需允许"安装未知来源应用"）。

---

## 四、从源码构建

需要：JDK 17、Android SDK（build-tools 34 + platform 34）

```powershell
python tools/build_apk.py
```

---

## 五、添加声库

应用支持导入外部 .v4voice 声库包：

1. 在电脑上运行转换工具生成声库包：
   ```powershell
   python tools/voicebank_pack.py --bank "E:\你的声库目录" --out build/voicepacks/my_bank.v4voice
   ```

2. 把生成的 .v4voice 文件传进手机
3. 在应用内「声库 → 导入声库文件」选中该文件

---

## 六、代码结构

```
app/src/com/dsh/v4tune/
  model/     数据模型（Song、Track、Part、Note、Voice、ControlCurve）
  format/    格式读写（VsqxReader/VsqxWriter、Vsq2Reader/Vsq2Writer、MidiReader）
  phoneme/   音素处理（PhonemeDef、PhonemeTable、ChineseG2P、JapaneseG2P、PinyinDict）
  synth/     合成引擎（VoiceSource、FormantFilter、HarmonicVoice、FrameVoice、Renderer）
  audio/     音频引擎（AudioEngine、Mixer、PluginChain、DSP 插件）
  io/        文件操作（ProjectIO、PackStore、WavReader）
  ui/        界面（ PianoRollView、ParamView、Theme、Dialogs）
  ai/        AI 调参（AiTuner、ExtApi、Json）
  MainActivity.java
tools/      构建脚本与测试工具
```

**合成链路**：谱面 → 音素分段 → F0 曲线 → 声门源 → Klatt 共振峰 → 包络/动态 → 立体声混音

---

## 七、技术规格

- 包名：**com.bri.voice**
- 版本：7.0（versionCode 51）
- minSdk：**21**（Android 5.0）
- targetSdk：**34**（Android 14）
- 渲染速度：约 **24–49× 实时**（44.1 kHz 单声道）

---

## 八、许可证

MIT（仅覆盖源代码）。声库数据与第三方权利的说明见 [NOTICE.md](NOTICE.md)。

---

## 九、免责声明

本软件仅供个人学习与研究使用，不附带任何担保。本体不含任何第三方采样或声库文件。使用者自行导入的声库/音频，其来源合法性、授权范围与后果由使用者负责。请勿将本软件用于商业用途或再分发受版权保护的素材。