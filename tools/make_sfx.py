# -*- coding: utf-8 -*-
"""Builds the sound-effect track for the release film (riser, whooshes, bells, chimes)."""
import numpy as np, wave, io

SR = 44100
LEN = 108.0
N = int(SR * LEN)
buf = np.zeros(N, dtype=np.float64)
rng = np.random.default_rng(7)


def add(sig, at):
    i = int(at * SR)
    j = min(N, i + len(sig))
    if i < N:
        buf[i:j] += sig[:j - i]


def lowpass(x, cut, sr=SR):
    y = np.empty_like(x)
    a = 0.0
    for i in range(len(x)):
        a += (x[i] - a) * cut
        y[i] = a
    return y


def riser(dur=1.4, peak=0.22):
    t = np.arange(int(dur * SR)) / SR
    env = (t / dur) ** 2
    f = 220 * (1 + 5.0 * (t / dur) ** 1.6)
    ph = 2 * np.pi * np.cumsum(f) / SR
    tone = 0.5 * np.sin(ph) + 0.25 * np.sin(2 * ph)
    noise = lowpass(rng.normal(0, 1, len(t)), 0.25) * 0.5
    return peak * env * (tone + noise)


def whoosh(dur=0.55, peak=0.20):
    t = np.arange(int(dur * SR)) / SR
    env = np.sin(np.pi * t / dur) ** 1.5
    noise = rng.normal(0, 1, len(t))
    swept = np.empty_like(noise)
    a = 0.0
    for i in range(len(noise)):
        cut = 0.02 + 0.35 * (t[i] / dur)          # opens up as it sweeps past
        a += (noise[i] - a) * cut
        swept[i] = a
    return peak * env * swept


def bell(peak=0.16, dur=0.9, base=880.0):
    t = np.arange(int(dur * SR)) / SR
    env = np.exp(-4.5 * t)
    return peak * env * (np.sin(2 * np.pi * base * t) + 0.45 * np.sin(2 * np.pi * base * 1.5 * t))


def chime(peak=0.18):
    dur = 2.4
    out = np.zeros(int(dur * SR))
    for k, f in enumerate([523.25, 659.25, 783.99]):
        start = int(k * 0.18 * SR)
        t = np.arange(int(1.2 * SR)) / SR
        seg = peak * np.exp(-3.5 * t) * np.sin(2 * np.pi * f * t)
        out[start:start + len(seg)] += seg
    return out


add(riser(), 0.15)
for t0 in (10.0, 25.0, 38.0, 46.0, 67.0):
    add(whoosh(), t0 - 0.32)
for t0 in (11.6, 26.6, 39.6, 47.6, 68.6):
    add(bell(), t0)
add(bell(0.16, 0.9, 1046.5), 83.2)
add(chime(), 95.4)

buf = np.clip(buf, -1.0, 1.0)
pcm = (buf * 32767.0).astype('<i2')
w = wave.open(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\trailer\sfx.wav', 'wb')
w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
w.writeframes(pcm.tobytes()); w.close()
print('sfx written, peak', round(float(np.abs(buf).max()), 3))