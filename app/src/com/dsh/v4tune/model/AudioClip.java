package com.dsh.v4tune.model;

import com.dsh.v4tune.io.PcmFile;
import com.dsh.v4tune.io.WavReader;

/**
 * One imported audio clip (WAV backing track, vocal take, ...) placed on a track timeline.
 *
 * <p>The audio itself normally lives in an int16 stereo sidecar file ({@link #pcm}) and is read
 * block by block while mixing: a four minute backing track would be ~63 MB as float arrays in the
 * heap, which is why imports used to be cut at three minutes. The in-memory {@link #data} field is
 * still honoured for small clips and for the desktop probes.</p>
 *
 * <p>Plain java: no android classes, so it compiles and runs on a desktop JVM.</p>
 */
public class AudioClip {

    /** Clip start on the track timeline, in ticks (same base as {@link Part#pos}). */
    public long startTick;
    /** Linear gain applied while mixing; 1.0 = unity. */
    public float gain = 1.0f;
    /** When true the clip is mixed as silence. */
    public boolean mute;
    /** Display name; never null when the clip was built with {@link #fromWav}/{@link #fromPcm}. */
    public String name;
    /** Decoded samples (44100 Hz); only set by the small in-memory path. */
    public WavReader.AudioData data;
    /** Sidecar file with this clip's audio; the normal path for imported WAVs. */
    public java.io.File pcm;
    /** Frames stored in {@link #pcm} at 44100 Hz (0 when there is no sidecar). */
    public int pcmFrames;
    /** Normalised 0..1 waveform thumbnail; empty when unknown. */
    public float[] peaks;

    /** Frames of audio this clip carries, whichever storage it uses. */
    public int frames() {
        if (data != null) return data.frames;
        return Math.max(0, pcmFrames);
    }

    /**
     * Clip length in ticks at the given tempo.
     *
     * @param bpm100 tempo as bpm * 100 (120 bpm is 12000); values {@code <= 0} yield 0
     * @return {@code round(frames * TPQ * bpm / (60 * sampleRate))}, 0 when there is no audio
     */
    public long lengthTicks(int bpm100) {
        int frames = frames();
        int rate = (data != null && data.sampleRate > 0) ? data.sampleRate : PcmFile.RATE;
        if (frames <= 0 || rate <= 0 || bpm100 <= 0) return 0;
        double ticks = (double) frames * TimeUtil.TPQ * (bpm100 / 100.0) / (60.0 * rate);
        if (ticks >= 9.0e18) return Long.MAX_VALUE;
        return Math.round(ticks);
    }

    /** Tick just past the end of this clip ({@code startTick + lengthTicks}). */
    public long endTick(int bpm100) {
        return startTick + lengthTicks(bpm100);
    }

    /**
     * Builds a clip from decoded audio (in-memory path).
     *
     * @param d         decoded audio, normally from {@link WavReader#read}
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
     * Builds a clip backed by a streamed sidecar file.
     *
     * @param pcm       sidecar written by {@link WavReader#exportPcm}
     * @param frames    frames in the sidecar at 44100 Hz
     * @param startTick clip position in ticks
     * @param name      display name; null or empty becomes "AudioClip"
     * @return the clip, or null for a missing file / empty audio
     */
    public static AudioClip fromPcm(java.io.File pcm, int frames, long startTick, String name) {
        if (pcm == null || frames <= 0) return null;
        AudioClip c = new AudioClip();
        c.pcm = pcm;
        c.pcmFrames = frames;
        c.startTick = startTick;
        c.name = (name == null || name.length() == 0) ? "AudioClip" : name;
        c.gain = 1.0f;
        c.mute = false;
        return c;
    }

    /** Start frame of this clip in a 44100 Hz mix buffer, following the song tempo map. */
    public int startFrame(Song s) {
        if (s == null || startTick <= 0) return 0;
        double sec = TimeUtil.tickToSec(s, startTick);
        if (sec <= 0) return 0;
        double frame = sec * PcmFile.RATE;
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
        if (mute || gain == 0.0f || outL == null) return 0;
        if (data == null) {
            // streamed clip: PcmFile reads only the frames this call needs
            if (pcm == null) return 0;
            return PcmFile.mix(pcm, 0, gain, outL, outR, startFrame, outL.length);
        }
        if (!data.ok()) return 0;
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

    /**
     * Waveform peaks for a thumbnail of {@code steps} columns, in 0..1. Never null, never throws.
     *
     * <p>Uses the stored thumbnail when the clip is streamed, otherwise scans the samples; both
     * paths do the index arithmetic in {@code long} (a 8M-sample clip overflowed {@code int} and
     * killed the app in the strip view).</p>
     */
    public float[] wave(int steps) {
        if (steps < 1) steps = 1;
        float[] out = new float[steps];
        if (peaks != null && peaks.length > 0) {
            final int n = peaks.length;
            for (int i = 0; i < steps; i++) {
                int from = (int) ((long) i * n / steps);
                int to = (int) Math.max((long) from + 1L, (long) (i + 1) * n / steps);
                if (from < 0) from = 0;
                if (to > n) to = n;
                if (from >= to) { from = Math.min(n - 1, from); to = from + 1; }
                float p = 0f;
                for (int j = from; j < to; j++) if (peaks[j] > p) p = peaks[j];
                out[i] = p;
            }
            return out;
        }
        if (data != null && data.ok() && data.left != null) {
            final float[] s = data.left;
            final int n = Math.min(data.frames, s.length);
            if (n <= 0) return out;
            for (int i = 0; i < steps; i++) {
                int from = (int) ((long) i * n / steps);
                int to = (int) Math.max((long) from + 1L, (long) (i + 1) * n / steps);
                if (from < 0) from = 0;
                if (to > n) to = n;
                if (from >= to) { from = Math.min(n - 1, from); to = from + 1; }
                float p = 0f;
                for (int j = from; j < to; j++) {
                    float a = Math.abs(s[j]);
                    if (a > p) p = a;
                }
                out[i] = p;
            }
        }
        return out;
    }

    /** Shallow copy: the audio is shared, the placement fields are copied. */
    public AudioClip copy() {
        AudioClip c = new AudioClip();
        c.startTick = startTick;
        c.gain = gain;
        c.mute = mute;
        c.name = name;
        c.data = data;
        c.pcm = pcm;
        c.pcmFrames = pcmFrames;
        c.peaks = peaks;
        return c;
    }
}
