import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
b = root + r'\tools\build_apk.py'
s = io.open(b, encoding='utf-8').read().replace('"--version-code", "52", "--version-name", "7.1",', '"--version-code", "53", "--version-name", "7.2",')
io.open(b, 'w', encoding='utf-8', newline='').write(s)
m = root + r'\app\AndroidManifest.xml'
t = io.open(m, encoding='utf-8').read().replace('android:versionCode="52"', 'android:versionCode="53"').replace('android:versionName="7.1"', 'android:versionName="7.2"')
io.open(m, 'w', encoding='utf-8', newline='').write(t)
print('bumped to 7.2 / 53')