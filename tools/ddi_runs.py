# -*- coding: utf-8 -*-
"""Extract the .ddi offset tables as sample units, with the formants each unit carries.

The converter flattens every offset into one set and guesses phonemes by k-means clustering, which gives
several phonemes the SAME curve (measured: o = 7 = u, y = i`). Keeping the run structure and identifying
each run by its own formants is what makes per-phoneme curves possible.

Deduplication keys on the run's CENTRE offset: the same table is found at several byte alignments, and
an alignment shifts the start by a few entries, so keying on the first offset/stride alone left ~10k
duplicates that polluted the frame pool.
"""
import io, os, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voicebank_pack as vp


def runs(ddi_path, ddb_size, min_len=8):
    ddi = io.open(ddi_path, 'rb').read()
    found = {}
    n = len(ddi)
    for align in range(8):
        arr = np.frombuffer(ddi[align:n - ((n - align) % 8)], dtype='<u8').astype(np.int64)
        if arr.size < min_len + 3:
            continue
        ok = (arr[:-2] >= 0) & (arr[:-2] < ddb_size) & (arr[1:-1] < ddb_size) & (arr[2:] < ddb_size)
        d = np.diff(arr)
        const = (d[1:] == d[:-1]) & (d[1:] > 512) & (d[1:] < 200000) & ok
        i = 0
        while i < const.size:
            if not const[i]:
                i += 1
                continue
            j = i
            while j + 1 < const.size and const[j + 1]:
                j += 1
            length = j - i + 3
            if length >= min_len:
                stride = int(d[i])
                first = int(arr[i])
                centre = first + (length // 2) * stride
                key = (centre, stride)
                prev = found.get(key)
                if prev is None or length > prev['len']:
                    found[key] = {'ddi_pos': align + 8 * i, 'stride': stride,
                                  'first': first, 'len': int(length)}
            i = j + 1
    return sorted(found.values(), key=lambda r: -r['len'])


def formants(freqs, mags):
    """F1/F2/F3 after removing the one-octave source slope"""
    f = np.asarray(freqs, dtype=float)
    m = np.asarray(mags, dtype=float)
    keep = (f > 80) & (f < 4200)
    f, m = f[keep], m[keep]
    if f.size < 6:
        return 0.0, 0.0, 0.0
    grid = np.exp(np.linspace(np.log(80.0), np.log(4200.0), 128))
    db = np.interp(np.log(grid), np.log(f), m)
    res = np.empty_like(db)
    for i in range(db.size):
        w = np.abs(np.log(grid / grid[i])) <= 0.5 * np.log(2.0)
        res[i] = db[i] - db[w].mean()
    pk = []
    for i in range(1, db.size - 1):
        if res[i] >= res[i - 1] and res[i] >= res[i + 1] and res[i] > 0.8:
            pk.append(float(grid[i]))
    f1 = next((x for x in pk if x < 1100), 0.0)
    f2 = next((x for x in pk if f1 and x > f1 * 1.3), 0.0)
    f3 = next((x for x in pk if f2 and x > f2 * 1.2), 0.0)
    return f1, f2, f3


if __name__ == '__main__':
    import glob
    bank = sys.argv[1]
    ddi_path = glob.glob(os.path.join(bank, '*.ddi'))[0]
    ddb = glob.glob(os.path.join(bank, '*.ddb'))[0]
    ddb_size = os.path.getsize(ddb)
    rs = runs(ddi_path, ddb_size)
    print('unique runs: %d   (largest %d entries, smallest %d)' % (len(rs), rs[0]['len'], rs[-1]['len']))
    seen = {}
    with io.open(ddb, 'rb') as f:
        for r in rs[:60]:
            step = max(1, r['len'] // 10)
            f0s, f1s, f2s, f3s = [], [], [], []
            for k in range(0, r['len'], step):
                off = r['first'] + k * r['stride']
                if off + vp.REC_BYTES > ddb_size:
                    break
                f.seek(off)
                dec = vp.decode_frame(f.read(vp.REC_BYTES), vp.HARM_MIN_HARMONICS, vp.HARM_F0_MIN, vp.HARM_F0_MAX)
                if dec is None:
                    continue
                f0, hf, mags, err = dec
                f0s.append(f0)
                a, b, c = formants(hf, mags)
                f1s.append(a); f2s.append(b); f3s.append(c)
            if not f0s:
                continue
            sig = (round(float(np.median(f1s)) / 100.0), round(float(np.median(f2s)) / 100.0))
            seen[sig] = seen.get(sig, 0) + 1
            print('%-5d len=%-4d f0=%-5.0f F1=%-5.0f F2=%-5.0f F3=%-5.0f stride=%d' % (
                r['ddi_pos'], r['len'], float(np.median(f0s)), float(np.median(f1s)),
                float(np.median(f2s)), float(np.median(f3s)), r['stride']))
    print('distinct formant signatures among the first 60 runs: %d' % len(seen))