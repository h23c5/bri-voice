# -*- coding: utf-8 -*-
import json, sys, zipfile
for p in sys.argv[1:]:
    m = json.loads(zipfile.ZipFile(p).read('voice.json'))
    h = m.get('harmonics') or {}
    ph = h.get('phonemes') or {}
    print('===', p.split('\\')[-1], '| phonemes:', len(ph), '| freqs:', len(h.get('freqs', [])))
    items = sorted(ph.items(), key=lambda kv: -len(kv[1]))
    print('   covered:', ', '.join('%s(%d)' % (k, len(v)) for k, v in items))
