import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read()
s = s.replace('"--version-code", "53", "--version-name", "7.2",', '"--version-code", "54", "--version-name", "7.3",')
s = s.replace('"--version-code", "52", "--version-name", "7.1",', '"--version-code", "54", "--version-name", "7.3",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read()
import re
t = re.sub(r'android:versionCode="\d+"', 'android:versionCode="54"', t, count=1)
t = re.sub(r'android:versionName="[^"]+"', 'android:versionName="7.3"', t, count=1)
io.open(m, 'w', encoding='utf-8', newline='').write(t)
print('version now: ' + re.search(r'android:versionCode="(\d+)" android:versionName="([^"]+)"', t).group(0))