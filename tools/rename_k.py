# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
s = s.replace('int k = st + i;', 'int idx = st + i;')
s = s.replace('if (k < 0 || k >= out.length) continue;', 'if (idx < 0 || idx >= out.length) continue;')
s = s.replace('out[k] += (float) (nz.next() * e * e * 0.06 * vm.breathSoundAmount);', 'out[idx] += (float) (nz.next() * e * e * 0.06 * vm.breathSoundAmount);')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('renamed')