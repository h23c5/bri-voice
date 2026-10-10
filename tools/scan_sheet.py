# -*- coding: utf-8 -*-
import glob, os
from PIL import Image, ImageDraw
T = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\trailer'
fs = sorted(glob.glob(os.path.join(T, 'scan', 's*.jpg')), key=lambda p: int(''.join(c for c in os.path.basename(p) if c.isdigit())))
print('scanned frames:', len(fs))
cols, rows, tw, th = 5, 5, 256, 114
per = cols * rows
for part in range((len(fs) + per - 1) // per):
    chunk = fs[part * per:(part + 1) * per]
    sheet = Image.new('RGB', (cols * tw, rows * (th + 14)), (16, 18, 24))
    d = ImageDraw.Draw(sheet)
    for i, f in enumerate(chunk):
        im = Image.open(f).convert('RGB').resize((tw, th))
        x = (i % cols) * tw; y = (i // cols) * (th + 14)
        sheet.paste(im, (x, y))
        d.text((x + 3, y + th + 1), ''.join(c for c in os.path.basename(f) if c.isdigit()) + 's', fill=(210, 220, 235))
    sheet.save(os.path.join(T, 'scan_sheet%d.png' % part))
    print('sheet', part, sheet.size)