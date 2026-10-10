# -*- coding: utf-8 -*-
"""Rebuild a pack's harmonics by assigning every FRAME to the phoneme it sounds like.

The converter quantises frames into k-means clusters and hands each phoneme its nearest cluster, which
made several phonemes share one curve (measured: o = 7 = u, y = i` at 0.00 dB). Assigning units instead
does not work either: a bank stores diphone samples, so a unit is not one phoneme. Frames are the right
granularity - the middle of a diphone sample belongs to its vowel.

A frame joins a phoneme only when that phoneme's reference is clearly the closest; ambiguous frames
(transitions, fricatives, silence) are dropped. Phonemes that end up with too few frames keep the curve
they had, so coverage never drops.
"""
import io, json, os, sys, zipfile, glob
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voicebank_pack as vp
from ddi_runs import runs, formants

SAMPLE_PER_RUN = int(os.environ.get('SAMPLE_PER_RUN', '12'))
MIN_FRAMES = int(os.environ.get('MIN_FRAMES', '40'))
WIN = float(os.environ.get('WIN', '0.80'))


def frame_curve(freqs, mags, grid):
    f = np.asarray(freqs, dtype=float)
    m = np.asarray(mags, dtype=float)
    ok = (f > 20) & np.isfinite(m)
    if ok.sum() < 6:
        return None
    db = np.interp(np.log(grid), np.log(f[ok]), m[ok])
    return np.clip(db - db.max(), vp.HARM_DB_FLOOR, 0.0)


def main():
    bank, pack_in, pack_out = sys.argv[1], sys.argv[2], sys.argv[3]
    ddi = glob.glob(os.path.join(bank, '*.ddi'))[0]
    ddb = glob.glob(os.path.join(bank, '*.ddb'))[0]
    ddb_size = os.path.getsize(ddb)
    symbols, _, _, _ = vp.read_phdc(ddi)
    refs = [(s, vp.REFERENCE_FORMANTS[s]) for s in symbols if vp.REFERENCE_FORMANTS.get(s) and vp.REFERENCE_FORMANTS[s][0] > 0]
    with zipfile.ZipFile(pack_in) as z:
        voice = json.loads(z.read('voice.json').decode('utf-8'))
        lic = z.read('LICENSE.txt')
    grid = np.asarray(voice['harmonics']['freqs'], dtype=float)
    original = voice['harmonics']['phonemes']
    rs = runs(ddi, ddb_size)
    curves_by_sym, f0_by_sym = {}, {}
    frames_used = frames_dropped = 0
    with io.open(ddb, 'rb') as f:
        for r in rs:
            step = max(1, r['len'] // SAMPLE_PER_RUN)
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
                f1, f2, _ = formants(grid, c)
                if f1 <= 0 or f2 <= 0:
                    frames_dropped += 1
                    continue
                scored = sorted(((abs(np.log(f1 / ref[0])) + abs(np.log(f2 / ref[1])), s) for s, ref in refs))
                if scored[0][0] > WIN * scored[1][0]:
                    frames_dropped += 1
                    continue
                sym = scored[0][1]
                curves_by_sym.setdefault(sym, []).append(c)
                f0_by_sym.setdefault(sym, []).append(f0)
                frames_used += 1
    print('frames assigned: %d, dropped (ambiguous): %d, symbols with frames: %d'
          % (frames_used, frames_dropped, len(curves_by_sym)))
    phonemes, replaced, kept = {}, [], []
    for sym in original:
        cs = curves_by_sym.get(sym)
        if cs and len(cs) >= MIN_FRAMES:
            bands = vp.pitch_bands(np.asarray(cs), np.asarray(f0_by_sym[sym]))
            if bands:
                phonemes[sym] = bands
                replaced.append(sym)
                continue
        phonemes[sym] = original[sym]
        kept.append(sym)
    print('per-frame curves: %d replaced, %d kept' % (len(replaced), len(kept)))
    print('  replaced: %s' % ' '.join(sorted(replaced)))
    print('  kept    : %s' % ' '.join(sorted(kept)))
    assert len(phonemes) == len(original)
    voice['harmonics']['phonemes'] = phonemes
    # the rebuilt bands carry mean curves only: a frame sequence is the consecutive frames of one
    # recorded take, and frames gathered per phoneme from different units cannot be made into one.
    # The app falls back to the mean curve where seqN < 2, so this is graceful, but the sequence
    # engine has no trajectory for the rebuilt symbols.
    bands_total = sum(len(v) for v in phonemes.values())
    if 'stats' in voice:
        voice['stats']['harmonicBands'] = int(bands_total)
        voice['stats']['harmonicPhonemes'] = int(sum(1 for v in phonemes.values() if v))
    print('stats updated: harmonicBands=%d' % bands_total)
    voice['harmonics']['source'] = (voice['harmonics']['source']
        + ' | harmonics rebuilt per frame (%d frames) by formant match' % frames_used)
    with zipfile.ZipFile(pack_out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('voice.json', json.dumps(voice, separators=(',', ':'), ensure_ascii=False).encode('utf-8'))
        z.writestr('LICENSE.txt', lic)
    print('wrote %s (%.1f KB)' % (pack_out, os.path.getsize(pack_out) / 1024.0))


if __name__ == '__main__':
    main()