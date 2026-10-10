# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
if 'vm.noiseDb.get' in s:
    print('already wired')
else:
    old = "                        if (ns != null) {\n                            if (!voicedCur && b1 != null) ns.set(vm.harmonicFreqs, envMix, 30.0);\n                            else ns.reset();\n                        }"
    if old not in s: raise SystemExit('anchor missing')
    s = s.replace(old, "                        if (ns != null) {\n                            // A fricative the bank's own SND records measured beats the generic shape:\n                            // the converter names a class only when its spectrum really matches the\n                            // CHN signature, so this is the bank's own noise.\n                            double[] measured = voicedCur ? null : vm.noiseDb.get(cur.sym);\n                            if (measured != null) {\n                                double[] f = vm.noiseFreqs.length > 1 ? vm.noiseFreqs : vm.harmonicFreqs;\n                                if (measured.length == f.length) ns.set(f, measured, 30.0);\n                                else ns.reset();\n                            } else if (!voicedCur && b1 != null) {\n                                ns.set(vm.harmonicFreqs, envMix, 30.0);\n                            } else {\n                                ns.reset();\n                            }\n                        }", 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('renderer uses measured fricative noise')