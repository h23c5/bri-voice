# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\FrameVoice.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
    print('ok:', what)

rep('    private int pending;                         // grains still overlapping', "    private int readIdx;                         // where the next output sample lives\n    private int leftInHop;                       // samples still to emit from the current grain", 'state')
rep('        pending = 0;', '        readIdx = 0;' + chr(10) + '        leftInHop = 0;', 'reset')
rep("    /** one output sample; hops are synthesised on demand */\n    public float sample() {\n        if (!haveFrame) return 0.0f;\n        if (pending <= 0) synthHop();\n        double v = acc[0];\n        System.arraycopy(acc, 1, acc, 0, acc.length - 1);\n        acc[acc.length - 1] = 0.0;\n        pending--;\n        return (float) v;\n    }", "    /** one output sample; a new grain is synthesised every hop */\n    public float sample() {\n        if (!haveFrame) return 0.0f;\n        if (leftInHop <= 0) {\n            synthHop();\n            leftInHop = hop;\n        }\n        double v = acc[readIdx];\n        acc[readIdx] = 0.0;                       // consumed: the tail has already been summed in\n        readIdx = (readIdx + 1) % window;\n        leftInHop--;\n        return (float) v;\n    }", 'sample loop')
rep("        // overlap-add: Hann at hop = window/4 sums to a constant\n        for (int i = 0; i < window; i++) acc[i] += grain[i];\n        pending = hop;\n        hopCount++;", "        // overlap-add: the new grain starts at the sample the reader is about to emit, so the tails\n        // of the previous grains are added to it in place (circular buffer, O(1) per sample)\n        int at = readIdx;\n        for (int i = 0; i < window; i++) {\n            acc[at] += grain[i];\n            at++;\n            if (at == window) at = 0;\n        }\n        hopCount++;", 'ola')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('circular OLA in place')