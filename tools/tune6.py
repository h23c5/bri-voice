# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
old = '            int head = Math.min((int) (fs * 0.090), lead);            // at most 90 ms of "borrowed" time'
new = ('            // The consonant is sung before the beat so the vowel lands on it, but 90 ms of that read' + chr(10) +
       '            // as a puff of air in front of every syllable. 55 ms still places the vowel on the' + chr(10) +
       '            // beat without turning the onset into a separate sound.' + chr(10) +
       '            int head = Math.min((int) (fs * 0.055), lead);')
if old not in s: raise SystemExit('lead anchor missing')
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('borrowed lead shortened to 55 ms')