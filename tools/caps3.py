# -*- coding: utf-8 -*-
import io
T = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\trailer'
caps = {
  1: '选择声库、增删音轨、设置音量与声像',
  2: '钢琴卷帘：添加音符、填写歌词、拖动与缩放',
  3: '说出你的要求，AI 自动修改参数与节奏',
  4: '参数轨：画笔 / 直线 / 选择，逐参数独立曲线',
  5: '实时渲染播放：先出声，再继续渲染',
}
for k, v in caps.items():
    io.open(T + '\\capC%d.txt' % k, 'w', encoding='utf-8').write(v)
print('captions ready')
# SFX for the new timeline: cards at 10/25/38/46/67, credits 83, outro 95
src = io.open(T + '\\make_sfx_case.txt', 'w')