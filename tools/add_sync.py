# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\ui\PianoRollView.java'
s = io.open(p, encoding='utf-8').read()
if 'private void syncPartDur(' in s:
    print('already present')
else:
    i = s.find('void deleteSelected()')
    if i < 0:
        raise SystemExit('anchor not found')
    j = s.rfind('\n', 0, i) + 1
    helper = ('    /** the part duration must cover its notes: the renderer sizes its buffer from it */\n'
              '    private void syncPartDur(Part p) {\n'
              '        if (p == null) return;\n'
              '        long end = p.pos + 480L;\n'
              '        for (Note n : p.notes) end = Math.max(end, n.pos + n.dur);\n'
              '        if (end - p.pos > p.dur) p.dur = end - p.pos;\n'
              '    }\n\n')
    s = s[:j] + helper + s[j:]
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('syncPartDur added')