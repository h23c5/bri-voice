import importlib.util, io, os, sys, math
import numpy as np
spec = importlib.util.spec_from_file_location('vp', os.path.join(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android', 'tools', 'voicebank_pack.py'))
vp = importlib.util.module_from_spec(spec); spec.loader.exec_module(vp)
N, M = 140, vp.HARM_POINTS
curves = np.zeros((N, M)); f0s = np.zeros(N)
for i in range(N):
    f0s[i] = 180.0 + 90.0 * math.sin(i * 0.13)
    lvl = -6.0 * math.sin(i * 0.21)
    for j in range(M):
        curves[i, j] = -30.0 - 0.9 * j + 8.0 * math.sin(j * 0.35 + i * 0.11) + lvl
lines = ['%d %d' % (N, M)]
for i in range(N):
    lines.append(' '.join('%.9e' % v for v in curves[i]))
lines.append(' '.join('%.9e' % v for v in f0s))
io.open(sys.argv[1], 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
def shape(b):
    return 'f0=%s dyn=%s dbn=%d seqN=%d lvl=%s seq=%s' % (('%.1f' % b['f0']) if 'f0' in b else '-', '-' if b.get('dyn') is None else ('%g' % b['dyn']), len(b.get('db') or []), b.get('seqN') or 0, ','.join('%.1f' % v for v in (b.get('lvl') or [])), b.get('seqB64') or '-')
out = []
out.append('pitch_bands -> %d bands' % len(vp.pitch_bands(curves, f0s)))
for b in vp.pitch_bands(curves, f0s): out.append('  ' + shape(b))
part = np.arange(N)
one = vp.band_curve(curves, f0s, 0.5); out.append('band_curve ' + shape(one))
sq = vp.frame_sequence(curves, f0s, part); out.append('frame_sequence ' + shape(sq))
for b in vp.dyn_variants(curves, f0s, part): out.append('dyn_variants ' + shape(b))
io.open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')
print('py lines=%d' % len(out))