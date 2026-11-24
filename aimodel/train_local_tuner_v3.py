# -*- coding: utf-8 -*-
"""BriTune-Net v3: beat-aware teacher distillation with note-internal shape.

What changed from v2 (and why):
  * The v2 teacher used beat position (accents), but the network could not see the tick at
    all: 12 features were note-only. v3 adds four beat/context features per slot
    (beat position in bar, strong-beat flag, off-beat flag, repeated-pitch flag) => 16 x 5
    context slots + 8 globals = 88 inputs. This removes a structural ceiling: the same
    visible note had different targets depending on a beat the net was blind to.
  * v2's validation split was random per row, so two notes of the same generated phrase
    landed on both sides and the reported MAE was optimistic. v3 splits by SONG and
    normalises with train statistics only.
  * v2 wrote one DYN point per note, so DYN could only step from note to note. v3 predicts
    a note-internal shape (end minus start, output 11) and the app writes a second point.
  * v2 could not ask for a delayed vibrato bloom: the only VIB control was depth. v3
    predicts an onset fraction (output 12); the app writes VIB=0 at the note start and the
    depth at that onset, so long notes bloom late the way a singer's do.
  * Wider head: 88 -> 256 -> 128 -> 13 (~57k weights, ~225 KB). Still one 57k-MAC pass
    per note, well inside a mid-range phone's budget.

Exports britune-v3.bin / britune-v3.json, and evaluates v2 vs v3 on the same song-level
validation songs. No network, numpy only.
"""
import json, math, os, random, struct
import numpy as np

random.seed(20261123); np.random.seed(20261123)

N_FEAT = 16
CTX = 2
GLOBAL = 8
IN_DIM = CTX * 2 * N_FEAT + N_FEAT + GLOBAL
OUT_DIM = 13
STYLES = ["pop", "ballad", "rock", "electronic", "guofeng"]
OUT_NAME = ["dyn", "bre", "bri", "cle", "ope", "por", "vib", "pitLead", "pitTail",
            "vel", "gen", "dynShape", "vibOnset"]
OUT_SCALE = [20.0, 20.0, 15.0, 15.0, 15.0, 70.0, 30.0, 2600.0, 1600.0, 15.0, 8.0, 8.0, 0.55]

PIT_UNIT = 8192.0 / 200.0
VIB_UNIT = 64.0 / 22.0
POR_UNIT = 127.0 / 110.0


def gen_phrase(rng, tempo):
    n = rng.randint(4, 10)
    start = rng.randint(48, 74)
    contour = rng.choice(["arch", "rise", "fall", "wave", "flat"])
    notes = []
    key = start
    tick = rng.choice([0, 0, 480, 960, 1440])
    for i in range(n):
        t = i / max(1, n - 1)
        if contour == "arch":   target = start + int(round(9 * math.sin(math.pi * t)))
        elif contour == "rise": target = start + int(round(8 * t))
        elif contour == "fall": target = start - int(round(8 * t))
        elif contour == "wave": target = start + int(round(6 * math.sin(2 * math.pi * t)))
        else:                   target = start
        if rng.random() < 0.18:
            target += rng.choice([-1, 1]) * rng.randint(5, 9)
        key = max(36, min(88, target + rng.randint(-1, 1)))
        if i == n - 1 and rng.random() < 0.55: dur = rng.choice([960, 1200, 1440])
        elif i == n - 2 and rng.random() < 0.2: dur = rng.choice([720, 960])
        else: dur = rng.choice([240, 240, 480, 480, 720])
        vel = int(np.clip(rng.gauss(78, 9), 30, 118))
        rest = 0 if i == 0 else (rng.choice([0, 0, 0, 120, 240]) if rng.random() < 0.3 else 0)
        lyr = rng.choice([1, 1, 1, 1, 2, 2, 3, 4])
        tick += rest
        notes.append({"key": key, "dur": dur, "vel": vel, "rest": rest, "lyr": lyr,
                      "tick": tick, "beat": 480 * 60.0 / tempo})
        tick += dur
    return notes


