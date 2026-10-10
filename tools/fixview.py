# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
p = root + r'\app\src\com\dsh\v4tune\ui\PianoRollView.java'
s = io.open(p, encoding='utf-8').read()
old = 'public void setSong(Song s) { song = s; selected = null; justCreated = null; hitNote = null; hitPart = null; barsDirty = true; zoomInited = false; vInited = false; pendingRange = false; scrollTick = 0f; zoomInited = applyDefaultZoom(); clampScroll(); if (listener != null) listener.onSelectionChanged(null); invalidate(); }'
if old not in s: raise SystemExit('setSong not found')
new = ('public void setSong(Song s) { setSong(s, false); }' + chr(10) + chr(10) +
       '    /**' + chr(10) +
       '     * Same, but keeps the scroll position and zoom. Use this for edits: resetting the view on' + chr(10) +
       '     * every refresh made the timeline jump back to the start after each drag.' + chr(10) +
       '     */' + chr(10) +
       '    public void setSongKeepView(Song s) { setSong(s, true); }' + chr(10) + chr(10) +
       '    private void setSong(Song s, boolean keepView) {' + chr(10) +
       '        song = s; selected = null; justCreated = null; hitNote = null; hitPart = null;' + chr(10) +
       '        barsDirty = true; pendingRange = false;' + chr(10) +
       '        if (!keepView) {' + chr(10) +
       '            zoomInited = false; vInited = false; scrollTick = 0f;' + chr(10) +
       '            zoomInited = applyDefaultZoom();' + chr(10) +
       '        }' + chr(10) +
       '        clampScroll();' + chr(10) +
       '        if (listener != null) listener.onSelectionChanged(null);' + chr(10) +
       '        invalidate();' + chr(10) +
       '    }')
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('setSongKeepView added')
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
t = io.open(m, encoding='utf-8').read()
if 'roll.setSongKeepView(song)' not in t:
    t = t.replace('        roll.setSong(song);' + chr(10) + '        roll.setTrackIndex(trackIndex);',
                  '        roll.setSongKeepView(song);      // keep the view still while editing' + chr(10) +
                  '        roll.setTrackIndex(trackIndex);', 1)
    io.open(m, 'w', encoding='utf-8', newline='').write(t)
    print('refreshAll keeps the view')