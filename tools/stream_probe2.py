# -*- coding: utf-8 -*-
import json, http.client, time, subprocess
ADB = r'E:\leidian\LDPlayer14\adb.exe'
def call(method, path, body=None, timeout=60):
    c = http.client.HTTPConnection('127.0.0.1', 8765, timeout=timeout)
    payload = json.dumps(body).encode('utf-8') if body is not None else None
    c.request(method, path, payload, {'Content-Type': 'application/json'} if payload else {})
    r = c.getresponse(); d = r.read().decode('utf-8'); c.close()
    return r.status, d
call('POST', '/command', {'action': 'stop'}); time.sleep(1)
subprocess.run([ADB, '-s', 'emulator-5554', 'logcat', '-c'], capture_output=True)
call('POST', '/command', {'action': 'play', 'fromTick': 0})
t0 = time.time(); prev = None
for i in range(24):
    time.sleep(1.0)
    st = json.loads(call('GET', '/status')[1])
    tick = st['playTick']; el = time.time() - t0
    rate = '' if prev is None else ('  rate=%5.0f tick/s' % ((tick - prev) / (el - prevEl)))
    print('t=%5.1fs tick=%6d play=%s%s' % (el, tick, st['playing'], rate))
    prev = tick; prevEl = el
    if not st['playing']: break
out = subprocess.run([ADB, '-s', 'emulator-5554', 'logcat', '-d', '-s', 'V4Tune:I', 'V4Tune:W'],
                     capture_output=True, text=True, encoding='utf-8', errors='ignore').stdout
print('logs:', [l.split('V4Tune  : ')[-1].strip()[:70] for l in out.splitlines() if 'V4Tune' in l][-3:])
call('POST', '/command', {'action': 'stop'})