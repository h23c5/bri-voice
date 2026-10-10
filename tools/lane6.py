# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(m, encoding='utf-8').read()
add = "    /** renders the song (with clips and plugins) straight to a file: used by the external API */\n    private java.io.File exportWavTo(java.io.File f) throws Exception {\n        ensureMixerTracks();\n        engine.mixer = mixer;\n        com.dsh.v4tune.audio.AudioEngine.Rendered r =\n                engine.render(song, currentVoice, new Renderer.Cfg(), null);\n        applyPostDsp(r);\n        java.io.File dir = f.getParentFile();\n        if (dir != null && !dir.exists()) dir.mkdirs();\n        com.dsh.v4tune.audio.AudioEngine.writeWav(f, r);\n        return f;\n    }\n"
anchor = '    /** the clips of the current track, as JSON */'
if 'private java.io.File exportWavTo(' in s:
    print('helper present')
elif anchor in s:
    s = s.replace(anchor, add + anchor, 1)
    io.open(m, 'w', encoding='utf-8', newline='').write(s)
    print('exportWavTo added')
else:
    raise SystemExit('anchor missing')
io.open(m, 'w', encoding='utf-8', newline='').write(s)