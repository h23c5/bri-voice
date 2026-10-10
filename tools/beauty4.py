# -*- coding: utf-8 -*-
import io, re
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
T = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\i18n_selftest\I18nSelftest.java'
s = io.open(L, encoding='utf-8').read()
keys = re.findall(r'^\s*\{"([a-zA-Z0-9_.]+)",', s, re.M)
def group(k):
    p = k.split('.')
    if len(p) >= 3 and p[0] in ('about', 'pack') and p[1] in ('line', 'info'):
        return p[0] + '.' + p[1]
    return p[0]
groups = {}
for k in keys:
    g = group(k)
    groups[g] = groups.get(g, 0) + 1
t = io.open(T, encoding='utf-8').read()
t = re.sub(r'private static final int EXPECTED_KEYS = \d+;', 'private static final int EXPECTED_KEYS = ' + str(len(keys)) + ';', t)
def repl(m):
    name = m.group(1)
    return 'want.put("' + name + '", Integer.valueOf(' + str(groups.get(name, 0)) + '));'
t = re.sub(r'want\.put\("([a-z.]+)", Integer\.valueOf\(\d+\)\);', repl, t)
io.open(T, 'w', encoding='utf-8', newline='').write(t)
print('keys', len(keys))
print('groups', sorted(groups.items()))