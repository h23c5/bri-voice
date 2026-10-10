# -*- coding: utf-8 -*-
"""Look for the unit -> phoneme index array in the .ddi.

The unit tables carry a header with a count but no obvious PHDC index, and identifying units by formants
mis-assigns (a /7/ unit landed on an /a/-like position). A real index would make the mapping exact.
This scans the .ddi for runs of small integers in [0,61] (the PHDC symbol count) of unit-table length.
"""
import io, os, sys, glob
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

bank = sys.argv[1]
ddi_path = glob.glob(os.path.join(bank, '*.ddi'))[0]
raw = io.open(ddi_path, 'rb').read()
print('.ddi size: %d bytes' % len(raw))

for width, dt in ((1, 'u1'), (2, '<u2'), (4, '<u4')):
    arr = np.frombuffer(raw[:len(raw) - (len(raw) % width)], dtype=dt).astype(np.int64)
    small = (arr >= 0) & (arr <= 61)
    # longest run of small values
    best_len, best_at, i = 0, -1, 0
    while i < small.size:
        if not small[i]:
            i += 1
            continue
        j = i
        while j + 1 < small.size and small[j + 1]:
            j += 1
        if j - i + 1 > best_len:
            best_len, best_at = j - i + 1, i
        i = j + 1
    print('width %d: %d values in [0,61] (%.1f%%), longest run %d at index %d (byte 0x%X)'
          % (width, int(small.sum()), 100.0 * small.sum() / max(1, arr.size), best_len,
             best_at, best_at * width))
    if best_len >= 40:
        seg = arr[best_at:best_at + min(best_len, 80)]
        print('     first values: %s' % ' '.join(str(int(x)) for x in seg[:60]))
        hist = np.bincount(seg, minlength=62)
        print('     distinct symbols used: %d' % int((hist > 0).sum()))