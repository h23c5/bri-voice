# -*- coding: utf-8 -*-
"""BriTune-Net v2: a stronger on-device tuning teacher.

What changed from v1 (and why):
  * PITCH UNITS WERE WRONG. v1 wrote PIT deltas in "cents" straight into the 14-bit PIT
    parameter, whose full scale is +/-2400 while PBS=2 semitones only spans +/-200 cents.
    A 45-unit delta therefore moved the pitch by ~1 cent: the network's pitch output was
    audibly dead. v2 converts every expressive pitch idea to real PIT units
    (cents * 8192 / (pbsRange * 100), pbsRange = 2) and widens the output scale.
  * PORTAMENTO IS A TIME, NOT A DELTA. The renderer maps POR to porMs = POR/127*110 ms,
    so the teacher now decides portamento length from leap size, note length and tempo,
    then converts that to parameter units.
  * VIBRATO DEPTH IS A TIMING/DEPTH DECISION. Renderer's VIB scales a ~22 cent natural
    vibrato by VIB/64, so the teacher asks for a depth in cents and converts it.
  * Beat position (from the real tick onsets) drives accents, which v1 could not see.
  * Bigger head: 68 -> 192 -> 96 -> 11 (~33k weights, ~130 KB). Still CPU inference of
    ~33k MACs per note, so a mid-range phone tunes a 1000-note part in well under a second.

No network, numpy only.
"""
import json, math, os, random, struct
import numpy as np

random.seed(20261123); np.random.seed(20261123)

N_FEAT = 12
CTX = 2
GLOBAL = 8
IN_DIM = CTX * 2 * N_FEAT + N_FEAT + GLOBAL
OUT_DIM = 11
STYLES = ["pop", "ballad", "rock", "electronic", "guofeng"]
OUT_NAME = ["dyn", "bre", "bri", "cle", "ope", "por", "vib", "pitLead", "pitTail", "vel", "gen"]
# physical units now: PIT outputs are 14-bit bend units, POR is parameter units
OUT_SCALE = [20.0, 20.0, 15.0, 15.0, 15.0, 70.0, 30.0, 2600.0, 1600.0, 15.0, 8.0]

PIT_UNIT = 8192.0 / 200.0     # cents -> 14-bit units at PBS = 2 semitones
VIB_UNIT = 64.0 / 22.0        # desired cents -> VIB parameter value
POR_UNIT = 127.0 / 110.0      # milliseconds -> POR parameter value


def gen_phrase(rng, tempo):
    """one phrase: 4..10 notes with a contour, rests, leaps and real tick onsets"""
    n = rng.randint(4, 10)
    start = rng.randint(48, 74)
    contour = rng.choice(["arch", "rise", "fall", "wave", "flat"])
    beat = 480 * 60.0 / tempo
    notes = []
    key = start
    tick = rng.choice([0, 0, 480, 960])
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
                      "tick": tick, "beat": beat})
        tick += dur
    return notes


def make_song(rng):
    tempo = float(rng.choice([72, 84, 96, 104, 112, 120, 128, 140, 152, 168]))
    style = rng.choice(STYLES)
    intensity = rng.uniform(0.35, 1.0)
    phrases = [gen_phrase(rng, tempo) for _ in range(rng.randint(1, 3))]
    return {"tempo": tempo, "style": style, "intensity": intensity, "phrases": phrases,
            "express": rng.uniform(0.85, 1.15)}


STYLE_IDX = {s: i for i, s in enumerate(STYLES)}


