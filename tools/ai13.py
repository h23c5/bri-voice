# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
add = "            if (\"aiConfig\".equals(action)) {\n                // lets a script point the assistant at another endpoint (a local model, a proxy)\n                if (args.containsKey(\"enabled\")) {\n                    Object en = args.get(\"enabled\");\n                    aiCfg.aiEnabled = Boolean.TRUE.equals(en) || \"true\".equalsIgnoreCase(String.valueOf(en));\n                }\n                if (args.containsKey(\"baseUrl\")) aiCfg.baseUrl = String.valueOf(args.get(\"baseUrl\"));\n                if (args.containsKey(\"baseurl\")) aiCfg.baseUrl = String.valueOf(args.get(\"baseurl\"));\n                if (args.containsKey(\"model\")) aiCfg.model = String.valueOf(args.get(\"model\"));\n                if (args.containsKey(\"apiKey\")) aiCfg.apiKey = String.valueOf(args.get(\"apiKey\"));\n                if (args.containsKey(\"strength\")) aiCfg.strength = (float) num(args.get(\"strength\"));\n                aiCfg.save(MainActivity.this);\n                return com.dsh.v4tune.ai.Json.obj(\"ok\", Boolean.TRUE, \"enabled\", Boolean.valueOf(aiCfg.aiEnabled),\n                        \"baseUrl\", aiCfg.baseUrl, \"model\", aiCfg.model);\n            }\n"
anchor = '            if ("aiTune".equals(action)) {'
if 'aiConfig' in s:
    print('already present')
elif anchor in s:
    s = s.replace(anchor, add + anchor, 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('aiConfig added')
else:
    raise SystemExit('anchor missing')