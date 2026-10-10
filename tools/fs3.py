# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\FrameVoice.java'
s = io.open(p, encoding='utf-8').read()
old = "            if (restart) {\n                dphi[k] = k * f0 / sampleRate;               // turns per sample\n                phase[k] = voiced ? 0.0 : rnd.nextDouble();\n            }\n            amp[k] = norm * raw[k] * level;\n            if (restart) smoothed[k] = 0.0;"
new = "            // The frequency step belongs to every frame: keeping it inside the restart branch meant the\n            // oscillators held the frequency of the first frame for the whole note, so vibrato and pitch\n            // bends detuned every harmonic against the frame data - heard as electronic noise, and the\n            // reason smoothing the amplitudes changed nothing. Only the phase may restart.\n            dphi[k] = k * f0 / sampleRate;                   // turns per sample\n            if (restart) phase[k] = voiced ? 0.0 : rnd.nextDouble();\n            amp[k] = norm * raw[k] * level;\n            if (restart) smoothed[k] = 0.0;"
if old not in s: raise SystemExit('anchor missing')
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('dphi now updated every frame')