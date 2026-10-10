# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\voicebank_pack.py'
s = io.open(p, encoding='utf-8').read()
old = '        if sym in formants or ci in taken or dd > MATCH_MAX_OCT:'
new = ('        if sym in formants or ci in taken or dd > MATCH_MAX_OCT:' + chr(10) +
       '            continue' + chr(10) +
       '        # Do not push a symbol onto a distant free cluster just to make it unique: that traded' + chr(10) +
       '        # identical curves for shifted formants. Accept the free cluster only when it is nearly' + chr(10) +
       '        # as good as the symbol\'s own nearest one.' + chr(10) +
       '        own = min(d0 for d0, s0, _ in pairs if s0 == sym)' + chr(10) +
       '        if dd > own * MATCH_FREE_SLACK:')
if 'MATCH_FREE_SLACK' in s:
    print('penalty already present')
else:
    if old not in s:
        raise SystemExit('anchor missing')
    s = s.replace(old, new, 1)
    s = s.replace('            continue' + chr(10) + '        formants[sym] = [int(round(v)) for v in clusters[ci]]',
                  '            continue' + chr(10) + '        formants[sym] = [int(round(v)) for v in clusters[ci]]', 1)
    s = s.replace('MATCH_MAX_OCT = 0.45', 'MATCH_MAX_OCT = 0.45' + chr(10) +
                  '# how much worse than its own nearest cluster a free cluster may be (1.0 = no slack)' + chr(10) +
                  'MATCH_FREE_SLACK = 1.12', 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('distance penalty added')
import ast
ast.parse(io.open(p, encoding='utf-8').read())
print('syntax ok')