# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
i = s.find('private final class StreamProducer')
j = s.find('    /** clips and the plugin chain for one freshly rendered slice */', i)
if i < 0 or j < 0: raise SystemExit('producer block not found')
blk = s[i:j]
blk2 = blk.replace('float[] L, R;', 'float[] bufL, bufR;')
blk2 = blk2.replace('L = new float[total];', 'bufL = new float[total];')
blk2 = blk2.replace('R = new float[total];', 'bufR = new float[total];')
blk2 = blk2.replace('public float[] left() { return L; }', 'public float[] left() { return bufL; }')
blk2 = blk2.replace('public float[] right() { return R; }', 'public float[] right() { return bufR; }')
blk2 = blk2.replace('System.arraycopy(mixL, 0, L, 0, limit);', 'System.arraycopy(mixL, 0, bufL, 0, limit);')
blk2 = blk2.replace('System.arraycopy(mixR, 0, R, 0, limit);', 'System.arraycopy(mixR, 0, bufR, 0, limit);')
blk2 = blk2.replace('rr.left = L; rr.right = R;', 'rr.left = bufL; rr.right = bufR;')
s = s[:i] + blk2 + s[j:]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('renamed; remaining bare L refs in the producer:', blk2.count(' L,') + blk2.count('(L)'))