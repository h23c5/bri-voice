import importlib.util, os, sys, struct
import numpy as np
spec = importlib.util.spec_from_file_location('vp', os.path.join(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android', 'tools', 'voicebank_pack.py'))
vp = importlib.util.module_from_spec(spec); spec.loader.exec_module(vp)
ddb = sys.argv[1]
offs, _ = vp.scan_snd_offsets(ddb, os.path.getsize(ddb), 64 << 20)
grid = vp.harmonic_grid(vp.HARM_POINTS)
f = open(ddb, 'rb'); f.seek(int(offs[0])); h = f.read(18)
n = struct.unpack_from('<H', h, 14)[0]
pcm = np.frombuffer(f.read(2 * n), dtype='<i2'); f.close()
curve, feat = vp.snd_analysis(pcm, np.log(grid))
io = open(sys.argv[2], 'w')
io.write(' '.join('%.9e' % v for v in curve) + '\n')
io.write('centroid=%.6f bandwidth=%.6f flatness=%.9e level=%.6f samples=%d\n' % (feat['centroid'], feat['bandwidth'], feat['flatness'], feat['level'], feat['samples']))
io.close()
print('py ok, samples=%d' % n)