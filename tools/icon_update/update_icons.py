#!/usr/bin/env python3
"""Regenerate every BriVoice launcher icon from dist/图.png.

Legacy PNGs support API 21-25; v26 adaptive icons use white background
and an aspect-preserving foreground fully inside the adaptive safe circle.
No app code, version, manifest, builder or update metadata is changed.
Build only after the team lead grants exclusive access to shared build/.
"""
from __future__ import annotations

import argparse
import hashlib
import io
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'dist' / '图.png'
RES = ROOT / 'app' / 'res'
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'''
BACKGROUND = '''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#FFFFFF" />
</shape>
'''

def artwork():
    with Image.open(SOURCE) as im:
        if im.format != 'PNG':
            raise ValueError('The supplied artwork must be PNG')
        im.verify()
    with Image.open(SOURCE) as im:
        image = ImageOps.exif_transpose(im).convert('RGBA')
        if min(image.size) < 48:
            raise ValueError('Artwork must be at least 48 pixels in each dimension')
        return image

def centered(image, size, box, background):
    canvas = Image.new('RGBA', (size, size), background)
    scaled = ImageOps.contain(image, (box, box), Image.Resampling.LANCZOS)
    canvas.alpha_composite(scaled, ((size-scaled.width)//2, (size-scaled.height)//2))
    return canvas

def expected_files():
    image = artwork()
    files = {}
    def png(path, pixels):
        buf = io.BytesIO()
        pixels.save(buf, format='PNG', optimize=True)
        files[path] = buf.getvalue()
    for density, scale in DENSITIES.items():
        size = round(48 * scale)
        directory = RES / ('mipmap-' + density)
        png(directory / 'ic_launcher.png', centered(image, size, size, 'white').convert('RGB'))
        # 70% square is fully within a circular mask (sqrt(2)/2 ~= 70.7%).
        rounded = centered(image, size, int(size * .70), 'white')
        mask = Image.new('L', (size * 4, size * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        rounded.putalpha(mask.resize((size, size), Image.Resampling.LANCZOS))
        png(directory / 'ic_launcher_round.png', rounded)
        # A 46dp square (diagonal 65.1dp) fits the 66dp adaptive safe circle.
        png(directory / 'ic_launcher_foreground.png',
            centered(image, round(108 * scale), round(46 * scale), (0, 0, 0, 0)))
    for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        files[RES / 'mipmap-anydpi-v26' / name] = ADAPTIVE.encode('utf-8')
    files[RES / 'drawable' / 'ic_launcher_background.xml'] = BACKGROUND.encode('utf-8')
    return files

def verify(files):
    for path, expected in files.items():
        actual = path.read_bytes()
        if actual != expected:
            raise AssertionError('Unexpected icon content: ' + str(path.relative_to(ROOT)))
        if path.suffix == '.png':
            with Image.open(io.BytesIO(actual)) as im:
                im.verify()
        else:
            ET.fromstring(actual)
    icons = {p for p in RES.rglob('ic_launcher*') if p.is_file()}
    if icons != set(files):
        raise AssertionError('Uncovered icon resources: ' + str(icons.symmetric_difference(files.keys())))
    ns = '{http://schemas.android.com/apk/res/android}'
    app = ET.parse(ROOT / 'app' / 'AndroidManifest.xml').getroot().find('application')
    assert app is not None
    assert app.get(ns + 'icon') == '@mipmap/ic_launcher'
    assert app.get(ns + 'roundIcon') == '@mipmap/ic_launcher_round'
    print('PASS: all 18 icon resources match supplied artwork; manifest references intact')
    print('source SHA256:', hashlib.sha256(SOURCE.read_bytes()).hexdigest())

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='verify without changing files')
    parser.add_argument('--build-confirmed', action='store_true',
                        help='invoke existing shared builder ONLY after lead coordination')
    args = parser.parse_args()
    files = expected_files()
    if not args.check:
        for path, data in files.items():
            # Read before replacing any existing binary or text resource.
            previous = path.read_bytes() if path.exists() else None
            if previous != data:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                print('updated:', path.relative_to(ROOT))
    verify(files)
    if args.build_confirmed:
        subprocess.run([sys.executable, str(ROOT / 'tools' / 'build_apk.py'),
                        '--name', 'BriVoice-7.6-new-icon.apk'], cwd=ROOT, check=True)

if __name__ == '__main__':
    main()
