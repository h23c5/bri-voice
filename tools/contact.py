# -*- coding: utf-8 -*-
import glob, os
from PIL import Image, ImageDraw
base = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\trailer'
fs = sorted(glob.glob(os.path.join(base, 'frames', '*.bmp')))
print('frames:', len(fs))
if not fs:
    raise SystemExit('no frames')
cols, rows = 4, 4
tw, th = 320, 144
sheet = Image.new('RGB', (cols * tw, rows * (th + 16)), (18, 20, 26))
d = ImageDraw.Draw(sheet)
for i, f in enumerate(fs[:cols * rows]):
    im = Image.open(f).convert('RGB').resize((tw, th))
    x = (i % cols) * tw
    y = (i // cols) * (th + 16)
    sheet.paste(im, (x, y))
    d.text((x + 4, y + th + 2), os.path.basename(f) + '  t=' + str(i * 10) + 's', fill=(200, 210, 225))
out = os.path.join(base, 'contact.png')
sheet.save(out)
print('sheet:', out, sheet.size)