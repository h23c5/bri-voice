# -*- coding: utf-8 -*-
"""Compare the keyboard strip (left 110 px) between screenshots: tapping a note or a key must not move it."""
import sys
from PIL import Image, ImageChops
imgs = [Image.open(p).convert('RGB') for p in sys.argv[1:]]
strips = [im.crop((0, 230, 110, 1000)) for im in imgs]
base = strips[0]
for i, s in enumerate(strips[1:], 1):
    diff = ImageChops.difference(base, s)
    bbox = diff.getbbox()
    hist = diff.histogram()
    changed = sum(hist[1:256]) + sum(hist[257:512]) + sum(hist[513:768])
    print('strip 0 vs %d: bbox=%s  changed_pixels=%d  %s' % (i, bbox, changed, 'SAME' if changed == 0 else 'MOVED'))
