# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(m, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
# 1) hand the clips of the current track to the roll
rep('        roll.setSong(song);' + chr(10) + '        roll.setTrackIndex(trackIndex);',
    ('        roll.setSong(song);' + chr(10) +
     '        roll.setTrackIndex(trackIndex);' + chr(10) +
     '        if (roll != null) roll.setClips(clipsFor(trackIndex));'), 'refreshAll')
# 2) helper + listener callbacks
rep('    private Part currentPart() {',
    ('    /** the clip list of a track, created on demand */' + chr(10) +
     '    private java.util.List<AudioClip> clipsFor(int ti) {' + chr(10) +
     '        while (clips.size() <= ti) clips.add(new java.util.ArrayList<AudioClip>());' + chr(10) +
     '        return clips.get(ti);' + chr(10) +
     '    }' + chr(10) + chr(10) +
     '    /** a clip drag started: snapshot so it can be undone */' + chr(10) +
     '    public void onClipDragStart() { beginEdit(); }' + chr(10) + chr(10) +
     '    /** a clip was dragged: commit and report */' + chr(10) +
     '    public void onClipEdited(AudioClip c, long oldTick) {' + chr(10) +
     '        if (c == null) return;' + chr(10) +
     '        commitEdit();' + chr(10) +
     '        refreshAll();' + chr(10) +
     '        status(L.t("status.clip.moved", c.name == null ? "audio" : c.name, formatTick(c.startTick)));' + chr(10) +
     '    }' + chr(10) + chr(10) +
     '    private Part currentPart() {'), 'helpers')
io.open(m, 'w', encoding='utf-8', newline='').write(s)
print('MainActivity wired for the clip lane')
# 3) i18n
L = root + r'\app\src\com\dsh\v4tune\i18n\L.java'
u = io.open(L, encoding='utf-8').read()
rows = [["status.clip.moved","音频片段 %s 移到 %s","Clip %s moved to %s","クリップ %s を %s に移動"],["clip.start.title","起始位置（拍）","Start position (beats)","開始位置（拍）"],["clip.align.playhead","对齐到播放头","Align to playhead","カーソルに合わせる"],["clip.align.first","对齐到第一个音符","Align to first note","最初の音符に合わせる"],["status.clip.aligned","已对齐：%s → %s","Aligned: %s → %s","整列しました：%s → %s"]]
add = []
for r in rows:
    if ('"' + r[0] + '"') in u: continue
    add.append('        {"' + r[0] + '", ' + ', '.join('"' + v + '"' for v in r[1:]) + '},')
if add:
    i = u.find('{"status.clip.removed"')
    if i < 0: raise SystemExit('status.clip anchor missing')
    j = u.rfind(chr(10), 0, i) + 1
    u = u[:j] + chr(10).join(add) + chr(10) + u[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(u)
print('keys added:', len(add))