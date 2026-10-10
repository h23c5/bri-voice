# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\rebuild_harmonics.py'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

# identify from the run's median curve, not its first frame, and sample the whole run
rep('            step = max(1, r\'len\' // FRAMES_PER_RUN)',
    '            step = max(1, r[\'len\'] // max(FRAMES_PER_RUN, 40))', 'sampling')
rep('            f1, f2 = formants(np.asarray(grid), np.asarray(cs[0]))',
    '            med = np.median(np.asarray(cs), axis=0)      # the whole unit, not its first (attack) frame' + chr(10) +
    '            f1, f2 = formants(np.asarray(grid), med)', 'median id')
rep('            if len(cs) < vp.HARM_BAND_MIN_FRAMES:',
    '            if len(cs) < 4:', 'min frames')
rep('    rs = runs(ddi, ddb_size)[:MAX_RUNS]',
    '    rs = runs(ddi, ddb_size)' + chr(10) +
    '    print(\'runs detected: %d\' % len(rs))', 'no cap')
rep('    print(\'symbols written: %d (was %d)\' % (len(phonemes), len(voice[\'harmonics\'][\'phonemes\'])))',
    ('    print(\'symbols written: %d (was %d)\' % (len(phonemes), len(voice[\'harmonics\'][\'phonemes\'])))' + chr(10) +
     '    print(\'frames per symbol: \' + \', \'.join(\'%s:%d\' % (k, len(v)) for k, v in sorted(per_sym_curves.items())))'), 'report')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('patched')