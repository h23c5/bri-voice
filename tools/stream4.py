# -*- coding: utf-8 -*-
import io
p = r'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\app\src\com\dsh\v4tune\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
new = "    /**\n     * Renders while playing: the first slice is heard after about a second instead of after the\n     * whole song, and the rest is produced in the background as it is needed.\n     */\n    private void playSong() {\n        if (engine.isPlaying()) { stopPlay(); return; }\n        if (!requireVoice()) return;\n        if (!checkBeforePlay()) return;        // overlapping notes: ask before making a sound\n        ensureMixerTracks();\n        engine.mixer = mixer;\n        final VoiceModel vm = currentVoice;\n        if (streamProducer != null) { streamProducer.cancel(); streamProducer = null; }\n        streamProducer = new StreamProducer(song, vm);\n        final long startFrame = (long) (TimeUtil.tickToSec(song, startTick) * 44100);\n        status(L.t(\"status.stream.start\"));\n        playBtn.setText(L.t(\"transport.stop\"));\n        streamProducer.start();\n        engine.playStream(streamProducer, startFrame, new AudioEngine.Callback() {\n            public void onProgress(final long frame) {\n                ui.post(new Runnable() {\n                    public void run() {\n                        long tick = TimeUtil.secToTick(song, frame / 44100.0);\n                        roll.setPlayheadTick(tick);\n                        roll.setVisibleTickRange(tick - 1920, tick + 1920 * 3);\n                        paramView.setPlayheadTick(tick);\n                        posText.setText(formatTick(tick));\n                    }\n                });\n            }\n            public void onDone() {\n                ui.post(new Runnable() {\n                    public void run() {\n                        playBtn.setText(L.t(\"transport.play\"));\n                        startTick = 0L;\n                        roll.setPlayheadTick(0L);\n                        if (paramView != null) paramView.setPlayheadTick(0L);\n                        posText.setText(formatTick(0L));\n                        status(L.t(\"status.play.done\"));\n                    }\n                });\n            }\n            public void onError(final String m) {\n                ui.post(new Runnable() { public void run() { status(L.t(\"status.stream.fail\", m)); } });\n            }\n        });\n    }"
sig = '    private void playSong() {'
i = s.find(sig)
if i < 0: raise SystemExit('playSong not found')
depth = 0; q = s.find('{', i)
while q < len(s):
    if s[q] == '{': depth += 1
    elif s[q] == '}':
        depth -= 1
        if depth == 0: break
    q += 1
end = s.find(chr(10), q)
old = s[i:end]
print('replacing %d chars' % len(old))
s = s[:i] + new + s[end:]
# stopPlay must also stop the producer
if 'streamProducer.cancel()' not in s.split('private void stopPlay()')[1][:600]:
    s = s.replace('    private void stopPlay() {',
                  '    private void stopPlay() {' + chr(10) +
                  '        if (streamProducer != null) { streamProducer.cancel(); streamProducer = null; }', 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('playSong is streaming now')
# the play-done status key
L = p.replace('MainActivity.java', 'i18n\\L.java')
u = io.open(L, encoding='utf-8').read()
if '"status.play.done"' not in u:
    row = '        {"status.play.done", "播放结束", "Playback finished", "再生終了"},'
    k = u.find('{"status.stream.start"')
    j = u.rfind(chr(10), 0, k) + 1
    u = u[:j] + row + chr(10) + u[j:]
    io.open(L, 'w', encoding='utf-8', newline='').write(u)
    print('status.play.done added')