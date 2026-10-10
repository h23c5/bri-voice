# -*- coding: utf-8 -*-
import json, http.client, time
def call(method, path, body=None):
    c = http.client.HTTPConnection('127.0.0.1', 8765, timeout=90)
    payload = json.dumps(body).encode('utf-8') if body is not None else None
    c.request(method, path, payload, {'Content-Type': 'application/json'} if payload else {})
    r = c.getresponse()
    d = r.read().decode('utf-8')
    c.close()
    return r.status, d
print(call('POST', '/command', {'action': 'aiConfig', 'enabled': True,
           'baseUrl': 'https://api.openai.com/v1', 'model': 'gpt-4o-mini', 'apiKey': 'sk-not-a-real-key'})[1][:160])
call('POST', '/command', {'action': 'aiTune'})
for i in range(60):
    time.sleep(1)
    st = json.loads(call('GET', '/status')[1])
    if not st['ai']['busy']:
        print('after %ds: lastApplied=%s lastError=%r' % (i + 1, st['ai']['lastApplied'], st['ai']['lastError']))
        break
else:
    print('still busy')
print('help:', call('GET', '/help')[1][:220])