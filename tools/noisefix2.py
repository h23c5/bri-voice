# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\voicebank_pack.py'
s = io.open(p, encoding='utf-8').read()
if 'noisiest window' in s:
    print('already patched')
else:
    old = "                    cur, ft = snd_analysis(pcm, harm_log)"
    if old not in s: raise SystemExit('anchor missing')
    s = s.replace(old, "                    # Analyse the noisiest window, not the whole record: a fricative sample often\n                    # opens with voicing, and averaging that over the record hides the noise (the\n                    # first version never found an /s/, whose energy sits at 5-8 kHz).\n                    cur, ft = None, None\n                    _win = 8192\n                    if pcm.size > _win + _win // 2:\n                        for _a in range(0, pcm.size - _win + 1, _win // 2):\n                            _c, _f = snd_analysis(pcm[_a:_a + _win], harm_log)\n                            if _c is None:\n                                continue\n                            if ft is None or _f[\"flatness\"] > ft[\"flatness\"]:\n                                cur, ft = _c, _f\n                    if cur is None:\n                        cur, ft = snd_analysis(pcm, harm_log)", 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('noisiest-window analysis applied')
import ast; ast.parse(io.open(p, encoding='utf-8').read()); print('syntax ok')