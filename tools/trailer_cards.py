# -*- coding: utf-8 -*-
"""Renders every title card, caption bar and animated frame of the BriVoice release film."""
import math, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H, FPS = 1280, 720, 30
OUT = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\trailer'
ICON = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\res\mipmap-xhdpi\ic_launcher.png'
FONT = 'C:/Windows/Fonts/simhei.ttf'
BG_TOP, BG_BOT = (11, 16, 23), (20, 28, 38)
ACCENT, ACCENT2 = (63, 140, 255), (53, 208, 224)
TEXT, DIM = (232, 238, 247), (143, 163, 189)


def font(size):
    return ImageFont.truetype(FONT, size)


def ease_out(t):
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


def bg(glow_x=0.5, glow_y=0.42, glow=1.0):
    im = Image.new('RGB', (W, H))
    d = ImageDraw.Draw(im)
    for y in range(H):
        k = y / (H - 1.0)
        d.line([(0, y), (W, y)], fill=(int(BG_TOP[0] + (BG_BOT[0] - BG_TOP[0]) * k),
                                       int(BG_TOP[1] + (BG_BOT[1] - BG_TOP[1]) * k),
                                       int(BG_TOP[2] + (BG_BOT[2] - BG_TOP[2]) * k)))
    g = Image.new('RGB', (W, H), (0, 0, 0))
    gd = ImageDraw.Draw(g)
    cx, cy, r = W * glow_x, H * glow_y, 420
    gd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(26, 74, 150))
    g = g.filter(ImageFilter.GaussianBlur(150))
    im = Image.blend(im, Image.blend(im, g, 0.0), 0.0)
    im = Image.composite(im, im, Image.new('L', (W, H), 0))
    px = im.load(); gp = g.load()
    for y in range(0, H, 2):
        for x in range(0, W, 2):
            r0, g0, b0 = px[x, y]; gr, gg, gb = gp[x, y]
            v = (int(min(255, r0 + gr * 0.55 * glow)), int(min(255, g0 + gg * 0.55 * glow)),
                 int(min(255, b0 + gb * 0.55 * glow)))
            for dx in (0, 1):
                for dy in (0, 1):
                    if x + dx < W and y + dy < H:
                        px[x + dx, y + dy] = v
    return im


def centred(d, y, text, f, fill, alpha=255):
    w = d.textlength(text, font=f)
    d.text(((W - w) / 2, y), text, font=f, fill=fill + (alpha,))


def write_frames(prefix, frames):
    os.makedirs(os.path.join(OUT, 'cards'), exist_ok=True)
    for i, im in enumerate(frames):
        im.convert('RGB').save(os.path.join(OUT, 'cards', '%s%04d.png' % (prefix, i)))


def intro(seconds=10.0):
    n = int(seconds * FPS)
    icon = Image.open(ICON).convert('RGBA')
    out = []
    for i in range(n):
        t = i / FPS
        im = bg(0.5 + 0.02 * math.sin(t * 0.7), 0.40, 0.7 + 0.15 * math.sin(t * 0.9))
        ov = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        a_icon = ease_out((t - 0.3) / 0.9)
        if a_icon > 0:
            s = int(150 * (0.65 + 0.35 * a_icon))
            ic = icon.resize((s, s))
            alpha = ic.split()[3].point(lambda v: int(v * a_icon))
            ic.putalpha(alpha)
            ov.alpha_composite(ic, (int(W / 2 - s / 2), int(H * 0.20 - s / 2 + s / 2)))
        a_title = ease_out((t - 1.1) / 0.9)
        if a_title > 0:
            f = font(96)
            dy = int((1 - a_title) * 18)
            w = d.textlength('BriVoice', font=f)
            d.text(((W - w) / 2, H * 0.47 + dy), 'BriVoice', font=f,
                   fill=TEXT + (int(255 * a_title),))
        a_line = ease_out((t - 1.9) / 0.8)
        if a_line > 0:
            half = int(230 * a_line)
            d.rounded_rectangle([W / 2 - half, H * 0.47 + 128, W / 2 + half, H * 0.47 + 134], radius=3,
                                fill=ACCENT + (int(255 * a_line),))
        a_tag = ease_out((t - 2.5) / 0.9)
        if a_tag > 0:
            centred(d, H * 0.47 + 168, '把歌声，调成你想要的样子', font(38), TEXT, int(255 * a_tag))
        a_sub = ease_out((t - 3.3) / 0.9)
        if a_sub > 0:
            centred(d, H * 0.47 + 232, 'VOCALOID4 格式兼容  ·  安卓  ·  实时渲染  ·  AI 调音', font(26), DIM, int(255 * a_sub))
        fade = 1.0
        if t < 0.5:
            fade = t / 0.5
        elif t > seconds - 0.8:
            fade = max(0.0, (seconds - t) / 0.8)
        if fade < 1.0:
            ov = Image.blend(Image.new('RGBA', (W, H), (0, 0, 0, 255)), ov, fade)
            im = Image.alpha_composite(im.convert('RGBA'), ov).convert('RGB')
        else:
            im = Image.alpha_composite(im.convert('RGBA'), ov).convert('RGB')
        out.append(im)
    return out


