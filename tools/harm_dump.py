# -*- coding: utf-8 -*-
import importlib.util, io, os, sys
import numpy as np
spec = importlib.util.spec_from_file_location('vp', os.path.join(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android', 'tools', 'voicebank_pack.py'))
vp = importlib.util.module_from_spec(spec); spec.loader.exec_module(vp)
print('py HARM_F_LO=%s HARM_F_HI=%s HARM_POINTS=%s' % (getattr(vp,'HARM_F_LO',None), getattr(vp,'HARM_F_HI',None), vp.HARM_POINTS))
ddb, out = sys.argv[1], sys.argv[2]
grid = vp.harmonic_grid(vp.HARM_POINTS)
offs = vp.scan_frm2_offsets(ddb, os.path.getsize(ddb), 8 << 20)
rows = [' '.join('%.9e' % float(v) for v in grid)]
n = 0
with open(ddb, 'rb') as f:
    for off in offs:
        if n >= 20: break
        f.seek(int(off)); rec = f.read(vp.REC_BYTES)
        r = vp.decode_frame(rec)
        if r is None: continue
        f0, freqs, mags, err = r
        c = vp.frame_harm_curve(freqs, mags, np.log(grid))   # the function wants log frequencies
        rows.append(' '.join('%.9e' % float(v) for v in c)); n += 1
io.open(out, 'w', encoding='utf-8', newline='\n').write('\n'.join(rows) + '\n')
print('py rows: grid + %d curves' % n)