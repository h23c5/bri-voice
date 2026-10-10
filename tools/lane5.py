# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
m = root + r'\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(m, encoding='utf-8').read()
add = "            if (\"importWav\".equals(action)) {\n                String path = com.dsh.v4tune.ai.Json.str(args, \"path\", \"\");\n                if (path.length() == 0) throw new IllegalArgumentException(\"need path\");\n                java.io.File f = new java.io.File(path);\n                if (!f.isFile()) throw new IllegalArgumentException(\"no such file: \" + path);\n                com.dsh.v4tune.io.WavReader.AudioData d =\n                        com.dsh.v4tune.io.WavReader.read(new java.io.FileInputStream(f), 44100 * 180);\n                if (d == null || !d.ok()) throw new IllegalStateException(\"wav: \" + (d == null ? \"?\" : d.error));\n                long songEnd = song.maxTick();\n                long at = (long) com.dsh.v4tune.ai.Json.num(args, \"at tick\", Math.max(0L, startTick));\n                if (songEnd > 0 && at >= songEnd) at = 0L;\n                AudioClip c = AudioClip.fromWav(d, at, f.getName());\n                if (c == null) throw new IllegalStateException(\"clip build failed\");\n                beginEdit();\n                clipsFor(trackIndex).add(c);\n                commitEdit();\n                refreshAll();\n                return com.dsh.v4tune.ai.Json.obj(\"ok\", Boolean.TRUE, \"name\", c.name,\n                        \"seconds\", Double.valueOf(d.frames / 44100.0), \"startTick\", Long.valueOf(c.startTick),\n                        \"clips\", Integer.valueOf(clipsFor(trackIndex).size()));\n            }\n            if (\"exportWav\".equals(action)) {\n                String path = com.dsh.v4tune.ai.Json.str(args, \"path\", \"\");\n                if (path.length() == 0) throw new IllegalArgumentException(\"need path\");\n                final String fp = path;\n                exportWavTo(new java.io.File(fp));\n                return com.dsh.v4tune.ai.Json.obj(\"ok\", Boolean.TRUE, \"path\", fp);\n            }\n            if (\"moveClip\".equals(action)) {\n                int idx = (int) Math.round(com.dsh.v4tune.ai.Json.num(args, \"index\", 0));\n                long tick = (long) com.dsh.v4tune.ai.Json.num(args, \"startTick\", 0);\n                java.util.List<AudioClip> cs = clipsFor(trackIndex);\n                if (idx < 0 || idx >= cs.size()) throw new IllegalArgumentException(\"no clip \" + idx);\n                beginEdit();\n                cs.get(idx).startTick = Math.max(0L, tick);\n                commitEdit();\n                refreshAll();\n                return com.dsh.v4tune.ai.Json.obj(\"ok\", Boolean.TRUE, \"startTick\", Long.valueOf(cs.get(idx).startTick));\n            }\n"
anchor = '            if ("aiConfig".equals(action)) {'
if '"importWav".equals(action)' in s:
    print('already present')
elif anchor in s:
    s = s.replace(anchor, add + anchor, 1)
    # clips in /status
    s = s.replace('"singer", singer,', '"singer", singer,' + chr(10) +
                  '                "clips", ' + 'com.dsh.v4tune.ai.Json.raw(clipJson()),', 1)
    io.open(m, 'w', encoding='utf-8', newline='').write(s)
    print('api actions added')
else:
    raise SystemExit('anchor missing')
# clipJson helper + exportWavTo (export reuses the existing routine if there is one)
if 'private String clipJson(' not in s:
    helper = ('    /** the clips of the current track, as JSON */' + chr(10) +
              '    private String clipJson() {' + chr(10) +
              '        java.util.List<AudioClip> cs = clipsFor(trackIndex);' + chr(10) +
              '        StringBuilder b = new StringBuilder("[");' + chr(10) +
              '        for (int i = 0; i < cs.size(); i++) {' + chr(10) +
              '            AudioClip c = cs.get(i);' + chr(10) +
              '            if (i > 0) b.append(',');' + chr(10) +
              '            b.append(com.dsh.v4tune.ai.Json.obj("index", Integer.valueOf(i),' + chr(10) +
              '                    "name", c.name == null ? "" : c.name,' + chr(10) +
              '                    "startTick", Long.valueOf(c.startTick),' + chr(10) +
              '                    "frames", Integer.valueOf(c.data == null ? 0 : c.data.frames)));' + chr(10) +
              '        }' + chr(10) +
              '        return b.append(\']\').toString();' + chr(10) +
              '    }' + chr(10) + chr(10))
    s = s.replace('    /** the clip list of a track, created on demand */', helper + '    /** the clip list of a track, created on demand */', 1)
    io.open(m, 'w', encoding='utf-8', newline='').write(s)
    print('clipJson added')