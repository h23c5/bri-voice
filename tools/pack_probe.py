# -*- coding: utf-8 -*-
import json, sys, zipfile, os, datetime
for p in sys.argv[1:]:
    z = zipfile.ZipFile(p); m = json.loads(z.read('voice.json'))
    h = m.get('harmonics') or {}
    print('===', os.path.basename(p), '%.1f KB' % (os.path.getsize(p)/1024), 'mtime', datetime.datetime.fromtimestamp(os.path.getmtime(p)).strftime('%H:%M:%S'))
    print('   keys:', sorted(m.keys()))
    print('   source:', (h.get('source') or '')[:110])
    print('   phonemes:', len(h.get('phonemes') or {}), '| bands:', sum(len(v) for v in (h.get('phonemes') or {}).values()), '| freqs:', len(h.get('freqs') or []))
    st = m.get('stats') or {}
    print('   stats:', {k: st[k] for k in sorted(st) if 'harm' in k or k in ('frames','f0Median')})
