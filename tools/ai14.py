# -*- coding: utf-8 -*-
import io
m = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\AndroidManifest.xml'
s = io.open(m, encoding='utf-8').read()
if 'usesCleartextTraffic' not in s:
    i = s.find('<application')
    j = s.find('>', i)
    s = s[:j] + chr(10) + '        android:usesCleartextTraffic="true"' + s[j:]
    io.open(m, 'w', encoding='utf-8', newline='').write(s)
    print('cleartext allowed')
else:
    print('already present')