def make_song(rng):
    tempo = float(rng.choice([72, 84, 96, 104, 112, 120, 128, 140, 152, 168]))
    style = rng.choice(STYLES)
    intensity = rng.uniform(0.35, 1.0)
    phrases = [gen_phrase(rng, tempo) for _ in range(rng.randint(1, 3))]
    return {"tempo": tempo, "style": style, "intensity": intensity, "phrases": phrases,
            "express": rng.uniform(0.85, 1.15),
            "nnotes": sum(len(p) for p in phrases)}


def style_profile(st):
    return {
        "pop":        dict(por=1.00, vib=1.00, dyn=1.00, bre=1.00, bri=1.00, cle=1.00, ope=1.00, pitch=1.00),
        "ballad":     dict(por=1.25, vib=1.30, dyn=1.20, bre=1.55, bri=0.85, cle=0.95, ope=0.90, pitch=1.05),
        "rock":       dict(por=0.80, vib=0.80, dyn=1.35, bre=0.65, bri=1.45, cle=1.15, ope=1.25, pitch=0.90),
        "electronic": dict(por=0.55, vib=0.70, dyn=0.85, bre=0.75, bri=1.35, cle=1.20, ope=1.20, pitch=0.70),
        "guofeng":    dict(por=1.35, vib=1.20, dyn=0.95, bre=1.15, bri=0.95, cle=0.90, ope=0.85, pitch=1.10),
    }[st]


def beat_info(tick):
    beat_in_bar = (tick % 1920) / 480.0
    on_beat = abs(beat_in_bar - round(beat_in_bar)) < 0.06
    strong = on_beat and int(round(beat_in_bar)) % 2 == 0
    off = abs(beat_in_bar - (math.floor(beat_in_bar) + 0.5)) < 0.08
    return beat_in_bar, on_beat, strong, off


