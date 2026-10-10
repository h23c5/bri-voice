# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()

# 1) importing places the clip where it can actually be heard
old = '            String nm = uri.getLastPathSegment();' + chr(10) +
      '            AudioClip c = AudioClip.fromWav(d, startTick, nm == null ? "audio" : nm);'
new = ('            String nm = uri.getLastPathSegment();' + chr(10) +
       '            // A clip dropped past the end of the song would never be mixed in: the render' + chr(10) +
       '            // buffer stops at the last note. Fall back to the start instead of silent success.' + chr(10) +
       '            long songEnd = song.maxTick();' + chr(10) +
       '            long at = Math.max(0L, startTick);' + chr(10) +
       '            boolean moved = false;' + chr(10) +
       '            if (songEnd > 0 && at >= songEnd) { at = 0L; moved = true; }' + chr(10) +
       '            AudioClip c = AudioClip.fromWav(d, at, nm == null ? "audio" : nm);' + chr(10) +
       '            if (c == null) { status(L.t("status.wav.fail", "?")); return; }')
if old not in s: raise SystemExit('import block not found')
s = s.replace(old, new, 1)

# 2) the mix buffer grows so a clip longer than the song is heard in full
old2 = '        float[] tl = new float[scratch], tr = new float[scratch];'
new2 = ('        // a clip may reach past the last note: grow the rendered buffers so nothing is cut' + chr(10) +
        '        int need = 0;' + chr(10) +
        '        for (int ti = 0; ti < clips.size(); ti++) {' + chr(10) +
        '            for (AudioClip c : clips.get(ti)) {' + chr(10) +
        '                if (c == null || c.data == null) continue;' + chr(10) +
        '                need = Math.max(need, c.startFrame(song) + c.data.frames);' + chr(10) +
        '            }' + chr(10) +
        '        }' + chr(10) +
        '        if (need > r.left.length) {' + chr(10) +
        '            r.left = java.util.Arrays.copyOf(r.left, need);' + chr(10) +
        '            if (r.right != null) r.right = java.util.Arrays.copyOf(r.right, need);' + chr(10) +
        '        }' + chr(10) +
        '        float[] tl = new float[scratch], tr = new float[scratch];')
if old2 not in s: raise SystemExit('mix buffer anchor not found')
s = s.replace(old2, new2, 1)

# 3) tell the user when the clip had to move
s = s.replace('            status(L.t("status.wav.added", nm, d.frames / 44100.0));' + chr(10) +
              '            toast(L.t("status.wav.added", nm, d.frames / 44100.0));',
              '            status(moved ? L.t("status.wav.added.atstart", nm, d.frames / 44100.0)' + chr(10) +
              '                           : L.t("status.wav.added", nm, d.frames / 44100.0));' + chr(10) +
              '            toast(L.t("status.wav.added", nm, d.frames / 44100.0));', 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('import + mix length fixed')