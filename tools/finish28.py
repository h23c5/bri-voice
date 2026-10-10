# -*- coding: utf-8 -*-
import io
root = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"
t = root + r"\tools\i18n_selftest\I18nSelftest.java"
v = io.open(t, encoding="utf-8").read()
v = v.replace("int EXPECTED_KEYS = 225;", "int EXPECTED_KEYS = 226;")
v = v.replace('want.put("transport", Integer.valueOf(11));', 'want.put("transport", Integer.valueOf(12));')
io.open(t, "w", encoding="utf-8", newline="").write(v)
r = root + r"\README.md"
d = io.open(r, encoding="utf-8").read()
d = d.replace("版本：2.7（versionCode 15）", "版本：2.8（versionCode 16）")
d = d.replace("- SHA-256：235D7909FA0B8B18E3B9B5416A7BC692E8A199BBF2AADF5A13C62C3874F3216F", "- SHA-256：835D7BDE2886DCE732895EDFD732570DD16086EFF245C52AEB490530E4877A94")
entry = "\n".join([
    "### 本版（v2.8）：缩放滑块 + 数值读数 + 控件尺寸统一",
    "",
    "- 缩放做成滑块，和吸附/速度同一个控件样式，8 档（25%-400%）；点名称 = 适应窗口。",
    "- 所有滑块控件自带数值读数：左上角是名称，右上角是当前值（吸附 1/8、速度 120、缩放 100%），下方是滑条。",
    "- 全部控件统一尺寸：按钮与滑块控件都是 96dp x 38dp，排在一起是齐的。",
    "- 名称仍可点击进入精确输入（速度→数字对话框，缩放→适应窗口，吸附→下一档）。",
    "",
    "### 转换步骤（在你自己的电脑上、用你自己的授权声库）",
])
d = d.replace("### 转换步骤（在你自己的电脑上、用你自己的授权声库）", entry, 1)
io.open(r, "w", encoding="utf-8", newline="").write(d)
print("counts + readme updated")