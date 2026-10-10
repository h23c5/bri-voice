# -*- coding: utf-8 -*-
"""Rebuild a pack's harmonics from PER-PHONEME frames instead of shared cluster curves.

The converter gives every phoneme the curve of its nearest k-means cluster, so o = 7 = u and y = i` end
up byte-identical (measured 0.00 dB apart) and no synthesis tuning can separate them.

This tool decodes every sample unit of the .ddi, identifies it by the formants of its median curve and
gives its frames to that symbol. A run is only used when its best match beats the runner-up clearly;
symbols that end up with no frames keep the original cluster curve, so coverage never drops.
"""
import io, json, os, sys, zipfile, glob
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voicebank_pack as vp
from ddi_runs import runs, formants

SAMPLE_PER_RUN = int(os.environ.get('SAMPLE_PER_RUN', '24'))
MIN_FRAMES = int(os.environ.get('MIN_FRAMES', '20'))
WIN_MARGIN = float(os.environ.get('WIN_MARGIN', '0.85'))


def frame_curve(freqs, mags, grid):
    f = np.asarray(freqs, dtype=float)
    m = np.asarray(mags, dtype=float)
    ok = (f > 20) & np.isfinite(m)
    if ok.sum() < 6:
        return None
    db = np.interp(np.log(grid), np.log(f[ok]), m[ok])
    return np.clip(db - db.max(), vp.HARM_DB_FLOOR, 0.0)


def score(f1, f2, f3, ref):
    d = abs(np.log(f1 / ref[0])) if f1 > 0 else 0.8
    d += abs(np.log(f2 / ref[1])) if (f2 > 0 and ref[1] > 0) else 0.8
    d += 0.4 * (abs(np.log(f3 / ref[2])) if (f3 > 0 and ref[2] > 0) else 0.8)
    return d


def identify(f1, f2, f3, symbols):
    """best symbol, and the runner-up, by formant distance"""
    best, bestd, second = None, 1e9, 1e9
    for sym in symbols:
        ref = vp.REFERENCE_FORMANTS.get(sym)
        if not ref or ref[0] <= 0:
            continue
        d = score(f1, f2, f3, ref)
        if d < bestd:
            best, bestd, second = sym, d, bestd
        elif d < second:
            second = d
    return best, bestd, second


def main():
    bank, pack_in, pack_out = sys.argv[1], sys.argv[2], sys.argv[3]
    ddi = glob.glob(os.path.join(bank, '*.ddi'))[0]
    ddb = glob.glob(os.path.join(bank, '*.ddb'))[0]
    ddb_size = os.path.getsize(ddb)
    symbols, flags, _, _ = vp.read_phdc(ddi)
    with zipfile.ZipFile(pack_in) as z:
        voice = json.loads(z.read('voice.json').decode('utf-8'))
        lic = z.read('LICENSE.txt')
    grid = np.asarray(voice['harmonics']['freqs'], dtype=float)
    original = voice['harmonics']['phonemes']
    rs = runs(ddi, ddb_size)
    print('sample units: %d; symbols in the grid: %d' % (len(rs), len(symbols)))
    curves_by_sym, f0_by_sym = {}, {}
    used = skipped = 0
    with io.open(ddb, 'rb') as f:
        for r in rs:
            step = max(1, r['len'] // SAMPLE_PER_RUN)
            cs, f0s = [], []
            for k in range(0, r['len'], step):
                off = r['first'] + k * r['stride']
                if off + vp.REC_BYTES > ddb_size:
                    break
                f.seek(off)
                dec = vp.decode_frame(f.read(vp.REC_BYTES), vp.HARM_MIN_HARMONICS, vp.HARM_F0_MIN, vp.HARM_F0_MAX)
                if dec is None:
                    continue
                f0, hf, mags, err = dec
                c = frame_curve(hf, mags, grid)
                if c is None:
                    continue
                cs.append(c)
                f0s.append(f0)
            if len(cs) < MIN_FRAMES:
                skipped += 1
                continue
            med = np.median(np.asarray(cs), axis=0)
            f1, f2, f3 = formants(grid, med)
            sym, bestd, second = identify(f1, f2, f3, symbols)
            if sym is None or bestd > WIN_MARGIN * second:
                skipped += 1
                continue
            curves_by_sym.setdefault(sym, []).extend(cs)
            f0_by_sym.setdefault(sym, []).extend(f0s)
            used += 1
    print('units used: %d, skipped (unmeasured or ambiguous): %d, symbols with own frames: %d'
          % (used, skipped, len(curves_by_sym)))
    phonemes = {}
    replaced, kept = [], []
    for sym in original:
        cs = curves_by_sym.get(sym)
        if cs and len(cs) >= MIN_FRAMES:
            bands = vp.pitch_bands(np.asarray(cs), np.asarray(f0_by_sym[sym]))
            if bands:
                phonemes[sym] = bands
                replaced.append(sym)
                continue
        phonemes[sym] = original[sym]          # no measured frames of its own: keep the old curve
        kept.append(sym)
    print('per-phoneme curves: %d replaced, %d kept from the cluster table' % (len(replaced), len(kept)))
    print('  replaced: %s' % ' '.join(sorted(replaced)))
    print('  kept    : %s' % ' '.join(sorted(kept)))
    assert len(phonemes) == len(original), 'symbol coverage changed'
    voice['harmonics']['phonemes'] = phonemes
    voice['harmonics']['source'] = (voice['harmonics']['source']
        + ' | harmonics rebuilt per phoneme from %d .ddi sample units (%d symbols), no cluster sharing'
           % (used, len(replaced)))
    with zipfile.ZipFile(pack_out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('voice.json', json.dumps(voice, separators=(',', ':'), ensure_ascii=False).encode('utf-8'))
        z.writestr('LICENSE.txt', lic)
    print('wrote %s (%.1f KB)' % (pack_out, os.path.getsize(pack_out) / 1024.0))


if __name__ == '__main__':
    main()