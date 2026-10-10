# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
p = root + r'\app\src\com\dsh\v4tune\ui\PianoRollView.java'
s = io.open(p, encoding='utf-8').read()
blk = io.open(root + r'\tools\lane_fields.txt', encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)

rep('    // ------------------------------------------------------------------- touch', blk + '    // ------------------------------------------------------------------- touch', 'touch section')
if 'MODE_CLIP' not in s:
    rep('MODE_LINE = 5, MODE_DRAGPT = 6;', 'MODE_LINE = 5, MODE_DRAGPT = 6, MODE_CLIP = 7;', 'modes')
if 'clipH = dp(' not in s:
    rep('keyW = dp(64f);', 'keyW = dp(64f); clipH = dp(26f);', 'lane height')
rep('        if (selSeen) Theme.rr(c, selL - dp(1f), selT - dp(1f), selR + dp(1f), selB + dp(1f), dp(4f), selP);',
    '        if (!clips.isEmpty()) drawClipLane(c, gl, gr, gb);' + chr(10) +
    '        if (selSeen) Theme.rr(c, selL - dp(1f), selT - dp(1f), selR + dp(1f), selB + dp(1f), dp(4f), selP);', 'draw lane')
rep('                if (x < keyW && y > rulerH) { mode = MODE_KEYS; auditionKeyAt(y);',
    ('                if (!clips.isEmpty() && y >= clipTop() && x > keyW) {' + chr(10) +
     '                    com.dsh.v4tune.model.AudioClip hit = clipAt(x);' + chr(10) +
     '                    selClip = hit;' + chr(10) +
     '                    if (hit != null) {' + chr(10) +
     '                        dragClip = hit; dragOrigTick = hit.startTick;' + chr(10) +
     '                        dragGrabTick = (long) tickAtX(x) - hit.startTick;' + chr(10) +
     '                        mode = MODE_CLIP;' + chr(10) +
     '                        if (listener != null) listener.onClipDragStart();' + chr(10) +
     '                    } else {' + chr(10) +
     '                        mode = MODE_SCROLL;' + chr(10) +
     '                    }' + chr(10) +
     '                    invalidate();' + chr(10) +
     '                    return true;' + chr(10) +
     '                }' + chr(10) +
     '                if (x < keyW && y > rulerH) { mode = MODE_KEYS; auditionKeyAt(y);'), 'lane down')
rep('                if (mode == MODE_SCROLL) { scrollTick = downScroll - (x - downX) / pxPerTick;',
    ('                if (mode == MODE_CLIP && dragClip != null) {' + chr(10) +
     '                    dragClip.startTick = Math.max(0L, snap((long) tickAtX(x) - dragGrabTick));' + chr(10) +
     '                    changed = true;' + chr(10) +
     '                    invalidate();' + chr(10) +
     '                    return true;' + chr(10) +
     '                }' + chr(10) +
     '                if (mode == MODE_SCROLL) { scrollTick = downScroll - (x - downX) / pxPerTick;'), 'lane move')
rep('            case MotionEvent.ACTION_UP: {' + chr(10) + '                cancelPress();',
    ('            case MotionEvent.ACTION_UP: {' + chr(10) +
     '                cancelPress();' + chr(10) +
     '                if (mode == MODE_CLIP) {' + chr(10) +
     '                    com.dsh.v4tune.model.AudioClip c2 = dragClip;' + chr(10) +
     '                    dragClip = null; mode = MODE_NONE;' + chr(10) +
     '                    if (c2 != null && c2.startTick != dragOrigTick && listener != null) {' + chr(10) +
     '                        listener.onClipEdited(c2, dragOrigTick);' + chr(10) +
     '                    }' + chr(10) +
     '                    invalidate(); performClick(); return true;' + chr(10) +
     '                }'), 'lane up')
rep('public interface Listener { void onSelectionChanged(Note n); void onNoteEdited(Note n, String what);',
    ('public interface Listener { void onSelectionChanged(Note n); void onNoteEdited(Note n, String what);' + chr(10) +
     '        /** a clip drag begins: the caller snapshots the project for undo */' + chr(10) +
     '        void onClipDragStart();' + chr(10) +
     '        /** a clip was dragged to a new start tick */' + chr(10) +
     '        void onClipEdited(com.dsh.v4tune.model.AudioClip c, long oldTick);'), 'listener')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('PianoRollView patched')