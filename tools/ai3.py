# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\ai2.py'
s = io.open(p, encoding='utf-8').read()
broken = ("    new_l = '        aiCfg = com.dsh.v4tune.ai.AiConfig.load(this);' + chr(10) +" + chr(10) +
          "        '        reloadPacksAsync(true);' + chr(10) +" + chr(10) +
          "        '        applyExtServer();          // only starts anything when the user enabled it'")
fixed = ("    new_l = ('        aiCfg = com.dsh.v4tune.ai.AiConfig.load(this);' + chr(10)" + chr(10) +
         "             + '        reloadPacksAsync(true);' + chr(10)" + chr(10) +
         "             + '        applyExtServer();          // only starts anything when the user enabled it')")
if broken not in s: raise SystemExit('broken block not found')
s = s.replace(broken, fixed, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('fixed')