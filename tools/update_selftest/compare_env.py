# -*- coding: utf-8 -*-
import sys
a = [l.split() for l in open(sys.argv[1]) if l.strip()]
b = [l.split() for l in open(sys.argv[2]) if l.strip()]
print('rows py=%d java=%d' % (len(a), len(b)))
if len(a) != len(b) or not a: print('RESULT: FAIL (row count)'); sys.exit(1)
worst = 0.0; wi = None
for r, (ra, rb) in enumerate(zip(a, b)):
    if len(ra) != len(rb): print('RESULT: FAIL (width at row %d)' % r); sys.exit(1)
    for i, (x, y) in enumerate(zip(ra, rb)):
        d = abs(float(x) - float(y))
        if d > worst: worst, wi = d, (r, i, x, y)
print('values compared: %d' % (len(a) * len(a[0])))
print('max |diff| = %.3e' % worst)
if wi: print('  at row %d col %d: py=%s java=%s' % wi)
ok = worst < 1e-9
print('RESULT: ' + ('PASS - frame envelopes are identical' if ok else 'FAIL'))
sys.exit(0 if ok else 1)