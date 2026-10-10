# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "33", "--version-name", "4.5",', '"--version-code", "34", "--version-name", "4.6",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="33"', 'android:versionCode="34"').replace('android:versionName="4.5"', 'android:versionName="4.6"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：4.5（versionCode 33）', '版本：4.6（versionCode 34）')
d = d.replace('726019482A175F8EFA52A5AC0322435DBA0006F4008B5E2859F89CF8C79EF65E', 'PENDING')
entry = "### 本版（v4.6）：「选择」工具下可拖动调音轨道（平移时间轴）\n\n之前参数区（卷帘下方的调音轨道）只能靠双指缩放/平移，单指拖动在「选择」模式下什么都不做；\n「画笔」「直线」下拖动则会画曲线。现在：\n\n- **「选择」模式下在空白处拖动 = 拖动整条调音轨道**（平移时间轴），画笔/直线仍然画曲线，互不干扰；\n- 点中控制点仍是选中并可移动该点（原行为保留）；\n- **卷帘会跟着一起走**：调音轨道平移后，应用把钢琴卷帘设到同一可视区间，两边的刻度始终对齐；\n- 平移不产生任何编辑，因此不占用撤销栈。\n\n**验证**（真机）：切到「选择」后在调音区从 x=1300 拖到 700 —— 标尺由第 1–2 小节变为 **3–4 小节**，音符与调音曲线同步左移，工具高亮停在「选择」✓。\ni18n 3030/3030（292 键）、渲染 7/7、音符重叠 31/31。\n\n### 转换步骤（在你自己的电脑上、用你自己的授权声库）"
d = d.replace('### 转换步骤（在你自己的电脑上、用你自己的授权声库）', entry, 1)
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('docs + version ok')