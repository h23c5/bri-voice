# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
p = root + r'\app\src\com\dsh\v4tune\ui\ParamView.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)

# 1) a mode for dragging the tuning lane itself
rep('MODE_LINE = 5, MODE_DRAGPT = 6;', 'MODE_LINE = 5, MODE_DRAGPT = 6,' + chr(10) +
    '            MODE_DRAGVIEW = 7;', 'modes')
if 'private float panStartX' not in s:
    rep('    private int tool = TOOL_PENCIL;',
        '    private int tool = TOOL_PENCIL;' + chr(10) +
        '    /** one-finger pan of the tuning lane (select tool) */' + chr(10) +
        '    private float panStartX, panStartTick;', 'pan fields')

# 2) select + drag on empty space pans the lane
oldSel = '''        if (tool == TOOL_SELECT) {
            selIdx = nearestPoint(x, y, top, bot);
            if (selIdx >= 0) {
                mode = MODE_DRAGPT;
                cancelPress();
                invalidate();
            } else {
                mode = MODE_INERT;
            }
            return true;
        }'''
newSel = '''        if (tool == TOOL_SELECT) {
            selIdx = nearestPoint(x, y, top, bot);
            if (selIdx >= 0) {
                mode = MODE_DRAGPT;
                cancelPress();
                invalidate();
            } else {
                // nothing under the finger: dragging the lane scrolls the timeline instead
                mode = MODE_DRAGVIEW;
                panStartX = x;
                panStartTick = tickStart;
            }
            return true;
        }'''
rep(oldSel, newSel, 'select down')

# 3) the move
oldMove = '                if (mode == MODE_DRAGPT) {'
newMove = ('                if (mode == MODE_DRAGVIEW) {' + chr(10) +
           '                    tickStart = panStartTick - (x - panStartX) / pxPerTick;' + chr(10) +
           '                    clampRange();' + chr(10) +
           '                    if (listener != null) listener.onTickRangeChanged(tickStart, tickStart + visibleTicks());' + chr(10) +
           '                    invalidate();' + chr(10) +
           '                    return true;' + chr(10) +
           '                }' + chr(10) +
           '                if (mode == MODE_DRAGPT) {')
rep(oldMove, newMove, 'drag move')

# 4) release
oldUp = '                if (mode == MODE_DRAGPT) {' + chr(10) + '                    finishStroke();'
newUp = ('                if (mode == MODE_DRAGVIEW) {' + chr(10) +
         '                    mode = MODE_NONE;' + chr(10) +
         '                    performClick();' + chr(10) +
         '                    return true;' + chr(10) +
         '                }' + chr(10) +
         '                if (mode == MODE_DRAGPT) {' + chr(10) + '                    finishStroke();')
rep(oldUp, newUp, 'drag up')

# 5) the listener gets the new visible range
rep('public interface Listener { void onCurveEdited(String paramId); void onParamSelected(String paramId); }',
    ('public interface Listener { void onCurveEdited(String paramId); void onParamSelected(String paramId);' + chr(10) +
     '        /** the user dragged the tuning lane: the roll should follow the same range */' + chr(10) +
     '        void onTickRangeChanged(float from, float to); }'), 'listener')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('ParamView: select-drag pans the lane')

# 6) MainActivity follows
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
t = io.open(m, encoding='utf-8').read()
anchor = '        paramView.setListener(new ParamView.Listener() {'
if 'onTickRangeChanged(float from' in t:
    print('already wired')
else:
    i = t.find(anchor)
    if i < 0: raise SystemExit('listener anchor missing')
    j = t.find(chr(10), i) + 1
    add = ('            public void onTickRangeChanged(float from, float to) {' + chr(10) +
           '                // keep the piano roll on the same window while the tuning lane is dragged' + chr(10) +
           '                if (roll != null && to > from) roll.setVisibleTickRange((long) from, (long) to);' + chr(10) +
           '            }' + chr(10))
    t = t[:j] + add + t[j:]
    io.open(m, 'w', encoding='utf-8', newline='').write(t)
    print('MainActivity wired')