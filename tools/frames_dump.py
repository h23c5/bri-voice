# -*- coding: utf-8 -*-
import importlib.util, os, sys
spec = importlib.util.spec_from_file_location('vp', os.path.join(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android', 'tools', 'voicebank_pack.py'))
vp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vp)
ddb, out = sys.argv[1], sys.argv[2]
size = os.path.getsize(ddb)
offs = vp.scan_frm2_offsets(ddb, size, 8 << 20)
lines = []
with open(ddb, 'rb') as f:
    for off in list(offs)[:200]:
        f.seek(int(off))
        rec = f.read(vp.REC_BYTES)
        r = vp.decode_frame(rec)
        if r is None:
            lines.append('%d SKIP' % int(off)); continue
        f0, freqs, mags, err = r
        lines.append('%d %.9f %d %.9g %.6f' % (int(off), float(f0), len(freqs), float(err), float(mags.sum())))
open(out, 'w').write('\n'.join(lines) + '\n')
print('python: offsets=%d dumped=%d' % (len(offs), len(lines)))