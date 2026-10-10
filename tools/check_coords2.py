# -*- coding: utf-8 -*-
import glob, os
import xml.etree.ElementTree as ET
N = '{http://www.yamaha.co.jp/vocaloid/schema/vsq4/}'
for f in sorted(glob.glob(r'E:\自己作品\**\*.vsqx', recursive=True)):
    r = ET.parse(f).getroot()
    if not r.tag.startswith(N): continue
    print('===', os.path.basename(f))
    for ti, tr in enumerate(r.findall(N + 'vsTrack')):
        for pi, part in enumerate(tr.findall(N + 'vsPart')):
            t = int(part.find(N + 't').text)
            pt = int(part.find(N + 'playTime').text)
            ts = [int(n.find(N+'t').text) for n in part.findall(N + 'note')]
            dur = [int(n.find(N+'dur').text) for n in part.findall(N + 'note')]
            if ts:
                print('   part%d t=%d playTime=%d end=%d | notes %d..%d | maxNoteEnd=%d | notesInsidePart=%s' %
                      (pi, t, pt, t+pt, min(ts), max(ts), max(a+b for a,b in zip(ts,dur)),
                       (min(ts) >= t and max(a+b for a,b in zip(ts,dur)) <= t+pt)))
