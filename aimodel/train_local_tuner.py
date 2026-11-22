# -*- coding: utf-8 -*-
"""BriTune-Net: derive a compact local tuning model from general Chinese vocal-tuning strategy.

The corpus is generated from a strategy teacher rather than scraped audio. Each strategy comes from
common public vocal-tuning practice (long-note decay and delayed vibrato, breathy phrase openings,
portamento on wide leaps, softer high notes, dry short notes, four broad style profiles).
The teacher is randomised over melodies, tempo, style and intensity, and the network distils it into
a ~20k-parameter model that streams note by note on a mid-range phone.

No network access, no external data, numpy only.
"""
import json, math, os, random, struct
import numpy as np

random.seed(20261122); np.random.seed(20261122)

N_FEAT = 12
CTX = 2                      # notes of context on each side -> 5 * 12 = 60
GLOBAL = 8                   # tempo, 5 style one-hots, intensity, note-count
IN_DIM = CTX * 2 * N_FEAT + N_FEAT + GLOBAL
OUT_DIM = 11
STYLES = ["pop", "ballad", "rock", "electronic", "guofeng"]
# output scales: index -> (name, scale) ; network emits raw, Java multiplies by scale
OUT_SCALE = [20.0, 20.0, 15.0, 15.0, 15.0, 20.0, 30.0, 60.0, 40.0, 15.0, 8.0]
OUT_NAME = ["dyn", "bre", "bri", "cle", "ope", "por", "vib", "pitLead", "pitTail", "vel", "gen"]

def gen_phrase(rng):
    """one phrase: 4..10 notes with a contour and occasional rests / leaps"""
    n = rng.randint(4, 10)
    start = rng.randint(48, 74)
    contour = rng.choice(["arch", "rise", "fall", "wave", "flat"])
    notes = []
    key = start
    for i in range(n):
        t = i / max(1, n - 1)
        if contour == "arch":   target = start + int(round(9 * math.sin(math.pi * t)))
        elif contour == "rise": target = start + int(round(8 * t))
        elif contour == "fall": target = start - int(round(8 * t))
        elif contour == "wave": target = start + int(round(6 * math.sin(2 * math.pi * t)))
        else:                   target = start
        if rng.random() < 0.18:              # expressive leap
            target += rng.choice([-1, 1]) * rng.randint(5, 9)
        key = max(36, min(88, target + rng.randint(-1, 1)))
        # durations: mostly quarters/eighths, one long note near the end
        if i == n - 1 and rng.random() < 0.55: dur = rng.choice([960, 1200, 1440])
        elif i == n - 2 and rng.random() < 0.2: dur = rng.choice([720, 960])
        else: dur = rng.choice([240, 240, 480, 480, 720])
        vel = int(np.clip(rng.gauss(78, 9), 30, 118))
        rest = 0 if i == 0 else (rng.choice([0, 0, 0, 120, 240]) if rng.random() < 0.3 else 0)
        # lyric length in characters (most Chinese syllables are one hanzi, some are two or three)
        lyr = rng.choice([1, 1, 1, 1, 2, 2, 3, 4])
        notes.append({"key": key, "dur": dur, "vel": vel, "rest": rest, "lyr": lyr})
    return notes

def make_song(rng):
    tempo = rng.choice([72, 84, 96, 104, 112, 120, 128, 140, 152, 168])
    style = rng.choice(STYLES)
    intensity = rng.uniform(0.35, 1.0)
    phrases = [gen_phrase(rng) for _ in range(rng.randint(1, 3))]
    return {"tempo": tempo, "style": style, "intensity": intensity, "phrases": phrases}

STYLE_IDX = {s: i for i, s in enumerate(STYLES)}

