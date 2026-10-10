# -*- coding: utf-8 -*-
import sys
from xml.etree import ElementTree as ET

def fp(path, maxd=3):
    root = ET.parse(path).getroot()
    out = []
    def walk(el, d, prefix):
        if d > maxd: return
        kids = list(el)
        names = []
        for k in kids:
            nm = k.tag.split('}')[-1]
            if names and names[-1][0] == nm: names[-1][1] += 1
            else: names.append([nm, 1])
        if d > 0:
            tag = el.tag.split('}')[-1]
            out.append(prefix + tag + ' [' + ', '.join('%s%s' % (n, ('x%d' % c) if c > 1 else '') for n, c in names) + ']')
        for k in kids:
            walk(k, d + 1, prefix + '  ')
    walk(root, 0, '')
    return out

ref = fp(sys.argv[1])
ours = fp(sys.argv[2])
print('=== reference (%d nodes) ===' % len(ref))
for l in ref: print(l)
print()
print('=== ours (%d nodes) ===' % len(ours))
for l in ours: print(l)
print()
import re
def norm(lines):
    s = set()
    for l in lines:
        s.add(re.sub(r'x\d+', '', l.strip()))
    return s
r, o = norm(ref), norm(ours)
print('--- in reference but NOT in ours ---')
for l in sorted(r - o): print('  ' + l)
print('--- in ours but NOT in reference ---')
for l in sorted(o - r): print('  ' + l)