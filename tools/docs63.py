# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "43", "--version-name", "6.2",', '"--version-code", "44", "--version-name", "6.3",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="43"', 'android:versionCode="44"').replace('android:versionName="6.2"', 'android:versionName="6.3"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
print('version 6.3')