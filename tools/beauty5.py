# -*- coding: utf-8 -*-
"""Rewrite the remaining list dialogs into the styled menu(..) call."""
import io, re
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
src = io.open(p, encoding='utf-8').read()

def balanced(s, start, open_ch, close_ch):
    depth = 0
    i = start
    while i < len(s):
        c = s[i]
        if c == open_ch: depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0: return i
        i += 1
    return -1

out = []
pos = 0
converted = 0
while True:
    i = src.find('new AlertDialog.Builder(this)', pos)
    if i < 0: break
    j = src.find('.show();', i)
    if j < 0: break
    seg = src[i:j]
    if '.setItems(' not in seg or '.setMessage(' in seg:
        out.append(src[pos:j + len('.show();')]); pos = j + len('.show();'); continue
    ti = seg.find('.setTitle(')
    if ti < 0:
        out.append(src[pos:j + len('.show();')]); pos = j + len('.show();'); continue
    tstart = ti + len('.setTitle(')
    tend = balanced(seg, tstart - 1, '(', ')')
    title = seg[tstart:tend]
    ii = seg.find('.setItems(')
    istart = ii + len('.setItems(')
    iend = balanced(seg, istart - 1, '(', ')')
    inner = seg[istart:iend]
    comma = inner.find(',')
    items = inner[:comma].strip()
    listener = inner[comma + 1:].strip()
    if 'OnClickListener' not in listener:
        out.append(src[pos:j + len('.show();')]); pos = j + len('.show();'); continue
    new = 'menu(' + title.strip() + ', null, ' + items + ', -1, ' + listener + ');'
    out.append(src[pos:i])
    out.append(new)
    pos = j + len('.show();')
    converted += 1
out.append(src[pos:])
res = ''.join(out)
io.open(p, 'w', encoding='utf-8', newline='').write(res)
print('converted', converted, 'list dialogs')