def style_profile(st):
    """per-style multipliers for the expressive axes; pop is the neutral baseline"""
    return {
        "pop":        dict(por=1.00, vib=1.00, dyn=1.00, bre=1.00, bri=1.00, cle=1.00, ope=1.00, pitch=1.00),
        "ballad":     dict(por=1.25, vib=1.30, dyn=1.20, bre=1.55, bri=0.85, cle=0.95, ope=0.90, pitch=1.05),
        "rock":       dict(por=0.80, vib=0.80, dyn=1.35, bre=0.65, bri=1.45, cle=1.15, ope=1.25, pitch=0.90),
        "electronic": dict(por=0.55, vib=0.70, dyn=0.85, bre=0.75, bri=1.35, cle=1.20, ope=1.20, pitch=0.70),
        "guofeng":    dict(por=1.35, vib=1.20, dyn=0.95, bre=1.15, bri=0.95, cle=0.90, ope=0.85, pitch=1.10),
    }[st]


def teacher(song, pi, ni):
    """strategy teacher: returns OUT_DIM *physical* deltas"""
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
    d = [0.0] * OUT_DIM

    beat_in_bar = (tick % 1920) / 480.0          # 0..3.x, quarter-note beats
    on_beat = abs(beat_in_bar - round(beat_in_bar)) < 0.06
    strong_beat = on_beat and int(round(beat_in_bar)) % 2 == 0
    off_beat = abs(beat_in_bar - (math.floor(beat_in_bar) + 0.5)) < 0.08

    # ---------------- dynamics
    dyn = 0.0
    dyn += 5.5 * math.sin(math.pi * pos)                       # phrase arch
    dyn += 7.5 if strong_beat else (3.0 if on_beat else 0.0)   # metric accent
    dyn -= 2.0 if off_beat else 0.0
    if first: dyn -= 3.0
    if last: dyn -= 4.5
    if dur >= 960: dyn -= min(5.0, (dur - 480) / 240.0 * 1.2)  # long notes settle
    if dur <= 240: dyn += 1.0                                   # short notes need presence
    if key >= 72: dyn -= (key - 72) * 1.0
    if key <= 48: dyn += 2.5
    if abs(leap) >= 5: dyn += 2.5
    d[0] = dyn * st["dyn"] * inten

    # ---------------- breathiness
    bre = 0.0
    if first: bre += 9.0
    if last: bre += 5.0
    if rest_after >= 240 and last: bre += 3.0
    if dur >= 960: bre += 3.5
    if key <= 48: bre += 4.0
    if song["style"] == "ballad": bre += 5.0
    if song["style"] in ("rock", "electronic"): bre -= 4.0
    d[1] = bre * st["bre"] * inten

    # ---------------- brightness / clarity / opening
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

    # ---------------- portamento: decide the glide time, then convert
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

    # ---------------- vibrato: ask for a depth in cents, convert to VIB units
    if dur >= 960:   depth = 30.0
    elif dur >= 720: depth = 22.0
    elif dur >= 480: depth = 12.0
    else:            depth = 0.0
    if last and dur >= 720: depth += 4.0
    depth *= st["vib"] * inten
    vib_param = 0.0 if depth <= 0.5 else depth * VIB_UNIT
    vib_param = max(0.0, min(127.0, vib_param))
    d[6] = vib_param - 64.0

    # ---------------- pitch: scoop into the note, release at the tail (14-bit units)
    lead_c = 0.0
    if ni == 0:
        lead_c = -14.0
    elif abs(leap) >= 1:
        # approaching a higher note from below (negative cents), a lower note from above
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

    # ---------------- velocity
    vel_d = 0.0
    if strong_beat: vel_d += 3.5
    elif on_beat:   vel_d += 1.5
    if first: vel_d += 3.0
    if last:  vel_d -= 3.5
    if abs(leap) >= 6 and leap > 0: vel_d += 2.0
    d[9] = vel_d * inten

    # ---------------- gender
    gen = 0.0
    if key >= 76 and dur >= 720: gen += 2.0
    if key <= 45: gen -= 1.0
    d[10] = gen * inten

    # pStyle-ish per-phrase humanisation: no two phrases are shaped identically
    for i in range(OUT_DIM):
        d[i] = d[i] * (1.0 + random.gauss(0, 0.04)) + random.gauss(0, 0.25 if i not in (7, 8) else 30.0)
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
    g = [song["tempo"] / 120.0 - 1.0] + [1.0 if song["style"] == s else 0.0 for s in STYLES] \
        + [song["intensity"], song["nnotes"] / 64.0]
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
    X, Y = build(12000)
    print("dataset", X.shape, Y.shape)
    mu = X.mean(axis=0); raw_sd = X.std(axis=0); sd = raw_sd + 1e-6
    print("dims with flat features:", int((raw_sd < 1e-3).sum()))
    Xn = (X - mu) / sd
    S = np.array(OUT_SCALE)
    Yn = Y / S
    rng = np.random.default_rng(7)
    idx = rng.permutation(len(Xn))
    Xn, Yn = Xn[idx], Yn[idx]
    n_val = len(Xn) // 12
    Xtr, Ytr = Xn[:-n_val], Yn[:-n_val]
    Xva, Yva = Xn[-n_val:], Yn[-n_val:]
    H1, H2 = 192, 96
    r2 = np.random.default_rng(11)
    W1 = r2.normal(0, math.sqrt(2.0 / IN_DIM), (IN_DIM, H1)); b1 = np.zeros(H1)
    W2 = r2.normal(0, math.sqrt(2.0 / H1), (H1, H2)); b2 = np.zeros(H2)
    W3 = r2.normal(0, math.sqrt(1.0 / H2), (H2, OUT_DIM)); b3 = np.zeros(OUT_DIM)
    params = [W1, b1, W2, b2, W3, b3]
    m = [np.zeros_like(p) for p in params]; v = [np.zeros_like(p) for p in params]
    lr0 = 2.0e-3; beta1, beta2, eps = 0.9, 0.999, 1e-8
    bs = 128; steps = 0; best = 1e9; best_params = None
    epochs = 34
    for epoch in range(epochs):
        lr = lr0 * (0.5 if epoch >= int(epochs * 0.6) else 1.0)
        order = rng.permutation(len(Xtr))
        tot = 0.0; nb = 0
        for s in range(0, len(Xtr) - bs, bs):
            b = order[s:s + bs]
            xb, yb = Xtr[b], Ytr[b]
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
        val = float(((h2 @ W3 + b3 - Yva) ** 2).mean())
        if epoch % 2 == 0 or epoch == epochs - 1:
            print("epoch %2d train %.5f val %.5f" % (epoch + 1, tot / max(1, nb), val))
        if val < best:
            best = val; best_params = [p.copy() for p in params]
    W1, b1, W2, b2, W3, b3 = best_params
    h1 = np.maximum(0, Xva @ W1 + b1); h2 = np.maximum(0, h1 @ W2 + b2)
    pred = (h2 @ W3 + b3) * S
    ytrue = Yva * S
    mae = np.abs(pred - ytrue).mean(axis=0)
    print("val MAE per output", " ".join("%s=%.2f" % (OUT_NAME[i], mae[i]) for i in range(OUT_DIM)))
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "britune-v2.bin")
    with open(out, "wb") as f:
        f.write(b"BRTN")
        f.write(struct.pack("<IIIII", 2, IN_DIM, H1, H2, OUT_DIM))
        for arr in (mu, sd):
            f.write(np.asarray(arr, dtype="<f4").tobytes())
        for arr in (W1, b1, W2, b2, W3, b3):
            f.write(np.asarray(arr, dtype="<f4").tobytes())
        f.write(np.asarray(OUT_SCALE, dtype="<f4").tobytes())
    nparam = W1.size + b1.size + W2.size + b2.size + W3.size + b3.size
    print("wrote", out, os.path.getsize(out), "bytes, params", nparam)
    meta = {"version": 2, "in": IN_DIM, "h1": H1, "h2": H2, "out": OUT_DIM, "outName": OUT_NAME,
            "outScale": OUT_SCALE, "styles": STYLES,
            "valMae": [round(float(x), 3) for x in mae], "params": int(nparam)}
    with open(os.path.join(os.path.dirname(out), "britune-v2.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    train()
