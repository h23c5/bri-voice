# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
# FrameVoice: a clear() so the renderer can fall back when a phoneme has no measured data
f = root + r'\app\src\com\dsh\v4tune\synth\FrameVoice.java'
s = io.open(f, encoding='utf-8').read()
if 'public void clear()' not in s:
    s = s.replace('    public boolean isActive()',
                  '    /** no measured data for the current phoneme: fall back to the formant path */' + chr(10) +
                  '    public void clear() { haveFrame = false; }' + chr(10) + chr(10) +
                  '    public boolean isActive()', 1)
    io.open(f, 'w', encoding='utf-8', newline='').write(s)
    print('ok: clear()')
# Renderer: level + only use the frame path for phonemes that actually have measured frames
p = root + r'\app\src\com\dsh\v4tune\synth\Renderer.java'
t = io.open(p, encoding='utf-8').read()
t = t.replace('FRAME_GAIN = 1.0;', 'FRAME_GAIN = 0.27;   // measured: matches the table path level', 1)
old = ('                        if (fv != null) {' + chr(10) +
       '                            fv.setFrame(f0, vm.harmonicFreqs, envMix, voicedCur, seqGain * FRAME_GAIN);' + chr(10) +
       '                        }')
new = ('                        if (fv != null) {' + chr(10) +
       '                            if (b1 != null) {' + chr(10) +
       '                                fv.setFrame(f0, vm.harmonicFreqs, envMix, voicedCur, seqGain * FRAME_GAIN);' + chr(10) +
       '                            } else {' + chr(10) +
       '                                // this phoneme was never measured: sing it with the formant path' + chr(10) +
       '                                fv.clear();' + chr(10) +
       '                            }' + chr(10) +
       '                        }')
if old not in t: raise SystemExit('frame feed anchor missing')
t = t.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(t)
print('ok: fallback + gain')