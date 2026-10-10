import io, re
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read()
s = re.sub(r'"--version-code", "\d+", "--version-name", "[^"]+",', '"--version-code", "54", "--version-name", "7.3-beta1",', s, count=1)
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read()
t = re.sub(r'android:versionCode="\d+"', 'android:versionCode="54"', t, count=1)
t = re.sub(r'android:versionName="[^"]+"', 'android:versionName="7.3-beta1"', t, count=1)
io.open(m, 'w', encoding='utf-8', newline='').write(t)
print('version set to 54 / 7.3-beta1')