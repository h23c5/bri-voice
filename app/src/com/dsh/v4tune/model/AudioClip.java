package com.dsh.v4tune.model;

import com.dsh.v4tune.io.WavReader;

/**
 * One imported audio clip (WAV backing track, vocal take, ...) placed on a track timeline.
 *
 * <p>A clip owns decoded 44100 Hz samples ({@link WavReader.AudioData}) plus a tick position,
 * so several clips can live on several tracks at once. The class is plain java: no android
 * classes, so it compiles and runs on a desktop JVM.</p>
 */
public class AudioClip {

    /** Clip start on the track timeline, in ticks (same base as {@link Part#pos}). */
    public long startTick;
    /** Linear gain applied while mixing; 1.0 = unity. */
    public float gain = 1.0f;
    /** When true the clip is mixed as silence. */
    public boolean mute;
    /** Display name; never null when the clip was built with {@link #fromWav}. */
    public String name;
    /** Decoded samples (44100 Hz, mono or stereo); may be null for an empty placeholder. */
    public WavReader.AudioData data;

    /**
     * Clip length in ticks at the given tempo.
     *
     * @param bpm100 tempo as bpm * 100 (120 bpm is 12000); values {@code <= 0} yield 0
     * @return {@code round(frames * TPQ * bpm / (60 * sampleRate))}, 0 when there is no audio
     */
    public long lengthTicks(int bpm100) {
        if (data == null || !data.ok() || data.sampleRate <= 0 || bpm100 <= 0) return 0;
        double ticks = (double) data.frames * TimeUtil.TPQ * (bpm100 / 100.0)
                / (60.0 * data.sampleRate);
        if (ticks >= 9.0e18) return Long.MAX_VALUE;
        return Math.round(ticks);
    }

    /** Tick just past the end of this clip ({@code startTick + lengthTicks}). */
    public long endTick(int bpm100) {
        return startTick + lengthTicks(bpm100);
    }

    /**
     * Builds a clip from decoded audio.
     *
     * @param d         decoded audio, normally from {@link WavReader#read} / {@link WavReader#readFile}
     * @param startTick clip position in ticks
     * @param name      display name; null or empty becomes "AudioClip"
     * @return the clip, or null when {@code d} is null or failed ({@link WavReader.AudioData#ok()})
     */
    public static AudioClip fromWav(WavReader.AudioData d, long startTick, String name) {
        if (d == null || !d.ok()) return null;
        AudioClip c = new AudioClip();
        c.data = d;
        c.startTick = startTick;
        c.name = (name == null || name.length() == 0) ? "AudioClip" : name;
        c.gain = 1.0f;
        c.mute = false;
        return c;
    }

    /**
     * Start frame of this clip in a 44100 Hz mix buffer, following the song tempo map.
     * Returns 0 for a song that places the clip at or before tick 0.
     */
    public int startFrame(Song s) {
        if (s == null || startTick <= 0) return 0;
        double sec = TimeUtil.tickToSec(s, startTick);
        if (sec <= 0) return 0;
        double frame = sec * WavReader.OUT_RATE;
        if (frame >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.round(frame);
    }

    /**
     * Adds this clip into 44100 Hz mix buffers, honouring {@link #gain} and {@link #mute}.
     * Nothing is thrown for clips that fall outside the buffers, they are simply cropped.
     *
     * @param outL      left/mono destination (required)
     * @param outR      right destination, may be null for a mono mix; a mono clip feeds both
     * @param startFrame first destination frame (may be negative, the clip head is cropped)
     * @return number of frames mixed
     */
    public int mixInto(float[] outL, float[] outR, int startFrame) {
        if (mute || gain == 0.0f || outL == null || data == null || !data.ok()) return 0;
        int clipOff = 0;
        int dst = startFrame;
        if (dst < 0) {
            clipOff = -dst;
            dst = 0;
        }
        int n = Math.min(data.frames - clipOff, outL.length - dst);
        if (outR != null) n = Math.min(n, outR.length - dst);
        if (n <= 0) return 0;
        float[] sl = data.left;
        float[] sr = data.right;
        boolean stereoIn = (sr != null);
        for (int i = 0; i < n; i++) {
            int j = clipOff + i;
            float a = sl[j] * gain;
            outL[dst + i] += a;
            if (outR != null) outR[dst + i] += stereoIn ? (sr[j] * gain) : a;
        }
        return n;
    }

    /** Shallow copy: the decoded samples are shared, the placement fields are copied. */
    public AudioClip copy() {
        AudioClip c = new AudioClip();
        c.startTick = startTick;
        c.gain = gain;
        c.mute = mute;
        c.name = name;
        c.data = data;
        return c;
    }
}
