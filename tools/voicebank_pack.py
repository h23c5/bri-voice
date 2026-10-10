#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tools/voicebank_pack.py -- turn a *locally installed, user-owned* VOCALOID4 Chinese
voicebank into our small singer-pack format (.v4voice, see docs/voicepack-format.md).

WHAT IT DOES (all of it derived from the user's own licensed voicebank; nothing is copied)
--------------------------------------------------------------------------------------------
  1. reads <bank>.vvd              -> VOICEIDSTR / VOICENAME (id, name, compID)
  2. reads <bank>.ddi              -> the PHDC 62-symbol CHN phoneme table and the
                                      64-bit offset directory that points into the .ddb
  3. samples FRM2 records in <bank>.ddb (bounded seeks, ~2.8 KB per record) and decodes,
     for every sampled frame:  f0 (harmonic spacing) + harmonic magnitudes in dB
  4. turns each frame into a 96-point log-frequency envelope (60 Hz .. 16 kHz), frame mean
     removed and with a fixed glottal-source tilt added back -> vocal-tract estimate
  5. writes a ZIP with voice.json (+ LICENSE.txt) containing
        shelf     -- the voice's average spectral shape (7 log-spaced EQ points)
        formants  -- CHN phonemes -> [F1,F2,F3] measured from k-means cluster centroids
        voice     -- formantScale / tiltBias / breathBias / ... from the same measurements
        stats     -- frames sampled, f0 min/median/max, clusters, matched phonemes
        harmonics -- v2 section: shared log grid + per-phoneme pitch bands of the MEASURED
                     FRM2 harmonic magnitudes (see docs/voicepack-format.md "v2 增补")

EVIDENCE DISCIPLINE
-------------------
Every number printed with the tag [measured] was read out of the files during this run.
Numbers tagged [inferred] come from a documented rule that maps those measurements onto
our own synthesizer's parameters (the mappings are spelled out in the comments below and
repeated in the pack's "notes" field).  Nothing is invented: a phoneme that cannot be
matched to a measured cluster within MATCH_MAX_OCT is omitted and the app falls back to
its built-in table.

MEASURED CONTAINER FACTS USED HERE (see docs/voicebank-notes.md sections 3-4)
  * .vvd/.ved: byte >= 0x21 XOR 0x1A, 0x0D/0x0A/0x20 kept as-is.
  * .ddi: [4B magic][u32 size][payload]; first block PHDC (u32 4, u32 62, 62 x 31-byte records).
  * .ddi also carries 64-bit LE absolute .ddb offsets in constant-stride tables; every
    entry we sampled lands on an FRM2 header.  We use those tables as the frame index.
  * .ddb: flat [4B tag][u32 size][payload] stream.
  * FRM2 = [32-byte header][350-float row: harmonic frequencies k*f0][350-float row:
    harmonic magnitudes in negative dB (10000.0 = unused slot)][further rows...].
    The rows are fixed capacity (350 floats, the u32 at header 0x1C); only the first K
    entries are live, K = number of harmonics up to ~22.05 kHz.  Measured on both banks;
    the tool re-checks the row offset on every record it decodes.
  * header 0x0C (f64) = frame onset time in seconds inside its group; consecutive
    records differ by 256/44100 s -> one FRM2 record = one spectral frame.

USAGE
  python tools/voicebank_pack.py --bank <dir or .ddi> --out <dir> [--frames 40000]
                                 [--clusters 16] [--harm-points 64] [--noise-records 800]
                                 [--snd-scan-mb 0] [--no-noise] [--verify]

WHY THE HARMONICS SECTION IS THE REAL TIMBRE (v2, docs/voicepack-format.md "v2 增补")
  The 350-float magnitude row of an FRM2 record IS the voicebank's own harmonic spectrum for
  that frame -- we do not model it.  Each sampled frame's (frequency, dB) pairs are
  interpolated onto one shared log grid (60 Hz..16 kHz, written verbatim into "freqs"), the
  frame is normalised to its own maximum (0 dB) so only the spectral SHAPE survives, and the
  frames of a phoneme's cluster are split into f0 tertiles (low/mid/high, >= 40 frames each)
  and averaged IN THE LINEAR AMPLITUDE DOMAIN (mean of 10^(dB/20)) because averaging dB is
  wrong.  Everything is quantised to 0.5 dB.  The app picks the band nearest the note f0 and
  drives its sine harmonics with the measured shape.  Frames with < 8 harmonics or an f0
  outside 80..1200 Hz are not used; a phoneme with too few measured frames is omitted
  entirely rather than invented.

THE NOISE SECTION (v2) -- measured, omitted when it does not hold up
  SND records (18-byte header + int16 PCM, 44.1 kHz mono) are found by a validated header
  signature, sampled, Welch-analysed on the same log grid, k-means clustered and compared
  with the CHN fricative signatures (s ~5-8 kHz, "s\\" / x ~3-5 kHz, "s`" ~2.5-3.5 kHz, f broadband,
  h ~1-3 kHz, Asp broadband + low level).  A class is written only when its measured
  centroid, bandwidth, flatness and level really match; otherwise the key is omitted and the
  measured counts go into "stats"/"notes".
"""

from __future__ import annotations

import argparse
import base64
import collections
import glob
import importlib.util
import json
import os
import struct
import sys
import time
import zipfile
from datetime import datetime

import numpy as np

TOOL_TAG = "tools/voicebank_pack.py v2"
FORMAT_TAG = "v4voice/1"

# --------------------------------------------------------------------------------------
# measured container constants
# --------------------------------------------------------------------------------------
FRM2_MAGIC = b"FRM2"
FRM2_HEADER = 32           # [measured] 4B magic + u32 size + u32 version + f64 time + 4 x u32
FRM2_ROW = 350             # [measured] fixed row capacity of the harmonic tables (header u32 @0x1C)
FRM2_MAG_ROW = 350         # [measured] float index of the first harmonic magnitude (dB)
MAG_FILLER = 10000.0       # [measured] unused magnitude slots are exactly 10000.0
HARMONIC_MAX_HZ = 22050.0  # [measured] the highest harmonic of every decoded frame stays below this

# envelope
ENV_POINTS = 96
ENV_F_LO, ENV_F_HI = 60.0, 16000.0
TILT_HZ = 400.0            # [inferred] glottal source tilt modelled as -12 dB/oct above 400 Hz
TILT_DB_PER_OCT = 12.0

# shelf
SHELF_POINTS = 7
SHELF_MIN_DB, SHELF_MAX_DB = -24.0, 6.0   # safety clamp so the exported EQ stays sane

# formant peak picking
FORMANT_BANDS = ((200.0, 1100.0), (800.0, 3000.0), (2300.0, 4000.0))
BAND_GATE_DB = -25.0       # ignore a band whose peak is below this, relative to the centroid max
F2_OVER_F1 = 1.12          # [inferred] keep F1/F2 apart: closer than ~0.17 oct they are one peak
F3_OVER_F2 = 1.03
MATCH_MAX_OCT = 0.45
# how much worse than its own nearest cluster a free cluster may be (1.0 = no slack)
MATCH_FREE_SLACK = 1.12       # [inferred] accept a phoneme only if its cluster is closer than this (RMS, octaves)

# frame acceptance
F0_MIN_HZ, F0_MAX_HZ = 50.0, 1500.0
MIN_HARMONICS = 10
# [measured] how far the harmonic-frequency row may sit from an exact k*f0 grid
# (max over k of |f_k - k*f0| / f_k).  MIKU_V4_Chinese is exact (5e-8 on every frame);
# Luotianyi_CHN_Meng is not (median 1.1e-2, p90 2.8e-2, p99 6.1e-2, max 0.22), so the
# gate is set to 0.08, which keeps 99.5% of its frames.  The envelope is interpolated on
# the ACTUAL harmonic frequencies, so a few percent of grid error is harmless.
SPACING_MAX_REL = 0.08

# --------------------------------------------------------------------------------------
# v2: "harmonics" section (docs/voicepack-format.md "v2 增补")
# --------------------------------------------------------------------------------------
HARM_POINTS = 64                 # shared log-spaced grid; the exact values go into "freqs"
HARM_F_LO, HARM_F_HI = 60.0, 16000.0
HARM_DB_STEP = 0.5               # exported dB quantisation (required: 0.5 dB)
HARM_DB_FLOOR = -96.0            # inaudible floor: keeps the JSON (and the pack) small
HARM_MIN_HARMONICS = 8           # [required] usable frame: >= 8 harmonics ...
HARM_F0_MIN, HARM_F0_MAX = 80.0, 1200.0   # ... and an f0 inside this window
HARM_BAND_MIN_FRAMES = 40        # [required] minimum measured frames per pitch band
HARM_MAX_BANDS = 3               # low / mid / high f0 tertiles

# --------------------------------------------------------------------------------------
# v2: "noise" section -- SND records
# --------------------------------------------------------------------------------------
SND_MAGIC = b"SND "
SND_RATE = 44100
NOISE_SEG, NOISE_HOP = 2048, 1024   # [inferred] Welch segment/hop for the PCM records
NOISE_CLASSES = 8                   # classes the SND spectra are clustered into
NOISE_MIN_CLASS_MEMBERS = 5         # a named class must rest on this many measured records
NOISE_FLAT_MIN = 0.02               # geometric/arithmetic PSD mean: noise-like gate
NOISE_SMOOTH = 3                    # mild smoothing of the interpolated curve (estimator variance)
# [inferred] the CHN fricative signatures the classes are compared against; a class must sit
# inside the centroid window, be noise-like (flatness) and -- for the broadband ones -- have
# a bandwidth that is a large fraction of its centroid.  Asp additionally has to be quiet
# relative to the other SND records of the bank.
NOISE_TARGETS = (
    ("s",    4500.0, 9000.0, False),
    ("s\\",  2800.0, 5500.0, False),
    ("x",    2800.0, 5500.0, False),
    ("s`",    2200.0, 3800.0, False),
    ("f",    1500.0, 6500.0, True),
    ("h",     900.0, 3000.0, False),
    ("Asp",   500.0, 6000.0, True),
)
NOISE_BROADBAND = 0.5               # bandwidth / centroid for a "broadband" class
NOISE_ASP_MARGIN_DB = 6.0           # Asp must be this much quieter than the bank median

PUBLIC_LICENSE = """{name} ({id})
Derived singer pack for V4Tune -- NOT an official voicebank release.

This pack was generated by the owner of the voicebank from their own local, licensed
installation:

    voicebank           : {bank}
    compID (VOICEIDSTR) : {compid}
    source file         : {ddb}
    derived at          : {when}
    tool                : {tool}

WHAT THIS FILE CONTAINS
  Only derived parametric measurements, a few kilobytes in total: a small average timbre
  curve, per-phoneme formant triples in Hz, per-phoneme averaged harmonic magnitude curves
  (spectral SHAPES only -- each frame normalised to its own peak, averaged per phoneme and
  pitch band, quantised to 0.5 dB) and a handful of scalar voice parameters.
  It contains NO audio samples, NO PCM and NO raw FRM2 records or frames: the harmonic
  curves are averaged, peak-normalised measurements, not resamplable spectrum data.  It
  carries no product key beyond the bank identifier used to label the pack.

TERMS
  This pack is for the personal use of the voicebank owner on their own devices.
  It must not be redistributed, published, sold or bundled with any product, because
  it is derived from a commercial, licensed voicebank.  Delete it together with the
  voicebank.  V4Tune is not affiliated with, endorsed by or licensed by YAMAHA
  Corporation or by the voice provider; all voicebank names and identifiers remain
  the property of their respective owners.
"""

# --------------------------------------------------------------------------------------
# The app's built-in neutral formant table, transcribed from
#   app/src/com/dsh/v4tune/phoneme/PhonemeTable.java  (the 62 proven CHN symbols).
# It is used ONLY as the reference for matching a measured cluster to a phoneme.
# "Sil" has no formants (it is silence) and is therefore never matched.
# --------------------------------------------------------------------------------------
REFERENCE_FORMANTS = {
    "a": [850, 1200, 2500], "o": [500, 800, 2500], "7": [400, 1100, 2400],
    "aI": [690, 1530, 2650], "ei": [410, 2090, 2720], "@`": [500, 1400, 1700],
    "AU": [700, 1080, 2470], "@U": [460, 1220, 2400],
    "a_n": [850, 1200, 2500], "@_n": [500, 1400, 2400], "AN": [800, 1100, 2400],
    "@N": [400, 1000, 2300],
    "i\\": [350, 1600, 2500], "i`": [400, 1500, 1800], "i": [300, 2300, 3000],
    "ia": [470, 1970, 2850], "iE_r": [400, 1800, 1700], "iAU": [475, 1670, 2730],
    "i@U": [370, 1730, 2700], "iE_n": [380, 2180, 2880], "i_n": [300, 2300, 3000],
    "iAN": [450, 1940, 2820], "iN": [300, 2100, 2800], "iUN": [330, 1850, 2820],
    "u": [350, 800, 2400], "ua": [500, 920, 2430], "uo": [400, 800, 2430],
    "uaI": [490, 1220, 2550], "uei": [370, 1460, 2580], "ua_n": [500, 920, 2430],
    "u@_n": [400, 980, 2400], "uAN": [485, 890, 2400], "UN": [400, 800, 2400],
    "u@N": [370, 860, 2370],
    "y": [300, 1900, 2300], "yE_r": [350, 1750, 1650], "y{_n": [390, 1840, 2360],
    "y_n": [300, 1900, 2300],
    "n": [250, 1700, 2600], "m": [250, 1100, 2200], "z`": [400, 1400, 1700],
    "l": [350, 1000, 2600],
    "t": [250, 1700, 2600], "p": [250, 800, 2200], "k": [250, 1900, 2200],
    "k_h": [250, 1900, 2200], "p_h": [250, 800, 2200], "t_h": [250, 1700, 2600],
    "s\\": [250, 2200, 3000], "f": [250, 1200, 2200], "x": [250, 1200, 2200],
    "s": [250, 1700, 2600], "s`": [250, 1500, 2000],
    "ts\\_h": [250, 2200, 3000], "ts\\": [250, 2200, 3000],
    "ts`_h": [250, 1500, 2000], "ts_h": [250, 1700, 2600],
    "ts`": [250, 1500, 2000], "ts": [250, 1700, 2600],
    "?": [500, 1500, 2500], "Asp": [500, 1500, 2500],
}

ENV_F = np.geomspace(ENV_F_LO, ENV_F_HI, ENV_POINTS)          # log-spaced analysis grid
ENV_TILT = np.where(ENV_F > TILT_HZ,
                    TILT_DB_PER_OCT * np.log2(np.maximum(ENV_F, TILT_HZ) / TILT_HZ),
                    0.0)                                       # dB to ADD back to the measurement


def log(msg=""):
    print(msg, flush=True)


# ======================================================================================
# 1. .vvd -- XOR 0x1A text obfuscation
# ======================================================================================
def read_vvd(path):
    """
    [measured] bytes >= 0x21 are XOR 0x1A; 0x0D/0x0A/0x20 are stored in clear.  The two
    known limits of that rule do not touch the fields we use: a plaintext ':' is encoded
    as 0x20 too, so it decodes back as a space ("VOCALOID VIRTUAL VOICE" is really
    "VOCALOID:VIRTUAL:VOICE" -- docs/voicebank-notes.md section 2), and the key/value
    separator decodes as '='.  VOICEIDSTR and VOICENAME are plain ASCII and unaffected.
    """
    with open(path, "rb") as f:
        raw = f.read()
    txt = bytes((b ^ 0x1A) if b >= 0x21 else b for b in raw).decode("latin-1")
    fields, lines = {}, []
    for line in txt.replace("\r", "\n").split("\n"):
        line = line.strip()
        if not line:
            continue
        lines.append(line)
        if "=" in line:
            k, _, v = line.partition("=")
            fields[k.strip().strip('"').lower()] = v.strip().strip('"')
    return fields, lines


# ======================================================================================
# 2. .ddi -- PHDC phoneme table + the 64-bit .ddb offset directory
# ======================================================================================
def read_phdc(ddi_path):
    """PHDC = [4B magic][u32 size][u32 4][u32 count][count * 31B: 30B symbol + 1B flag]."""
    with open(ddi_path, "rb") as f:
        f.seek(0x1C)
        head = f.read(16)
        if head[:4] != b"PHDC":
            raise RuntimeError("PHDC block not found at 0x1C -- unexpected .ddi layout")
        size, ver, count = struct.unpack("<III", head[4:16])
        payload = f.read(count * 31)
    symbols, flags = [], []
    for i in range(count):
        rec = payload[i * 31:(i + 1) * 31]
        symbols.append(rec[:30].split(b"\x00")[0].decode("latin-1"))
        flags.append(rec[30])
    return symbols, flags, size, ver


def ddi_directory_offsets(ddi_path, ddb_size):
    """
    [measured] The .ddi stores 64-bit LE absolute .ddb file offsets in tables whose
    neighbouring entries share a constant stride equal to the FRM2 record size.  We
    collect every u64 that sits inside such a constant-stride run (all 8 alignments,
    strides 512 B .. 200 KB) and that is a plausible .ddb offset.  Each value is
    re-checked against the FRM2 magic while sampling; the caller verifies a sample
    up-front and falls back to a linear scan if the tables turn out to be unusable.
    """
    with open(ddi_path, "rb") as f:
        ddi = f.read()
    chunks = []
    n = len(ddi)
    for align in range(8):
        arr = np.frombuffer(ddi[align:n - ((n - align) % 8)], dtype="<u8").astype(np.int64)
        if arr.size < 12:
            continue
        ok = (arr[:-2] >= 0) & (arr[:-2] < ddb_size) & (arr[1:-1] < ddb_size) & (arr[2:] < ddb_size)
        d = np.diff(arr)
        const = (d[1:] == d[:-1]) & (d[1:] > 512) & (d[1:] < 200000) & ok
        idx = np.nonzero(const)[0]
        if idx.size:
            chunks.append(np.concatenate([arr[idx], arr[idx + 1], arr[idx + 2]]))
    if not chunks:
        return np.zeros(0, dtype=np.int64), 0
    uniq = np.unique(np.concatenate(chunks))
    return uniq, len(chunks)


def scan_frm2_offsets(ddb_path, ddb_size, budget_bytes):
    """
    Fallback index: linear scan for the b"FRM2" tag in a bounded number of windows
    spread over the .ddb (records are gap-free, so tag hits inside a window are real
    record starts).  Never reads more than budget_bytes.
    """
    windows = 8
    win = max(1, budget_bytes // windows)
    offsets = []
    with open(ddb_path, "rb") as f:
        for w in range(windows):
            start = int((ddb_size - win) * (w / max(1, windows - 1))) if windows > 1 else 0
            start = max(0, start)
            f.seek(start)
            buf = f.read(win)
            i = buf.find(b"FRM2")
            while i >= 0:
                if i + 8 <= len(buf):
                    size = struct.unpack_from("<I", buf, i + 4)[0]
                    if 1024 <= size <= (1 << 20):
                        offsets.append(start + i)
                i = buf.find(b"FRM2", i + 4)
    return np.unique(np.asarray(offsets, dtype=np.int64))


# ======================================================================================
# 3. FRM2 frames
# ======================================================================================
REC_BYTES = FRM2_HEADER + 4 * (FRM2_ROW + FRM2_MAG_ROW)   # bytes needed per record (2832)


def decode_frame(buf, min_harmonics=MIN_HARMONICS, f0_lo=F0_MIN_HZ, f0_hi=F0_MAX_HZ):
    """
    Decode one FRM2 record prefix.  Returns (f0, freqs, mags_db, spacing_err) or None.
    The acceptance gate is parameterised so that the v2 harmonics section can apply its own
    (looser) rule -- >= HARM_MIN_HARMONICS harmonics and an f0 inside HARM_F0_MIN..HARM_F0_MAX
    -- without changing the v1 behaviour of the formants path, which keeps these defaults.

    [measured] layout: 32-byte header; float[0..K) = harmonic frequencies k*f0 ascending;
    float[350..350+K) = the matching harmonic magnitudes in dB (10000.0 in unused slots).
    K is found by walking the ascending harmonic run (capped at the 350-float row).
    """
    if len(buf) < REC_BYTES or buf[:4] != FRM2_MAGIC:
        return None
    body = np.frombuffer(buf[FRM2_HEADER:REC_BYTES], dtype="<f4")
    freq_row = body[:FRM2_ROW]
    mag_row = body[FRM2_MAG_ROW:FRM2_MAG_ROW + FRM2_ROW]

    k = 0
    prev = 0.0
    for v in freq_row:
        v = float(v)
        if not (v > prev) or v > HARMONIC_MAX_HZ * 1.01:
            break
        prev = v
        k += 1
    if k < min_harmonics:
        return None
    freqs = freq_row[:k].astype(np.float64)
    f0 = float(freqs[0])
    if not (f0_lo <= f0 <= f0_hi):
        return None
    mags = mag_row[:k].astype(np.float64)
    if not np.all(np.isfinite(mags)) or np.any(np.abs(mags - MAG_FILLER) < 1e-3):
        return None
    if mags.max() < -200.0 or mags.min() < -200.0:
        return None
    # [measured] harmonic spacing consistency: max |f_k - k*f0| / f_k.  The row is
    # k*f0 for essentially every frame (measured median deviation ~1e-7); a few percent
    # of frames carry a mildly stretched series (up to ~3%), which is still a usable
    # frame because the envelope is interpolated on the ACTUAL frequencies.
    err = float(np.max(np.abs(freqs - f0 * np.arange(1, k + 1)) / freqs))
    if err > SPACING_MAX_REL:
        return None
    return f0, freqs, mags, err


def frame_envelope(freqs, mags):
    """
    [inferred] Build the vocal-tract estimate for one frame:
      interpolate (f_k, dB_k) onto the 96-point log grid (flat outside the measured
      range), subtract the frame mean so only the SHAPE survives, then add back a fixed
      glottal-source tilt of +12 dB/oct above 400 Hz, which removes the -12 dB/oct
      source roll-off and leaves (approximately) the vocal-tract transfer function.
    """
    env = np.interp(ENV_F, freqs, mags)
    env = env - env.mean()
    return env + ENV_TILT


# ======================================================================================
# 4. analysis helpers
# ======================================================================================
def moving_average(y, w):
    if w <= 1:
        return y
    k = np.ones(w) / w
    pad = w // 2
    return np.convolve(np.pad(y, (pad, pad), mode="edge"), k, mode="valid")[:len(y)]


def kmeans(X, k, iters=40, seed=20261006):
    """Plain Lloyd k-means with deterministic random seeding."""
    rng = np.random.default_rng(seed)
    C = X[rng.choice(len(X), k, replace=False)].copy()
    lab = np.zeros(len(X), dtype=np.int64)
    X2 = (X * X).sum(1)
    for _ in range(iters):
        C2 = (C * C).sum(1)
        d = X2[:, None] - 2.0 * (X @ C.T) + C2[None, :]
        new = d.argmin(1)
        if np.array_equal(new, lab):
            break
        lab = new
        for j in range(k):
            m = lab == j
            if m.any():
                C[j] = X[m].mean(0)
    return C, lab


def pick_formants(centroid, shelf, f0_med):
    """
    [inferred] F1/F2/F3 from one cluster centroid.
      * smooth the centroid (1/3-octave-ish) and subtract the global average envelope
        (= the shelf) so the shared broadband shape cannot hide the formants,
      * take the strongest point of that residual inside each search band
        (200-1100 / 800-3000 / 2300-4000 Hz; see FORMANT_BANDS),
      * the F1 band starts at 1.15 x the cluster's median f0 because below the first
        harmonic the envelope is flat-extrapolated and carries no measured data,
      * enforce F2 > 1.12 F1 and F3 > 1.03 F2 so one broad peak cannot fill two bands,
      * drop the whole cluster when a band peak sits more than BAND_GATE_DB below the
        centroid maximum (the app then keeps its built-in value for those phonemes).
    """
    s = moving_average(centroid, 5)
    res = moving_average(centroid - shelf, 5)
    cmax = float(s.max())
    lo0 = max(FORMANT_BANDS[0][0], 1.15 * f0_med)
    bands = [(lo0, FORMANT_BANDS[0][1]), FORMANT_BANDS[1], FORMANT_BANDS[2]]
    out = []
    for b, (lo, hi) in enumerate(bands):
        if b == 1:
            lo = max(lo, F2_OVER_F1 * out[0])
        if b == 2:
            lo = max(lo, F3_OVER_F2 * out[1])
        idx = np.nonzero((ENV_F >= lo) & (ENV_F <= hi))[0]
        if idx.size == 0:
            return None
        i = int(idx[np.argmax(res[idx])])
        if s[i] - cmax < BAND_GATE_DB:
            return None
        out.append(float(ENV_F[i]))
    return out


def match_phonemes(clusters, symbols, cluster_ids=None, sym_cluster=None):
    """
    [inferred] Nearest measured cluster for every CHN phoneme symbol, by RMS distance of
    log2(F1),log2(F2),log2(F3).  Symbols farther away than MATCH_MAX_OCT are omitted (the
    app then uses its built-in table); nothing is invented.  The return value is unchanged
    (formants, dists) so other tools that import this module keep working; when the caller
    passes a dict as sym_cluster it is filled with symbol -> k-means cluster index, which is
    what the v2 harmonics section needs to find "the frames assigned to that phoneme's cluster".
    """
    # A cluster serves only one symbol. The old nearest-cluster rule let several phonemes share a
    # cluster and therefore get byte-identical curves - measured: o = 7 = u and y = i` at 0.00 dB
    # apart, which no synthesis tuning can undo. Leftovers keep their nearest cluster.
    pairs = []
    for sym in symbols:
        ref = REFERENCE_FORMANTS.get(sym)
        if ref is None:
            continue
        t = np.log2(np.asarray(ref, dtype=np.float64))
        for ci, c in enumerate(clusters):
            dd = float(np.sqrt(np.mean((np.log2(np.asarray(c)) - t) ** 2)))
            pairs.append((dd, sym, ci))
    pairs.sort(key=lambda x: x[0])
    formants, dists, taken = {}, {}, set()
    for dd, sym, ci in pairs:
        if sym in formants or ci in taken or dd > MATCH_MAX_OCT:
            continue
        # Do not push a symbol onto a distant free cluster just to make it unique: that traded
        # identical curves for shifted formants. Accept the free cluster only when it is nearly
        # as good as the symbol's own nearest one.
        own = min(d0 for d0, s0, _ in pairs if s0 == sym)
        if dd > own * MATCH_FREE_SLACK:
            continue
        formants[sym] = [int(round(v)) for v in clusters[ci]]
        dists[sym] = dd
        taken.add(ci)
        if sym_cluster is not None and cluster_ids is not None and 0 <= ci < len(cluster_ids):
            sym_cluster[sym] = int(cluster_ids[ci])
    for dd, sym, ci in pairs:
        if sym in formants or dd > MATCH_MAX_OCT:
            continue
        formants[sym] = [int(round(v)) for v in clusters[ci]]
        dists[sym] = dd
        if sym_cluster is not None and cluster_ids is not None and 0 <= ci < len(cluster_ids):
            sym_cluster[sym] = int(cluster_ids[ci])
    return formants, dists



# ======================================================================================
# 4b. v2 -- harmonics: the bank's own FRM2 harmonic magnitudes on one shared log grid
# ======================================================================================
def harmonic_grid(n_points):
    """[required] The shared log-spaced frequency grid, written verbatim into "freqs"."""
    return np.geomspace(HARM_F_LO, HARM_F_HI, int(n_points))


def frame_harm_curve(freqs, mags, log_grid):
    """
    One frame's harmonic spectrum on the shared log grid.
    [required] interpolate (f_k, dB_k) LINEARLY IN LOG-FREQUENCY.  The actual frequency row is
    used, not k*f0: Luotianyi_CHN_Meng's row deviates from an exact harmonic series by ~1e-2
    (relative, median), MIKU_V4_Chinese's is exact -- interpolating the measured frequencies
    is correct for both.  The frame is then normalised to its own maximum so only the spectral
    SHAPE is kept.  Outside the measured harmonic range the curve stays flat (np.interp holds
    the edge values): nothing is extrapolated.
    """
    y = np.interp(log_grid, np.log(freqs), mags)
    return y - float(y.max())


def assign_clusters(X, C):
    """Nearest centroid (squared euclidean) for every row of X -- the same metric as kmeans()."""
    d = (X * X).sum(1)[:, None] - 2.0 * (X @ C.T) + (C * C).sum(1)[None, :]
    return d.argmin(1)


# Display names per UI language for the banks we know; anything else falls back to the folder name.
NAME_TABLE = {
    "Luotianyi_CHN_Meng": ("洛天依 V4 中文·萌", "Luo Tianyi V4 Chinese Meng", "洛天依 V4 中国語・萌"),
    "MIKU_V4_Chinese": ("初音未来 V4 中文", "Hatsune Miku V4 Chinese", "初音ミク V4 中国語"),
}


def voice_display_names(bank_name):
    """(zh, en, ja) display names for a voicebank folder name."""
    key = bank_name.strip()
    if key in NAME_TABLE:
        return NAME_TABLE[key]
    pretty = key.replace("_", " ").strip()
    return pretty, pretty, pretty


def band_curve(rows_db, f0_sel, dyn=None):
    """
    [required] One pitch band: average the member frames' normalised curves IN THE LINEAR
    AMPLITUDE DOMAIN (mean of 10^(dB/20)) and convert back to dB -- averaging the dB values
    themselves would be wrong.  The band's f0 is the MEDIAN f0 of its frames; the dB values
    are quantised to HARM_DB_STEP and clamped at HARM_DB_FLOOR.  The mean of several
    unit-peak curves peaks slightly below 0 dB where the frames disagree, so the result is
    re-normalised to exactly 0 dB before quantisation.
    """
    lin = np.power(10.0, np.asarray(rows_db, dtype=np.float64) / 20.0)
    m = lin.mean(axis=0)
    db = 20.0 * np.log10(np.maximum(m, 1e-9))
    db = db - db.max()
    db = np.round(db / HARM_DB_STEP) * HARM_DB_STEP
    db = np.clip(db, HARM_DB_FLOOR, 0.0)
    db[db == 0.0] = 0.0                                   # kill -0.0
    out = {"f0": round(float(np.median(f0_sel)), 1), "db": [float(v) for v in db]}
    if dyn is not None:
        out["dyn"] = round(float(dyn), 3)          # 0 = softest variant, 1 = loudest (see dyn_variants)
    return out


# how many loudness variants one pitch band is split into (soft / mid / loud)
HARM_DYN_VARIANTS = 3
# how many REAL consecutive frames of that cell are stored as a sequence (spectral evolution)
HARM_SEQ_FRAMES = 12
# dB per int8 step for the packed frame sequence (0.5 dB is inaudible and halves the payload)
SEQ_Q = 0.5


def frame_sequence(curves, f0a, part, max_frames=HARM_SEQ_FRAMES):
    """
    [measured] A run of REAL consecutive FRM2 frames from one cell.

    The single averaged curve per cell makes every note sound frozen: the real voicebank stores a
    frame every 256/44100 s and the spectrum moves across a note.  Here the cell's frames are put
    back into their original order (the decode order is the file order, and within one FRM2 group
    consecutive records are exactly 256/44100 s apart), the run whose f0 sits closest to the cell's
    median f0 is selected, and its curves are stored as a sequence.  Each curve keeps its own shape
    (normalised to 0 dB) and the frame's relative level is stored alongside, so the app can replay
    the measured swell as well as the timbre.
    """
    n = len(part)
    if n == 0:
        return None
    order = np.argsort(np.asarray(part), kind="stable")          # back to file order
    f0s = np.asarray(f0a, dtype=np.float64)[order]
    rows = np.asarray(curves, dtype=np.float64)[order]
    take = min(max_frames, n)
    if take < 3:
        return None
    # centre the window on the frame whose f0 is closest to the cell median
    med = float(np.median(f0s))
    c = int(np.argmin(np.abs(f0s - med)))
    a = max(0, min(n - take, c - take // 2))
    sel = rows[a:a + take]
    if sel.shape[0] < 3:
        return None
    lin = np.power(10.0, sel / 20.0)
    energy = lin.sum(axis=1)
    ref = float(np.median(energy))
    lvl = 20.0 * np.log10(np.maximum(energy, 1e-12) / max(ref, 1e-12))     # relative level, dB
    # int8 quantisation (SEQ_Q dB per step, 0 dB = the frame's own peak) packed into base64.
    # JSON text with one number per value made the phone's parser allocate a boxed Double per
    # sample: 441 bands x 12 frames x 64 points = ~340k objects per pack, which GC-thrashed the
    # app for tens of seconds.  64 signed bytes per frame decode straight into a double[].
    q = np.rint(np.clip(sel - sel.max(axis=1, keepdims=True), HARM_DB_FLOOR, 0.0) / SEQ_Q)
    q = np.clip(q, -128, 0).astype(np.int8)
    return {"seqB64": base64.b64encode(q.tobytes()).decode("ascii"),
            "seqN": int(q.shape[0]),
            "seqQ": SEQ_Q,
            "lvl": [round(float(v), 1) for v in np.clip(lvl, -24.0, 12.0)]}


def dyn_variants(curves, f0a, part):
    """
    [measured] Loudness variants of one pitch band.

    A real voicebank stores frames at many dynamics, and the spectrum really does change with
    effort (more energy in the upper harmonics when singing louder).  Averaging them into a
    single curve throws that away, so the band's frames are sorted by their total linear energy
    and split into up to HARM_DYN_VARIANTS parts, each averaged like a normal band and tagged
    with dyn = 0 / .. / 1.  The app picks the variant that matches the note's DYN parameter, so
    DYN now changes the timbre and not just the level.
    """
    n = len(part)
    if n < HARM_BAND_MIN_FRAMES * 2:
        return [band_curve(curves[part], f0a[part], 0.5)]
    lin = np.power(10.0, np.asarray(curves[part], dtype=np.float64) / 20.0)
    energy = lin.sum(axis=1)
    order = np.argsort(energy, kind="stable")
    nv = HARM_DYN_VARIANTS if n >= HARM_BAND_MIN_FRAMES * HARM_DYN_VARIANTS else 2
    out = []
    for v in range(nv):
        a = int(round(n * v / float(nv)))
        b = int(round(n * (v + 1) / float(nv)))
        if b - a < 1:
            continue
        sub = part[order[a:b]]
        band = band_curve(curves[sub], f0a[sub], 0.0 if nv == 1 else v / float(nv - 1))
        seqinfo = frame_sequence(curves, f0a, sub)
        if seqinfo:
            band.update(seqinfo)
            # The mean curve is only a fallback for readers without sequence support, and it cost
            # 28k JSON numbers per bank (45% of voice.json) - the phone spent seconds parsing it.
            # Keep it only when there is no sequence to fall back on.
            if seqinfo.get("seqN", 0) < 2:
                pass
            else:
                band.pop("db", None)
        out.append(band)
    return out


def pitch_bands(curves, f0s):
    """
    [required] Split one phoneme's frames into up to HARM_MAX_BANDS pitch bands by f0
    tertiles (low / mid / high), every band needing at least HARM_BAND_MIN_FRAMES frames.
    Falls back to 2 bands, then to 1, and returns [] when even a single band cannot be
    filled -- the caller then omits the phoneme instead of inventing data.
    """
    n = len(f0s)
    if n < HARM_BAND_MIN_FRAMES:
        return []
    f0a = np.asarray(f0s, dtype=np.float64)
    order = np.argsort(f0a, kind="stable")
    for nb in range(HARM_MAX_BANDS, 0, -1):
        if n < HARM_BAND_MIN_FRAMES * nb:
            continue
        edges = [int(round(n * i / float(nb))) for i in range(nb + 1)]
        parts = [order[edges[i]:edges[i + 1]] for i in range(nb)]
        if any(len(part) < HARM_BAND_MIN_FRAMES for part in parts):
            continue
        out = []
        for part in parts:
            out.extend(dyn_variants(curves, f0a, part))
        return out
    return []


# ======================================================================================
# 4c. v2 -- noise: "SND " PCM records of the .ddb, measured on the same log grid
# ======================================================================================
def scan_snd_offsets(ddb_path, ddb_size, budget_bytes, chunk_bytes=8 << 20):
    """
    [measured] Find the b"SND " PCM records of the .ddb.  The .ddb is a gap-free
    [4B tag][u32 total_len][payload] stream, so a byte-stream search is exact once every hit
    is validated against the 18-byte SND header -- total_len == 18 + 2*sample_count, 44100 Hz,
    1 channel, reserved 0 and a sane sample count.  Consecutive chunks overlap by 18 bytes so
    a header cannot be lost at a chunk boundary, and offsets are de-duplicated.
    Cross-checked against a full tag-by-tag traversal of the record stream: both methods find
    7749 records on Luotianyi_CHN_Meng and 5195 on MIKU_V4_Chinese, with identical offsets.
    Reading is bounded by budget_bytes (0 = the whole file).
    """
    limit = ddb_size if budget_bytes <= 0 else min(ddb_size, budget_bytes)
    offs, read = [], 0
    with open(ddb_path, "rb") as f:
        pos = 0
        while pos < limit:
            f.seek(pos)                       # the chunk start is explicit: the overlap below
            buf = f.read(min(chunk_bytes, limit - pos))
            if not buf:                       # means the file position is NOT the next base
                break
            read += len(buf)
            i = buf.find(SND_MAGIC)
            while i >= 0:
                if i + 18 <= len(buf):
                    tl, sr, ch, n, resv = struct.unpack_from("<IIHHH", buf, i + 4)
                    if tl == 18 + 2 * n and sr == SND_RATE and ch == 1 and resv == 0 and n >= 256:
                        offs.append(pos + i)
                i = buf.find(SND_MAGIC, i + 4)
            pos += max(1, len(buf) - 18)
    return np.unique(np.asarray(offs, dtype=np.int64)), read


def welch_psd(x):
    """[inferred] Welch PSD of one PCM record: Hann window, 50% overlap, power averaged."""
    n = x.size
    seg = NOISE_SEG
    if n < seg:
        seg = 1 << max(5, int(np.log2(max(1, n))))
    if n < seg:
        x = np.pad(x, (0, seg - n))
        n = seg
    hop = NOISE_HOP if seg >= NOISE_SEG else max(1, seg // 2)
    frames = np.lib.stride_tricks.sliding_window_view(x, seg)[::hop]
    w = np.hanning(seg)
    F = np.fft.rfft(frames * w, axis=1)
    P = (F.real ** 2 + F.imag ** 2).mean(axis=0)
    return np.fft.rfftfreq(seg, 1.0 / SND_RATE), P


def snd_analysis(pcm, log_grid, smooth=NOISE_SMOOTH):
    """
    One SND record -> (curve on the log grid, measured features) or (None, None).
    The curve: Welch PSD interpolated onto the shared log grid in POWER and in
    log-frequency, converted to dB, lightly smoothed and normalised to 0 dB (same shape
    convention as the harmonic bands).  The features are measured on the 60 Hz..16 kHz band
    of the native PSD and are what the naming decision uses:
      centroid  -- power-weighted mean frequency,
      bandwidth -- power-weighted standard deviation around it,
      flatness  -- geometric mean / arithmetic mean of the PSD (1 = white, ~0 = tonal),
      level     -- RMS of the record in dBFS (the only absolute number here).
    """
    x = np.asarray(pcm, dtype=np.float64) / 32768.0
    x = x - x.mean()
    rms = float(np.sqrt((x ** 2).mean())) if x.size else 0.0
    if not np.isfinite(rms) or rms < 1e-6:
        return None, None
    fr, P = welch_psd(x)
    band = (fr >= HARM_F_LO) & (fr <= HARM_F_HI)
    f2, P2 = fr[band], np.maximum(P[band], 1e-30)
    tot = float(P2.sum())
    if not np.isfinite(tot) or tot <= 0.0 or f2.size < 4:
        return None, None
    fc = float((f2 * P2).sum() / tot)
    bw = float(np.sqrt(((f2 - fc) ** 2 * P2).sum() / tot))
    flat = float(np.exp(np.log(P2).mean()) / P2.mean())
    curve = np.interp(log_grid, np.log(f2), 10.0 * np.log10(P2))
    if smooth > 1:
        curve = moving_average(curve, smooth)
    curve = curve - float(curve.max())
    return curve, {"centroid": fc, "bandwidth": bw, "flatness": flat,
                   "level": 20.0 * np.log10(rms), "samples": int(x.size)}


def classify_noise_classes(classes):
    """
    [inferred] Compare every measured SND class with the CHN fricative signatures.  A class
    may be named only when ALL of these hold:
      * at least NOISE_MIN_CLASS_MEMBERS sampled records support it,
      * it is noise-like: median flatness >= NOISE_FLAT_MIN (voiced material sits near 1e-3),
      * its median spectral centroid lies inside the signature's window,
      * "broadband" signatures (f, Asp) also need bandwidth/centroid >= NOISE_BROADBAND,
      * Asp also needs to be >= NOISE_ASP_MARGIN_DB quieter than the bank's median SND level.
    The assignment is one-to-one and greedy, closest class to the window centre first.
    Classes that do not match are reported in the log but NOT written into the pack.
    """
    named, used, cand = {}, set(), []
    levels = [c["level"] for c in classes if np.isfinite(c["level"])]
    lvl_med = float(np.median(levels)) if levels else 0.0
    for j, c in enumerate(classes):
        if c["n"] < NOISE_MIN_CLASS_MEMBERS or c["flatness"] < NOISE_FLAT_MIN:
            continue
        ratio = c["bandwidth"] / max(1.0, c["centroid"])
        for sym, lo, hi, broad in NOISE_TARGETS:
            if not (lo <= c["centroid"] <= hi):
                continue
            if broad and ratio < NOISE_BROADBAND:
                continue
            if sym == "Asp" and c["level"] > lvl_med - NOISE_ASP_MARGIN_DB:
                continue
            cand.append((abs(np.log(c["centroid"] / np.sqrt(lo * hi))), j, sym))
    for _score, j, sym in sorted(cand):
        if j in used or sym in named:
            continue
        used.add(j)
        named[sym] = j
    return named, lvl_med


# ======================================================================================
# 5. pack assembly
# ======================================================================================
def slugify(text):
    out = []
    for ch in text:
        if ch.isalnum():
            out.append(ch.lower())
        elif out and out[-1] != "_":
            out.append("_")
    return "".join(out).strip("_")


def build_pack(bank, out_dir, n_frames, n_clusters, budget_mb,
               harm_points=HARM_POINTS, noise_records=800, snd_scan_mb=0):
    t_start = time.time()
    ddb_path = bank["ddb"]
    ddb_size = os.path.getsize(ddb_path)
    harm_grid = harmonic_grid(harm_points)      # v2 shared grid -> "harmonics.freqs"
    harm_log = np.log(harm_grid)

    # ---- .vvd -------------------------------------------------------------------
    vvd_fields, vvd_lines = read_vvd(bank["vvd"])
    comp_id = vvd_fields.get("voiceidstr", "")
    voice_name = vvd_fields.get("voicename", os.path.splitext(os.path.basename(bank["ddi"]))[0])
    log("[vvd] %s  (XOR 0x1A text, %d bytes)" % (os.path.basename(bank["vvd"]), os.path.getsize(bank["vvd"])))
    for line in vvd_lines:
        log("      %s" % line)
    log("      [measured] VOICEIDSTR=%s  VOICENAME=%s" % (comp_id, voice_name))

    # ---- PHDC -------------------------------------------------------------------
    symbols, flags, phdc_size, phdc_ver = read_phdc(bank["ddi"])
    log("[ddi] %s (%d bytes)  PHDC size=%d ver=%d count=%d"
        % (os.path.basename(bank["ddi"]), os.path.getsize(bank["ddi"]), phdc_size, phdc_ver, len(symbols)))
    log("      [measured] CHN phoneme table: %s" % " ".join(symbols))

    # ---- directory --------------------------------------------------------------
    t0 = time.time()
    cand, n_runs = ddi_directory_offsets(bank["ddi"], ddb_size)
    log("[ddb] %s (%.2f GB)" % (os.path.basename(ddb_path), ddb_size / 1e9))
    log("      [measured] .ddi 64-bit offset directory: %d constant-stride runs, %d distinct block offsets (%.1fs)"
        % (n_runs, cand.size, time.time() - t0))

    verified = 0
    probe = cand[np.linspace(0, cand.size - 1, min(200, cand.size)).astype(np.int64)] if cand.size else cand
    if probe.size:
        with open(ddb_path, "rb") as f:
            for off in probe:
                f.seek(int(off))
                if f.read(4) == FRM2_MAGIC:
                    verified += 1
    log("      [measured] %d/%d sampled directory entries land on an FRM2 header" % (verified, probe.size))

    if cand.size < 200 or verified < 0.9 * max(1, probe.size):
        log("      [inferred] directory unusable for indexing -> linear FRM2 scan fallback")
        cand = scan_frm2_offsets(ddb_path, ddb_size, budget_mb * 1024 * 1024)
        log("      [measured] fallback scan found %d FRM2 offsets" % cand.size)
        source_kind = "linear-scan"
    else:
        source_kind = "directory"

    if cand.size == 0:
        raise RuntimeError("no FRM2 block offsets found")

    # ---- frame sampling ---------------------------------------------------------
    pick = np.unique(np.linspace(0, cand.size - 1, min(n_frames, cand.size)).astype(np.int64))
    envs, f0s = [], []
    harm_curves, harm_f0s, harm_rows, harm_pos = [], [], [], []
    harm_extra_env, harm_extra_pos = [], []
    harm_extra = 0
    rej_magic = rej_shape = 0
    spacing_errs = []
    k_min, k_max = 10 ** 9, 0
    t0 = time.time()
    with open(ddb_path, "rb") as f:
        for j in pick:
            f.seek(int(cand[j]))
            buf = f.read(REC_BYTES)
            dec = decode_frame(buf)
            if dec is None:
                if not buf.startswith(FRM2_MAGIC):
                    rej_magic += 1
                else:
                    rej_shape += 1
                    # v2: the harmonic gate is LOOSER than the v1 decode gate, so a record the
                    # formants path drops can still be a usable harmonic frame.  It is used for
                    # the harmonics section only -- it never enters E/F0, so the formants
                    # section cannot regress.
                    dec2 = decode_frame(buf, HARM_MIN_HARMONICS, HARM_F0_MIN, HARM_F0_MAX)
                    if dec2 is not None:
                        f0h, freqsh, magsh, _eh = dec2
                        harm_extra_pos.append(len(harm_curves))
                        harm_curves.append(frame_harm_curve(freqsh, magsh, harm_log))
                        harm_f0s.append(f0h)
                        harm_extra_env.append(frame_envelope(freqsh, magsh))
                        harm_extra += 1
                continue
            f0, freqs, mags, err = dec
            spacing_errs.append(err)
            k_min = min(k_min, len(freqs))
            k_max = max(k_max, len(freqs))
            envs.append(frame_envelope(freqs, mags))
            f0s.append(f0)
            # v2 gate: a frame is usable for the harmonics section with >= HARM_MIN_HARMONICS
            # harmonics and an f0 inside HARM_F0_MIN..HARM_F0_MAX.  decode_frame above still
            # applies the unchanged v1 gate (>= MIN_HARMONICS, f0 50..1500) first, so the
            # formants path sees exactly the frames it saw before.
            if len(freqs) >= HARM_MIN_HARMONICS and HARM_F0_MIN <= f0 <= HARM_F0_MAX:
                harm_pos.append(len(harm_curves))
                harm_curves.append(frame_harm_curve(freqs, mags, harm_log))
                harm_f0s.append(f0)
                harm_rows.append(len(envs) - 1)
    dt = time.time() - t0
    if not envs:
        raise RuntimeError("no decodable FRM2 frames")
    E = np.asarray(envs, dtype=np.float64)
    F0 = np.asarray(f0s, dtype=np.float64)
    Hc = np.asarray(harm_curves, dtype=np.float64).reshape(len(harm_curves), harm_grid.size)
    Hf0 = np.asarray(harm_f0s, dtype=np.float64)
    Hrow = np.asarray(harm_rows, dtype=np.int64)
    log("      [measured] frame decode: %d frames from %d records in %.1fs (%.1f MB read); rejected %d not-FRM2, %d bad shape"
        % (E.shape[0], pick.size, dt, pick.size * REC_BYTES / 1e6, rej_magic, rej_shape))
    sp_med = float(np.median(spacing_errs)) if spacing_errs else 0.0
    sp_max = float(np.max(spacing_errs)) if spacing_errs else 0.0
    log("      [measured] harmonics per frame K=%d..%d; harmonic grid vs exact k*f0: median %.2e, max %.2e (relative); magnitude row read at float %d"
        % (k_min, k_max, sp_med, sp_max, FRM2_MAG_ROW))
    f0_min, f0_med, f0_max = float(F0.min()), float(np.median(F0)), float(F0.max())
    log("      [measured] f0: min %.1f  median %.1f  max %.1f Hz  (p05 %.1f, p95 %.1f)"
        % (f0_min, f0_med, f0_max, np.percentile(F0, 5), np.percentile(F0, 95)))

    # ---- global timbre / shelf ---------------------------------------------------
    smooth = moving_average(E.mean(axis=0), 5)
    shelf_f = np.geomspace(ENV_F_LO, ENV_F_HI, SHELF_POINTS)
    shelf_db = np.interp(shelf_f, ENV_F, smooth)
    shelf_db = np.clip(shelf_db - shelf_db.max(), SHELF_MIN_DB, SHELF_MAX_DB)
    shelf = [[int(round(float(hz))), round(float(db), 1)] for hz, db in zip(shelf_f, shelf_db)]
    log("[shelf] average envelope over %d frames, %d log-spaced points, max normalised to 0 dB:" % (E.shape[0], SHELF_POINTS))
    log("      " + "  ".join("%d Hz %+.1f dB" % (hz, db) for hz, db in shelf))
    hf_db = float(smooth[(ENV_F >= 4000) & (ENV_F < 10000)].mean() - smooth[(ENV_F >= 200) & (ENV_F < 800)].mean())
    tail_db = float(smooth[ENV_F >= 8000].mean() - smooth[(ENV_F >= 4000) & (ENV_F < 8000)].mean())
    log("      [measured] HF balance (4-10k vs 200-800 Hz): %+.1f dB ; HF tail (8-16k vs 4-8k): %+.1f dB" % (hf_db, tail_db))

    # ---- clusters ---------------------------------------------------------------
    rng = np.random.default_rng(20261006)
    k = int(min(n_clusters, max(2, E.shape[0] // 10)))
    sub = np.sort(rng.choice(E.shape[0], min(E.shape[0], max(2000, min(8000, E.shape[0]))), replace=False))
    Xs = E[sub]
    t0 = time.time()
    C, lab = kmeans(Xs, k)
    log("[clusters] k-means k=%d on %d sampled envelopes (%.1fs)" % (k, len(Xs), time.time() - t0))
    clusters, cluster_ids, info = [], [], []
    for j in range(k):
        m = lab == j
        f0m = float(np.median(F0[sub][m])) if m.any() else f0_med
        p = pick_formants(C[j], smooth, f0m)
        if p is not None:
            clusters.append(p)
            cluster_ids.append(j)
        info.append((j, int(m.sum()), f0m, p))
    for j, n, f0m, p in info:
        log("      cluster %2d  n=%4d  median f0=%6.1f Hz  ->  %s"
            % (j, n, f0m, "no usable F1/F2/F3" if p is None else "F1=%.0f F2=%.0f F3=%.0f Hz" % tuple(p)))
    log("      [measured] %d/%d cluster centroids yielded a usable F1/F2/F3 triple" % (len(clusters), k))

    # ---- phoneme matching --------------------------------------------------------
    sym_cluster = {}
    formants, dists = match_phonemes(clusters, symbols, cluster_ids, sym_cluster)
    matched = len(formants)
    log("[formants] %d of %d CHN symbols matched (nearest cluster, threshold %.2f oct RMS; Sil is silence and has no formants)"
        % (matched, len(symbols), MATCH_MAX_OCT))
    for sym in symbols:
        if sym in formants:
            log("      %-7s -> F1=%4d F2=%4d F3=%4d Hz   (distance %.3f oct)"
                % (sym, formants[sym][0], formants[sym][1], formants[sym][2], dists[sym]))
        else:
            log("      %-7s -> omitted (built-in table)" % sym)
    if dists:
        log("      [measured] match distance: min %.3f  median %.3f  max %.3f octaves"
            % (min(dists.values()), float(np.median(list(dists.values()))), max(dists.values())))

    # ---- v2: harmonics -- the measured FRM2 magnitude curves, per phoneme and pitch ------
    # Every harmonic frame is assigned to the nearest k-means centroid (same metric as the
    # clustering, so this is "the frames assigned to that phoneme's cluster"), then split by
    # f0 into up to 3 pitch bands.  Phonemes that share a cluster share its bands.
    # Cluster label of every harmonic frame, in the same order as harm_curves/Hf0.
    lab_h = np.zeros(Hc.shape[0], dtype=np.int64)
    if Hrow.size:
        lab_h[np.asarray(harm_pos, dtype=np.int64)] = assign_clusters(E[Hrow], C)
    if harm_extra_env:      # frames the v1 gate rejected but the v2 harmonics gate accepts
        lab_h[np.asarray(harm_extra_pos, dtype=np.int64)] = assign_clusters(np.asarray(harm_extra_env), C)
    bands_of_cluster, harm_phonemes = {}, {}
    bands_per_phoneme = collections.defaultdict(int)   # curves per phoneme (pitch bands x loudness variants)
    for sym in symbols:
        cid = sym_cluster.get(sym)
        if cid is None:
            continue
        if cid not in bands_of_cluster:
            sel = np.nonzero(lab_h == cid)[0] if lab_h.size else np.zeros(0, dtype=np.int64)
            bands_of_cluster[cid] = pitch_bands(Hc[sel], Hf0[sel]) if sel.size else []
        bands = bands_of_cluster[cid]
        if bands:
            harm_phonemes[sym] = bands
            bands_per_phoneme[len(bands)] += 1
    total_bands = sum(len(v) for v in harm_phonemes.values())
    log("[harmonics] v2: %d of %d decoded frames are usable (>= %d harmonics, f0 %.0f-%.0f Hz, %d recovered by the looser v2 gate); "
        "grid = %d log points %.0f Hz - %.0f kHz; %d of %d CHN symbols got %d curves (%d pitch bands x up to %d loudness variants)"
        % (Hc.shape[0], E.shape[0], HARM_MIN_HARMONICS, HARM_F0_MIN, HARM_F0_MAX, harm_extra,
           harm_grid.size, HARM_F_LO, HARM_F_HI / 1000.0,
           len(harm_phonemes), len(symbols), total_bands,
           bands_per_phoneme.get(3, 0), HARM_DYN_VARIANTS))
    for sym in symbols:
        if sym in harm_phonemes:
            log("      %-7s -> %d band(s): f0 %s Hz"
                % (sym, len(harm_phonemes[sym]), " ".join("%.1f" % b["f0"] for b in harm_phonemes[sym])))
        else:
            log("      %-7s -> omitted (needs >= %d usable frames in a band)"
                % (sym, HARM_BAND_MIN_FRAMES))
    if Hc.shape[0]:
        log("      [measured] usable-frame f0: min %.1f  median %.1f  max %.1f Hz"
            % (Hf0.min(), np.median(Hf0), Hf0.max()))

    # ---- voice parameters (all [inferred] mappings of the measurements above) ----
    # formantScale: median measured formant frequency divided by the median reference
    #   formant frequency of the same phonemes.  The reference table is our neutral (1.0)
    #   vocal tract -- the ~165 cm-speaker baseline the app is built around -- so a ratio
    #   above 1 means a shorter vocal tract and every formant moves up.
    ratios = []
    for sym, f in formants.items():
        ref = REFERENCE_FORMANTS[sym]
        ratios.extend([f[i] / ref[i] for i in range(3)])
    formant_scale = float(min(1.30, max(0.80, float(np.median(ratios)) if ratios else 1.0)))
    # tiltBias: HF balance; 1 dB of measured HF excess = 0.02 tiltBias, 0 dB = the
    #   -12 dB/oct source this analysis already compensates for.
    tilt_bias = float(min(0.20, max(-0.20, 0.02 * hf_db)))
    # breathBias: the very-high-frequency tail relative to the presence band -- a noise-like
    #   (breathy) voice keeps more energy there.  Anchor: tail = -3 dB -> 0.10, 1 dB = 0.03.
    breath_bias = float(min(0.40, max(0.0, 0.10 + 0.03 * (tail_db + 3.0))))
    voice = {
        "formantScale": round(formant_scale, 3),
        "f1Mul": 1.0, "f2Mul": 1.0, "f3Mul": 1.0,
        "tiltBias": round(tilt_bias, 3),
        "breathBias": round(breath_bias, 3),
        "oqBias": 0.0,
        "vibratoRate": 5.5,
        "vibratoDepthCents": 0.0,
        "gain": 1.0,
        "consonantGain": 1.0,
        "breathSoundAmount": round(min(0.6, 0.3 + 0.5 * breath_bias), 3),
        "description": "从本机已购声库 %s 的 FRM2 帧测量得到的音色参数（仅参数，不含任何采样）" % voice_name,
    }
    log("[voice] [inferred] formantScale=%.3f (median measured/reference formant ratio over %d symbols), tiltBias=%+.3f (HF balance %+.1f dB), breathBias=%.3f (HF tail %+.1f dB)"
        % (voice["formantScale"], len(formants), voice["tiltBias"], hf_db, voice["breathBias"], tail_db))


    # ---- v2: noise -- the "SND " PCM records --------------------------------------------
    # [measured] scan_snd_offsets() finds every SND record; a bounded sample of them is
    # Welch-analysed on the same log grid, k-means clustered and compared with the CHN
    # fricative signatures.  Only classes that confidently match are written -- a bank whose
    # SND records are voiced material gets no "noise" key (and keeps the built-in model).
    noise_sec, noise_named_txt = {}, "not attempted"
    snd_found = snd_read = noise_sampled = noise_like = noise_nclasses = 0
    if noise_records > 0:
        t0 = time.time()
        snd_off, snd_read = scan_snd_offsets(ddb_path, ddb_size, snd_scan_mb * 1024 * 1024)
        snd_found = int(snd_off.size)
        log("[noise] v2: %d SND records found by validated header scan (%.0f MB read = %.1f%% of the .ddb, %.1fs)"
            % (snd_found, snd_read / 1e6, 100.0 * snd_read / max(1, ddb_size), time.time() - t0))
        if snd_found:
            pick_s = np.unique(np.linspace(0, snd_found - 1, min(noise_records, snd_found)).astype(np.int64))
            ncurves, nfeat = [], []
            bad_hdr = 0
            with open(ddb_path, "rb") as f:
                for j in pick_s:
                    f.seek(int(snd_off[j]))
                    hdr = f.read(18)
                    if len(hdr) < 18:
                        bad_hdr += 1
                        continue
                    _mg, tl, _sr, _ch, n, _rv = struct.unpack("<4sIIHHH", hdr)
                    if tl != 18 + 2 * n:          # re-validate: a scan bug shows up here
                        bad_hdr += 1
                        continue
                    pcm = np.frombuffer(f.read(2 * n), dtype="<i2")
                    if pcm.size < 256:
                        continue
                    # Analyse the noisiest window, not the whole record: a fricative sample often
                    # opens with voicing, and averaging that over the record hides the noise (the
                    # first version never found an /s/, whose energy sits at 5-8 kHz).
                    cur, ft = None, None
                    _win = 8192
                    if pcm.size > _win + _win // 2:
                        for _a in range(0, pcm.size - _win + 1, _win // 2):
                            _c, _f = snd_analysis(pcm[_a:_a + _win], harm_log)
                            if _c is None:
                                continue
                            if ft is None or _f["flatness"] > ft["flatness"]:
                                cur, ft = _c, _f
                    if cur is None:
                        cur, ft = snd_analysis(pcm, harm_log)
                    if cur is None:
                        continue
                    ncurves.append(cur)
                    nfeat.append([ft["centroid"], ft["bandwidth"], ft["flatness"], ft["level"]])
            noise_sampled = len(ncurves)
            if bad_hdr:
                log("      [measured] WARNING: %d of %d sampled SND offsets did not re-validate as records"
                    % (bad_hdr, len(pick_s)))
            if noise_sampled >= 8:
                Xn = np.asarray(ncurves, dtype=np.float64)
                Fn = np.asarray(nfeat, dtype=np.float64)
                noise_like = int(((Fn[:, 0] >= 2500.0) & (Fn[:, 2] >= NOISE_FLAT_MIN)).sum())
                # Cluster only the noise-like records. Clustering all of them let the handful of
                # fricative samples be absorbed by the voiced classes, so no class ever showed a
                # fricative signature and the whole section was dropped (measured: 10 of 2988 were
                # noise-like, yet a wide survey of the same bank finds ~9%).
                nz = (Fn[:, 2] >= NOISE_FLAT_MIN) & (Fn[:, 0] >= 1200.0)
                if int(nz.sum()) < 6:
                    nz = Fn[:, 2] >= float(np.percentile(Fn[:, 2], 92.0))
                if int(nz.sum()) < 6:
                    nz = np.ones(Fn.shape[0], dtype=bool)
                Xc = Xn[nz]
                Fc = Fn[nz]
                log("      [measured] noise-like pre-filter: %d of %d sampled records" % (int(nz.sum()), noise_sampled))
                kk = int(min(NOISE_CLASSES, max(2, Xc.shape[0] // 5)))
                noise_nclasses = kk
                Cn, labn = kmeans(Xc, kk)
                classes = []
                for j in range(kk):
                    m = labn == j
                    if not m.any():
                        classes.append({"n": 0, "centroid": 0.0, "bandwidth": 0.0,
                                        "flatness": 0.0, "level": -120.0, "curve": Cn[j]})
                        continue
                    classes.append({"n": int(m.sum()),
                                    "centroid": float(np.median(Fc[m, 0])),
                                    "bandwidth": float(np.median(Fc[m, 1])),
                                    "flatness": float(np.median(Fc[m, 2])),
                                    "level": float(np.median(Fc[m, 3])),
                                    "curve": Cn[j]})
                named, lvl_med = classify_noise_classes(classes)
                show = [int(np.argmin(np.abs(harm_grid - v))) for v in (125, 250, 500, 1000, 2000, 4000, 8000, 12000)]
                log("      %d sampled SND records -> %d k-means classes; bank median level %.1f dBFS; %d of %d sampled records are noise-like (centroid >= 2.5 kHz and flatness >= %.2f)"
                    % (noise_sampled, kk, lvl_med, noise_like, noise_sampled, NOISE_FLAT_MIN))
                log("      class    n  centroid  bandwidth  flatness  level(dBFS)  shape at %s Hz"
                    % " ".join("%d" % harm_grid[i2] for i2 in show))
                for j, c in enumerate(classes):
                    log("      %5d %4d  %7.0f   %7.0f   %7.4f  %8.1f   %s"
                        % (j, c["n"], c["centroid"], c["bandwidth"], c["flatness"], c["level"],
                           " ".join("%6.1f" % c["curve"][i2] for i2 in show)))
                for sym, j in sorted(named.items()):
                    cur = np.asarray(classes[j]["curve"], dtype=np.float64)
                    db = np.round((cur - cur.max()) / HARM_DB_STEP) * HARM_DB_STEP
                    db = np.clip(db, HARM_DB_FLOOR, 0.0)
                    db[db == 0.0] = 0.0
                    noise_sec[sym] = {"freqs": [round(float(v), 4) for v in harm_grid],
                                      "db": [float(v) for v in db]}
                if noise_sec:
                    noise_named_txt = "named %s" % ", ".join("%s (class %d, centroid %.0f Hz)" % (s, j, classes[j]["centroid"])
                                                             for s, j in sorted(named.items()))
                    log("      [measured] named classes: %s" % noise_named_txt)
                else:
                    noise_named_txt = ("no class matched a CHN fricative signature (%d of %d sampled SND "
                                       "records are tonal, median flatness %.4f)" %
                                       (noise_sampled - noise_like, noise_sampled, float(np.median(Fn[:, 2]))))
                    log("      [measured] %s -> the 'noise' section is omitted" % noise_named_txt)
            else:
                noise_named_txt = "too few usable SND records to cluster (%d)" % noise_sampled
                log("      [measured] %s" % noise_named_txt)

    # ---- v2 sections -------------------------------------------------------------
    # "freqs" is the shared grid the bands were measured on; the app reads it verbatim.
    harmonics = {
        "freqs": [round(float(v), 4) for v in harm_grid],
        "source": ("VOCALOID4 FRM2 frames of %s (%s), measured harmonic magnitudes (%s): %d curves from %d frames "
                   "(%d of %d decoded) on a %d-point log grid, %d CHN symbols in %d pitch bands; "
                   "noise: %s" % (voice_name, comp_id, TOOL_TAG, Hc.shape[0], E.shape[0], Hc.shape[0], E.shape[0],
                                  harm_grid.size, len(harm_phonemes), total_bands, noise_named_txt)),
        "phonemes": harm_phonemes,
    }
    if noise_sec:
        harmonics["noise"] = noise_sec

    # ---- pack -------------------------------------------------------------------
    pid = "%s_bank" % slugify(voice_name)
    # names in the three UI languages; the app appends its own localised "installed" suffix
    display, name_en, name_ja = voice_display_names(voice_name)
    now = datetime.now().astimezone().isoformat(timespec="seconds")
    src_dir = os.path.dirname(os.path.abspath(bank["ddi"]))
    pack = {
        "format": FORMAT_TAG,
        "id": pid,
        "name": display,
        "nameEn": name_en,
        "nameJa": name_ja,
        "lang": 2,                                  # [inferred] PHDC holds the 62-symbol CHN set
        "compID": comp_id,
        "source": {
            "kind": "vocaloid4-voicebank",
            "bank": voice_name,
            "path": src_dir.replace("\\", "/"),
            "extractedAt": now,
            "tool": TOOL_TAG,
        },
        "voice": voice,
        "shelf": shelf,
        "formants": formants,
        "harmonics": harmonics,
        "stats": {
            "frames": int(E.shape[0]),
            "f0Median": round(f0_med, 1),
            "f0Min": round(f0_min, 1),
            "f0Max": round(f0_max, 1),
            "clusters": int(len(clusters)),
            "matchedPhonemes": int(matched),
            "harmonicFrames": int(Hc.shape[0]),
            "harmonicPhonemes": int(len(harm_phonemes)),
            "harmonicBands": int(total_bands),
            "harmonicBandsPerPhoneme": {str(k): int(v) for k, v in sorted(bands_per_phoneme.items())},
            "harmonicDynVariants": int(HARM_DYN_VARIANTS),
            "noiseClasses": int(len(noise_sec)),
            "noiseRecords": int(noise_sampled),
            "noiseSndFound": int(snd_found),
            "noiseLikeRecords": int(noise_like),
        },
        "notes": (
            "[measured] %d FRM2 frames decoded from %s (%s index), harmonics K=%d..%d, harmonic grid vs exact k*f0 median %.1e max %.1e (relative), "
            "f0 %.1f-%.1f Hz (median %.1f); magnitudes read from float %d of every FRM2 record; %d/%d cluster centroids "
            "gave F1/F2/F3; %d of %d CHN symbols matched within %.2f oct (Sil has no formants). "
            "[inferred] envelope = 96 log points 60 Hz-16 kHz, frame mean removed, +12 dB/oct above 400 Hz added back for "
            "the glottal source tilt; shelf = average envelope, max normalised to 0 dB, clamped to [%g,%g] dB; "
            "formantScale = median(measured/reference formant) against the built-in 165 cm reference table; "
            "tiltBias = 0.02 x (4-10 kHz vs 200-800 Hz balance %+.1f dB); breathBias = 0.10 + 0.03 x (8-16 kHz tail %+.1f dB + 3). "
            "No audio data is embedded: derived parametric measurements only. "
            "[measured] v2 harmonics: %d of %d decoded frames usable (>= %d harmonics, f0 %.0f-%.0f Hz); "
            "shared grid = %d log points %.0f Hz-%.0f kHz written into harmonics.freqs; %d of %d CHN symbols got "
            "curves (%d in total = pitch bands x up to %d loudness variants, at least %d usable frames per band); "
            "a band is the LINEAR-domain mean of its frames' normalised curves, re-normalised to 0 dB, quantised to %.1f dB; "
            "the band f0 is the median f0 of its frames; curves of one band that share an f0 differ in loudness "
            "(dyn 0..1, measured by sorting the band's frames by total linear energy) so the app can follow DYN. "
            "[measured] v2 noise: %d SND records in the .ddb, %d sampled and Welch-analysed into %d classes, of which %d "
            "are noise-like (flatness >= %.2f): %s. "
            "[inferred] the bands drive the app's sine harmonics with the bank's own measured spectral shape, so the timbre "
            "comes from the voicebank rather than from the formant model."
            % (E.shape[0], os.path.basename(ddb_path), source_kind, k_min, k_max, sp_med, sp_max,
               f0_min, f0_max, f0_med, FRM2_MAG_ROW, len(clusters), k, matched, len(symbols), MATCH_MAX_OCT,
               SHELF_MIN_DB, SHELF_MAX_DB, hf_db, tail_db,
               Hc.shape[0], E.shape[0], HARM_MIN_HARMONICS, HARM_F0_MIN, HARM_F0_MAX,
               harm_grid.size, HARM_F_LO, HARM_F_HI / 1000.0,
               len(harm_phonemes), len(symbols), total_bands, HARM_DYN_VARIANTS,
               HARM_BAND_MIN_FRAMES,
               HARM_DB_STEP, snd_found, noise_sampled, noise_nclasses, noise_like, NOISE_FLAT_MIN,
               noise_named_txt)
        ),
    }

    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "%s.v4voice" % pid)
    lic = PUBLIC_LICENSE.format(name=display, id=pid, bank=voice_name, compid=comp_id,
                                ddb=bank["ddb"].replace("\\", "/"), when=now, tool=TOOL_TAG)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("voice.json", json.dumps(pack, ensure_ascii=False, indent=1) + "\n")
        z.writestr("LICENSE.txt", lic)
    log("[pack] wrote %s (%.1f KB) in %.1fs total"
        % (out_path, os.path.getsize(out_path) / 1024.0, time.time() - t_start))
    return out_path


def resolve_bank(path):
    """Accept a bank directory, or any of its .ddi/.vvd/.ddb files."""
    p = os.path.abspath(path)
    base = p if os.path.isdir(p) else os.path.dirname(p)
    vvd = sorted(glob.glob(os.path.join(base, "*.vvd")))
    ddi = sorted(glob.glob(os.path.join(base, "*.ddi")))
    ddb = sorted(glob.glob(os.path.join(base, "*.ddb")))
    if not os.path.isdir(p):
        stem = os.path.splitext(os.path.basename(p))[0]
        vvd = [x for x in vvd if os.path.splitext(os.path.basename(x))[0] == stem] or vvd
        ddi = [x for x in ddi if os.path.splitext(os.path.basename(x))[0] == stem] or ddi
        ddb = [x for x in ddb if os.path.splitext(os.path.basename(x))[0] == stem] or ddb
    if not (vvd and ddi and ddb):
        raise SystemExit("could not find .vvd/.ddi/.ddb next to %s" % path)
    return {"vvd": vvd[0], "ddi": ddi[0], "ddb": ddb[0]}


def load_pack_check():
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "pack_selftest", "pack_check.py")
    if not os.path.isfile(path):
        return None
    spec = importlib.util.spec_from_file_location("pack_check", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main(argv=None):
    ap = argparse.ArgumentParser(description="Convert a locally installed VOCALOID4 voicebank into a .v4voice singer pack.")
    ap.add_argument("--bank", required=True, help="voicebank directory, or any of its .ddi/.vvd/.ddb files")
    ap.add_argument("--out", required=True, help="output directory for <id>.v4voice")
    ap.add_argument("--frames", type=int, default=40000, help="max FRM2 frames to sample (default 40000)")
    ap.add_argument("--clusters", type=int, default=16, help="k-means clusters for the formant table (default 16)")
    ap.add_argument("--harm-points", type=int, default=HARM_POINTS,
                    help="log-spaced points of the shared harmonics grid (default %d, 32..96)" % HARM_POINTS)
    ap.add_argument("--noise-records", type=int, default=800,
                    help="SND records to analyse for the noise section (default 800; 0 disables it)")
    ap.add_argument("--snd-scan-mb", type=int, default=0,
                    help="read budget for the SND record scan, 0 = the whole .ddb (default 0)")
    ap.add_argument("--no-noise", action="store_true", help="skip the optional SND/noise analysis entirely")
    ap.add_argument("--verify", action="store_true", help="re-open the written pack and validate it with pack_selftest/pack_check.py")
    ap.add_argument("--scan-mb", type=int, default=192, help="read budget for the linear-scan fallback (default 192 MB)")
    args = ap.parse_args(argv)

    if not (32 <= args.harm_points <= 96):
        raise SystemExit("--harm-points must be 32..96 (docs/voicepack-format.md)")

    bank = resolve_bank(args.bank)
    log("=== %s" % TOOL_TAG)
    log("    bank : %s" % os.path.dirname(bank["ddi"]))
    out_path = build_pack(bank, args.out, args.frames, args.clusters, args.scan_mb,
                          harm_points=args.harm_points,
                          noise_records=0 if args.no_noise else args.noise_records,
                          snd_scan_mb=args.snd_scan_mb)

    if args.verify:
        mod = load_pack_check()
        if mod is None:
            log("[verify] pack_selftest/pack_check.py not found -- skipping")
            return 0
        ok, report = mod.check_pack(out_path)
        for line in report:
            log("[verify] " + line)
        log("[verify] %s" % ("OK" if ok else "FAILED"))
        return 0 if ok else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
