# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
e = root + r'\app\src\com\dsh\v4tune\audio\AudioEngine.java'
s = io.open(e, encoding='utf-8').read()
if 'Song song();' not in s:
    s = s.replace('        float[] left();',
                  '        /** the song being rendered, so ticks can be converted with its tempo map */' + chr(10) +
                  '        Song song();' + chr(10) +
                  '        float[] left();', 1)
s = s.replace('playTick = TimeUtil.secToTick(null, played / (double) sr);',
              'playTick = TimeUtil.secToTick(src.song(), played / (double) sr);', 1)
io.open(e, 'w', encoding='utf-8', newline='').write(s)
print('engine song() added:', 'Song song();' in s)
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
t = io.open(m, encoding='utf-8').read()
if 'public Song song() { return sng; }' not in t:
    t = t.replace('        public int ready() { return ready; }',
                  '        public Song song() { return sng; }' + chr(10) +
                  '        public int ready() { return ready; }', 1)
    io.open(m, 'w', encoding='utf-8', newline='').write(t)
    print('producer song() added')