def teacher(song, pi, ni):
    ph = song["phrases"][pi]; n = ph[ni]
    key, dur, vel, tick = n["key"], n["dur"], n["vel"], n["tick"]
    first, last = ni == 0, ni == len(ph) - 1
    pos = ni / max(1, len(ph) - 1)
    rest_after = ph[ni + 1]["rest"] if ni + 1 < len(ph) else 240
    prev_key = ph[ni - 1]["key"] if ni > 0 else key
    next_key = ph[ni + 1]["key"] if ni + 1 < len(ph) else key
    leap = key - prev_key
    inten = song["intensity"] * song["express"]
    st = style_profile(song["style"])
    beat_in_bar, on_beat, strong_beat, off_beat = beat_info(tick)
    d = [0.0] * OUT_DIM

    dyn = 0.0
    dyn += 5.5 * math.sin(math.pi * pos)
    dyn += 7.5 if strong_beat else (3.0 if on_beat else 0.0)
    dyn -= 2.0 if off_beat else 0.0
    if first: dyn -= 3.0
    if last: dyn -= 4.5
    if dur >= 960: dyn -= min(5.0, (dur - 480) / 240.0 * 1.2)
    if dur <= 240: dyn += 1.0
    if key >= 72: dyn -= (key - 72) * 1.0
    if key <= 48: dyn += 2.5
    if abs(leap) >= 5: dyn += 2.5
    d[0] = dyn * st["dyn"] * inten

    bre = 0.0
    if first: bre += 9.0
    if last: bre += 5.0
    if rest_after >= 240 and last: bre += 3.0
    if dur >= 960: bre += 3.5
    if key <= 48: bre += 4.0
    if song["style"] == "ballad": bre += 5.0
    if song["style"] in ("rock", "electronic"): bre -= 4.0
    d[1] = bre * st["bre"] * inten

    bri = 0.0
    if key >= 72: bri += (key - 72) * 0.8
    if key <= 48: bri -= 3.0
    if abs(leap) >= 6: bri += 2.0
    if dur <= 240: bri += 1.5
    d[2] = bri * st["bri"] * inten

    cle = 0.0
    if first: cle += 3.5
    if dur <= 240: cle += 2.5
    if strong_beat: cle += 2.0
    if n["lyr"] >= 3: cle += 1.5
    d[3] = cle * st["cle"] * inten

    ope = 0.0
    if key >= 72: ope += 2.5
    if abs(leap) >= 5: ope += 2.0
    if dur >= 960: ope += 1.5
    d[4] = ope * st["ope"] * inten

    por_ms = 10.0
    if ni > 0:
        semis = abs(leap)
        if semis >= 1:
            por_ms = 22.0 + 17.0 * semis
            if dur <= 240: por_ms *= 0.55
            elif dur >= 960: por_ms *= 1.20
            por_ms *= 1.20 - 0.35 * (song["tempo"] - 120.0) / 120.0
        else:
            por_ms = 8.0
    por_ms *= st["por"]
    por_ms = max(0.0, min(110.0, por_ms * (0.8 + 0.4 * inten)))
    d[5] = por_ms * POR_UNIT - 64.0

    if dur >= 960:   depth = 30.0
    elif dur >= 720: depth = 22.0
    elif dur >= 480: depth = 12.0
    else:            depth = 0.0
    if last and dur >= 720: depth += 4.0
    depth *= st["vib"] * inten
    vib_param = 0.0 if depth <= 0.5 else depth * VIB_UNIT
    vib_param = max(0.0, min(127.0, vib_param))
    d[6] = vib_param - 64.0

    lead_c = 0.0
    if ni == 0:
        lead_c = -14.0
    elif abs(leap) >= 1:
        direction = -1.0 if leap > 0 else 1.0
        lead_c = direction * min(70.0, 16.0 + 8.0 * abs(leap))
        lead_c *= 0.75 + 0.4 * inten
    else:
        lead_c = -5.0
    lead_c *= st["pitch"]
    tail_c = 0.0
    if last and dur >= 480: tail_c = -28.0
    elif dur >= 960:        tail_c = -16.0
    if last and next_key == key: tail_c -= 6.0
    tail_c *= st["pitch"] * (0.7 + 0.5 * inten)
    d[7] = lead_c * PIT_UNIT
    d[8] = tail_c * PIT_UNIT

    vel_d = 0.0
    if strong_beat: vel_d += 3.5
    elif on_beat:   vel_d += 1.5
    if first: vel_d += 3.0
    if last:  vel_d -= 3.5
    if abs(leap) >= 6 and leap > 0: vel_d += 2.0
    d[9] = vel_d * inten

    gen = 0.0
    if key >= 76 and dur >= 720: gen += 2.0
    if key <= 45: gen -= 1.0
    d[10] = gen * inten

    # note-internal dynamics: an accent leans in at the start and releases at the tail, long
    # notes settle, phrase ends breathe out. Second DYN point = start + shape.
    shape = 0.0
    if last: shape -= 4.0
    if dur >= 960: shape -= 2.5
    if strong_beat: shape -= 1.5
    if first: shape += 1.0
    if dur <= 240: shape += 1.5
    d[11] = shape * st["dyn"] * inten

    # delayed vibrato bloom: seconds -> fraction of the note's own duration
    sec_per_tick = 60.0 / (song["tempo"] * 480.0)
    dur_s = max(1e-3, dur * sec_per_tick)
    if depth <= 0.5:
        onset = 0.0
    elif dur >= 960: onset = 0.14
    elif dur >= 720: onset = 0.11
    else:            onset = 0.06
    onset *= {"ballad": 1.25, "rock": 0.70, "electronic": 0.80, "guofeng": 1.15}.get(song["style"], 1.0)
    d[12] = max(0.0, min(0.55, onset / dur_s))

    for i in range(OUT_DIM):
        d[i] = d[i] * (1.0 + random.gauss(0, 0.04)) + random.gauss(0, 0.25 if i not in (7, 8) else 30.0)
    return [float(np.clip(d[i], -OUT_SCALE[i], OUT_SCALE[i])) for i in range(OUT_DIM)]


def note_feat(song, pi, ni):
    ph = song["phrases"][pi]; n = ph[ni]
    key, dur, vel, tick = n["key"], n["dur"], n["vel"], n["tick"]
    first, last = ni == 0, ni == len(ph) - 1
    pos = ni / max(1, len(ph) - 1)
    rest_before = min(1.0, n["rest"] / 480.0)
    rest_after = min(1.0, (ph[ni + 1]["rest"] if ni + 1 < len(ph) else 240) / 480.0)
    prev_key = ph[ni - 1]["key"] if ni > 0 else key
    next_key = ph[ni + 1]["key"] if ni + 1 < len(ph) else key
    beat_in_bar, _on, strong, off = beat_info(tick)
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
        beat_in_bar / 4.0,
        1.0 if strong else 0.0,
        1.0 if off else 0.0,
        1.0 if key == prev_key else 0.0,
    ]


