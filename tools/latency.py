# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\audio\AudioEngine.java'
s = io.open(p, encoding='utf-8').read()
old = "                        long now = System.currentTimeMillis();\n                        if (cb != null && now - lastCb > 100) {\n                            lastCb = now;\n                            long played = Math.min(pos, start + (long) ((now - wallStart) * r.sampleRate / 1000.0));\n                            playTick = TimeUtil.secToTick(r.song, played / (double) r.sampleRate);\n                            cb.onProgress(playTick);\n                        }"
new = "                        long now = System.currentTimeMillis();\n                        if (cb != null && now - lastCb > 100) {\n                            lastCb = now;\n                            // Follow the frames the device has actually played out, not the writer's\n                            // position: with a few hundred ms of output buffer the cursor used to run\n                            // visibly ahead of the sound. The wall clock stays the upper bound so the\n                            // cursor can never jump forward.\n                            long head = 0;\n                            try { head = at.getPlaybackHeadPosition(); } catch (Throwable ignored) { }\n                            long byClock = start + (long) ((now - wallStart) * r.sampleRate / 1000.0);\n                            long played = (head > 0) ? Math.min(start + head, byClock) : byClock;\n                            if (played < start) played = start;\n                            playTick = TimeUtil.secToTick(r.song, played / (double) r.sampleRate);\n                            cb.onProgress(playTick);\n                        }"
if 'getPlaybackHeadPosition' in s:
    print('already patched')
elif old in s:
    s = s.replace(old, new, 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('cursor now follows the playback head')
else:
    raise SystemExit('play loop anchor missing')