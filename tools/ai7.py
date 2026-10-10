# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()

# 1) undo the damage inside importPackFrom's callback
bad = '''                            aiCfg = com.dsh.v4tune.ai.AiConfig.load(this);
        reloadPacksAsync(true);
        applyExtServer();          // only starts anything when the user has enabled it
                            status("\u5df2\u5bfc\u5165\u58f0\u5e93: " + singerName(r.model));'''
good = '''                            status("\u5df2\u5bfc\u5165\u58f0\u5e93: " + singerName(r.model));'''
if bad in s:
    s = s.replace(bad, good, 1)
    print('importPackFrom restored')
else:
    print('WARN: importPackFrom block not matched')

# 2) the config belongs in onCreate, right after the pack load is kicked off
old = '        reloadPacksAsync(false);' + chr(10) + '        song = Demo.build();'
new = ('        aiCfg = com.dsh.v4tune.ai.AiConfig.load(this);' + chr(10) +
       '        reloadPacksAsync(false);' + chr(10) +
       '        applyExtServer();          // only starts anything when the user has enabled it' + chr(10) +
       '        song = Demo.build();')
if old in s:
    s = s.replace(old, new, 1)
    print('onCreate wired')
else:
    print('WARN: onCreate anchor not matched')

# 3) the checkbox import
if 'import android.widget.CheckBox;' not in s:
    s = s.replace('import android.widget.Button;', 'import android.widget.Button;' + chr(10) + 'import android.widget.CheckBox;', 1)
    print('CheckBox imported')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('done')