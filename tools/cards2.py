# -*- coding: utf-8 -*-
"""Fast card renderer (numpy background) for the revised release film."""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H, FPS = 1280, 720, 30
T = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\trailer'
ICON = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\res\mipmap-xhdpi\ic_launcher.png'
FONT = 'C:/Windows/Fonts/simhei.ttf'
ACCENT, ACCENT2 = (63, 140, 255), (53, 208, 224)
TEXT, DIM = (232, 238, 247), (150, 170, 196)


def font(s):
    return ImageFont.truetype(FONT, s)


def ease(t):
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


# background as a numpy gradient + a soft glow, built once and reused
_YY, _XX = np.mgrid[0:H, 0:W]
def background(gx=0.5, gy=0.45, g=0.6):
    top = np.array([11, 16, 23]); bot = np.array([21, 29, 40])
    k = (_YY / (H - 1.0))[..., None]
    img = top + (bot - top) * k
    d2 = ((_XX - W * gx) ** 2 + (_YY - H * gy) ** 2) / (420.0 ** 2)
    glow = np.exp(-d2)[..., None] * np.array([26, 74, 150]) * (0.9 * g)
    img = np.clip(img + glow, 0, 255).astype(np.uint8)
    return Image.fromarray(img, 'RGB')


BG = background()


def write(prefix, frames):
    for i, im in enumerate(frames):
        im.convert('RGB').save(os.path.join(T, 'jpg2', '%s%04d.jpg' % (prefix, i)), quality=95, subsampling=0)


def section(num, title, sub, secs=3.0):
    out = []
    n = int(secs * FPS)
    for i in range(n):
        t = i / FPS
        ov = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        a = ease(min((t - 0.12) / 0.45, (secs - 0.32 - t) / 0.45))
        if a > 0:
            d.text((120, H * 0.34), num, font=font(150), fill=ACCENT + (int(80 * a),))
            d.text((126, H * 0.36), num, font=font(150), fill=ACCENT2 + (int(255 * a),))
            d.text((340, H * 0.40), title, font=font(64), fill=TEXT + (int(255 * a),))
            d.text((344, H * 0.55), sub, font=font(30), fill=DIM + (int(255 * a),))
            d.rounded_rectangle([340, H * 0.37 - 30, 340 + int(96 * a), H * 0.37 - 24], radius=3,
                                fill=ACCENT + (int(255 * a),))
        im = Image.alpha_composite(BG.convert('RGBA'), ov).convert('RGB')
        out.append(im)
    return out


def credits(secs=12.0):
    lines = [
        ('作者：Ling-tech', 34, TEXT),
        ('辅助：Deepseek Harness', 30, TEXT),
        ('动画：Deepseek Harness', 30, TEXT),
        ('图标：酒石酸菌 　酒狐', 30, TEXT),
        ('示例：MIMI 《魔术之心》', 30, TEXT),
        ('内置声库：洛天依 BV', 30, TEXT),
        ('', 18, DIM),
        ('本项目与 VOCALOID 官方无关；内置声库为参数化派生数据，不包含原始录音', 20, (120, 138, 160)),
    ]
    icon = Image.open(ICON).convert('RGBA').resize((86, 86))
    out = []
    n = int(secs * FPS)
    total = sum(14 + h for _, h, _ in lines)
    y0 = int((H - total) / 2) + 10
    for i in range(n):
        t = i / FPS
        a = ease((t - 0.25) / 0.9)
        ov = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        ic = icon.copy()
        ic.putalpha(ic.split()[3].point(lambda v: int(v * a)))
        ov.alpha_composite(ic, (int(W / 2 - 43), y0 - 120))
        y = y0
        for text, size, col in lines:
            if text:
                f = font(size)
                w = d.textlength(text, font=f)
                d.text(((W - w) / 2, y), text, font=f, fill=col + (int(255 * a),))
            y += 14 + size
        fade = 1.0
        if t < 0.5:
            fade = t / 0.5
        elif t > secs - 1.0:
            fade = max(0.0, (secs - t) / 1.0)
        if fade < 1.0:
            ov = Image.blend(Image.new('RGBA', (W, H), (0, 0, 0, 255)), ov, fade)
        out.append(Image.alpha_composite(BG.convert('RGBA'), ov).convert('RGB'))
    return out


def main():
    os.makedirs(os.path.join(T, 'jpg2'), exist_ok=True)
    write('c1', section('01', '声库与音轨', '选择歌手 · 增删音轨 · 音量与声像'))
    write('c2', section('02', '音符与歌词', '钢琴卷帘 · 添加与拖动'))
    write('c3', section('03', 'AI 调音', '说出要求 · 自动改参数'))
    write('c4', section('04', '接入自有接口', 'OpenAI 兼容 · 密钥仅存本机'))
    write('c5', section('05', '参数精调', 'DYN / PIT / VIB … 逐条曲线'))
    write('c6', section('06', '试听与实时渲染', '先出声 · 再继续渲染'))
    write('cred', credits(12.0))
    print('cards rendered')


if __name__ == '__main__':
    main()