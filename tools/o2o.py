# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\voicebank_pack.py'
s = io.open(p, encoding='utf-8').read()
old_head = '    formants, dists = {}, {}' + chr(10) + '    for sym in symbols:'
if 'one-to-one' in s:
    print('already patched')
else:
    i = s.find(old_head)
    if i < 0:
        raise SystemExit('match body not found')
    j = s.find('    return formants, dists', i)
    if j < 0:
        raise SystemExit('return not found')
    body = '\n'.join([
        '    # one-to-one: nearest measured cluster per symbol, but a cluster serves only one symbol.',
        '    # The old nearest-cluster rule let several phonemes share a cluster and therefore get',
        '    # byte-identical curves - measured: o = 7 = u and y = i` at 0.00 dB apart, which no',
        '    # synthesis tuning can undo. Symbols that find no free cluster keep their nearest one.',
        '    pairs = []',
        '    for sym in symbols:',
        '        ref = REFERENCE_FORMANTS.get(sym)',
        '        if ref is None:',
        '            continue',
        '        t = np.log2(np.asarray(ref, dtype=np.float64))',
        '        for ci, c in enumerate(clusters):',
        '            dd = float(np.sqrt(np.mean((np.log2(np.asarray(c)) - t) ** 2)))',
        '            pairs.append((dd, sym, ci))',
        '    pairs.sort(key=lambda x: x[0])',
        '    formants, dists, taken = {}, {}, set()',
        '    for dd, sym, ci in pairs:',
        '        if sym in formants or ci in taken or dd > MATCH_MAX_OCT:',
        '            continue',
        '        formants[sym] = [int(round(v)) for v in clusters[ci]]',
        '        dists[sym] = dd',
        '        taken.add(ci)',
        '        if sym_cluster is not None and cluster_ids is not None and 0 <= ci < len(cluster_ids):',
        '            sym_cluster[sym] = int(cluster_ids[ci])',
        '    for dd, sym, ci in pairs:                      # leftovers may share again',
        '        if sym in formants or dd > MATCH_MAX_OCT:',
        '            continue',
        '        formants[sym] = [int(round(v)) for v in clusters[ci]]',
        '        dists[sym] = dd',
        '        if sym_cluster is not None and cluster_ids is not None and 0 <= ci < len(cluster_ids):',
            sym_cluster[sym] = int(cluster_ids[ci])',
    ]) + chr(10)
    s = s[:i] + body + s[j:]
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('match_phonemes is one-to-one now')