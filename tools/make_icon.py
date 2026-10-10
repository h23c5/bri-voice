# -*- coding: utf-8 -*-
"""[icon] turn the user's photo into launcher icons for every density."""
import os, sys
from PIL import Image, ImageOps

src = r"C:\Users\H23_C5\Pictures\IMG_20261006_124715.jpg"
out = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\res"
im = Image.open(src)
print('source', im.size, im.mode)
im = ImageOps.exif_transpose(im).convert('RGB')
w, h = im.size
s = min(w, h)
im = im.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))
sizes = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
for d, px in sizes.items():
    dst = os.path.join(out, 'mipmap-' + d)
    os.makedirs(dst, exist_ok=True)
    im.resize((px, px), Image.LANCZOS).save(os.path.join(dst, 'ic_launcher.png'), 'PNG', optimize=True)
    # round variant for launchers that ask for it
    r = im.resize((px, px), Image.LANCZOS)
    mask = Image.new('L', (px, px), 0)
    from PIL import ImageDraw
    ImageDraw.Draw(mask).ellipse((0, 0, px - 1, px - 1), fill=255)
    rnd = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    rnd.paste(r, (0, 0), mask)
    rnd.save(os.path.join(dst, 'ic_launcher_round.png'), 'PNG', optimize=True)
    print('wrote', d, px)
