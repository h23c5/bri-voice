# -*- coding: utf-8 -*-
import glob, os, io
from PIL import Image
T = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\trailer'
os.makedirs(os.path.join(T, 'jpg'), exist_ok=True)
n = 0
for f in glob.glob(os.path.join(T, 'cards', '*.png')):
    im = Image.open(f).convert('RGB')
    im.save(os.path.join(T, 'jpg', os.path.basename(f)[:-4] + '.jpg'), quality=95, subsampling=0)
    n += 1
print('converted', n, 'frames to jpg')
caps = [
    '钢琴卷帘：添加音符、填写歌词、拖动与缩放',
    '参数轨：画笔 / 直线 / 选择，每个参数独立曲线',
    'AI 调音：输入需求，自动修改参数与节奏',
    '混音器：每轨音量、声像与插件',
    '实时渲染：先出声再继续渲染，导出无损 WAV',
]
for i, c in enumerate(caps, 1):
    io.open(os.path.join(T, 'cap%d.txt' % i), 'w', encoding='utf-8').write(c)
print('caption text files written')