def teacher(song, pi, ni):
    """the strategy teacher: returns OUT_DIM deltas in physical units"""
    ph = song["phrases"][pi]; n = ph[ni]
    key, dur, vel = n["key"], n["dur"], n["vel"]
    first, last = ni == 0, ni == len(ph) - 1
    pos = ni / max(1, len(ph) - 1)
    rest_before = n["rest"]
    rest_after = ph[ni + 1]["rest"] if ni + 1 < len(ph) else 240
    prev_key = ph[ni - 1]["key"] if ni > 0 else key
    leap = key - prev_key
    inten = song["intensity"]
    d = [0.0] * OUT_DIM
    # DYNAMICS — arcs, accents, phrase edges
    d[0] -= 3.0 * inten                                   # keep headroom
    d[0] += 6.0 * math.sin(math.pi * pos) * inten         # phrase arch
    if first: d[0] -= 4.0 * inten
    if last:  d[0] -= 5.0 * inten
    if dur >= 960: d[0] -= 4.0 * inten                    # long notes settle
    if key >= 72: d[0] -= (key - 72) * 1.1 * inten         # high notes must not be pushed
    if key <= 48: d[0] += 3.0
    if abs(leap) >= 5: d[0] += 2.0 * inten
    # BREATH — phrase openings and endings, ballads breathe more
    d[1] += 8.0 * inten if first else 0.0
    d[1] += 6.0 * inten if last else 0.0
    if dur >= 960: d[1] += 4.0 * inten
    # BRIGHTNESS / CLARITY / OPENING
    if key >= 72: d[2] += 3.0
    if key <= 48: d[2] -= 4.0
    if first: d[3] += 4.0 * inten
    if dur <= 240: d[3] += 2.0
    d[4] += 3.0 * inten if key >= 72 else 0.0
    # PORTAMENTO — legato into leaps, tighter on short attacks
    if abs(leap) >= 5: d[5] += 9.0 * inten
    elif abs(leap) >= 2: d[5] += 3.0
    if first: d[5] += 4.0 * inten
    if dur <= 240: d[5] -= 4.0
    # VIBRATO — long notes only, delayed feel
    if dur >= 960: d[6] += 18.0 * inten
    elif dur >= 720: d[6] += 9.0 * inten
    elif dur <= 240: d[6] -= 22.0
    # PITCH — leading slide into the note, tail release/vibrato support
    if abs(leap) >= 4:
        d[7] += -math.copysign(min(45.0, abs(leap) * 7.0), leap) * inten
    if dur >= 960: d[8] += -12.0 * inten
    elif last: d[8] += -6.0 * inten
    # VELOCITY — accent first beats, soften phrase ends
    if first and ni == 0: d[9] += 4.0 * inten
    if last: d[9] -= 4.0 * inten
    # GEN — neutral; very slight lift on high sustained notes
    if key >= 76 and dur >= 720: d[10] += 2.5
    # style profile
    st = song["style"]
    if st == "ballad":      d[0] -= 4; d[1] += 8; d[6] += 6; d[5] += 4; d[2] -= 2
    elif st == "rock":      d[0] += 6; d[2] += 6; d[6] -= 5; d[3] += 3; d[4] += 2
    elif st == "electronic":d[2] += 5; d[4] += 4; d[6] -= 4; d[3] += 2
    elif st == "guofeng":   d[1] += 5; d[6] += 8; d[5] += 6; d[4] -= 3
    # intensity scaling for everything except the spring offsets already scaled
    for i in range(OUT_DIM):
        d[i] *= inten if i != 0 else 1.0
    # small natural jitter so the net does not memorise one exact curve
    for i in range(OUT_DIM):
        d[i] += random.gauss(0, 0.6)
    return [float(np.clip(d[i], -OUT_SCALE[i], OUT_SCALE[i])) for i in range(OUT_DIM)]

def note_feat(song, pi, ni):
    ph = song["phrases"][pi]; n = ph[ni]
    key, dur, vel = n["key"], n["dur"], n["vel"]
    first, last = ni == 0, ni == len(ph) - 1
    pos = ni / max(1, len(ph) - 1)
    rest_before = min(1.0, n["rest"] / 480.0)
    rest_after = min(1.0, (ph[ni + 1]["rest"] if ni + 1 < len(ph) else 240) / 480.0)
    prev_key = ph[ni - 1]["key"] if ni > 0 else key
    next_key = ph[ni + 1]["key"] if ni + 1 < len(ph) else key
    return [
        (key - 60) / 24.0,
        math.log2(max(30, dur) / 120.0) / 3.0,
        vel / 127.0,
        1.0 if first else 0.0,
        1.0 if last else 0.0,
        pos,
        rest_before,
        rest_after,
        max(-1.0, min(1.0, (key - prev_key) / 12.0)),
        max(-1.0, min(1.0, (next_key - key) / 12.0)),
        1.0 if dur >= 480 else 0.0,
        min(1.0, float(n["lyr"]) / 8.0),
    ]

def sample(song, pi, ni):
    ph = song["phrases"][pi]
    ctx = []
    for off in range(-CTX, CTX + 1):
        j = max(0, min(len(ph) - 1, ni + off))
        ctx.extend(note_feat(song, pi, j))
    g = [song["tempo"] / 120.0 - 1.0] + [1.0 if song["style"] == s else 0.0 for s in STYLES] + [song["intensity"], song["nnotes"] / 64.0]
    return ctx + g

def build(n_songs):
    X, Y = [], []
    for _ in range(n_songs):
        song = make_song(random)
        song["nnotes"] = sum(len(p) for p in song["phrases"])
        for pi, ph in enumerate(song["phrases"]):
            for ni in range(len(ph)):
                X.append(sample(song, pi, ni))
                Y.append(teacher(song, pi, ni))
    return np.array(X, dtype=np.float64), np.array(Y, dtype=np.float64)

