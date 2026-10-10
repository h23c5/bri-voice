import importlib.util, os, sys, io
spec = importlib.util.spec_from_file_location('vp', os.path.join(r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android', 'tools', 'voicebank_pack.py'))
vp = importlib.util.module_from_spec(spec); spec.loader.exec_module(vp)
rows = [
  (60, 5200.0, 2600.0, 0.30, -30.0),   # s-ish
  (40, 3000.0, 1800.0, 0.25, -28.0),   # s\/x window
  (30, 3000.0, 900.0, 0.20, -26.0),    # narrow, same window -> competes
  (50, 2600.0, 700.0, 0.15, -25.0),    # s`
  (70, 2000.0, 1500.0, 0.40, -27.0),   # f (broad, ratio 0.75)
  (55, 1000.0, 900.0, 0.22, -24.0),    # h
  (80, 900.0, 600.0, 0.35, -60.0),     # Asp (quiet + broad)
  (10, 3000.0, 500.0, 0.30, -20.0),    # too few members -> ignored
  (60, 3000.0, 800.0, 0.005, -22.0),   # tonal -> ignored
]
classes = [{'n': r[0], 'centroid': r[1], 'bandwidth': r[2], 'flatness': r[3], 'level': r[4]} for r in rows]
named, lvl = vp.classify_noise_classes(classes)
lines = ['lvl_med=%.6f' % lvl] + ['%s=%d' % (k, named[k]) for k in sorted(named)]
io.open(sys.argv[1], 'w', newline='\n').write('\n'.join(lines) + '\n')
print('py: ' + ' '.join(lines))