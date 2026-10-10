# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
lines = io.open(p, encoding='utf-8').read().split(chr(10))
bi = -1
for i, l in enumerate(lines):
    if 'private static final Map<String, String[]> BY_KEY;' in l:
        bi = i
        break
if bi < 0: raise SystemExit('BY_KEY not found')
# the table closing line is the last '    };' before BY_KEY
ti = -1
for i in range(bi, 0, -1):
    if lines[i].strip() == '};':
        ti = i
        break
if ti < 0: raise SystemExit('table end not found')
print('table ends at line', ti + 1)
# misplaced rows sit between the table end and BY_KEY
mis = [l for l in lines[ti + 1:bi] if l.strip().startswith('{"')]
print('misplaced rows:', len(mis))
if mis:
    rest = [l for l in lines[ti + 1:bi] if not l.strip().startswith('{"')]
    lines = lines[:ti + 1] + rest + lines[bi:]
    lines = lines[:ti] + mis + lines[ti:]
    io.open(p, 'w', encoding='utf-8', newline='').write(chr(10).join(lines))
    print('moved inside the table')
else:
    print('nothing to move')