def train():
    print("building dataset ...")
    X, Y = build(9000)
    print("dataset", X.shape, Y.shape)
    mu = X.mean(axis=0); raw_sd = X.std(axis=0); sd = raw_sd + 1e-6
    print("min sd", float(raw_sd.min()), "dims below 1e-3:", int((raw_sd < 1e-3).sum()))
    Xn = (X - mu) / sd
    Yn = Y / np.array(OUT_SCALE)
    rng = np.random.default_rng(7)
    idx = rng.permutation(len(Xn))
    Xn, Yn = Xn[idx], Yn[idx]
    n_val = len(Xn) // 12
    Xtr, Ytr = Xn[:-n_val], Yn[:-n_val]
    Xva, Yva = Xn[-n_val:], Yn[-n_val:]
    H1, H2 = 96, 64
    rng2 = np.random.default_rng(11)
    W1 = rng2.normal(0, math.sqrt(2.0 / IN_DIM), (IN_DIM, H1))
    b1 = np.zeros(H1)
    W2 = rng2.normal(0, math.sqrt(2.0 / H1), (H1, H2))
    b2 = np.zeros(H2)
    W3 = rng2.normal(0, math.sqrt(1.0 / H2), (H2, OUT_DIM))
    b3 = np.zeros(OUT_DIM)
    params = [W1, b1, W2, b2, W3, b3]
    m = [np.zeros_like(p) for p in params]; v = [np.zeros_like(p) for p in params]
    lr = 2e-3; beta1, beta2, eps = 0.9, 0.999, 1e-8
    bs = 128; steps = 0
    best = 1e9
    for epoch in range(26):
        order = rng.permutation(len(Xtr))
        tot = 0.0
        for s in range(0, len(Xtr) - bs, bs):
            b = order[s:s + bs]
            xb, yb = Xtr[b], Ytr[b]
            h1 = np.maximum(0, xb @ W1 + b1)
            h2 = np.maximum(0, h1 @ W2 + b2)
            out = h2 @ W3 + b3
            diff = out - yb
            tot += float((diff ** 2).mean())
            g = 2 * diff / diff.size
            gW3 = h2.T @ g; gb3 = g.sum(axis=0)
            dh2 = (g @ W3.T) * (h2 > 0)
            gW2 = h1.T @ dh2; gb2 = dh2.sum(axis=0)
            dh1 = (dh2 @ W2.T) * (h1 > 0)
            gW1 = xb.T @ dh1; gb1 = dh1.sum(axis=0)
            grads = [gW1, gb1, gW2, gb2, gW3, gb3]
            steps += 1
            for i in range(6):
                m[i] = beta1 * m[i] + (1 - beta1) * grads[i]
                v[i] = beta2 * v[i] + (1 - beta2) * grads[i] ** 2
                mh = m[i] / (1 - beta1 ** steps); vh = v[i] / (1 - beta2 ** steps)
                params[i] -= lr * mh / (np.sqrt(vh) + eps)
        h1 = np.maximum(0, Xva @ W1 + b1); h2 = np.maximum(0, h1 @ W2 + b2)
        val = float(((h2 @ W3 + b3 - Yva) ** 2).mean())
        print("epoch %2d train %.5f val %.5f" % (epoch + 1, tot / max(1, (len(Xtr) // bs)), val))
        if val < best:
            best = val
            best_params = [p.copy() for p in params]
    W1, b1, W2, b2, W3, b3 = best_params
    h1 = np.maximum(0, Xva @ W1 + b1); h2a = np.maximum(0, h1 @ W2 + b2)
    pred = (h2a @ W3 + b3) * np.array(OUT_SCALE)
    ytrue = Yva * np.array(OUT_SCALE)
    mae = np.abs(pred - ytrue).mean(axis=0)
    print("val MAE per output", " ".join("%s=%.2f" % (OUT_NAME[i], mae[i]) for i in range(OUT_DIM)))
    # export: little-endian float32, header + matmul weights
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "britune-v1.bin")
    with open(out, "wb") as f:
        f.write(b"BRTN")
        f.write(struct.pack("<IIIII", 1, IN_DIM, H1, H2, OUT_DIM))
        for arr in (mu, sd):
            f.write(np.asarray(arr, dtype="<f4").tobytes())
        for arr in (W1, b1, W2, b2, W3, b3):
            f.write(np.asarray(arr, dtype="<f4").tobytes())
        f.write(np.asarray(OUT_SCALE, dtype="<f4").tobytes())
    print("wrote", out, os.path.getsize(out), "bytes, params", W1.size + b1.size + W2.size + b2.size + W3.size + b3.size)
    meta = {"version": 1, "in": IN_DIM, "h1": H1, "h2": H2, "out": OUT_DIM, "outName": OUT_NAME,
            "outScale": OUT_SCALE, "styles": STYLES, "valMae": [round(float(x), 3) for x in mae]}
    with open(os.path.join(os.path.dirname(out), "britune-v1.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)

if __name__ == "__main__":
    train()
