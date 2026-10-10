import json, zipfile, sys
def load(p):
    z = zipfile.ZipFile(p); return json.loads(z.read('voice.json').decode('utf-8'))
pr, pm = load(sys.argv[1]), load(sys.argv[2])
def bandsets(d, which):
    h = d['harmonics']['phonemes']
    sets = {}
    for sym, bl in h.items():
        for b in bl: sets[tuple(sorted(b))] = sets.get(tuple(sorted(b)), 0) + 1
    return sets
sr, sm = bandsets(pr, 'real'), bandsets(pm, 'mine')
print('band key-sets real: %s' % {','.join(k): v for k, v in sr.items()})
print('band key-sets mine: %s' % {','.join(k): v for k, v in sm.items()})
print('bands: real=%d mine=%d ; symbols: real=%d mine=%d' % (sum(sr.values()), sum(sm.values()), len(pr['harmonics']['phonemes']), len(pm['harmonics']['phonemes'])))
print('noise real=%s mine=%s' % (sorted(pr['harmonics'].get('noise', {})), sorted(pm['harmonics'].get('noise', {}))))
print('stats mine: noiseFound=%s sampled=%s named=%s' % (pm['stats'].get('noiseSndFound'), pm['stats'].get('noiseSampled'), pm['stats'].get('noiseNamed')))