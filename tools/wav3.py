# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
old = "            String nm = uri.getLastPathSegment();\n            AudioClip c = AudioClip.fromWav(d, startTick, nm == null ? \"audio\" : nm);"
new = "            String nm = uri.getLastPathSegment();\n            // A clip dropped past the end of the song would never be mixed in: the render buffer\n            // stops at the last note. Fall back to the start instead of a silent \"imported\".\n            long songEnd = song.maxTick();\n            long at = Math.max(0L, startTick);\n            boolean moved = false;\n            if (songEnd > 0 && at >= songEnd) { at = 0L; moved = true; }\n            AudioClip c = AudioClip.fromWav(d, at, nm == null ? \"audio\" : nm);\n            if (c == null) { status(L.t(\"status.wav.fail\", \"?\")); return; }"
if old not in s: raise SystemExit('import block not found')
s = s.replace(old, new, 1)
old2 = "        float[] tl = new float[scratch], tr = new float[scratch];"
new2 = "        // a clip may reach past the last note: grow the rendered buffers so nothing is cut off\n        int need = 0;\n        for (int ti = 0; ti < clips.size(); ti++) {\n            for (AudioClip c : clips.get(ti)) {\n                if (c == null || c.data == null) continue;\n                need = Math.max(need, c.startFrame(song) + c.data.frames);\n            }\n        }\n        if (need > r.left.length) {\n            r.left = java.util.Arrays.copyOf(r.left, need);\n            if (r.right != null) r.right = java.util.Arrays.copyOf(r.right, need);\n        }\n        float[] tl = new float[scratch], tr = new float[scratch];"
if old2 not in s: raise SystemExit('mix anchor not found')
s = s.replace(old2, new2, 1)
old3 = "            status(L.t(\"status.wav.added\", nm, d.frames / 44100.0));\n            toast(L.t(\"status.wav.added\", nm, d.frames / 44100.0));"
new3 = "            status(moved ? L.t(\"status.wav.added.atstart\", nm, d.frames / 44100.0)\n                           : L.t(\"status.wav.added\", nm, d.frames / 44100.0));\n            toast(L.t(\"status.wav.added\", nm, d.frames / 44100.0));"
if old3 not in s: raise SystemExit('status block not found')
s = s.replace(old3, new3, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('wav import + mix length patched')