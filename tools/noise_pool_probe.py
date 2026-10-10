import importlib.util, os, sys, struct
import numpy as np
spec = importlib.util.spec_from_file_location('vp', os.path.join(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android', 'tools', 'voicebank_pack.py'))
vp = importlib.util.module_from_spec(spec); spec.loader.exec_module(vp)
ddb = sys.argv[1]
offs, _ = vp.scan_snd_offsets(ddb, os.path.getsize(ddb), 0)
F = []
with open(ddb, 'rb') as f:
    for off in offs:
        f.seek(int(off)); h = f.read(18)
        n = struct.unpack_from('<H', h, 14)[0]
        pcm = np.frombuffer(f.read(2 * n), dtype='<i2')
        c, ft = vp.snd_analysis(pcm, np.log(vp.harmonic_grid(vp.HARM_POINTS)))
        if ft is None: continue
        F.append((ft['centroid'], ft['bandwidth'], ft['flatness'], ft['level']))
F = np.array(F)
nz = (F[:, 0] >= 2500.0) & (F[:, 2] >= vp.NOISE_FLAT_MIN)
print('total analysed=%d ; noise-like pre-filter kept=%d' % (len(F), int(nz.sum())))
if nz.sum() >= 8:
    kk = min(vp.NOISE_CLASSES, max(2, int(nz.sum()) // 5))
    print('kk would be %d' % kk)
print('flatness: median=%.4f  p90=%.4f  max=%.4f' % (np.median(F[:, 2]), np.percentile(F[:, 2], 90), F[:, 2].max()))
print('centroid >= 2500 only: %d ; flatness >= 0.02 only: %d' % (int((F[:,0] >= 2500).sum()), int((F[:,2] >= vp.NOISE_FLAT_MIN).sum())))