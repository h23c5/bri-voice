# -*- coding: utf-8 -*-
import json, http.client, time
def call(method, path, body=None, timeout=90):
    c = http.client.HTTPConnection('127.0.0.1', 8765, timeout=timeout)
    payload = json.dumps(body).encode('utf-8') if body is not None else None
    c.request(method, path, payload, {'Content-Type': 'application/json'} if payload else {})
    r = c.getresponse()
    d = r.read().decode('utf-8')
    c.close()
    return r.status, d
st = json.loads(call('GET', '/status')[1])
print('clips before:', st.get('clips'))
print('import ->', call('POST', '/command', {'action': 'importWav', 'path': '/sdcard/Download/tone1500.wav'})[1][:200])
st = json.loads(call('GET', '/status')[1])
print('clips after :', st.get('clips'))
print('move ->', call('POST', '/command', {'action': 'moveClip', 'index': 0, 'startTick': 3840})[1][:120])
print('export ->', call('POST', '/command', {'action': 'exportWav', 'path': '/sdcard/BriVoice_root/export/api_mix.wav'})[1][:160])