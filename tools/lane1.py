# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
p = root + r'\app\src\com\dsh\v4tune\ui\PianoRollView.java'
s = io.open(p, encoding='utf-8').read()
blk = io.open(root + r'\tools\lane_fields.txt', encoding='utf-8').read()

# 1) fields + helpers before the touch section
anchor = '    // ------------------------------------------------------------------- touch'
if 'private void drawClipLane(' not in s:
    if anchor not in s: raise SystemExit('touch anchor missing')
    s = s.replace(anchor, blk + anchor, 1)
    print('lane added')

# 2) another mode
s = s.replace('MODE_LINE = 5, MODE_DRAGPT = 6;', 'MODE_LINE = 5, MODE_DRAGPT = 6, MODE_CLIP = 7;', 1)

# 3) lane height init
if 'clipH = dp(' not in s:
    s = s.replace('keyW = dp(64f);', 'keyW = dp(64f); clipH = dp(26f);', 1)

# 4) draw it after the notes
oldDraw = '        if (selSeen) Theme.rr(c, selL - dp(1f), selT - dp(1f), selR + dp(1f), selB + dp(1f), dp(4f), selP);'
newDraw = ('        if (!clips.isEmpty()) drawClipLane(c, gl, gr, gb);' + chr(10) +
           '        if (selSeen) Theme.rr(c, selL - dp(1f), selT - dp(1f), selR + dp(1f), selB + dp(1f), dp(4f), selP);')
if oldDraw not in s: raise SystemExit('draw anchor missing')
s = s.replace(oldDraw, newDraw, 1)

# 5) touch: grab a clip in the lane
oldDown = '                if (x < keyW && y > rulerH) { mode = MODE_KEYS; auditionKeyAt(y);'
newDown = ('                if (!clips.isEmpty() && y >= clipTop() && x > keyW) {' + chr(10) +
           '                    com.dsh.v4tune.model.AudioClip hit = clipAt(x);' + chr(10) +
           '                    selClip = hit;' + chr(10) +
           '                    if (hit != null) {' + chr(10) +
           '                        dragClip = hit; dragOrigTick = hit.startTick;' + chr(10) +
           '                        dragGrabTick = (long) tickAtX(x) - hit.startTick;' + chr(10) +
           '                        mode = MODE_CLIP;' + chr(10) +
           '                        if (listener != null) listener.onClipDragStart();' + chr(10) +
           '                    } else {' + chr(10) +
           '                        mode = MODE_SCROLL;      // empty lane: scroll as usual' + chr(10) +
           '                    }' + chr(10) +
           '                    invalidate();' + chr(10) +
           '                    return true;' + chr(10) +
           '                }' + chr(10) +
           '                if (x < keyW && y > rulerH) { mode = MODE_KEYS; auditionKeyAt(y);')
if oldDown not in s: raise SystemExit('down anchor missing')
s = s.replace(oldDown, newDown, 1)

# 6) move
oldMove = '                if (mode == MODE_LINE) {'
newMove = ('                if (mode == MODE_CLIP && dragClip != null) {' + chr(10) +
           '                    long t = snap((long) tickAtX(x) - dragGrabTick);' + chr(10) +
           '                    dragClip.startTick = Math.max(0L, t);' + chr(10) +
           '                    moved = true;' + chr(10) +
           '                    invalidate();' + chr(10) +
           '                    return true;' + chr(10) +
           '                }' + chr(10) +
           '                if (mode == MODE_LINE) {')
if oldMove not in s: raise SystemExit('move anchor missing')
s = s.replace(oldMove, newMove, 1)

# 7) release
oldUp = '            case MotionEvent.ACTION_UP: {' + chr(10) + '                cancelPress();'
newUp = ('            case MotionEvent.ACTION_UP: {' + chr(10) +
         '                cancelPress();' + chr(10) +
         '                if (mode == MODE_CLIP) {' + chr(10) +
         '                    com.dsh.v4tune.model.AudioClip c2 = dragClip;' + chr(10) +
         '                    dragClip = null;' + chr(10) +
         '                    mode = MODE_NONE;' + chr(10) +
         '                    if (c2 != null && c2.startTick != dragOrigTick && listener != null) {' + chr(10) +
         '                        listener.onClipEdited(c2, dragOrigTick);' + chr(10) +
         '                    }' + chr(10) +
         '                    invalidate();' + chr(10) +
         '                    performClick();' + chr(10) +
         '                    return true;' + chr(10) +
         '                }')
if oldUp not in s: raise SystemExit('up anchor missing')
s = s.replace(oldUp, newUp, 1)

# 8) listener callbacks
s = s.replace('public interface Listener { void onSelectionChanged(Note n); void onNoteEdited(Note n, String what);',
              'public interface Listener { void onSelectionChanged(Note n); void onNoteEdited(Note n, String what);' + chr(10) +
              '        /** a clip drag begins: the caller snapshots for undo */' + chr(10) +
              '        void onClipDragStart();' + chr(10) +
              '        /** a clip was dragged to a new start tick */' + chr(10) +
              '        void onClipEdited(com.dsh.v4tune.model.AudioClip c, long oldTick);', 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('PianoRollView patched')