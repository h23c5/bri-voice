# -*- coding: utf-8 -*-
import glob, os
from PIL import Image, ImageDraw
T = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\trailer'
fs = sorted(glob.glob(os.path.join(T, 'mid', '*.jpg')), key=lambda p: int(''.join(c for c in os.path.basename(p) if c.isdigit())))
print('frames:', len(fs))
cols, rows, tw, th = 4, 4, 320, 143
sheet = Image.new('RGB', (cols * tw, rows * (th + 16)), (16, 18, 24))
d = ImageDraw.Draw(sheet)
for i, f in enumerate(fs[:cols * rows]):
    im = Image.open(f).convert('RGB').resize((tw, th))
    x = (i % cols) * tw; y = (i // cols) * (th + 16)
    sheet.paste(im, (x, y))
    d.text((x + 4, y + th + 2), os.path.basename(f)[:-4], fill=(215, 225, 240))
sheet.save(os.path.join(T, 'mid_sheet.png'))
print('ok')