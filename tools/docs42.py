# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
# version bump
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "29", "--version-name", "4.1",', '"--version-code", "30", "--version-name", "4.2",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="29"', 'android:versionCode="30"').replace('android:versionName="4.1"', 'android:versionName="4.2"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
# readme
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：4.1（versionCode 29）', '版本：4.2（versionCode 30）')
d = d.replace('DA03970FBBC65422B3080657E750B4F97E6C5C1239F5C274145C755574BED570', 'PENDING')
entry = "### 本版（v4.2）：AI 自动调参 + 外部控制接口（实验功能，默认关闭）\n\n工具菜单最下面新增「AI 助手 / 外部接口（实验）」。**默认全关**，不开就不会联网、也不会开端口。\n\n**AI 自动调参（自带 API）**\n- 填 OpenAI 兼容的接口地址、模型名、API Key（只存本机），调整幅度滑块决定改动强度；\n- 点「立即自动调参」：应用把当前片段（速度、音符、已有曲线）打包成 JSON 发给模型，要求返回控制点；\n- **回复一律不信任**：抽出第一个能解析的 JSON 对象、参数名必须在白名单内、tick 裁到片段范围、数值按各参数上下限裁剪，\n  不合格直接报错而不是写进工程；通过后与现有曲线按调整幅度混合，可撤销。\n\n**外部控制接口（给外部代码用）**\n- 本地 HTTP 服务，**只绑定 127.0.0.1**，不对外网开放；`curl`/脚本/别的 App 都能驱动：\n  `GET /status`、`GET /help`、`POST /command {action:...}`；\n- 动作：`setParam` `clearParam` `addNote` `deleteNote` `setLyrics` `play` `stop` `save` `newProject`\n  `aiConfig`（脚本可改接口地址）`aiTune`（触发 AI 调参）`aiApply`（**外部优化器直接回写曲线**）；\n- 所有动作都走主线程 + 现有编辑快照，因此外部改动同样可以撤销。\n\n**过程中修掉的三个真问题**（都是设备/联调时才暴露的）：\n1. 应用没有 INTERNET 权限 → 本地服务与 AI 调用全部失败（已加，并在免责声明里写明默认不联网）；\n2. Android 默认禁止明文 HTTP → 用户指向本地模型（http://…）会失败（已允许，界面里也提示仅访问你填写的接口）；\n3. AI 请求里的 `messages` 被当成字符串发出（应为数组）→ 真接口会拒绝（已被桌面桩服务器测试抓到并修复）。\n\n**验证**：\n- `AiTunerTest` 13/13 —— 解析、白名单、越界裁剪、拒绝非法回复；\n- `AiClientTest` 9/9 —— 对桩端点完成真实 HTTP 往返、带 Authorization、解析出 4 个参数、死端点报错清晰；\n- 真机：外部接口实测可用（`/status` 返回真实工程状态；`addNote` 让音符 9→10；非法 action 被拒；`setParam`/`aiApply` 写入成功）。\n- **未在真机验证的部分**：我的模拟器是桥接网段（设备 172.16.1.15，10.0.2.2 不通，adb reverse 也不通），\n  所以「应用内出网调用真实 API」这一步没能在设备上跑通端到端；该路径以桌面桩服务器验证（同一份代码）。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs + version ok')