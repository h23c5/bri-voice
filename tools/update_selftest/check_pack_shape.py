import json, zipfile, sys
real = json.loads(zipfile.ZipFile(sys.argv[1]).read('voice.json').decode('utf-8'))
mine = json.loads(zipfile.ZipFile(sys.argv[2]).read('voice.json').decode('utf-8'))
print('zip entries real=%s mine=%s' % (zipfile.ZipFile(sys.argv[1]).namelist(), zipfile.ZipFile(sys.argv[2]).namelist()))
only_real = sorted(set(real) - set(mine)); only_mine = sorted(set(mine) - set(real))
print('top keys: missing=%s extra=%s' % (only_real or 'none', only_mine or 'none'))
hr, hm = real['harmonics'], mine['harmonics']
print('harmonics keys: real=%s mine=%s' % (sorted(hr), sorted(hm)))
print('freqs len real=%d mine=%d  first real=%.1f mine=%.1f' % (len(hr['freqs']), len(hm['freqs']), hr['freqs'][0], hm['freqs'][0]))
kr = sorted(hr['phonemes'])[0]; km = sorted(hm['phonemes'])[0]
print('band keys: real=%s mine=%s' % (sorted(hr['phonemes'][kr][0]), sorted(hm['phonemes'][km][0])))
nr = sorted(hr['noise'])[0] if 'noise' in hr else None
print('noise entry keys: real=%s mine=%s' % (sorted(hr['noise'][nr]) if nr else '-', sorted(hm['noise'][sorted(hm['noise'])[0]]) if 'noise' in hm else '-'))
fd = max(abs(a - b) for a, b in zip(hr['freqs'], hm['freqs']))
print('max |d freqs| = %.3e' % fd)
print('relative freqs diff = %.3e' % (fd / hr['freqs'][-1]))
ok = (not only_real) and (not only_mine) and len(hm['freqs']) == len(hr['freqs']) and (fd / hr['freqs'][-1]) < 1e-6
print('RESULT: ' + ('PASS - writer matches the contract' if ok else 'FAIL'))