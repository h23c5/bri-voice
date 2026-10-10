# -*- coding: utf-8 -*-
"""
Add REAL harmonic voice data to an existing .v4voice pack.

tools/voicebank_pack.py measures formant peaks and a global shelf from the user's own VOCALOID4
voicebank.  That is enough to steer a formant synthesizer, but it throws away the thing that
actually makes a voicebank sound like itself: the full harmonic magnitude spectrum of every frame.

This companion tool re-reads the same FRM2 frames, clusters them exactly like the main tool, matches
clusters to the CHN phoneme symbols through the pack's own per-symbol formants, and writes - per
phoneme and per pitch band - the average harmonic envelope (64 log-spaced points, 0.5 dB steps).

The result is injected into the .v4voice as

    "harmonics": { "freqs":[...], "phonemes": { sym: [ {"f0":.., "db":[...]}, ... ] }, ... }

which the app turns into an additive harmonic source (see synth/HarmonicVoice.java): the note's
timbre then comes from the real voicebank spectrum instead of a formant model.

Only parametric measurements are written - no samples, no raw frames.
"""
import argparse
import json
import os
import shutil
import sys
import tempfile
import time
import zipfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voicebank_pack as vbp

GRID_POINTS = 64
GRID_LO, GRID_HI = 60.0, 16000.0
MATCH_OCT = 0.45          # same threshold the main tool uses
MIN_BAND_FRAMES = 40
MAX_BANDS = 3
TOOL_TAG = "tools/harmonic_pack.py v1"


def log(msg=""):
    print(msg, flush=True)


def load_pack(path):
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if "voice.json" not in names:
            raise SystemExit("%s has no voice.json" % path)
        model = json.loads(z.read("voice.json").decode("utf-8"))
        lic = z.read("LICENSE.txt") if "LICENSE.txt" in names else b""
    return model, lic, names


def save_pack(path, model, lic, extra_names):
    tmp = path + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        zi = zipfile.ZipInfo("voice.json", date_time=(1980, 1, 1, 0, 0, 0))
        z.writestr(zi, json.dumps(model, ensure_ascii=False, indent=1, sort_keys=True).encode("utf-8"))
        if lic:
            z.writestr(zipfile.ZipInfo("LICENSE.txt", date_time=(1980, 1, 1, 0, 0, 0)), lic)
        for n in extra_names:
            if n in ("voice.json", "LICENSE.txt"):
                continue
            with zipfile.ZipFile(path) as src:
                z.writestr(n, src.read(n))
    os.replace(tmp, path)


def read_frames(ddi, ddb, budget_frames, stride):
    """Decode up to budget_frames FRM2 records through the .ddi directory (falls back to a scan)."""
    size = os.path.getsize(ddb)
    res = vbp.ddi_directory_offsets(ddi, size)
    offs = res[0] if isinstance(res, (tuple, list)) else res
    offs = np.asarray(offs, dtype=np.int64).ravel()
    offs = offs[(offs > 0) & (offs < size - vbp.REC_BYTES)]
    if offs.size < 1000:      # directory unusable -> bounded linear scan, like the main tool
        log("      [measured] directory unusable (%d), falling back to a FRM2 scan" % offs.size)
        offs = np.asarray(vbp.scan_frm2_offsets(ddb, size, 96 * 1024 * 1024), dtype=np.int64)
    log("      [measured] .ddi directory: %d usable offsets" % offs.size)
    if offs.size > budget_frames:
        idx = np.linspace(0, offs.size - 1, budget_frames).astype(np.int64)
        pick = offs[idx]
    else:
        pick = offs
    envs, f0s, kmin, kmax, rej = [], [], 10 ** 9, 0, 0
    t0 = time.time()
    with open(ddb, "rb") as fh:
        for o in pick:
            fh.seek(int(o))
            buf = fh.read(vbp.REC_BYTES)
            if len(buf) < vbp.REC_BYTES or not buf.startswith(vbp.FRM2_MAGIC):
                rej += 1
                continue
            dec = vbp.decode_frame(buf)
            if dec is None:
                rej += 1
                continue
            f0, freqs, mags, _err = dec
            envs.append(vbp.frame_envelope(freqs, mags))
            f0s.append(f0)
            kmin = min(kmin, len(freqs)); kmax = max(kmax, len(freqs))
    log("      [measured] decoded %d frames (%d rejected) in %.1fs; K=%d..%d"
        % (len(envs), rej, time.time() - t0, kmin, kmax))
    return np.asarray(envs), np.asarray(f0s)


def match_cluster(formants, ref):
    """nearest reference symbol in log-frequency space"""
    best, bd = None, 1e9
    lf = np.log(np.asarray(formants, dtype=np.float64))
    for sym, rf in ref.items():
        try:
            lr = np.log(np.asarray(rf, dtype=np.float64))
        except Exception:
            continue
        if lr.shape != lf.shape:
            continue
        # mean absolute deviation in octaves (the main tool matches on the overall shape,
        # not on the worst single formant, so a single off formant must not reject a cluster)
        d = float(np.mean(np.abs(lf - lr)) / np.log(2.0))
        if d < bd:
            bd, best = d, sym
    return (best, bd) if bd <= MATCH_OCT else (None, bd)


