# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(m, encoding='utf-8').read()
cj = "    /** the clips of the current track, as JSON */\n    private String clipJson() {\n        java.util.List<AudioClip> cs = clipsFor(trackIndex);\n        StringBuilder b = new StringBuilder(\"[\");\n        for (int i = 0; i < cs.size(); i++) {\n            AudioClip c = cs.get(i);\n            if (i > 0) b.append(',');\n            b.append(com.dsh.v4tune.ai.Json.obj(\"index\", Integer.valueOf(i),\n                    \"name\", c.name == null ? \"\" : c.name,\n                    \"startTick\", Long.valueOf(c.startTick),\n                    \"frames\", Integer.valueOf(c.data == null ? 0 : c.data.frames)));\n        }\n        return b.append(']').toString();\n    }\n"
ex = "    /** renders the song (with clips and plugins) straight to a file: used by the external API */\n    private java.io.File exportWavTo(java.io.File f) throws Exception {\n        ensureMixerTracks();\n        engine.mixer = mixer;\n        com.dsh.v4tune.audio.AudioEngine.Rendered r =\n                engine.render(song, currentVoice, new Renderer.Cfg(), null);\n        applyPostDsp(r);\n        java.io.File dir = f.getParentFile();\n        if (dir != null && !dir.exists()) dir.mkdirs();\n        com.dsh.v4tune.audio.AudioEngine.writeWav(f, r);\n        return f;\n    }\n"
anchor = '    /** the clip list of a track, created on demand */'
changed = False
if 'private String clipJson()' not in s:
    if anchor not in s: raise SystemExit('clip anchor missing')
    s = s.replace(anchor, cj + anchor, 1)
    changed = True
    print('clipJson added')
if 'private java.io.File exportWavTo(' not in s:
    s = s.replace(anchor, ex + anchor, 1)
    changed = True
    print('exportWavTo added')
if changed:
    io.open(m, 'w', encoding='utf-8', newline='').write(s)
print('done')