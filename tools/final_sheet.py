# -*- coding: utf-8 -*-
import glob, os
from PIL import Image, ImageDraw
T = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\trailer'
fs = sorted(glob.glob(os.path.join(T, 'check', 't*.jpg')), key=lambda p: int(''.join(c for c in os.path.basename(p) if c.isdigit())))
print('frames found:', len(fs))
cols, rows, tw, th = 4, 3, 320, 180
sheet = Image.new('RGB', (cols * tw, rows * (th + 18)), (16, 18, 24))
d = ImageDraw.Draw(sheet)
for i, f in enumerate(fs[:cols * rows]):
    im = Image.open(f).convert('RGB').resize((tw, th))
    x = (i % cols) * tw; y = (i // cols) * (th + 18)
    sheet.paste(im, (x, y))
    lbl = ''.join(c for c in os.path.basename(f) if c.isdigit()) + ' s'
    d.text((x + 4, y + th + 3), lbl, fill=(210, 220, 235))
sheet.save(os.path.join(T, 'final_sheet.png'))
print('sheet:', sheet.size)