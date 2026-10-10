# 关于声库数据与第三方权利 / Voice data notice

本仓库**只包含程序源代码**，刻意不包含任何从商业声库派生的数据：

| 内容 | 是否包含 | 说明 |
|---|---|---|
| `app/src/**` 源码 | 是 | MIT 许可 |
| `tools/**` 脚本 | 是 | MIT 许可 |
| `app/assets/dict/pinyin.txt.gz` | 是 | 由 **Unicode Unihan** 数据库生成，Unicode License 允许再分发 |
| `app/assets/singers/*.v4voice`（内置声库） | **否** | 从作者本机已授权声库测量得到的参数化数据 |
| `dist/*.apk`、`dist/*.zip` | **否** | 构建产物，内含上述声库数据 |
| 用户工程 `.vsqx` | **否** | 个人作品 |

## 使用声库转换工具时请注意

`tools/voicebank_pack.py` 用于把你**自己已获授权**的 VOCALOID4 声库转换为 `.v4voice` 参数包
（只含频谱统计量，不含任何原始录音）。请在授权范围内使用，**不要分发转换结果**。

## 免责声明

本项目与 VOCALOID、Yamaha 及其任何关联公司**无关**，未使用其代码或引擎。
应用不附带任何歌手的原始录音。
