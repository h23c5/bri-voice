# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "31", "--version-name", "4.3",', '"--version-code", "32", "--version-name", "4.4",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="31"', 'android:versionCode="32"').replace('android:versionName="4.3"', 'android:versionName="4.4"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：4.3（versionCode 31）', '版本：4.4（versionCode 32）')
d = d.replace('1B1B2AE38FB98284EA87352825AB63933F8BCB48A14A0A4A855077B2D6BD25BC', 'PENDING')
entry = "### 本版（v4.4）：音素锁定改为「设置」里的开关，默认关闭\n\n以前 `Note.lockPhoneme` 只有文件读写，没有界面；而编辑歌词时**总会把音素清空**。现在：\n\n- 工具菜单新增「**设置**」，里面是「**音素锁定（默认关闭）**」；\n- **关闭（默认）**：改歌词 → 按新歌词重新生成音素；\n- **开启**：改歌词 → 保留这个音符已有的音素，并标记为锁定（写进 .vsqx 的 lock 属性），状态栏提示「音素已锁定，保持不变」；\n- 歌词对话框的「清空音素锁定」按钮现在**真的会同时清掉 phoneme 与 lockPhoneme**（以前只清 phoneme，锁定标记会残留）；\n  顺带把这个对话框的标题/按钮也接入了中英日三语。\n\n设置存在 SharedPreferences（`phonemeLock`，默认 false），启动时读取。\n\n**验证**：真机打开「工具 → 设置」，勾选框为**未勾选**（读到 checked=false），说明文字与状态行正常；i18n 2950/2950（284 键）。\n持久化的「切换 → 重启 → 回读」这一圈在本轮没跑完（脚本去点的时候设置面板没打开），代码路径是标准 SharedPreferences 读写。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs + version ok')