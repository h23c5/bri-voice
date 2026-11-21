package com.dsh.v4tune.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Measures the delay between an AudioTrack's playback head and what actually leaves the speaker.
 *
 * AudioTrack.getPlaybackHeadPosition() counts frames handed to the mixer, not frames the user is
 * hearing right now: on the test device the head ran 2793 frames (~63 ms) ahead of the frame that
 * AudioFlinger timestamped, and that timestamp was already ~30 ms in the past, so the cursor was
 * about 28 ms ahead of the sound. The same formula is used live in AudioEngine's sampler, which
 * keeps the piano-roll cursor on the audible frame even when the output route changes.
 */
public final class LatencyCalibrator {

    public static final class Result {
        public final int ms;
        public final boolean reliable;
        public final String source;
        public final int samples;

        Result(int ms, boolean reliable, String source, int samples) {
            this.ms = ms;
            this.reliable = reliable;
            this.source = source;
            this.samples = samples;
        }
    }

    private LatencyCalibrator() { }

    /** Estimated milliseconds until the frame at {@code head} is presented, or NaN if unknown. */
    public static double fromTimestamp(AudioTrack track, long head, int sampleRate) {
        if (track == null || head <= 0 || sampleRate <= 0) return Double.NaN;
        try {
            AudioTimestamp ts = new AudioTimestamp();
            if (!track.getTimestamp(ts)) return Double.NaN;
            if (ts.framePosition <= 0 || ts.nanoTime <= 0) return Double.NaN;
            double deltaMs = (ts.nanoTime - System.nanoTime()) / 1000000.0;
            double gapMs = (head - ts.framePosition) * 1000.0 / sampleRate;
            double latency = deltaMs + gapMs;
            // Bluetooth and USB routes can legitimately add a few hundred ms; anything outside this
            // range is a stale/unsupported timestamp, not a real output delay.
            if (latency < -100.0 || latency > 900.0) return Double.NaN;
            return latency;
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    /**
     * Plays a very quiet one-second test tone through a track configured like the song player and
     * samples AudioTrack timestamps. Returns -1/unreliable when the device cannot report them.
     */
    public static Result measure(Context ctx) {
        final int sr = 44100;
        AudioTrack at = null;
        try {
            int minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = 4096;
            // Measurement does not need the player's 3 s safety cushion; a smaller buffer starts the
            // probe quickly while still reporting the same output route's presentation latency.
            int bufferBytes = Math.max(minBuf, sr / 2 * 4);
            at = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            int chunkFrames = 1024;
            short[] tone = new short[chunkFrames * 2];
            for (int i = 0; i < chunkFrames; i++) {
                // ~-72 dBFS: keeps the mixer running but is inaudible on a phone speaker.
                short v = (short) (8 * Math.sin(2 * Math.PI * 440 * i / sr));
                tone[i * 2] = v;
                tone[i * 2 + 1] = v;
            }
            at.play();
            int written = 0;
            ArrayList<Double> values = new ArrayList<Double>();
            long begun = System.currentTimeMillis();
            long lastWrite = begun;
            long lastHead = 0;
            while (System.currentTimeMillis() - begun < 2000L) {
                long now = System.currentTimeMillis();
                if (now - lastWrite >= 20L) {          // keep a small cushion queued
                    int n = at.write(tone, 0, tone.length);
                    if (n > 0) written += n / 2;
                    lastWrite = now;
                }
                long head = 0;
                try { head = at.getPlaybackHeadPosition(); } catch (Throwable ignored) { }
                lastHead = head;
                if (head > 0) {
                    double latency = fromTimestamp(at, head, sr);
                    if (!Double.isNaN(latency)) values.add(Double.valueOf(latency));
                }
                try { Thread.sleep(12L); } catch (InterruptedException ignored) { }
            }
            if (values.size() >= 5) {
                Collections.sort(values);
                double median = values.get(values.size() / 2).doubleValue();
                int ms = (int) Math.round(median);
                if (ms < 0) ms = 0;
                android.util.Log.i("V4Tune", "latency measure: " + ms + " ms from "
                        + values.size() + " samples (written=" + written
                        + " lastHead=" + lastHead + " state=" + at.getPlayState() + ")");
                return new Result(ms, true, "AudioTrack 时间戳", values.size());
            }
        } catch (Throwable t) {
            android.util.Log.i("V4Tune", "latency measure failed: " + t);
        } finally {
            if (at != null) {
                try { at.stop(); } catch (Throwable ignored) { }
                try { at.release(); } catch (Throwable ignored) { }
            }
        }
        android.util.Log.i("V4Tune", "latency measure unavailable");
        return new Result(-1, false, "当前设备不支持时间戳测量", 0);
    }
}