def add_harmonics(pack_path, bank_path, frames_budget, clusters, points, verify_only=False):
    model, lic, names = load_pack(pack_path)
    ref = model.get("formants") or {}
    if len(ref) < 8:
        raise SystemExit("%s has no usable formants section - run voicebank_pack.py first" % pack_path)
    if verify_only:
        h = model.get("harmonics")
        if not h:
            log("[verify] %s: no harmonics section" % os.path.basename(pack_path)); return 1
        n_sym = len(h.get("phonemes", {}))
        n_bands = sum(len(v) for v in h.get("phonemes", {}).values())
        log("[verify] %s: %d freqs, %d phonemes, %d bands, %.1f KB"
            % (os.path.basename(pack_path), len(h.get("freqs", [])), n_sym, n_bands,
               os.path.getsize(pack_path) / 1024.0))
        return 0

    rb = vbp.resolve_bank(bank_path)
    vvd, ddi, ddb = (rb["vvd"], rb["ddi"], rb["ddb"]) if isinstance(rb, dict) else (rb[0], rb[1], rb[2])
    log("[bank] %s" % os.path.basename(vvd))
    E, F0 = read_frames(ddi, ddb, frames_budget, 1)
    if E.shape[0] < 500:
        raise SystemExit("too few frames")

    smooth = vbp.moving_average(E.mean(axis=0), 5)
    ref = vbp.REFERENCE_FORMANTS

    # Assign EVERY frame to the phoneme it resembles most (per-frame, not per-cluster: the
    # cluster centroids collapse onto a handful of distinct triples, which would leave most
    # phonemes without any measured spectrum at all).
    t0 = time.time()
    sym_of = np.full(E.shape[0], -1, dtype=np.int32)
    dist_of = np.full(E.shape[0], 9.9, dtype=np.float64)
    sym_names = list(ref.keys())
    ref_arr = np.asarray([ref[s] for s in sym_names], dtype=np.float64)
    lref = np.log(ref_arr)
    assigned = 0
    for i in range(E.shape[0]):
        p = vbp.pick_formants(E[i], smooth, float(F0[i]))
        if p is None:
            continue
        lp = np.log(np.asarray(p, dtype=np.float64))
        d = np.mean(np.abs(lref - lp[None, :]), axis=1) / np.log(2.0)
        j = int(np.argmin(d))
        if d[j] <= MATCH_OCT:
            sym_of[i] = j
            dist_of[i] = d[j]
            assigned += 1
    log("[match] %d/%d frames assigned to a phoneme in %.1fs; distance median %.3f, p90 %.3f oct"
        % (assigned, E.shape[0], time.time() - t0,
           float(np.median(dist_of[sym_of >= 0])) if assigned else 0.0,
           float(np.percentile(dist_of[sym_of >= 0], 90)) if assigned else 0.0))

    grid = np.geomspace(GRID_LO, GRID_HI, points)
    phonemes, bands_total = {}, 0
    for j, sym in enumerate(sym_names):
        idx = np.nonzero(sym_of == j)[0]
        if idx.size < MIN_BAND_FRAMES:
            continue
        f0s = F0[idx]
        order = np.argsort(f0s)
        idx = idx[order]; f0s = f0s[order]
        n_bands = min(MAX_BANDS, max(1, idx.size // MIN_BAND_FRAMES))
        bands = []
        for b in range(n_bands):
            a = int(round(b * idx.size / n_bands))
            e = int(round((b + 1) * idx.size / n_bands))
            if e - a < MIN_BAND_FRAMES:
                continue
            sel = idx[a:e]
            # average in the LINEAR domain: averaging dB would be wrong
            lin = np.power(10.0, E[sel] / 20.0)
            env_db = 20.0 * np.log10(np.maximum(lin.mean(axis=0), 1e-12))
            env_db = np.interp(grid, vbp.ENV_F, env_db)
            env_db = env_db - env_db.max()
            env_db = np.round(env_db * 2.0) / 2.0
            bands.append({"f0": round(float(np.median(F0[sel])), 1),
                          "db": [float(v) for v in env_db]})
        if bands:
            phonemes[sym] = bands
            bands_total += len(bands)
    if not phonemes:
        raise SystemExit("no harmonic bands could be measured")

    model["harmonics"] = {
        "freqs": [round(float(f), 1) for f in grid],
        "source": "VOCALOID4 FRM2 frames of %s (measured harmonic magnitudes, %s)"
                  % (model.get("source", {}).get("bank", "voicebank"), TOOL_TAG),
        "phonemes": phonemes,
    }
    st = model.setdefault("stats", {})
    st["harmonicFrames"] = int(E.shape[0])
    st["harmonicPhonemes"] = len(phonemes)
    st["harmonicBands"] = bands_total
    st["harmonicGridPoints"] = points
    save_pack(pack_path, model, lic, names)
    log("[harmonics] %d phonemes, %d bands, grid %d points -> %.1f KB"
        % (len(phonemes), bands_total, points, os.path.getsize(pack_path) / 1024.0))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="Add measured harmonic spectra to a .v4voice pack.")
    ap.add_argument("--pack", required=True, help="a .v4voice produced by voicebank_pack.py")
    ap.add_argument("--bank", help="the voicebank directory it was made from")
    ap.add_argument("--frames", type=int, default=30000)
    ap.add_argument("--clusters", type=int, default=40)
    ap.add_argument("--points", type=int, default=GRID_POINTS)
    ap.add_argument("--verify", action="store_true")
    a = ap.parse_args(argv)
    if not a.verify and not a.bank:
        ap.error("--bank is required unless --verify is used")
    return add_harmonics(a.pack, a.bank, a.frames, a.clusters, a.points, a.verify)


if __name__ == "__main__":
    sys.exit(main())
