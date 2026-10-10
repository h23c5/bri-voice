# -*- coding: utf-8 -*-
import io, re, sys, glob
from xml.etree import ElementTree as ET

def skeleton(path, depth=6, maxlines=60):
    it = ET.iterparse(path, events=('start',))
    lines = []
    for event, el in it:
        tag = el.tag.split('}')[-1]
        depthnow = 0
        # count ancestors via a stack
        lines.append((tag, tuple(sorted(el.attrib.keys()))))
    return lines

def tree(path, keep, maxout=40):
    root = ET.parse(path).getroot()
    out = []
    def walk(el, d):
        tag = el.tag.split('}')[-1]
        if len(out) < maxout:
            out.append('  ' * d + tag + (' ' + str(sorted(el.attrib.keys())) if el.attrib else ''))
        if d < keep:
            for c in list(el):
            
                walk(c, d + 1)
    walk(root, 0)
    return out

ref = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\fmt_in\萱草花.vsqx'
mine = sys.argv[1] if len(sys.argv) > 1 else None
print('=== reference (VOCALOID4) ===')
for l in tree(ref, 5, 46):
    print(l)