package com.dsh.v4tune.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.TimeUtil;
import com.dsh.v4tune.model.Track;
import com.dsh.v4tune.model.Voice;
import com.dsh.v4tune.synth.Renderer;
import com.dsh.v4tune.synth.SingerLibrary;
import com.dsh.v4tune.synth.VoiceModel;

import java.io.File;
import java.util.ArrayList;

/** Offline rendering, playback and WAV export. */
public class AudioEngine {

    public static final int SR = 44100;

    public interface Callback {
        void onProgress(long tick);
        void onDone();
        void onError(String msg);
    }

    private AudioTrack track;
    private Thread playThread;
    private static int playSession = 0;
    private volatile boolean playing;
    private volatile long playTick;
    private volatile boolean cancelRender;
    /** how far the on-screen cursor is shifted back to match what the user hears */
    public volatile int cursorOffsetMs;

    public boolean isPlaying() { return playing; }
    public long playTick() { return playTick; }
    public void cancelRender() { cancelRender = true; }

    /** map a project voice slot onto one of the built-in singers */
    public static VoiceModel modelFor(Voice v, VoiceModel fallback) {
        if (v == null) return fallback;
        // an exact name+compID match wins, so imported packs keep their own timbre
        VoiceModel exact = SingerLibrary.exact(v.compID == null ? "" : v.compID, v.name == null ? "" : v.name);
        if (exact != null) return exact;
        // No built-in singers exist any more, so a name that merely *looks* like one of the old
        // built-ins must never be sent to SingerLibrary.byId(): that returns null and the renderer
        // then blew up with a NullPointerException (this is what stopped a second track from
        // playing: its voice slot does not match an installed pack exactly).
        // Instead, try a loose match among the INSTALLED voicebanks and otherwise use the caller's
        // fallback, which is the voicebank the user has selected.
        String n = (v.name == null ? "" : v.name).toLowerCase();
        String id = (v.compID == null ? "" : v.compID).toLowerCase();
        String all = (n + " " + id).replace("_", " ").trim();
        if (!all.isEmpty()) {
            for (VoiceModel cand : SingerLibrary.all()) {
                if (cand == null) continue;
                String cn = (cand.name == null ? "" : cand.name).toLowerCase().replace("_", " ");
                String ce = (cand.nameEn == null ? "" : cand.nameEn).toLowerCase().replace("_", " ");
                String cj = (cand.nameJa == null ? "" : cand.nameJa).toLowerCase().replace("_", " ");
                if ((!cn.isEmpty() && (all.contains(cn) || cn.contains(all)))
                        || (!ce.isEmpty() && (all.contains(ce) || ce.contains(all)))
                        || (!cj.isEmpty() && (all.contains(cj) || cj.contains(all)))) {
                    return cand;
                }
            }
        }
        return fallback;
    }

    /** optional per-track mixer; when set it overrides the volume/pan stored in the project */
    public com.dsh.v4tune.audio.Mixer mixer;

    /** output device chosen by the user, or null for the system default */
    private android.media.AudioDeviceInfo preferred;

    public void setPreferredOutput(android.media.AudioDeviceInfo d) {
        preferred = d;
    }

    public android.media.AudioDeviceInfo getPreferredOutput() {
        return preferred;
    }

    /** route one track to the chosen device; silently keeps the default when that is not possible */
    private void applyPreferredDevice(AudioTrack t) {
        if (t == null || preferred == null) return;
        try {
            t.setPreferredDevice(preferred);
        } catch (Throwable ignored) {
        }
    }

    public static class Rendered {
        public float[] left, right;
        public int sampleRate = SR;
        public Song song;
        public long endTick;
    }

