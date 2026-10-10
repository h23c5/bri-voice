# -*- coding: utf-8 -*-
import json, http.client
def call(method, path, body=None):
    c = http.client.HTTPConnection('127.0.0.1', 8765, timeout=30)
    payload = json.dumps(body).encode('utf-8') if body is not None else None
    c.request(method, path, payload, {'Content-Type': 'application/json'} if payload else {})
    r = c.getresponse()
    d = r.read().decode('utf-8')
    c.close()
    return r.status, d
print('before:', json.loads(call('GET', '/status')[1])['partDur'], 'ticks')
tuning = {'action': 'aiApply',
          'curves': {'DYN': [[0, 110], [1500, 45], [3000, 100]], 'VIB': [[0, 60], [2000, 20]]},
          'notes': [{'pos': 0, 'key': 72, 'vel': 110, 'lyric': 'la'}, {'pos': 1440, 'key': 60, 'dur': 900}],
          'tempo': 140, 'trackVolume': 90}
print('aiApply ->', call('POST', '/command', tuning)[1][:200])
st = json.loads(call('GET', '/status')[1])
print('after :', 'notes=%s' % st['notes'], 'dur=%s' % st['partDur'])