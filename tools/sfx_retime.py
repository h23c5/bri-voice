# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\make_sfx.py'
s = io.open(p, encoding='utf-8').read()
i = s.find('add(riser()')
j = s.find('buf = np.clip')
new = '\n'.join([
    'add(riser(), 0.15)',
    'for t0 in (10.0, 25.0, 38.0, 46.0, 67.0):',
    '    add(whoosh(), t0 - 0.32)',
    'for t0 in (11.6, 26.6, 39.6, 47.6, 68.6):',
    '    add(bell(), t0)',
    'add(bell(0.16, 0.9, 1046.5), 83.2)',
    'add(chime(), 95.4)',
    '',
    '',
])
s = s[:i] + new + s[j:]
s = s.replace('LEN = 128.0', 'LEN = 108.0')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('sfx script retimed')