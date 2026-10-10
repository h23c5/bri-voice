# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\synth\Renderer.java'
s = io.open(p, encoding='utf-8').read()
def rep(old, new, what):
    global s
    if old not in s: raise SystemExit('anchor missing: ' + what)
    s = s.replace(old, new, 1)
# 1) the public entry point delegates to a limited variant
rep('    public static float[] renderPart(Song song, Part part, VoiceModel vm, Cfg cfg) {',
    ('    public static float[] renderPart(Song song, Part part, VoiceModel vm, Cfg cfg) {' + chr(10) +
     '        return renderPart(song, part, vm, cfg, Integer.MAX_VALUE);' + chr(10) +
     '    }' + chr(10) + chr(10) +
     '    /**' + chr(10) +
     '     * Renders the part up to {@code limitFrames}. The synthesis always starts at frame 0 so every' + chr(10) +
     '     * filter and envelope sees the same history as a full render; only the work past the limit is' + chr(10) +
     '     * skipped. This is what lets playback start after the first second instead of the whole song.' + chr(10) +
     '     */' + chr(10) +
     '    public static float[] renderPart(Song song, Part part, VoiceModel vm, Cfg cfg, int limitFrames) {'), 'entry')
# 2) clamp the length
rep('        if (n < 1) n = 1;',
    ('        if (limitFrames > 0 && n > limitFrames) n = limitFrames;' + chr(10) +
     '        if (n < 1) n = 1;'), 'clamp')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('renderPart limit added')