    /** Render the whole song (all unmuted tracks) into a stereo buffer. Runs on the caller thread. */
    public Rendered render(Song song, VoiceModel defaultVoice, Renderer.Cfg cfg, Callback cb) {
        cancelRender = false;
        Rendered r = new Rendered();
        r.song = song;
        double total = TimeUtil.tickToSec(song, Math.max(1, song.maxTick())) + 1.0;
        int n = (int) Math.ceil(total * cfg.sampleRate);
        r.left = new float[n];
        r.right = new float[n];
        r.sampleRate = cfg.sampleRate;
        r.endTick = song.maxTick();

        boolean anySolo = false;
        for (Track t : song.tracks) if (t.solo) anySolo = true;

        int trackNo = -1;
        for (Track t : song.tracks) {
            trackNo++;
            if (t.mute) continue;
            if (anySolo && !t.solo) continue;
            double vol = Math.pow(10, (t.vol / 100.0) / 20.0);   // vsqx vol is in centi-dB-ish units
            double pan = (t.pan - 64) / 64.0;                     // -1 .. 1
            boolean mixed = (mixer != null && trackNo < mixer.trackCount());
            if (mixed) {                                          // the mixer overrides the file values
                if (!mixer.isAudible(trackNo)) continue;
                vol = mixer.getGain(trackNo);
                pan = mixer.getPan(trackNo);
            }
            double gl = vol * Math.min(1.0, 1.0 - pan);
            double gr = vol * Math.min(1.0, 1.0 + pan);
            for (Part p : t.parts) {
                if (cancelRender) return r;
                Voice v = song.voice(p.bs, p.pc);
                VoiceModel vm = modelFor(v, defaultVoice);
                if (vm == null) vm = defaultVoice;
                if (vm == null) {                      // nothing installed at all
                    if (cb != null) cb.onError("没有可用的声库，请先安装声库");
                    return r;
                }
                float[] mono = Renderer.renderPart(song, p, vm, cfg);
                long startSample = (long) (TimeUtil.tickToSec(song, p.pos) * cfg.sampleRate);
                for (int i = 0; i < mono.length; i++) {
                    long k = startSample + i;
                    if (k < 0 || k >= n) continue;
                    r.left[(int) k] += (float) (mono[i] * gl);
                    r.right[(int) k] += (float) (mono[i] * gr);
                }
                if (cb != null) cb.onProgress(p.end());
            }
        }
        // master soft clip
        for (int i = 0; i < n; i++) {
            r.left[i] = (float) Math.tanh(r.left[i]);
            r.right[i] = (float) Math.tanh(r.right[i]);
        }
        if (mixer != null) {                      // master stage of the mixer
            float mg = mixer.getMasterGain();
            if (mg != 1f) {
                for (int i = 0; i < n; i++) {
                    r.left[i] = Mixer.softLimit(r.left[i] * mg);
                    r.right[i] = Mixer.softLimit(r.right[i] * mg);
                }
            }
        }
        if (cb != null) cb.onProgress(r.endTick);
        return r;
    }

    /** Render just one part (used for the preview button). */
    public float[] renderPart(Song song, Part part, VoiceModel vm, Renderer.Cfg cfg) {
        return Renderer.renderPart(song, part, vm, cfg);
    }

    public void play(final Rendered r, final Callback cb) {
        play(r, 0L, cb);
    }

