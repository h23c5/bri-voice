import io, re
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read()
s = re.sub(r'"--version-code", "\d+"', '"--version-code", "60"', s, count=1)
s = re.sub(r'"--version-name", "[^"]+"', '"--version-name", "7.4"', s, count=1)
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read()
t = re.sub(r'android:versionCode="\d+"', 'android:versionCode="60"', t, count=1)
t = re.sub(r'android:versionName="[^"]+"', 'android:versionName="7.4"', t, count=1)
io.open(m, 'w', encoding='utf-8', newline='').write(t)
print('59 -> 60 / 7.4 (release)')