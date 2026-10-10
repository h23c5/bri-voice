# -*- coding: utf-8 -*-
import math, struct, wave
sr, sec, f = 44100, 4.0, 1500.0
n = int(sr * sec)
with wave.open(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\tone1500.wav', 'wb') as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(sr)
    frames = bytearray()
    for i in range(n):
        v = int(0.35 * 32767 * math.sin(2 * math.pi * f * i / sr))
        frames += struct.pack('<hh', v, v)
    w.writeframes(bytes(frames))
print('wrote tone1500.wav', n, 'frames')