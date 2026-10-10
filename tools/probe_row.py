# -*- coding: utf-8 -*-
import sys
a = [l.split() for l in open(sys.argv[1]) if l.strip()]
b = [l.split() for l in open(sys.argv[2]) if l.strip()]
grid = [float(x) for x in a[0]]
print('grid[0]=%.3f grid[-1]=%.3f  n=%d' % (grid[0], grid[-1], len(grid)))
for row in (7,):
    pa = [float(x) for x in a[row]]
    pb = [float(x) for x in b[row]]
    bad = [i for i in range(len(pa)) if abs(pa[i] - pb[i]) > 1e-9]
    print('row %d: %d/%d columns differ; first=%s last=%s' % (row, len(bad), len(pa), bad[0] if bad else '-', bad[-1] if bad else '-'))
    print('  idx  freq(Hz)      python          java')
    for i in range(0, len(pa), 4):
        mark = ' <<<' if abs(pa[i]-pb[i]) > 1e-9 else ''
        print('  %3d  %9.2f  %13.6f  %13.6f%s' % (i, grid[i], pa[i], pb[i], mark))
    print('  max py=%.6f at %d ; max java=%.6f at %d' % (max(pa), pa.index(max(pa)), max(pb), pb.index(max(pb))))
    print('  min py=%.6f ; min java=%.6f' % (min(pa), min(pb)))