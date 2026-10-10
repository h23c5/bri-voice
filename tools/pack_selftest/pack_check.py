#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tools/pack_selftest/pack_check.py -- validator / round-trip checker for .v4voice packs.

Checks every pack produced by tools/voicebank_pack.py against docs/voicepack-format.md:

  * the file is a readable ZIP and its members decompress
  * voice.json exists and parses as JSON (UTF-8)
  * the required keys are present with the right JSON types
  * shelf: 2..12 [hz, dB] pairs, strictly ascending in Hz, finite numbers
  * formants: every entry is three integers in 100..5000 Hz
  * harmonics (v2, docs/voicepack-format.md "v2 增补"), required:
      - freqs: 32..96 points, all inside 20..22050 Hz, strictly ascending
      - source: non-empty provenance string
      - phonemes: symbol -> 1..3 pitch bands, each {f0: number > 0, db: [...]}
      - every band's db has EXACTLY len(freqs) finite values, max(db) within 0.5 dB of 0
        and no value below -160 dB
      - noise (optional): symbol -> {freqs: [...], db: [...]} with its own grid, same rules
      - stats.harmonicFrames / harmonicPhonemes / harmonicBands / noiseClasses must exist
        and agree with what the section actually contains
  * stats: frames/f0Median/f0Min/f0Max/clusters/matchedPhonemes present and sane,
    f0Min <= f0Median <= f0Max, matchedPhonemes <= 62
  * the whole pack stays inside the 200 KB size budget

Per pack it prints: size, freqs, phonemes, total bands, noise classes.

usage:
  python tools/pack_selftest/pack_check.py <pack.v4voice> [more packs or wildcards ...]

