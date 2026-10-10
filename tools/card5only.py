# -*- coding: utf-8 -*-
"""Re-render only the 05 section card (title now matches the footage)."""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H, FPS = 1280, 720, 30
T = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\trailer'
FONT = 'C:/Windows/Fonts/simhei.ttf'
ACCENT, ACCENT2 = (63, 140, 255), (53, 208, 224)
TEXT, DIM = (232, 238, 247), (150, 170, 196)
_YY, _XX = np.mgrid[0:H, 0:W]
top = np.array([11, 16, 23]); bot = np.array([21, 29, 40])
k = (_YY / (H - 1.0))[..., None]
img = top + (bot - top) * k
d2 = ((_XX - W * 0.5) ** 2 + (_YY - H * 0.45) ** 2) / (420.0 ** 2)
img = np.clip(img + np.exp(-d2)[..., None] * np.array([26, 74, 150]) * 0.6, 0, 255).astype(np.uint8)
BG = Image.fromarray(img, 'RGB')


def ease(t):
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


def main():
    os.makedirs(os.path.join(T, 'jpg2'), exist_ok=True)
    n = int(3.0 * FPS)
    for i in range(n):
        t = i / FPS
        ov = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        a = ease(min((t - 0.12) / 0.45, (3.0 - 0.32 - t) / 0.45))
        if a > 0:
            d.text((120, H * 0.34), '05', font=ImageFont.truetype(FONT, 150), fill=ACCENT + (int(80 * a),))
            d.text((126, H * 0.36), '05', font=ImageFont.truetype(FONT, 150), fill=ACCENT2 + (int(255 * a),))
            d.text((340, H * 0.40), '试听与工具', font=ImageFont.truetype(FONT, 64), fill=TEXT + (int(255 * a),))
            d.text((344, H * 0.55), '随时试听 · 调整设置', font=ImageFont.truetype(FONT, 30), fill=DIM + (int(255 * a),))
            d.rounded_rectangle([340, H * 0.37 - 30, 340 + int(96 * a), H * 0.37 - 24], radius=3, fill=ACCENT + (int(255 * a),))
        im = Image.alpha_composite(BG.convert('RGBA'), ov).convert('RGB')
        im.save(os.path.join(T, 'jpg2', 'c5%04d.jpg' % i), quality=95, subsampling=0)
    print('c5 card re-rendered')


if __name__ == '__main__':
    main()