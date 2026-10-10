import io, re
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "51", "--version-name", "7.0",', '"--version-code", "52", "--version-name", "7.1",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="51"', 'android:versionCode="52"').replace('android:versionName="7.0"', 'android:versionName="7.1"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
print('bumped to 7.1 / 52')