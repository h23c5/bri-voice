# -*- coding: utf-8 -*-
import io, re, glob, os
cands = glob.glob(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\device_out\*.vsqx') + glob.glob(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\fmt_out\*.vsqx')
print('candidates:', [os.path.basename(c) for c in cands])
for f in cands[:3]:
    s = io.open(f, encoding='utf-8', errors='ignore').read()
    head = s[:300].replace(chr(10), ' ')[:260]
    print('===', os.path.basename(f), 'len', len(s))
    print('  head:', head)
    m = re.search(r'<note>.*?</note>', s, re.S)
    if m:
        print('  note:', ' '.join(m.group(0).split())[:600])
    else:
        m2 = re.search(r'<note[^>]*>.*?</note[^>]*>', s, re.S)
        print('  note(v4):', (' '.join(m2.group(0).split())[:600]) if m2 else 'NONE')
    print()