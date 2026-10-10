# -*- coding: utf-8 -*-
import sys
from xml.etree import ElementTree as ET

def get(root, path):
    cur = root
    for step in path.split('/'):
        nxt = None
        for c in cur:
            if c.tag.split('}')[-1] == step:
                nxt = c; break
        if nxt is None: return None
        cur = nxt
    return cur

def val(root, path):
    el = get(root, path)
    return None if el is None else (el.text or '').strip()

paths = ['vender','version','vVoiceTable/vVoice/bs','vVoiceTable/vVoice/pc','vVoiceTable/vVoice/id',
         'vVoiceTable/vVoice/name','mixer/masterUnit/vol','mixer/masterUnit/rLvl','mixer/vsUnit/vol',
         'masterTrack/seqName','masterTrack/resolution','masterTrack/preMeasure','masterTrack/tempo/t',
         'masterTrack/tempo/v','vsTrack/tNo','vsTrack/name','vsTrack/vsPart/t','vsTrack/vsPart/playTime',
         'vsTrack/vsPart/name','vsTrack/vsPart/singer/t','vsTrack/vsPart/singer/bs','vsTrack/vsPart/singer/pc',
         'vsTrack/vsPart/sPlug/id']
files = [('reference', sys.argv[1]), ('ours', sys.argv[2])]
roots = {n: ET.parse(p).getroot() for n, p in files}
print('%-38s %-28s %s' % ('path', 'reference', 'ours'))
for pth in paths:
    r = val(roots['reference'], pth)
    o = val(roots['ours'], pth)
    r = (r[:26] + '..') if r and len(r) > 28 else r
    o = (o[:26] + '..') if o and len(o) > 28 else o
    flag = '' if (r or '') == (o or '') else '   <-- differs'
    print('%-38s %-28s %s%s' % (pth, str(r), str(o), flag))
print()
for n, p in files:
    root = roots[n]
    tstamp = val(root, 'tstamp')
    print(n, 'timeSig count', len(get(root, 'masterTrack').findall('{*}timeSig')), 'tempo count', len(get(root, 'masterTrack').findall('{*}tempo')), 'tstamp', tstamp)