import importlib.util, os, sys, struct
import numpy as np
spec = importlib.util.spec_from_file_location('vp', os.path.join(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android', 'tools', 'voicebank_pack.py'))
vp = importlib.util.module_from_spec(spec); spec.loader.exec_module(vp)
ddb = sys.argv[1]
offs, _ = vp.scan_snd_offsets(ddb, os.path.getsize(ddb), 0)
print('records found = %d' % len(offs))
step = max(1, len(offs) // 800)
fl = []
with open(ddb, 'rb') as f:
    for i in range(0, len(offs), step):
        f.seek(int(offs[i])); h = f.read(18)
        n = struct.unpack_from('<H', h, 14)[0]
        pcm = np.frombuffer(f.read(2 * n), dtype='<i2')
        curve, feat = vp.snd_analysis(pcm, np.log(vp.harmonic_grid(vp.HARM_POINTS)))
        if feat is None: continue
        fl.append((feat['flatness'], feat['centroid']))
fl_a = np.array([x[0] for x in fl])
print('sampled=%d  max flatness=%.4f  median=%.4f' % (len(fl), fl_a.max(), np.median(fl_a)))
print('records with flatness >= 0.02 : %d' % int((fl_a >= 0.02).sum()))
hi = [c for v, c in fl if v >= 0.02]
if hi: print('their centroids: %.0f .. %.0f Hz (median %.0f)' % (min(hi), max(hi), float(np.median(hi))))