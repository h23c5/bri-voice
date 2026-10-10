# -*- coding: utf-8 -*-
import json, http.client
def call(method, path, body=None):
    c = http.client.HTTPConnection('127.0.0.1', 8765, timeout=15)
    payload = json.dumps(body).encode('utf-8') if body is not None else None
    h = {'Content-Type': 'application/json'} if payload else {}
    c.request(method, path, payload, h)
    r = c.getresponse()
    data = r.read().decode('utf-8')
    c.close()
    return r.status, data

s, d = call('GET', '/status')
print('GET /status ->', s, json.loads(d)['notes'], 'notes')
s, d = call('POST', '/command', {'action': 'addNote', 'key': 72, 'pos': 5760, 'dur': 480, 'lyric': 'la'})
print('POST addNote ->', s, d[:160])
s, d = call('POST', '/command', {'action': 'fly'})
print('POST unknown ->', s, d[:160])
s, d = call('POST', '/command', {'action': 'setParam', 'param': 'DYN', 'points': [[0, 80], [960, 40], [1920, 100]]})
print('POST setParam ->', s, d[:160])
s, d = call('GET', '/status')
print('GET /status ->', s, json.loads(d)['notes'], 'notes')