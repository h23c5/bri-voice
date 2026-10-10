# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\render_test\VowelFormantTest.java'
s = io.open(p, encoding='utf-8').read()
s = s.replace('            double sum = 0; int n = 0;', '            double sum = 0; int cnt = 0;', 1)
s = s.replace('            for (int j = Math.max(1, k - 1); j <= Math.min(K, k + 1); j++) { sum += db[j]; n++; }',
              '            for (int j = Math.max(1, k - 1); j <= Math.min(K, k + 1); j++) { sum += db[j]; cnt++; }', 1)
s = s.replace('            sm[k] = sum / n;', '            sm[k] = sum / cnt;', 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('fixed')