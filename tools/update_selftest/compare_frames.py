# -*- coding: utf-8 -*-
import sys
def load(p):
    rows = []
    for ln in open(p):
        parts = ln.split()
        if len(parts) == 2:
            rows.append((int(parts[0]), None)); continue
        rows.append((int(parts[0]), (float(parts[1]), int(parts[2]), float(parts[3]), float(parts[4]))))
    return rows
a = load(sys.argv[1]); b = load(sys.argv[2])
print('rows: py=%d java=%d' % (len(a), len(b)))
if len(a) != len(b): print('RESULT: FAIL (row count)'); sys.exit(1)
skips = d_f0 = d_err = d_sum = 0.0
bad = 0
for (oa, va), (ob, vb) in zip(a, b):
    if oa != ob: bad += 1; print('  offset mismatch %d vs %d' % (oa, ob)); continue
    if (va is None) != (vb is None): bad += 1; print('  skip mismatch at %d' % oa); continue
    if va is None: skips += 1; continue
    if va[1] != vb[1]: bad += 1; print('  k mismatch at %d: %d vs %d' % (oa, va[1], vb[1])); continue
    d_f0 = max(d_f0, abs(va[0] - vb[0]))
    d_err = max(d_err, abs(va[2] - vb[2]))
    d_sum = max(d_sum, abs(va[3] - vb[3]))
print('max |d f0|   = %.3e' % d_f0)
print('max |d err|  = %.3e' % d_err)
print('max |d sum|  = %.3e  (sum of the magnitudes of one frame)' % d_sum)
print('skipped frames: %d' % skips)
tol = 1e-9
ok = bad == 0 and d_f0 < tol and d_err < 1e-15 and d_sum < 1e-6
print('RESULT: ' + ('PASS - Java and Python decode the same frames' if ok else 'FAIL'))
sys.exit(0 if ok else 1)