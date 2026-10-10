# -*- coding: utf-8 -*-
import io, re
L = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\i18n\L.java'
s = io.open(L, encoding='utf-8').read()
for k in ('dlg.singer.title','dlg.singer.sub','dlg.singer.stats','dlg.singer.legacy','dlg.singer.none','status.singer','menu.pack.title','menu.pack.sub'):
    m = re.search(r'\{"' + re.escape(k) + r'", "([^"]*)"', s)
    print(k, '->', (m.group(1)[:46] if m else 'MISSING'))
keys = re.findall(r'^\s*\{"([a-zA-Z0-9_.]+)",', s, re.M)
groups = {}
for k in keys:
    g = k.split('.')[0]
    groups[g] = groups.get(g, 0) + 1
print('TOTAL', len(keys))
print('GROUPS', sorted(groups.items()))