def sample(song, pi, ni, n_feat=N_FEAT):
    ph = song["phrases"][pi]
    out = []
    for off in range(-CTX, CTX + 1):
        j = max(0, min(len(ph) - 1, ni + off))
        f = note_feat(song, pi, j)
        out.extend(f[:n_feat] if n_feat < len(f) else f)
    g = [song["tempo"] / 120.0 - 1.0] + [1.0 if song["style"] == s else 0.0 for s in STYLES] \
        + [song["intensity"], song["nnotes"] / 64.0]
    return out + g


def build_song(song):
    X, Y = [], []
    for pi, ph in enumerate(song["phrases"]):
        for ni in range(len(ph)):
            X.append(sample(song, pi, ni))
            Y.append(teacher(song, pi, ni))
    return X, Y


def build(n_songs):
    songs = [make_song(random) for _ in range(n_songs)]
    tr_songs, va_songs = [], []
    idx = np.random.default_rng(7).permutation(len(songs))
    n_val = len(songs) // 12
    for k, i in enumerate(idx):
        (va_songs if k < n_val else tr_songs).append(songs[i])
    Xtr, Ytr, Xva, Yva, Xtr_old, Xva_old = [], [], [], [], [], []
    for s in tr_songs:
        a, b = build_song(s); Xtr += a; Ytr += b
        # v2's 68-dim layout for an ablation that sees exactly the same training songs
        for pi, ph in enumerate(s["phrases"]):
            for ni in range(len(ph)):
                Xtr_old.append(sample(s, pi, ni, 12))
    for s in va_songs:
        a, b = build_song(s); Xva += a; Yva += b
        # v2's 68-dim layout for an honest comparison on the exact same held-out notes
        for pi, ph in enumerate(s["phrases"]):
            for ni in range(len(ph)):
                Xva_old.append(sample(s, pi, ni, 12))
    return (np.array(Xtr, np.float64), np.array(Ytr, np.float64),
            np.array(Xva, np.float64), np.array(Yva, np.float64),
            np.array(Xtr_old, np.float64), np.array(Xva_old, np.float64))


def load_v2(path):
    with open(path, "rb") as f:
        data = f.read()
    assert data[:4] == b"BRTN"
    ver, ind, h1, h2, outd = struct.unpack("<IIIII", data[4:24])
    off = 24
    def take(n):
        nonlocal off
        a = np.frombuffer(data, dtype="<f4", count=n, offset=off).astype(np.float64); off += 4 * n
        return a
    mu = take(ind); sd = take(ind)
    W1 = take(ind * h1).reshape(ind, h1); b1 = take(h1)
    W2 = take(h1 * h2).reshape(h1, h2); b2 = take(h2)
    W3 = take(h2 * outd).reshape(h2, outd); b3 = take(outd)
    sc = take(outd)
    return dict(mu=mu, sd=sd, W1=W1, b1=b1, W2=W2, b2=b2, W3=W3, b3=b3, sc=sc)


def v2_forward(m, x):
    z = (x - m["mu"]) / (m["sd"] + 1e-6)
    z[:, m["sd"] < 1e-3] = 0.0
    h = np.maximum(0, z @ m["W1"] + m["b1"])
    h = np.maximum(0, h @ m["W2"] + m["b2"])
    return (h @ m["W3"] + m["b3"]) * m["sc"]


