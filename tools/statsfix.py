# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\rebuild_frames.py'
s = io.open(p, encoding='utf-8').read()
if 'harmonicBands' in s:
    print('already fixed')
else:
    old = "    voice['harmonics']['phonemes'] = phonemes"
    new = "\n".join([
        "    voice['harmonics']['phonemes'] = phonemes",
        "    # the rebuilt bands carry mean curves only: a frame sequence is the consecutive frames of one",
        "    # recorded take, and frames gathered per phoneme from different units cannot be made into one.",
        "    # The app falls back to the mean curve where seqN < 2, so this is graceful, but the sequence",
        "    # engine has no trajectory for the rebuilt symbols.",
        "    bands_total = sum(len(v) for v in phonemes.values())",
        "    if 'stats' in voice:",
        "        voice['stats']['harmonicBands'] = int(bands_total)",
        "        voice['stats']['harmonicPhonemes'] = int(sum(1 for v in phonemes.values() if v))",
        "    print('stats updated: harmonicBands=%d' % bands_total)",
    ])
    if old not in s:
        raise SystemExit('anchor missing')
    s = s.replace(old, new, 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('stats fix applied')
import ast; ast.parse(io.open(p, encoding='utf-8').read()); print('syntax ok')