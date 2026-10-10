import io, re
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read()
s = re.sub(r'"--version-code", "\d+"', '"--version-code", "56"', s, count=1)
s = re.sub(r'"--version-name", "[^"]+"', '"--version-name", "7.3-beta2"', s, count=1)
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read()
t = re.sub(r'android:versionCode="\d+"', 'android:versionCode="56"', t, count=1)
t = re.sub(r'android:versionName="[^"]+"', 'android:versionName="7.3-beta2"', t, count=1)
io.open(m, 'w', encoding='utf-8', newline='').write(t)
print('set to 56 / 7.3-beta2')