def train():
    print("building song-level dataset ...", flush=True)
    Xtr, Ytr, Xva, Yva, Xtr_old, Xva_old = build(12000)
    print("train", Xtr.shape, Ytr.shape, "val", Xva.shape, Yva.shape, flush=True)
    mu = Xtr.mean(axis=0); raw_sd = Xtr.std(axis=0); sd = raw_sd + 1e-6
    print("dims with flat features:", int((raw_sd < 1e-3).sum()), flush=True)
    Xtr = (Xtr - mu) / sd; Xva = (Xva - mu) / sd
    S = np.array(OUT_SCALE)
    Ytr_n, Yva_n = Ytr / S, Yva / S
    H1, H2 = 256, 128
    r2 = np.random.default_rng(11)
    W1 = r2.normal(0, math.sqrt(2.0 / IN_DIM), (IN_DIM, H1)); b1 = np.zeros(H1)
    W2 = r2.normal(0, math.sqrt(2.0 / H1), (H1, H2)); b2 = np.zeros(H2)
    W3 = r2.normal(0, math.sqrt(1.0 / H2), (H2, OUT_DIM)); b3 = np.zeros(OUT_DIM)
    params = [W1, b1, W2, b2, W3, b3]
    m = [np.zeros_like(p) for p in params]; v = [np.zeros_like(p) for p in params]
    lr0 = 2.0e-3; beta1, beta2, eps = 0.9, 0.999, 1e-8
    rng = np.random.default_rng(7)
    bs = 128; steps = 0; best = 1e9; best_params = None
    epochs = 44
    for epoch in range(epochs):
        lr = lr0 * (0.5 if epoch >= int(epochs * 0.6) else 1.0)
        order = rng.permutation(len(Xtr))
        tot = 0.0; nb = 0
        for s in range(0, len(Xtr) - bs, bs):
            b = order[s:s + bs]
            xb, yb = Xtr[b], Ytr_n[b]
            h1 = np.maximum(0, xb @ W1 + b1)
            h2 = np.maximum(0, h1 @ W2 + b2)
            out = h2 @ W3 + b3
            diff = out - yb
            tot += float((diff ** 2).mean()); nb += 1
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
        val = float(((h2 @ W3 + b3 - Yva_n) ** 2).mean())
        if epoch % 2 == 0 or epoch == epochs - 1:
            print("epoch %2d train %.5f val %.5f" % (epoch + 1, tot / max(1, nb), val), flush=True)
        if val < best:
            best = val; best_params = [p.copy() for p in params]
    W1, b1, W2, b2, W3, b3 = best_params
    h1 = np.maximum(0, Xva @ W1 + b1); h2 = np.maximum(0, h1 @ W2 + b2)
    pred = (h2 @ W3 + b3) * S
    mae = np.abs(pred - Yva).mean(axis=0)
    print("v3 val MAE per output", " ".join("%s=%.2f" % (OUT_NAME[i], mae[i]) for i in range(OUT_DIM)), flush=True)

    # honest apples-to-apples: same held-out songs and the same teacher targets, v2 weights on
    # v2's 68-dim layout. v3 is not allowed to look better just because the split changed.
    v2path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "britune-v2.bin")
    if os.path.exists(v2path):
        m2 = load_v2(v2path)
        pred2 = v2_forward(m2, Xva_old)
        mae2 = np.abs(pred2 - Yva[:, :11]).mean(axis=0)
        print("v2 val MAE (same songs)", " ".join("%s=%.2f" % (OUT_NAME[i], mae2[i]) for i in range(11)), flush=True)
        print("v3 minus v2", " ".join("%s=%+.2f" % (OUT_NAME[i], mae[i] - mae2[i]) for i in range(11)), flush=True)

    # fair ablation: same songs, same teacher, same epochs, but v2's 68-dim features and its
    # 68-192-96-11 shape. The shipped v2 weights were fit with a row split, so they have already
    # seen notes from these songs; this baseline has not, which makes the delta trustworthy.
    H1B, H2B = 192, 96
    mu_b = Xtr_old.mean(axis=0); sd_b = Xtr_old.std(axis=0) + 1e-6
    Xtr_b = (Xtr_old - mu_b) / sd_b
    Xva_b = (Xva_old - mu_b) / sd_b
    Ytr_bn = Ytr[:, :11] / S[:11]
    Yva_bn = Yva[:, :11] / S[:11]
    r3 = np.random.default_rng(13)
    W1b = r3.normal(0, math.sqrt(2.0 / 68), (68, H1B)); b1b = np.zeros(H1B)
    W2b = r3.normal(0, math.sqrt(2.0 / H1B), (H1B, H2B)); b2b = np.zeros(H2B)
    W3b = r3.normal(0, math.sqrt(1.0 / H2B), (H2B, 11)); b3b = np.zeros(11)
    params_b = [W1b, b1b, W2b, b2b, W3b, b3b]
    mb = [np.zeros_like(q) for q in params_b]; vb = [np.zeros_like(q) for q in params_b]
    rng_b = np.random.default_rng(17); steps_b = 0; best_b = 1e9; best_bp = None
    for epoch in range(44):
        lr = 2.0e-3 * (0.5 if epoch >= int(44 * 0.6) else 1.0)
        order = rng_b.permutation(len(Xtr_b))
        for s in range(0, len(Xtr_b) - 128, 128):
            bi = order[s:s + 128]
            xb, yb = Xtr_b[bi], Ytr_bn[bi]
            h1 = np.maximum(0, xb @ W1b + b1b)
            h2 = np.maximum(0, h1 @ W2b + b2b)
            outb = h2 @ W3b + b3b
            g = 2 * (outb - yb) / outb.size
            gW3 = h2.T @ g; gb3 = g.sum(axis=0)
            dh2 = (g @ W3b.T) * (h2 > 0)
            gW2 = h1.T @ dh2; gb2 = dh2.sum(axis=0)
            dh1 = (dh2 @ W2b.T) * (h1 > 0)
            gW1 = xb.T @ dh1; gb1 = dh1.sum(axis=0)
            grads_b = [gW1, gb1, gW2, gb2, gW3, gb3]
            steps_b += 1
            for i in range(6):
                mb[i] = 0.9 * mb[i] + 0.1 * grads_b[i]
                vb[i] = 0.999 * vb[i] + 0.001 * grads_b[i] ** 2
                mh = mb[i] / (1 - 0.9 ** steps_b); vh = vb[i] / (1 - 0.999 ** steps_b)
                params_b[i] -= lr * mh / (np.sqrt(vh) + 1e-8)
        h1b = np.maximum(0, Xva_b @ W1b + b1b); h2b = np.maximum(0, h1b @ W2b + b2b)
        valb = float(((h2b @ W3b + b3b - Yva_bn) ** 2).mean())
        if valb < best_b:
            best_b = valb; best_bp = [q.copy() for q in params_b]
    W1b, b1b, W2b, b2b, W3b, b3b = best_bp
    h1b = np.maximum(0, Xva_b @ W1b + b1b); h2b = np.maximum(0, h1b @ W2b + b2b)
    predb = (h2b @ W3b + b3b) * S[:11]
    maeb = np.abs(predb - Yva[:, :11]).mean(axis=0)
    print("v2-songsplit baseline MAE", " ".join("%s=%.2f" % (OUT_NAME[i], maeb[i]) for i in range(11)), flush=True)
    print("v3 minus baseline       ", " ".join("%s=%+.2f" % (OUT_NAME[i], mae[i] - maeb[i]) for i in range(11)), flush=True)
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "britune-v2-songsplit.bin"), "wb") as f:
        f.write(b"BRTN")
        f.write(struct.pack("<IIIII", 2, 68, H1B, H2B, 11))
        for arr in (mu_b, sd_b, W1b, b1b, W2b, b2b, W3b, b3b):
            f.write(np.asarray(arr, dtype="<f4").tobytes())
        f.write(np.asarray(OUT_SCALE[:11], dtype="<f4").tobytes())

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "britune-v3.bin")
    with open(out, "wb") as f:
        f.write(b"BRTN")
        f.write(struct.pack("<IIIII", 3, IN_DIM, H1, H2, OUT_DIM))
        for arr in (mu, sd):
            f.write(np.asarray(arr, dtype="<f4").tobytes())
        for arr in (W1, b1, W2, b2, W3, b3):
            f.write(np.asarray(arr, dtype="<f4").tobytes())
        f.write(np.asarray(OUT_SCALE, dtype="<f4").tobytes())
    nparam = W1.size + b1.size + W2.size + b2.size + W3.size + b3.size
    print("wrote", out, os.path.getsize(out), "bytes, params", nparam, flush=True)
    meta = {"version": 3, "in": IN_DIM, "h1": H1, "h2": H2, "out": OUT_DIM, "outName": OUT_NAME,
            "outScale": OUT_SCALE, "styles": STYLES,
            "valMae": [round(float(x), 3) for x in mae], "params": int(nparam),
            "split": "song-level, train-only normalisation"}
    with open(os.path.join(os.path.dirname(out), "britune-v3.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    train()
