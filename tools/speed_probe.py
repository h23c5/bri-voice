# -*- coding: utf-8 -*-
import json, http.client, time
def call(method, path, body=None, timeout=60):
    c = http.client.HTTPConnection('127.0.0.1', 8765, timeout=timeout)
    payload = json.dumps(body).encode('utf-8') if body is not None else None
    c.request(method, path, payload, {'Content-Type': 'application/json'} if payload else {})
    r = c.getresponse()
    d = r.read().decode('utf-8')
    c.close()
    return r.status, d
st = json.loads(call('GET', '/status')[1])
print('partDur=%s ticks, playing=%s' % (st['partDur'], st['playing']))
print('play ->', call('POST', '/command', {'action': 'play', 'fromTick': 0})[1][:120])
t0 = time.time()
prev = None
for i in range(28):
    time.sleep(0.25)
    st = json.loads(call('GET', '/status')[1])
    tick = st['playTick']; el = time.time() - t0
    rate = '' if prev is None else ('  rate=%.0f tick/s' % ((tick - prev[1]) / max(1e-6, el - prev[0])))
    print('t=%5.2fs tick=%6d%s%s' % (el, tick, rate, '' if st['playing'] else '  (stopped)'))
    prev = (el, tick)
    if not st['playing']:
        break
call('POST', '/command', {'action': 'stop'})