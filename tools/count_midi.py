# -*- coding: utf-8 -*-
"""Independent MIDI note count for the file the app imported."""
import sys, struct
data = open(sys.argv[1], 'rb').read()
print('size', len(data), 'header', data[:4])
fmt, ntrk, div = struct.unpack('>HHH', data[8:14])
print('format', fmt, 'tracks', ntrk, 'division', div)
pos = 14
total = 0
for t in range(ntrk):
    if data[pos:pos+4] != b'MTrk':
        print('track', t, 'missing MTrk at', pos); break
    ln = struct.unpack('>I', data[pos+4:pos+8])[0]
    body = data[pos+8:pos+8+ln]; pos += 8 + ln
    i = 0; run = None; notes = 0; name = ''
    while i < len(body):
        v = 0
        while True:
            b = body[i]; i += 1; v = (v << 7) | (b & 0x7f)
            if not (b & 0x80): break
        if i >= len(body): break
        st = body[i]
        if st & 0x80: run = st; i += 1
        else: st = run
        if st is None: break
        hi = st & 0xf0
        if hi == 0x90:
            if body[i+1] != 0: notes += 1
            i += 2
        elif hi in (0x80, 0xa0, 0xb0, 0xe0): i += 2
        elif hi in (0xc0, 0xd0): i += 1
        elif st == 0xff:
            mt = body[i]; i += 1
            l = 0
            while True:
                b = body[i]; i += 1; l = (l << 7) | (b & 0x7f)
                if not (b & 0x80): break
            payload = body[i:i+l]; i += l
            if mt == 0x03: name = payload.decode('latin-1', 'ignore')
        elif st == 0xf0 or st == 0xf7:
            l = 0
            while True:
                b = body[i]; i += 1; l = (l << 7) | (b & 0x7f)
                if not (b & 0x80): break
            i += l
        else:
            break
    total += notes
    print('  track %d: %-24s notes=%d' % (t, name[:24], notes))
print('TOTAL NOTE-ONs =', total)
