import json, zipfile, sys
z = zipfile.ZipFile(sys.argv[1])
print('zip entries: ' + ', '.join(z.namelist()))
d = json.loads(z.read('voice.json').decode('utf-8'))
print('top keys: ' + ', '.join('%s(%s)' % (k, type(d[k]).__name__) for k in d))
def sk(name, o, depth=1):
    if isinstance(o, dict):
        items = list(o.items())[:6]
        print('  ' * depth + name + ': dict keys=' + ', '.join(k for k, _ in items) + (' ...' if len(o) > 6 else ''))
        for k, v in items[:2]:
            sk(k, v, depth + 2)
    elif isinstance(o, list):
        print('  ' * depth + name + ': list len=%d first=%s' % (len(o), str(o[0])[:60] if o else '-'))
    else:
        print('  ' * depth + name + ': ' + str(o)[:60])
for k in ('voice', 'shelf', 'formants', 'stats'):
    if k in d: sk(k, d[k], 1)
h = d.get('harmonics') or {}
sk('harmonics', h, 1)
if 'phonemes' in h:
    ph = h['phonemes']
    k0 = list(ph)[0] if isinstance(ph, dict) else None
    print('  phonemes: %s, count=%d' % (type(ph).__name__, len(ph)))
    if k0 is not None: sk('phonemes[%s]' % k0, ph[k0], 3)
n = h.get('noise')
if n:
    k1 = list(n)[0]
    print('  noise symbols: ' + ', '.join(list(n)[:10]))
    sk('noise[%s]' % k1, n[k1], 3)