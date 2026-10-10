# -*- coding: utf-8 -*-
import importlib.util, io, os, math, sys
import numpy as np
spec = importlib.util.spec_from_file_location('vp', os.path.join(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android', 'tools', 'voicebank_pack.py'))
vp = importlib.util.module_from_spec(spec); spec.loader.exec_module(vp)
F = vp.ENV_F; N = len(F)
def idx(hz):
    return min(range(N), key=lambda i: abs(F[i] - hz))
def peak(base_db, amps):
    c = [base_db + 3.0 * math.sin(i / 7.0) for i in range(N)]
    for hz, a in amps:
        j = idx(hz); c[j] += a
    return c
cases = []
cases.append(('clear', peak(-40.0, [(700, 14.0), (1800, 12.0), (3000, 10.0)]), [-40.0] * N, 220.0))
cases.append(('weak',  peak(-40.0, [(700, 1.0), (1800, 1.0), (3000, 1.0)]), [-40.0] * N, 220.0))
cases.append(('lowf0', peak(-40.0, [(600, 14.0), (1500, 12.0), (2600, 10.0)]), [-40.0] * N, 500.0))
lines = []; expect = []
for name, c, sh, f0 in cases:
    lines.append('CASE ' + name)
    lines.append('CENTROID ' + ' '.join('%.9e' % v for v in c))
    lines.append('SHELF ' + ' '.join('%.9e' % v for v in sh))
    lines.append('F0MED %.9e' % f0)
    r = vp.pick_formants(np.asarray(c, dtype=np.float64), np.asarray(sh, dtype=np.float64), f0)
    expect.append(name + ' -> ' + ('NULL' if r is None else ' '.join('%.9e' % v for v in r)))
io.open(sys.argv[1], 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
io.open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write('\n'.join(expect) + '\n')
print('python pick_formants:'); [print('  ' + e) for e in expect]