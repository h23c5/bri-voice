# -*- coding: utf-8 -*-
import glob, re, os, collections
import xml.etree.ElementTree as ET
N = '{http://www.yamaha.co.jp/vocaloid/schema/vsq4/}'
targets = [r'E:\自己作品\术\填词\Hug\はぐ__拥抱__HUG.vsqx', r'E:\自己作品\术\填词\魔术之心\魔术之心.vsqx']
for f in targets:
    if not os.path.exists(f):
        print('missing', f); continue
    r = ET.parse(f).getroot()
    ids = collections.Counter()
    sample = {}
    for part in r.iter(N + 'vsPart'):
        for cc in part.findall(N + 'cc'):
            for v in cc.findall(N + 'v'):
                i = v.get('id'); ids[i] += 1
                sample.setdefault(i, []).append((cc.find(N+'t').text, v.text))
    print('===', os.path.basename(f), 'cc ids:', dict(ids))
    for i, vals in sample.items():
        print('   id=%-4s n=%d  first=%s  min=%s max=%s' % (i, len(vals),
              vals[:3], min(int(v) for _, v in vals), max(int(v) for _, v in vals)))
    # dump raw xml of the first cc of each id inside a vsPart
    txt = open(f, encoding='utf-8').read()
    m = re.search(r'<cc>.{0,200}?</cc>', txt, re.S)
    print('   raw cc sample:', m.group(0).replace('\n','').replace('\t','') if m else 'none')
