# -*- coding: utf-8 -*-
import sys
from xml.etree import ElementTree as ET

def find(el, name):
    for c in el.iter():
        if c.tag.split('}')[-1] == name:
            return c
    return None

def skel(el, d=0, maxd=5, out=None):
    if out is None: out = []
    if d > maxd: return out
    tag = el.tag.split('}')[-1]
    kids = list(el)
    names = [k.tag.split('}')[-1] for k in kids]
    # collapse repeated siblings
    summary = []
    for nm in names:
        if not summary or summary[-1][0] != nm:
            summary.append([nm, 1])
        else:
            summary[-1][1] += 1
    out.append('  ' * d + tag + (' ' + str(sorted(el.attrib.keys())) if el.attrib else '')
               + ('   [' + ', '.join('%s x%d' % (n, c) for n, c in summary) + ']' if summary else ''))
    for k in kids:
        if d < maxd and len(out) < 60:
            skel(k, d + 1, maxd, out)
    return out

def report(path, label):
    root = ET.parse(path).getroot()
    print('===', label, '===', path.split(chr(92))[-1])
    part = find(root, 'part')
    track = find(root, 'vsTrack') or find(root, 'vSTrack')
    if track is not None:
        print('--- track subtree ---')
        for l in skel(track, 0, 3)[:22]:
            print('  ' + l)
    if part is not None:
        print('--- part subtree ---')
        for l in skel(part, 0, 2)[:14]:
            print('  ' + l)
    else:
        print('  (no <part> element!)')

report(sys.argv[1], 'reference')
report(sys.argv[2], 'ours')