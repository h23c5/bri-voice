# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
# 1) i18n key
Lf = root + r'\app\src\com\dsh\v4tune\i18n\L.java'
u = io.open(Lf, encoding='utf-8').read()
key = 'set.mnoise.gain'
if key not in u:
    row = '        {"' + key + '", "实测谱强度：%d%%", "Measured spectrum level: %d%%", "実測スペクトル強度：%d%%"},'
    i = u.find('{"set.mnoise"')
    j = u.rfind(chr(10), 0, i) + 1
    u = u[:j] + row + chr(10) + u[j:]
    io.open(Lf, 'w', encoding='utf-8', newline='').write(u)
    print('key added')
else:
    print('key present')
# 2) version 6.5
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read()
s = s.replace('"--version-code", "45", "--version-name", "6.4",', '"--version-code", "46", "--version-name", "6.5",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read()
t = t.replace('android:versionCode="45"', 'android:versionCode="46"').replace('android:versionName="6.4"', 'android:versionName="6.5"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
print('version 6.5')
r = root + r'\README.md'
d = io.open(r, encoding='utf-8').read()
d = d.replace('版本：6.4（versionCode 45）', '版本：6.5（versionCode 46）')
io.open(r, 'w', encoding='utf-8', newline='').write(d)
print('readme version')