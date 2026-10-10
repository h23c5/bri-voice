# -*- coding: utf-8 -*-
import glob, os, re, collections
import xml.etree.ElementTree as ET

files = sorted(glob.glob(r'E:\自己作品\**\*.vsqx', recursive=True))
tags = collections.Counter()
ids = collections.Counter()
seqids = collections.Counter()
planes = collections.Counter()
roots = collections.Counter()
for f in files:
    try:
        t = ET.parse(f); r = t.getroot()
    except Exception as e:
        print('PARSE FAIL', os.path.basename(f), e); continue
    roots[r.tag.split('}')[-1]] += 1
    for el in r.iter():
        tag = el.tag.split('}')[-1]
        tags[tag] += 1
        v = el.get('id')
        if v:
            ids[tag + '#' + v] += 1
        if tag == 'seq' and v:
            seqids[v] += 1
        if tag == 'plane':
            planes[(el.text or '').strip()] += 1
print('ROOTS:', dict(roots))
print()
print('--- element vocabulary (tag: count) ---')
for k, v in tags.most_common(200):
    print('%-16s %d' % (k, v))
print()
print('--- id-bearing elements ---')
for k, v in ids.most_common(60):
    print('%-28s %d' % (k, v))
print()
print('--- seq ids ---', dict(seqids))
print('--- plane values ---', dict(planes))
