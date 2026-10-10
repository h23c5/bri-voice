# -*- coding: utf-8 -*-
import glob, os, re, sys
files = glob.glob(r'E:\自己作品\**\*.vsqx', recursive=True)
for f in files:
    d = open(f, 'rb').read(900).decode('utf-8', 'ignore')
    m = re.search(r'<(vsq\d)[^>]*xmlns="([^"]+)"', d)
    v = re.search(r'<version><!\[CDATA\[([^\]]*)\]\]>', d)
    print(os.path.basename(f), '|', m.group(1) if m else '?', '|', (m.group(2) if m else '?')[-10:], '|', v.group(1) if v else '?')
print('---- other project formats on E: ----')
for pat in ('E:/**/*.vsq', 'E:/**/*.vpr', 'E:/**/*.ust', 'E:/**/*.mid'):
    got = glob.glob(pat, recursive=True)
    print(pat, len(got), got[:5])
