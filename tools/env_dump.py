# -*- coding: utf-8 -*-
import importlib.util, os, sys
spec = importlib.util.spec_from_file_location('vp', os.path.join(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android', 'tools', 'voicebank_pack.py'))
vp = importlib.util.module_from_spec(spec); spec.loader.exec_module(vp)
ddb, out = sys.argv[1], sys.argv[2]
offs = vp.scan_frm2_offsets(ddb, os.path.getsize(ddb), 8 << 20)
rows = []
with open(ddb, 'rb') as f:
    for off in offs:
        if len(rows) >= 50: break
        f.seek(int(off)); rec = f.read(vp.REC_BYTES)
        r = vp.decode_frame(rec)
        if r is None: continue
        f0, freqs, mags, err = r
        env = vp.frame_envelope(freqs, mags)
        rows.append(' '.join('%.9e' % float(v) for v in env))
open(out, 'w').write('\n'.join(rows) + '\n')
print('python envelopes: %d x %d' % (len(rows), len(rows[0].split()) if rows else 0))