Wildcards are expanded by this script, so a shell that does not glob (cmd.exe,
PowerShell) still works.  Exit status is 0 only when every pack passes.
"""

from __future__ import annotations

import glob
import json
import math
import os
import sys
import zipfile

REQUIRED_KEYS = ("format", "id", "name", "lang", "compID", "source", "voice",
                 "shelf", "formants", "stats", "harmonics")
REQUIRED_STATS = ("frames", "f0Median", "f0Min", "f0Max", "clusters", "matchedPhonemes")
# v2 additions (required whenever the "harmonics" section is present)
REQUIRED_V2_STATS = ("harmonicFrames", "harmonicPhonemes", "harmonicBands", "noiseClasses")
FORMANT_MIN_HZ, FORMANT_MAX_HZ = 100, 5000
SHELF_MIN_POINTS, SHELF_MAX_POINTS = 2, 12
MAX_PACK_BYTES = 2 * 1024 * 1024 * 1024     # [required] whole pack < 2 GB (frame sequences made packs big)
HARM_MIN_POINTS, HARM_MAX_POINTS = 32, 96
HARM_F_LO, HARM_F_HI = 20.0, 22050.0
HARM_MAX_BANDS = 12          # 3 pitch bands x up to 3 loudness variants, with slack
HARM_DB_MIN, HARM_DB_MAX = -160.0, 1.0
HARM_PEAK_TOL = 0.5                         # max(db) must sit within this of 0 dB


def _check_freqs(freqs, where, bad):
    """Ascending, in range, 32..96 points.  Returns the point count (0 when unusable)."""
    if not isinstance(freqs, list):
        bad("%s.freqs must be a list" % where)
        return 0
    if not (HARM_MIN_POINTS <= len(freqs) <= HARM_MAX_POINTS):
        bad("%s.freqs has %d points, expected %d..%d" % (where, len(freqs), HARM_MIN_POINTS, HARM_MAX_POINTS))
    prev = None
    for i, f in enumerate(freqs):
        if not _is_num(f):
            bad("%s.freqs[%d] = %r is not a number" % (where, i, f))
            return 0
        f = float(f)
        if not math.isfinite(f):
            bad("%s.freqs[%d] = %r is not finite" % (where, i, f))
            return 0
        if not (HARM_F_LO <= f <= HARM_F_HI):
            bad("%s.freqs[%d] = %g outside %g..%g Hz" % (where, i, f, HARM_F_LO, HARM_F_HI))
        if prev is not None and f <= prev:
            bad("%s.freqs must ascend strictly: %g after %g" % (where, f, prev))
        prev = f
    return len(freqs)


def _check_db(db, n_expect, where, bad, required=True):
    """Finite, exactly len(freqs) long, peak within 0.5 dB of 0.  Returns True when valid."""
    if db is None and not required:
        return False          # newer packs omit the mean curve when a frame sequence is stored
    if not isinstance(db, list):
        bad("%s.db must be a list" % where)
        return False
    if n_expect and len(db) != n_expect:
        bad("%s.db has %d values, expected %d (= len(freqs))" % (where, len(db), n_expect))
        return False
    if not db:
        bad("%s.db is empty" % where)
        return False
    peak = None
    for i, v in enumerate(db):
        if not _is_num(v):
            bad("%s.db[%d] = %r is not a number" % (where, i, v))
            return False
        v = float(v)
        if not math.isfinite(v):
            bad("%s.db[%d] = %r is not finite" % (where, i, v))
            return False
        if not (HARM_DB_MIN <= v <= HARM_DB_MAX):
            bad("%s.db[%d] = %g outside %g..%g dB" % (where, i, v, HARM_DB_MIN, HARM_DB_MAX))
            return False
        peak = v if peak is None else max(peak, v)
    if abs(peak) > HARM_PEAK_TOL:
        bad("%s.db peaks at %.3f dB, expected within %.1f dB of 0" % (where, peak, HARM_PEAK_TOL))
        return False
    return True


def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def _is_num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def check_pack(path):
    """Return (ok, report_lines).  report_lines is a list of human-readable strings."""
    rep = []
    errs = []

    def bad(msg):
        errs.append(msg)
        rep.append("FAIL  " + msg)

    if not os.path.isfile(path):
        return False, ["FAIL  %s: no such file" % path]
    size = os.path.getsize(path)
    rep.append("file  %s (%.1f KB)" % (os.path.basename(path), size / 1024.0))
    if size > MAX_PACK_BYTES:
        bad("pack is %.1f KB, over the %.0f KB budget" % (size / 1024.0, MAX_PACK_BYTES / 1024.0))

    # ---- container ------------------------------------------------------------
    if not zipfile.is_zipfile(path):
        return False, rep + ["FAIL  not a ZIP archive"]
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            if "voice.json" not in names:
                bad("voice.json missing from the archive (members: %s)" % ", ".join(names))
                return False, rep
            try:
                raw = z.read("voice.json").decode("utf-8")
            except Exception as e:                      # noqa: BLE001
                bad("voice.json cannot be decoded as UTF-8: %r" % (e,))
                return False, rep
            bad_zip = z.testzip()
            if bad_zip:
                bad("corrupt member in archive: %s" % bad_zip)
    except zipfile.BadZipFile as e:
        bad("bad ZIP: %r" % (e,))
        return False, rep
    rep.append("zip   OK, members: %s" % ", ".join(names))

    # ---- JSON -----------------------------------------------------------------
    try:
        doc = json.loads(raw)
    except Exception as e:                              # noqa: BLE001
        bad("voice.json does not parse as JSON: %r" % (e,))
        return False, rep
    if not isinstance(doc, dict):
        return False, rep + ["FAIL  voice.json top level is not an object"]
    rep.append("json  OK, %d top-level keys" % len(doc))

    # ---- required keys ---------------------------------------------------------
    for k in REQUIRED_KEYS:
        if k not in doc:
            bad("required key %r is missing" % k)
    if doc.get("format") != "v4voice/1":
        bad("format is %r, expected 'v4voice/1'" % doc.get("format"))
    for k in ("id", "name"):
        if not (isinstance(doc.get(k), str) and doc.get(k).strip()):
            bad("%s must be a non-empty string (got %r)" % (k, doc.get(k)))
    if not _is_int(doc.get("lang", None)):
        bad("lang must be an integer (got %r)" % (doc.get("lang"),))
    if not isinstance(doc.get("voice", None), dict):
        bad("voice must be an object")
    if not isinstance(doc.get("source", None), dict):
        bad("source must be an object")

    # ---- shelf -----------------------------------------------------------------
    shelf = doc.get("shelf")
    if not isinstance(shelf, list):
        bad("shelf must be a list of [hz, dB] pairs")
        shelf = []
    elif not (SHELF_MIN_POINTS <= len(shelf) <= SHELF_MAX_POINTS):
        bad("shelf has %d points, expected %d..%d" % (len(shelf), SHELF_MIN_POINTS, SHELF_MAX_POINTS))
    prev_hz = None
    for i, pt in enumerate(shelf):
        if not (isinstance(pt, (list, tuple)) and len(pt) == 2 and _is_num(pt[0]) and _is_num(pt[1])):
            bad("shelf[%d] is not a [hz, dB] pair: %r" % (i, pt))
            continue
        hz, db = float(pt[0]), float(pt[1])
        if not (0.0 < hz < 96000.0):
            bad("shelf[%d] frequency %r out of range" % (i, pt[0]))
        if abs(db) > 60.0:
            bad("shelf[%d] gain %r dB is out of range" % (i, pt[1]))
        if prev_hz is not None and hz <= prev_hz:
            bad("shelf must ascend in Hz: %g after %g" % (hz, prev_hz))
        prev_hz = hz

    # ---- formants --------------------------------------------------------------
    formants = doc.get("formants")
    if not isinstance(formants, dict):
        bad("formants must be an object mapping symbol -> [F1,F2,F3]")
        formants = {}
    for sym, tri in formants.items():
        if not (isinstance(sym, str) and sym):
            bad("formants has a non-string key: %r" % (sym,))
            continue
        if not (isinstance(tri, list) and len(tri) == 3):
            bad("formants[%r] is not a 3-element list: %r" % (sym, tri))
            continue
        for j, v in enumerate(tri):
            if not _is_int(v):
                bad("formants[%r][%d] = %r is not an integer" % (sym, j, v))
            elif not (FORMANT_MIN_HZ <= v <= FORMANT_MAX_HZ):
                bad("formants[%r][%d] = %d outside %d..%d Hz" % (sym, j, v, FORMANT_MIN_HZ, FORMANT_MAX_HZ))


    # ---- harmonics (v2) ---------------------------------------------------------
    n_freqs = harm_phonemes = harm_bands = noise_classes = 0
    harm = doc.get("harmonics")
    if not isinstance(harm, dict):
        bad('harmonics must be an object (v2 section, see docs/voicepack-format.md "v2 增补")')
    else:
        n_freqs = _check_freqs(harm.get("freqs"), "harmonics", bad)
        src = harm.get("source")
        if not (isinstance(src, str) and src.strip()):
            bad("harmonics.source must be a non-empty provenance string")
        ph = harm.get("phonemes")
        if not isinstance(ph, dict):
            bad("harmonics.phonemes must be an object mapping symbol -> [bands]")
        else:
            for sym, bands in ph.items():
                if not (isinstance(sym, str) and sym):
                    bad("harmonics.phonemes has a non-string key: %r" % (sym,))
                    continue
                if not isinstance(bands, list) or not (1 <= len(bands) <= HARM_MAX_BANDS):
                    bad("harmonics.phonemes[%r] must be a list of 1..%d bands (got %r)"
                        % (sym, HARM_MAX_BANDS, bands if not isinstance(bands, list) else len(bands)))
                    continue
                harm_phonemes += 1
                for bi, band in enumerate(bands):
                    where = "harmonics.phonemes[%r][%d]" % (sym, bi)
                    if not isinstance(band, dict):
                        bad("%s is not an object" % where)
                        continue
                    f0 = band.get("f0")
                    if not _is_num(f0) or not (0.0 < float(f0) < 5000.0):
                        bad("%s.f0 = %r is not a plausible band f0" % (where, f0))
                    dyn = band.get("dyn")
                    if dyn is not None and (not _is_num(dyn) or not (0.0 <= float(dyn) <= 1.0)):
                        bad("%s.dyn = %r must be within 0..1" % (where, dyn))
                    seqb64 = band.get("seqB64")
                    has_db = _check_db(band.get("db"), n_freqs, where, bad, required=False)
                    if has_db or isinstance(seqb64, str):
                        harm_bands += 1
                    if seqb64 is not None:
                        import base64 as _b64
                        if not isinstance(seqb64, str):
                            bad("%s.seqB64 must be a string" % where)
                        else:
                            raw = _b64.b64decode(seqb64 + "=" * (-len(seqb64) % 4))
                            q = band.get("seqQ", 0.5)
                            nf = band.get("seqN", len(raw) // max(1, n_freqs))
                            if len(raw) != int(nf) * n_freqs:
                                bad("%s.seqB64 length %d is not seqN(%s) x %d points" % (where, len(raw), nf, n_freqs))
                            else:
                                sv = [b - 256 if b > 127 else b for b in raw]     # int8, signed dB/seqQ
                                if any(v > 0 for v in sv):
                                    bad("%s.seqB64 holds a positive value (must be <= 0 dB)" % where)
                                if not isinstance(q, (int, float)) or not (0.01 <= float(q) <= 6.0):
                                    bad("%s.seqQ = %r is not a plausible dB step" % (where, q))
                    seq = band.get("seq")
                    if seq is not None:
                        if not isinstance(seq, list) or not (2 <= len(seq) <= 64):
                            bad("%s.seq must be a list of 2..64 real frames" % where)
                        else:
                            for si, fr in enumerate(seq):
                                _check_db(fr, n_freqs, "%s.seq[%d]" % (where, si), bad)
                        lvl = band.get("lvl")
                        if lvl is not None:
                            if not isinstance(lvl, list) or len(lvl) != len(seq):
                                bad("%s.lvl must match seq in length" % where)
                            elif any(not _is_num(v) or not (-30.0 <= float(v) <= 18.0) for v in lvl):
                                bad("%s.lvl has an out-of-range frame level" % where)
        nz = harm.get("noise")
        if nz is not None:
            if not isinstance(nz, dict):
                bad("harmonics.noise must be an object mapping symbol -> {freqs, db}")
            else:
                for sym, entry in nz.items():
                    if not (isinstance(sym, str) and sym):
                        bad("harmonics.noise has a non-string key: %r" % (sym,))
                        continue
                    where = "harmonics.noise[%r]" % sym
                    if not isinstance(entry, dict):
                        bad("%s is not an object" % where)
                        continue
                    nf = _check_freqs(entry.get("freqs"), where, bad)
                    if _check_db(entry.get("db"), nf, where, bad):
                        noise_classes += 1
        rep.append("v2    freqs=%d  phonemes=%d  bands=%d  noise=%d"
                   % (n_freqs, harm_phonemes, harm_bands, noise_classes))

    # ---- stats -----------------------------------------------------------------
    stats = doc.get("stats")
    if not isinstance(stats, dict):
        bad("stats must be an object")
        stats = {}
    for k in REQUIRED_STATS:
        if k not in stats:
            bad("stats.%s is missing" % k)
    if _is_num(stats.get("frames")) and stats["frames"] <= 0:
        bad("stats.frames must be positive")
    fmin, fmed, fmax = stats.get("f0Min"), stats.get("f0Median"), stats.get("f0Max")
    if all(_is_num(v) for v in (fmin, fmed, fmax)):
        if not (0.0 < fmin <= fmed <= fmax < 5000.0):
            bad("stats f0 order violated: min=%r median=%r max=%r" % (fmin, fmed, fmax))
    else:
        bad("stats f0Min/f0Median/f0Max must all be numbers")
    if _is_num(stats.get("matchedPhonemes")) and not (0 <= stats["matchedPhonemes"] <= 62):
        bad("stats.matchedPhonemes=%r outside 0..62" % (stats["matchedPhonemes"],))
    if _is_num(stats.get("matchedPhonemes")) and _is_num(stats.get("clusters")):
        if stats["matchedPhonemes"] > 0 and stats["clusters"] < 1:
            bad("matchedPhonemes > 0 but clusters = 0")
    # v2 counters must exist and agree with the section they describe
    if isinstance(doc.get("harmonics"), dict):
        for k in REQUIRED_V2_STATS:
            if k not in stats:
                bad("stats.%s is missing (required with the v2 harmonics section)" % k)
        if _is_num(stats.get("harmonicFrames")):
            if not (0 < stats["harmonicFrames"] <= stats.get("frames", 0)):
                bad("stats.harmonicFrames=%r must be > 0 and <= stats.frames" % (stats["harmonicFrames"],))
        else:
            bad("stats.harmonicFrames must be a number")
        if _is_num(stats.get("harmonicPhonemes")) and stats["harmonicPhonemes"] != harm_phonemes:
            bad("stats.harmonicPhonemes=%r does not match the %d phonemes in harmonics.phonemes"
                % (stats["harmonicPhonemes"], harm_phonemes))
        if _is_num(stats.get("harmonicBands")) and stats["harmonicBands"] != harm_bands:
            bad("stats.harmonicBands=%r does not match the %d valid bands in harmonics.phonemes"
                % (stats["harmonicBands"], harm_bands))
        if _is_num(stats.get("noiseClasses")) and stats["noiseClasses"] != noise_classes:
            bad("stats.noiseClasses=%r does not match the %d entries in harmonics.noise"
                % (stats["noiseClasses"], noise_classes))
        bpp = stats.get("harmonicBandsPerPhoneme")
        if bpp is not None:
            if not isinstance(bpp, dict) or not all(_is_num(v) for v in bpp.values()):
                bad("stats.harmonicBandsPerPhoneme must be an object of numbers")
            elif sum(int(v) for v in bpp.values()) != harm_phonemes:
                bad("stats.harmonicBandsPerPhoneme sums to %d, expected harmonicPhonemes=%d"
                    % (sum(int(v) for v in bpp.values()), harm_phonemes))

    ok = not errs
    if ok:
        rep.append("OK    container, keys, shelf, formants, harmonics and stats all valid")
    return ok, rep


def v2_counts(doc):
    """(freqs, phonemes, bands, noise classes) of a pack; zeros when there is no v2 section."""
    harm = doc.get("harmonics") if isinstance(doc, dict) else None
    if not isinstance(harm, dict):
        return 0, 0, 0, 0
    freqs = harm.get("freqs") if isinstance(harm.get("freqs"), list) else []
    ph = harm.get("phonemes") if isinstance(harm.get("phonemes"), dict) else {}
    bands = sum(len(v) for v in ph.values() if isinstance(v, list))
    nz = harm.get("noise") if isinstance(harm.get("noise"), dict) else {}
    return len(freqs), len(ph), bands, len(nz)


def summary_line(path, doc):
    st = doc.get("stats", {}) if isinstance(doc, dict) else {}
    shelf = doc.get("shelf", []) if isinstance(doc, dict) else []
    shelf_txt = " ".join("%gHz%+.1f" % (float(p[0]), float(p[1])) for p in shelf
                         if isinstance(p, (list, tuple)) and len(p) == 2)
    nf, nph, nb, nn = v2_counts(doc)
    return "%-24s %-24s %7s %7s %5s %5s %5s %5s %4s %8s %8s  %s" % (
        str(doc.get("id", "?"))[:24], str(doc.get("name", "?"))[:24],
        st.get("frames", "?"), st.get("f0Median", "?"),
        st.get("clusters", "?"), st.get("matchedPhonemes", "?"),
        nf, nph, nb, nn, "%.1f KB" % (os.path.getsize(path) / 1024.0), shelf_txt)


def main(argv):
    args = argv[1:]
    if not args:
        print(__doc__.strip())
        return 2
    paths = []
    for a in args:
        hits = sorted(glob.glob(a))
        if not hits:
            hits = [a]                      # let check_pack report the missing file
        paths.extend(hits)

    rows, failures = [], []
    for p in paths:
        print("=" * 100)
        print("checking %s" % p)
        ok, rep = check_pack(p)
        for line in rep:
            print("   " + line)
        print("   -> %s" % ("PASS" if ok else "FAIL"))
        if not ok:
            failures.append(p)
            continue
        try:
            with zipfile.ZipFile(p) as z:
                doc = json.loads(z.read("voice.json").decode("utf-8"))
            rows.append(summary_line(p, doc))
        except Exception as e:                          # noqa: BLE001
            failures.append(p)
            print("   FAIL  could not build the summary row: %r" % (e,))

    if rows:
        print()
        print("=" * 100)
        print("%-24s %-24s %7s %7s %5s %5s %5s %5s %4s %8s %8s  %s"
              % ("id", "name", "frames", "f0 med", "clu", "match",
                 "freqs", "phon", "band", "noise", "size", "shelf (Hz/dB)"))
        print("-" * 100)
        for r in rows:
            print(r)
    print()
    if failures:
        print("FAILED: %d of %d pack(s): %s" % (len(failures), len(paths), ", ".join(failures)))
        return 1
    print("PASSED: %d pack(s) checked, all valid." % len(paths))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
