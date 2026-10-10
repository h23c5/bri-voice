# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\cards2.py'
s = io.open(p, encoding='utf-8').read()
s = s.replace("write('c5', section('05', '参数精调', 'DYN / PIT / VIB … 逐条曲线'))",
              "write('c5', section('05', '叙事与工具', '随时试听 · 调整设置'))")
s = s.replace("write('c6', section('06', '试听与实时渲染', '先出声 · 再继续渲染'))",
              "")
s = s.replace("write('c1',", "write('c1',")
io.open(p, 'w', encoding='utf-8', newline='').write(s)
import os
os.system('')
exec(compile(io.open(p, encoding='utf-8').read().split('def main():')[1].replace('main()', '', 1), 'x', 'exec')) if False else None
print('cards2 updated')