# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\voicebank_pack.py'
s = io.open(p, encoding='utf-8').read()
marker = 'taken.add(ci)'
if marker in s:
    print('patch present')
else:
    i = s.find('    formants, dists = {}, {}' + chr(10) + '    for sym in symbols:')
    j = s.find('    return formants, dists', i)
    if i < 0 or j < 0:
        raise SystemExit('anchors not found')
    s = s[:i] + "    # A cluster serves only one symbol. The old nearest-cluster rule let several phonemes share a\n    # cluster and therefore get byte-identical curves - measured: o = 7 = u and y = i` at 0.00 dB\n    # apart, which no synthesis tuning can undo. Leftovers keep their nearest cluster.\n    pairs = []\n    for sym in symbols:\n        ref = REFERENCE_FORMANTS.get(sym)\n        if ref is None:\n            continue\n        t = np.log2(np.asarray(ref, dtype=np.float64))\n        for ci, c in enumerate(clusters):\n            dd = float(np.sqrt(np.mean((np.log2(np.asarray(c)) - t) ** 2)))\n            pairs.append((dd, sym, ci))\n    pairs.sort(key=lambda x: x[0])\n    formants, dists, taken = {}, {}, set()\n    for dd, sym, ci in pairs:\n        if sym in formants or ci in taken or dd > MATCH_MAX_OCT:\n            continue\n        formants[sym] = [int(round(v)) for v in clusters[ci]]\n        dists[sym] = dd\n        taken.add(ci)\n        if sym_cluster is not None and cluster_ids is not None and 0 <= ci < len(cluster_ids):\n            sym_cluster[sym] = int(cluster_ids[ci])\n    for dd, sym, ci in pairs:\n        if sym in formants or dd > MATCH_MAX_OCT:\n            continue\n        formants[sym] = [int(round(v)) for v in clusters[ci]]\n        dists[sym] = dd\n        if sym_cluster is not None and cluster_ids is not None and 0 <= ci < len(cluster_ids):\n            sym_cluster[sym] = int(cluster_ids[ci])\n" + s[j:]
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('one-to-one applied')
print('marker now present:', marker in io.open(p, encoding='utf-8').read())