def section(num, title, sub, seconds=3.0):
    n = int(seconds * FPS)
    out = []
    for i in range(n):
        t = i / FPS
        im = bg(0.5, 0.5, 0.55)
        ov = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        a = ease_out(min((t - 0.15) / 0.5, (seconds - 0.35 - t) / 0.5))
        if a > 0:
            d.text((120, H * 0.34), num, font=font(150), fill=ACCENT + (int(90 * a),))
            d.text((126, H * 0.36), num, font=font(150), fill=ACCENT2 + (int(255 * a),))
            d.text((340, H * 0.40), title, font=font(64), fill=TEXT + (int(255 * a),))
            d.text((344, H * 0.55), sub, font=font(30), fill=DIM + (int(255 * a),))
            d.rounded_rectangle([340, H * 0.37 - 26, 340 + int(96 * a), H * 0.37 - 20], radius=3,
                                fill=ACCENT + (int(255 * a),))
        im = Image.alpha_composite(im.convert('RGBA'), ov).convert('RGB')
        out.append(im)
    return out


def caption(text, file):
    ov = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    f = font(30)
    w = d.textlength(text, font=f)
    pad = 34
    x0, y0 = 60, H - 116
    d.rounded_rectangle([x0, y0, x0 + w + pad * 2, y0 + 68], radius=16, fill=(6, 10, 16, 190))
    d.rounded_rectangle([x0, y0, x0 + 8, y0 + 68], radius=4, fill=ACCENT + (255,))
    d.text((x0 + pad, y0 + 16), text, font=f, fill=TEXT + (255,))
    ov.save(os.path.join(OUT, file))


def outro(seconds=12.0):
    n = int(seconds * FPS)
    icon = Image.open(ICON).convert('RGBA').resize((92, 92))
    out = []
    for i in range(n):
        t = i / FPS
        im = bg(0.5, 0.44, 0.6)
        ov = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        a = ease_out((t - 0.2) / 0.8)
        ov.alpha_composite(icon, (int(W / 2 - 46), int(H * 0.16)))
        centred(d, H * 0.16 + 112, 'BriVoice', font(58), TEXT, int(255 * a))
        centred(d, H * 0.16 + 196, 'v6.5 正式发布', font(44), ACCENT2, int(255 * a))
        centred(d, H * 0.16 + 276, '作者：Ling-tech      反馈 QQ：3361390582', font(28), DIM, int(255 * a))
        centred(d, H * 0.16 + 330, '本项目与 VOCALOID 官方无关；内置声库为参数化派生数据，不包含原始录音', font(22), (120, 138, 160), int(255 * a))
        fade = 1.0
        if t < 0.5:
            fade = t / 0.5
        elif t > seconds - 1.0:
            fade = max(0.0, (seconds - t) / 1.0)
        if fade < 1.0:
            ov = Image.blend(Image.new('RGBA', (W, H), (0, 0, 0, 255)), ov, fade)
        im = Image.alpha_composite(im.convert('RGBA'), ov).convert('RGB')
        out.append(im)
    return out


def main():
    os.makedirs(os.path.join(OUT, 'cards'), exist_ok=True)
    write_frames('open', intro(10.0))
    write_frames('sec1', section('01', '多轨编辑', '钢琴卷帘 · 音符与歌词 · 编辑与撤销'))
    write_frames('sec2', section('02', '参数精调', 'DYN / PIT / VIB / BRE … 逐个参数画线'))
    write_frames('sec3', section('03', 'AI 调音', '说出你的要求，自动调参数'))
    write_frames('sec4', section('04', '混音器与插件', '音量 / 声像 · 插件链'))
    write_frames('sec5', section('05', '实时渲染与导出', '边渲染边播放 · 导出 WAV'))
    write_frames('outro', outro(12.0))
    caption('钢琴卷帘：添加音符、填写歌词、拖动与缩放', 'cap1.png')
    caption('参数轨：画笔 / 直线 / 选择，每个参数独立曲线', 'cap2.png')
    caption('AI 调音：输入需求，自动修改参数与节奏', 'cap3.png')
    caption('混音器：每轨音量、声像与插件', 'cap4.png')
    caption('实时渲染：先出声再继续渲染，导出无损 WAV', 'cap5.png')
    print('cards done')


if __name__ == '__main__':
    main()