# -*- coding: utf-8 -*-
import json, http.client, time
def call(method, path, body=None):
    c = http.client.HTTPConnection('127.0.0.1', 8765, timeout=70)
    payload = json.dumps(body).encode('utf-8') if body is not None else None
    c.request(method, path, payload, {'Content-Type': 'application/json'} if payload else {})
    r = c.getresponse()
    d = r.read().decode('utf-8')
    c.close()
    return r.status, d
for url in ('http://10.0.2.2:8799/v1', 'http://localhost:8799/v1'):
    print('== trying', url)
    print(call('POST', '/command', {'action': 'aiConfig', 'enabled': True, 'baseUrl': url,
               'model': 'stub-model', 'apiKey': 'sk-test', 'strength': 0.5})[1][:140])
    call('POST', '/command', {'action': 'aiTune'})
    for i in range(25):
        time.sleep(1)
        st = json.loads(call('GET', '/status')[1])
        if not st['ai']['busy']:
            print('   after %ds: lastApplied=%s lastError=%r' % (i + 1, st['ai']['lastApplied'], st['ai']['lastError']))
            break
    else:
        print('   still busy')