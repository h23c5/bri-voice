# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
i = s.find('    private void about() {')
if i < 0: raise SystemExit('about() not found')
endmark = '                .setPositiveButton("好", null)\n                .show();\n    }'
j = s.find(endmark, i)
if j < 0: raise SystemExit('about() end not found')
j += len(endmark)
new = "    private void about() {\n        StringBuilder sb = new StringBuilder();\n        sb.append(L.t(\"about.line.app\")).append(\"\\n\\n\");\n        sb.append(L.t(\"about.line.formats\")).append(\"\\n\");\n        sb.append(L.t(\"about.line.fmt4\")).append(\"\\n\");\n        sb.append(L.t(\"about.line.fmt3\")).append(\"\\n\");\n        sb.append(L.t(\"about.line.fmt2\")).append(\"\\n\");\n        sb.append(L.t(\"about.line.wav\")).append(\"\\n\\n\");\n        sb.append(L.t(\"about.line.packs\")).append(\"\\n\");\n        if (SingerLibrary.isEmpty()) sb.append(L.t(\"about.line.no.pack\")).append(\"\\n\");\n        for (VoiceModel v : SingerLibrary.all()) {\n            int bands = 0;\n            for (java.util.Map.Entry<String, VoiceModel.HarmonicBand[]> e2 : v.harmonics.entrySet()) {\n                bands += e2.getValue().length;\n            }\n            if (bands > 0) {\n                sb.append(L.t(\"about.line.pack\", singerName(v), v.harmonics.size(), bands)).append(\"\\n\");\n            } else {\n                sb.append(L.t(\"about.line.pack.legacy\", singerName(v))).append(\"\\n\");\n            }\n        }\n        sb.append(\"\\n\").append(L.t(\"about.line.dir\", Storage.voicebanks(this).getAbsolutePath())).append(\"\\n\");\n        sb.append(\"\\n\").append(L.t(\"about.line.tip1\")).append(\"\\n\");\n        sb.append(L.t(\"about.line.tip2\")).append(\"\\n\");\n        sb.append(\"\\n\").append(L.t(\"about.line.author\")).append(\"\\n\");\n        sb.append(\"\\n\").append(L.t(\"about.line.disclaimer\")).append(\"\\n\");\n        new AlertDialog.Builder(this)\n                .setTitle(L.t(\"dlg.about.title\"))\n                .setMessage(sb.toString())\n                .setPositiveButton(L.t(\"dlg.ok\"), null)\n                .show();\n    }"
s = s[:i] + new + s[j:]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('about() localised')