# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\voicebank_pack.py'
s = io.open(p, encoding='utf-8').read()
if 'noise-like pre-filter' in s:
    print('already patched')
else:
    old = "                kk = int(min(NOISE_CLASSES, max(2, noise_sampled // 5)))\n                noise_nclasses = kk\n                Cn, labn = kmeans(Xn, kk)"
    new = "                # Cluster only the noise-like records. Clustering all of them let the handful of\n                # fricative samples be absorbed by the voiced classes, so no class ever showed a\n                # fricative signature and the whole section was dropped (measured: 10 of 2988 were\n                # noise-like, yet a wide survey of the same bank finds ~9%).\n                nz = (Fn[:, 2] >= NOISE_FLAT_MIN) & (Fn[:, 0] >= 1200.0)\n                if int(nz.sum()) < 6:\n                    nz = Fn[:, 2] >= float(np.percentile(Fn[:, 2], 92.0))\n                if int(nz.sum()) < 6:\n                    nz = np.ones(Fn.shape[0], dtype=bool)\n                Xc = Xn[nz]\n                Fc = Fn[nz]\n                log(\"      [measured] noise-like pre-filter: %d of %d sampled records\" % (int(nz.sum()), noise_sampled))\n                kk = int(min(NOISE_CLASSES, max(2, Xc.shape[0] // 5)))\n                noise_nclasses = kk\n                Cn, labn = kmeans(Xc, kk)"
    if old not in s: raise SystemExit('anchor missing')
    s = s.replace(old, new, 1)
    s = s.replace('"centroid": float(np.median(Fn[m, 0])),', '"centroid": float(np.median(Fc[m, 0])),', 1)
    s = s.replace('"bandwidth": float(np.median(Fn[m, 1])),', '"bandwidth": float(np.median(Fc[m, 1])),', 1)
    s = s.replace('"flatness": float(np.median(Fn[m, 2])),', '"flatness": float(np.median(Fc[m, 2])),', 1)
    s = s.replace('"level": float(np.median(Fn[m, 3])),', '"level": float(np.median(Fc[m, 3])),', 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('pre-filter applied')
import ast; ast.parse(io.open(p, encoding='utf-8').read()); print('syntax ok')