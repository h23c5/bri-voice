# -*- coding: utf-8 -*-
import io
root = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
t = root + r'\app\src\com\dsh\v4tune\ai\AiTuner.java'
s = io.open(t, encoding='utf-8').read()
blk = io.open(root + r'\tools\ai_parse_patch.txt', encoding='utf-8').read()
# replace the old curve-only parser with the full one
i = s.find('    /**')
j = s.find('     * Validates a reply and returns the curves to apply.')
# locate the old parseCurves block precisely
start = s.find('    /**', s.find('     * Validates a reply'))
if start < 0:
    start = s.find('     * Validates a reply')
    start = s.rfind('    /**', 0, start)
end = s.find('    private static double toD(Object o) {')
if start < 0 or end < 0: raise SystemExit('parser block not found')
s = s[:start] + blk.lstrip(chr(10)) + chr(10) + s[end:]
# the system prompt must describe the whole schema
oldp = s[s.find('    public static String systemPrompt() {'):s.find('    /** a compact description')]
newp = '''    public static String systemPrompt() {
        return "You are a vocal tuning assistant inside a singing synthesizer. "
                + "You receive a part (tempo, notes, current parameter curves) and a request in natural "
                + "language, and you answer with JSON only. "
                + "Schema (every field optional): {\"curves\":{\"DYN\":[[tick,value],...]}, "
                + "\"notes\":[{\"pos\":0,\"key\":64,\"dur\":480,\"vel\":64,\"lyric\":\"la\"}], "
                + "\"tempo\":128.5, \"trackVolume\":100, \"trackPan\":64}. "
                + "Allowed parameters: " + java.util.Arrays.toString(Params.IDS) + ". "
                + "Ranges: DYN/BRE/BRI/CLE/OPE/GEN/POR/VIB/XSY/GWL 0..127, PIT -2400..2400 cents, PBS 0..24, "
                + "key 0..127, vel 0..127, dur in ticks (60 minimum), tempo 20..400 BPM, trackVolume 0..127, "
                + "trackPan 0..127 with 64 as centre. "
                + "In \"notes\" use the pos of an EXISTING note as the key and send only the fields you "
                + "want to change. Ticks use the same unit as the note positions. Keep it musical: gentle "
                + "dynamics on stressed syllables, small pitch movement, no values outside the ranges. "
                + "Answer with the JSON object and nothing else.";
    }

'''
s = s.replace(oldp, newp, 1)
io.open(t, 'w', encoding='utf-8', newline='').write(s)
print('AiTuner extended')