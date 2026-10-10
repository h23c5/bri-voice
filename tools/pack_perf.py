# -*- coding: utf-8 -*-
"""[perf] how much of the pack is the redundant per-band "db" array?"""
import json, zipfile, io, os, sys
proj = r"C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android"
src = os.path.join(proj, 'build', 'voicepacks', 'luotianyi_chn_meng_bank.v4voice')
z = zipfile.ZipFile(src)
raw = z.read('voice.json')
print('voice.json bytes      :', len(raw))
d = json.loads(raw.decode('utf-8'))
ph = d['harmonics']['phonemes']
n_bands = sum(len(v) for v in ph.values())
n_db = 0
for v in ph.values():
    for b in v:
        n_db += len(b.get('db', []))
print('bands                 :', n_bands)
print('db numbers (redundant):', n_db)
# strip "db" where a sequence exists, and re-measure
stripped = 0
for v in ph.values():
    for b in v:
        if 'seqB64' in b and 'db' in b:
            del b['db']
            stripped += 1
out = json.dumps(d, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
print('stripped bands        :', stripped)
print('voice.json without db :', len(out), '(%.1f%% smaller)' % (100.0 * (1 - len(out) / len(raw))))
dst = os.path.join(proj, 'build', 'voicepacks', 'luotianyi_nodb.v4voice')
zo = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
zo.writestr('voice.json', out)
try:
    zo.writestr('LICENSE.txt', z.read('LICENSE.txt'))
except KeyError:
    pass
zo.close()
print('wrote', dst, os.path.getsize(dst), 'bytes')