    /** Play from an arbitrary sample offset (the draggable playback cursor). */
    public void play(final Rendered r, final long startFrame, final Callback cb) {
        stop();
        final int frames = Math.min(r.left.length, r.right.length);
        if (frames <= 0) { android.util.Log.i("V4Tune", "play(): empty buffer -> onDone"); if (cb != null) cb.onDone(); return; }
        final int bufFrames = 8192;
        final short[] inter = new short[bufFrames * 2];
        final int minBuf = AudioTrack.getMinBufferSize(r.sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        final AudioTrack at = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(r.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuf, bufFrames * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        applyPreferredDevice(at);
        track = at;
        playing = true;
        final int session = ++playSession;
        android.util.Log.i("V4Tune", "play(): session=" + session + " frames=" + frames + " startFrame=" + startFrame);
        playThread = new Thread(new Runnable() {
            public void run() {
                try {
                    at.play();
                    final long start = Math.max(0L, Math.min(startFrame, frames - 1));
                    final long wallStart = System.currentTimeMillis();
                    int pos = (int) start;
                    long lastCb = 0;
                    // feed the track; the writer usually finishes long before the audio has played
                    while (playing && pos < frames) {
                        int cnt = Math.min(bufFrames, frames - pos);
                        for (int i = 0; i < cnt; i++) {
                            float l = r.left[pos + i], rr = r.right[pos + i];
                            if (l > 1) l = 1; else if (l < -1) l = -1;
                            if (rr > 1) rr = 1; else if (rr < -1) rr = -1;
                            inter[i * 2] = (short) (l * 32000);
                            inter[i * 2 + 1] = (short) (rr * 32000);
                        }
                        int written = at.write(inter, 0, cnt * 2);
                        if (written <= 0) break;
                        pos += Math.min(cnt, written / 2);
                        long now = System.currentTimeMillis();
                        if (cb != null && now - lastCb > 100) {
                            lastCb = now;
                            // Follow the frames the device has actually played out, not the writer's
                            // position: with a few hundred ms of output buffer the cursor used to run
                            // visibly ahead of the sound. The wall clock stays the upper bound so the
                            // cursor can never jump forward.
                            long head = 0;
                            try { head = at.getPlaybackHeadPosition(); } catch (Throwable ignored) { }
                            long byClock = start + (long) ((now - wallStart) * r.sampleRate / 1000.0);
                            long played = (head > 0) ? Math.min(start + head, byClock) : byClock;
                            played -= (long) (cursorOffsetMs * r.sampleRate / 1000.0);
                            if (played < start) played = start;
                            playTick = TimeUtil.secToTick(r.song, played / (double) r.sampleRate);
                            cb.onProgress(playTick);
                        }
                    }
                    // The writer may finish long before the audio has been heard (big device buffer), so the
                    // remaining time is played out on a wall-clock schedule - that is what the user perceives
                    // and it keeps the on-screen playhead in step with the sound.
                    final long totalMs = (long) ((frames - start) * 1000.0 / r.sampleRate);
                    while (playing) {
                        long elapsed = System.currentTimeMillis() - wallStart;
                        long now = System.currentTimeMillis();
                        if (cb != null && now - lastCb > 80) {
                            lastCb = now;
                            long played = Math.min(frames, start + (long) (elapsed * r.sampleRate / 1000.0));
                            playTick = TimeUtil.secToTick(r.song, played / (double) r.sampleRate);
                            cb.onProgress(playTick);
                        }
                        if (elapsed >= totalMs) break;
                        try { Thread.sleep(25); } catch (InterruptedException ignored) {}
                    }
                    android.util.Log.i("V4Tune", "play loop end: session=" + session + " playing=" + playing + " start=" + start
                            + " frames=" + frames + " elapsedMs=" + (System.currentTimeMillis() - wallStart)
                            + " totalMs=" + totalMs);
                    if (playing && cb != null) cb.onDone();
                } catch (Throwable t) {
                    if (cb != null) cb.onError(String.valueOf(t.getMessage()));
                } finally {
                    playing = false;
                    try { at.stop(); } catch (Throwable ignored) {}
                    try { at.release(); } catch (Throwable ignored) {}
                }
            }
        }, "v4tune-play");
        playThread.start();
    }

    public void stop() {
        playing = false;
        if (playThread != null) {
            try { playThread.join(500); } catch (InterruptedException ignored) {}
            playThread = null;
        }
        if (track != null) {
            try { track.pause(); track.flush(); } catch (Throwable ignored) {}
            track = null;
        }
    }

    // ------------------------------------------------------------- auditioning


    // ------------------------------------------------------- streaming playback

    /** a song that keeps being rendered while it is already playing */
    public interface StreamSource {
        /** frames rendered and post-processed so far (grows) */
        int ready();
        /** frames the finished song will have */
        int total();
        /** true once nothing more will be produced */
        boolean finished();
        /** the song being rendered, so ticks can be converted with its tempo map */
        Song song();
        float[] left();
        float[] right();
    }

    /** frames a full render of this song produces (kept in step with render()) */
    public static int trackFrames(Song song, Renderer.Cfg cfg) {
        double total = TimeUtil.tickToSec(song, Math.max(1, song.maxTick())) + 1.0;
        return (int) Math.ceil(total * cfg.sampleRate);
    }

    /**
     * Plays a song that is still being rendered. The writer only waits when it would otherwise run
     * past what the producer has finished, so the first sound comes out after the first chunk
     * instead of after the whole song.
     */
    public void playStream(final StreamSource src, final long startFrame, final Callback cb) {
        stop();
        final int total = src.total();
        if (total <= 0) { if (cb != null) cb.onDone(); return; }
        final int sr = 44100;
        final int bufFrames = 8192;
        final short[] inter = new short[bufFrames * 2];
        final int minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        final AudioTrack at = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuf, bufFrames * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        applyPreferredDevice(at);
        track = at;
        playing = true;
        final int session = ++playSession;
        android.util.Log.i("V4Tune", "playStream(): session=" + session + " total=" + total
                + " startFrame=" + startFrame);
        playThread = new Thread(new Runnable() {
            public void run() {
                long wallStart = 0;
                try {
                    at.play();
                    final long start = Math.max(0L, Math.min(startFrame, total - 1));
                    wallStart = System.currentTimeMillis();
                    int pos = (int) start;
                    long lastCb = 0;
                    long waitedMs = 0;
                    while (playing && pos < total) {
                        int ready = src.ready();
                        boolean done = src.finished();
                        if (ready <= pos) {
                            if (done) break;
                            try { Thread.sleep(15); } catch (InterruptedException ignored) { }
                            waitedMs += 15;
                            continue;
                        }
                        int cnt = Math.min(bufFrames, Math.min(ready, total) - pos);
                        for (int i = 0; i < cnt; i++) {
                            float l = src.left()[pos + i], rr = src.right()[pos + i];
                            if (l > 1) l = 1; else if (l < -1) l = -1;
                            if (rr > 1) rr = 1; else if (rr < -1) rr = -1;
                            inter[i * 2] = (short) (l * 32000);
                            inter[i * 2 + 1] = (short) (rr * 32000);
                        }
                        int written = at.write(inter, 0, cnt * 2);
                        if (written <= 0) break;
                        pos += Math.min(cnt, written / 2);
                        long now = System.currentTimeMillis();
                        if (cb != null && now - lastCb > 100) {
                            lastCb = now;
                            long head = 0;
                            try { head = at.getPlaybackHeadPosition(); } catch (Throwable ignored) { }
                            long byClock = start + (long) ((now - wallStart) * sr / 1000.0);
                            long played = (head > 0) ? Math.min(start + head, byClock) : byClock;
                            played -= (long) (cursorOffsetMs * sr / 1000.0);
                            if (played < start) played = start;
                            playTick = TimeUtil.secToTick(src.song(), played / (double) sr);
                            cb.onProgress(played);
                        }
                    }
                    android.util.Log.i("V4Tune", "playStream writer done: session=" + session
                            + " pos=" + pos + " waitedMs=" + waitedMs);
                    final long startMs = wallStart;
                    while (playing) {
                        long elapsed = System.currentTimeMillis() - startMs;
                        // Playback must end when the audio track has actually played the song, not when
                        // the wall clock says so. Rendering takes real time and the writer sleeps while it
                        // waits for data, so the clock runs ahead of the sound; on a long song the doubling
                        // renderer falls several seconds behind and the clock-based check cut the tail off -
                        // the last notes simply never sounded. Prefer the track's own head position and keep
                        // the clock only as a fallback plus a hard ceiling so this can never hang.
                        long head = 0;
                        try { head = at.getPlaybackHeadPosition(); } catch (Throwable ignored) { }
                        long byClock = (long) (elapsed * sr / 1000.0);
                        long heard = (head > 0) ? (start + head) : byClock;
                        heard -= (long) (cursorOffsetMs * sr / 1000.0);
                        if (heard >= total - start) break;
                        if (elapsed > (long) ((total - start) * 1000.0 / sr) + 8000L) break;;
                        if (cb != null) {
                            long now = System.currentTimeMillis();
                            if (now - lastCb > 100) {
                                lastCb = now;
                                cb.onProgress(Math.max(start, start + heard));
                            }
                        }
                        try { Thread.sleep(25); } catch (InterruptedException ignored) { }
                    }
                    if (playing && cb != null) cb.onDone();
                } catch (Throwable t) {
                    android.util.Log.w("V4Tune", "playStream failed: " + t);
                } finally {
                    playing = false;
                    try { at.stop(); at.release(); } catch (Throwable ignored) { }
                    if (track == at) track = null;
                }
            }
        }, "v4tune-play");
        playThread.start();
    }
    private AudioTrack auditionTrack;
    private int auditionRate;

    /** Render a single "a" note for the piano-key audition. */
    public static float[] renderKey(VoiceModel vm, int key) {
        com.dsh.v4tune.model.Song s = com.dsh.v4tune.model.Song.empty();
        s.tempos.clear();
        s.tempos.add(new long[]{0, 12000});
        com.dsh.v4tune.model.Part p = s.tracks.get(0).parts.get(0);
        p.pos = 0;
        p.dur = 960;
        com.dsh.v4tune.model.Note n = new com.dsh.v4tune.model.Note();
        n.pos = 0;
        n.dur = 900;
        n.key = key;
        n.vel = 72;
        n.lyric = "a";
        p.notes.add(n);
        Renderer.Cfg c = new Renderer.Cfg();
        c.sampleRate = SR;
        c.tailSec = 0.10;
        c.masterGain = 0.8;
        return Renderer.renderPart(s, p, vm, c);
    }

    /** Fire-and-forget playback for the keyboard (kept on one reused track). */
    public synchronized void audition(float[] pcm, int sampleRate) {
        if (pcm == null || pcm.length == 0) return;
        try {
            if (auditionTrack == null || auditionRate != sampleRate) {
                releaseAudition();
                auditionRate = sampleRate;
                auditionTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(4096, pcm.length * 2))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                applyPreferredDevice(auditionTrack);
            }
            try { auditionTrack.pause(); auditionTrack.flush(); } catch (Throwable ignored) {}
            auditionTrack.play();
            short[] s16 = new short[pcm.length];
            for (int i = 0; i < pcm.length; i++) {
                float v = pcm[i];
                if (v > 1) v = 1; else if (v < -1) v = -1;
                s16[i] = (short) (v * 32000);
            }
            auditionTrack.write(s16, 0, s16.length);
        } catch (Throwable ignored) {
        }
    }

    public synchronized void releaseAudition() {
        if (auditionTrack != null) {
            try { auditionTrack.stop(); } catch (Throwable ignored) {}
            try { auditionTrack.release(); } catch (Throwable ignored) {}
            auditionTrack = null;
        }
    }

    /** Write a stereo 16 bit WAV file. */
    public static void writeWav(File f, Rendered r) throws Exception {
        com.dsh.v4tune.synth.WavWriter.writeStereo16(f, r.left, r.right, r.sampleRate);
    }

    public static void writeWavMono(File f, float[] mono, int sr) throws Exception {
        com.dsh.v4tune.synth.WavWriter.writeMono16(f, mono, sr);
    }
}
