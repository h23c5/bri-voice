# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\ai2.py'
lines = io.open(p, encoding='utf-8').read().split(chr(10))
i = -1
for k, l in enumerate(lines):
    if 'new_l = ' in l:
        i = k
        break
if i < 0: raise SystemExit('new_l not found')
print('replacing lines', i, '..', i + 2)
print(repr(lines[i]))
print(repr(lines[i + 1]))
print(repr(lines[i + 2]))
fixed = [
    "    new_l = ('        aiCfg = com.dsh.v4tune.ai.AiConfig.load(this);' + chr(10)",
    "             + '        reloadPacksAsync(true);' + chr(10)",
    "             + '        applyExtServer();          // only starts anything when the user enabled it')",
]
lines[i:i + 3] = fixed
io.open(p, 'w', encoding='utf-8', newline='').write(chr(10).join(lines))
print('ai2.py patched')