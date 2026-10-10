# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
# 1) INTERNET permission: needed for the local control server and for the AI call
m = root + r'\app\AndroidManifest.xml'
s = io.open(m, encoding='utf-8').read()
if 'android.permission.INTERNET' not in s:
    i = s.find('<uses-permission')
    if i < 0: raise SystemExit('no uses-permission anchor')
    s = s[:i] + '<uses-permission android:name="android.permission.INTERNET" />' + chr(10) + '    ' + s[i:]
    io.open(m, 'w', encoding='utf-8', newline='').write(s)
    print('INTERNET permission added')
else:
    print('INTERNET already present')
# 2) the disclaimer must say what actually happens now
L = root + r'\app\src\com\dsh\v4tune\i18n\L.java'
u = io.open(L, encoding='utf-8').read()
old5 = '5. 本软件不联网、不收集也不上传任何个人信息。'
new5 = '5. 本软件默认不联网、不收集也不上传任何个人信息；只有你主动开启「AI 实验功能」并填好接口后，它才会访问该接口，密钥只保存在本机。'
old5e = '5. The app does not use the network and collects no personal data.'
new5e = '5. The app is offline by default and collects no personal data; only after you switch on the experimental AI feature and enter an endpoint does it call that endpoint, and the key is stored on this device only.'
old5j = '5. 本ソフトウェアは通信を行わず、個人情報を収集・送信しません。'
new5j = '5. 既定では通信を行わず、個人情報も収集・送信しません。「AI 実験機能」を有効にしてエンドポイントを入力した場合にのみそのエンドポイントへ通信し、キーは端末内にのみ保存されます。'
for a, b in ((old5, new5), (old5e, new5e), (old5j, new5j)):
    if a not in u: print('WARN not found:', a[:24])
    u = u.replace(a, b)
io.open(L, 'w', encoding='utf-8', newline='').write(u)
print('disclaimer updated')