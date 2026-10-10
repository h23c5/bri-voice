# -*- coding: utf-8 -*-
import glob, os
import xml.etree.ElementTree as ET
N4 = '{http://www.yamaha.co.jp/vocaloid/schema/vsq4/}'
N3 = '{http://www.yamaha.co.jp/vocaloid/schema/vsq3/}'
for f in sorted(glob.glob(r'E:\自己作品\**\*.vsqx', recursive=True)):
    r = ET.parse(f).getroot()
    ns = N4 if r.tag.startswith(N4) else N3
    name = os.path.basename(f)
    info = []
    for tr in r.findall(ns + 'vsTrack'):
        for part in tr.findall(ns + 'vsPart') + tr.findall(ns + 'musicalPart'):
            pt = part.find(ns + 't')
            pt = pt.text if pt is not None else part.find(ns + 'posTick').text
            notes = part.findall(ns + 'note')
            ts = [int(n.find(ns + 't').text) if n.find(ns + 't') is not None else int(n.find(ns + 'posTick').text) for n in notes]
            info.append('partT=%s notes=%d range=%s..%s' % (pt, len(notes), min(ts) if ts else '-', max(ts) if ts else '-'))
    print(name, '|', '; '.join(info)[:200])
