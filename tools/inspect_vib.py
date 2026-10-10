# -*- coding: utf-8 -*-
import glob, os
import xml.etree.ElementTree as ET
NS = '{http://www.yamaha.co.jp/vocaloid/schema/vsq4/}'
d = r'E:\自己作品\术\填词\萱草花'
for f in sorted(glob.glob(d + r'\*.vsqx')):
    if '颤音测试' not in os.path.basename(f):
        continue
    print('===', os.path.basename(f))
    r = ET.parse(f).getroot()
    for tr in r.findall(NS + 'vsTrack'):
        for part in tr.findall(NS + 'vsPart'):
            pt = part.find(NS + 't').text
            print('  part t=%s playTime=%s' % (pt, part.find(NS + 'playTime').text))
            for note in part.findall(NS + 'note'):
                t = note.find(NS + 't').text; du = note.find(NS + 'dur').text
                ns = note.find(NS + 'nStyle')
                seqs = []
                if ns is not None:
                    for sq in ns.findall(NS + 'seq'):
                        pts = [(c.find(NS+'p').text, c.find(NS+'v').text) for c in sq.findall(NS+'cc')]
                        seqs.append(sq.get('id') + '=' + str(pts))
                    vals = [(v.get('id'), v.text) for v in ns.findall(NS + 'v')]
                print('    note t=%s dur=%s vals=%s' % (t, du, vals))
                for s in seqs: print('       ', s)
