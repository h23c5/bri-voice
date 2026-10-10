# -*- coding: utf-8 -*-
import json, http.client, time
def call(method, path, body=None, port=8765):
    c = http.client.HTTPConnection('127.0.0.1', port, timeout=60)
    payload = json.dumps(body).encode('utf-8') if body is not None else None
    c.request(method, path, payload, {'Content-Type': 'application/json'} if payload else {})
    r = c.getresponse()
    d = r.read().decode('utf-8')
    c.close()
    return r.status, d

print('point the assistant at the stub ->')
print(call('POST', '/command', {'action': 'aiConfig', 'enabled': True,
           'baseUrl': 'http://10.0.2.2:8799/v1', 'model': 'stub-model', 'apiKey': 'sk-test', 'strength': 0.5})[1][:200])
print('ask it to tune ->')
print(call('POST', '/command', {'action': 'aiTune'})[1][:200])
for i in range(30):
    time.sleep(1)
    st = json.loads(call('GET', '/status')[1])
    if not st['ai']['busy']:
        print('after %ds: busy=%s lastApplied=%s lastError=%r' % (i + 1, st['ai']['busy'], st['ai']['lastApplied'], st['ai']['lastError']))
        break
else:
    print('still busy after 30s')