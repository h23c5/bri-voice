# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
p = root + r'\app\src\com\dsh\v4tune\ai\AiTuner.java'
s = io.open(p, encoding='utf-8').read()
def jstr(x):
    return '"' + x.replace(chr(92), chr(92) * 2).replace('"', chr(92) + '"') + '"'
parts = [
    'You are a vocal tuning assistant inside a singing synthesizer. ',
    'You receive a part (tempo, notes, current parameter curves) and a request in natural language, and you answer with JSON only. ',
    'Schema (every field optional): ' + chr(123) + '"curves":{"DYN":[[tick,value],...]}, "notes":[{"pos":0,"key":64,"dur":480,"vel":64,"lyric":"la"}], "tempo":128.5, "trackVolume":100, "trackPan":64' + chr(125) + '. ',
]
lines = []
lines.append('    public static String systemPrompt() {')
lines.append('        return ' + jstr(parts[0]))
lines.append('                + ' + jstr(parts[1]))
lines.append('                + ' + jstr(parts[2]))
lines.append('                + ' + jstr('Allowed parameters: ') + ' + java.util.Arrays.toString(Params.IDS) + ' + jstr('. '))
lines.append('                + ' + jstr('Ranges: DYN/BRE/BRI/CLE/OPE/GEN/POR/VIB/XSY/GWL 0..127, PIT -2400..2400 cents, PBS 0..24, '))
lines.append('                + ' + jstr('key 0..127, vel 0..127, dur in ticks (60 minimum), tempo 20..400 BPM, trackVolume 0..127, trackPan 0..127 with 64 as centre. '))
lines.append('                + ' + jstr('In "notes" use the pos of an EXISTING note as the key and send only the fields you want to change. Ticks use the same unit as the note positions. '))
lines.append('                + ' + jstr('Keep it musical: gentle dynamics on stressed syllables, small pitch movement, no values outside the ranges. Answer with the JSON object and nothing else.'))
lines.append('                ;')
lines.append('    }')
new = chr(10).join(lines)
i = s.find('    public static String systemPrompt() {')
j = s.find('    /** a compact description', i)
if i < 0 or j < 0: raise SystemExit('prompt block not found')
s = s[:i] + new + chr(10) + chr(10) + s[j:]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('prompt rewritten')
print(new[:400])