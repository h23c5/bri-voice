# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\tools\render_test\FricativeSpectrumTest.java'
s = io.open(p, encoding='utf-8').read()
s = s.replace('VoiceModel.HarmonicBand band = vm.harmonics.get(c);',
              'VoiceModel.HarmonicBand[] arr = vm.harmonics.get(c);' + chr(10) +
              '        VoiceModel.HarmonicBand band = (arr == null || arr.length == 0) ? null : arr[Math.min(arr.length / 2, arr.length - 1)];')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('fixed')