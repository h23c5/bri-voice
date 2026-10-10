# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
p = root + r'\app\src\com\dsh\v4tune\ui\PianoRollView.java'
s = io.open(p, encoding='utf-8').read()
meth = "    /**\n     * setSong() resets the scroll position and the zoom - right when a project is loaded, wrong for\n     * an edit. This variant puts the view back afterwards, so dragging a clip or a note no longer\n     * makes the timeline jump to the start.\n     */\n    public void setSongKeepView(Song s) {\n        float keepScroll = scrollTick, keepPx = pxPerTick, keepTop = topKey;\n        setSong(s);\n        scrollTick = keepScroll;\n        pxPerTick = keepPx;\n        topKey = keepTop;\n        zoomInited = true;\n        vInited = true;\n        clampScroll();\n        invalidate();\n    }\n"
anchor = '    public void setSong(Song s) {'
if 'setSongKeepView' in s:
    print('already present')
elif anchor in s:
    s = s.replace(anchor, meth + anchor, 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('setSongKeepView inserted')
else:
    raise SystemExit('anchor missing')
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
t = io.open(m, encoding='utf-8').read()
if 'roll.setSongKeepView(song)' not in t:
    if '        roll.setSong(song);' not in t: raise SystemExit('refreshAll anchor missing')
    t = t.replace('        roll.setSong(song);', '        roll.setSongKeepView(song);      // keep the view still while editing', 1)
    io.open(m, 'w', encoding='utf-8', newline='').write(t)
    print('refreshAll keeps the view')