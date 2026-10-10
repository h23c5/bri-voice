# -*- coding: utf-8 -*-
import json, http.client, time, subprocess, re
ADB = r'E:\leidian\LDPlayer14\adb.exe'
def call(method, path, body=None, timeout=60):
    c = http.client.HTTPConnection('127.0.0.1', 8765, timeout=timeout)
    payload = json.dumps(body).encode('utf-8') if body is not None else None
    c.request(method, path, payload, {'Content-Type': 'application/json'} if payload else {})
    r = c.getresponse(); d = r.read().decode('utf-8'); c.close()
    return r.status, d
st = json.loads(call('GET', '/status')[1])
dur = st['partDur']
print('song length: %d ticks (~%.0f s at 120bpm), notes=%d' % (dur, dur / 480.0 * 0.5, st['notes']))
subprocess.run([ADB, '-s', 'emulator-5554', 'logcat', '-c'], capture_output=True)
t0 = time.time()
print('play ->', call('POST', '/command', {'action': 'play', 'fromTick': 0})[1][:100])
started = None
for i in range(120):
    time.sleep(0.25)
    out = subprocess.run([ADB, '-s', 'emulator-5554', 'logcat', '-d', '-s', 'V4Tune:I'],
                         capture_output=True, text=True, encoding='utf-8', errors='ignore').stdout
    if 'playStream():' in out:
        started = time.time() - t0
        for line in out.splitlines():
            if 'playStream():' in line:
                print('  ' + line.strip())
        break
print('time from play command to audio start: %.2f s' % (started if started else -1))
time.sleep(6)
out = subprocess.run([ADB, '-s', 'emulator-5554', 'logcat', '-d', '-s', 'V4Tune:I'],
                     capture_output=True, text=True, encoding='utf-8', errors='ignore').stdout
for line in out.splitlines()[-4:]:
    print('  ' + line.strip())
call('POST', '/command', {'action': 'stop'})