package com.dsh.v4tune.voicebank;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

/**
 * Phone-friendly microphone take recorder for the custom-voicebank record wizard (v11.0.1).
 *
 * <p>Like the UTAU/VOCALOID recording lists, the guide asks for one short take per syllable. This
 * class only records and measures: it trims the leading/trailing silence, reports peak/RMS/silence
 * figures so the wizard can flag clipped or too-quiet takes, estimates f0 (so the take lands in the
 * right pitch cluster of the voicebank), and returns both float samples and a finished WAV image.</p>
 *
 * <p>No audio ever leaves the process; the caller keeps the WAV bytes inside the pack.</p>
 */
public final class MicRecorder {

    public static final int RATE = 44100;
    private static final int MAX_MS = 8000;
    private static final double TRIM_DB = -48.0;
    private static final int TRIM_PAD_MS = 25;
    private static final int MIN_MS = 320;

    private AudioRecord rec;
    private volatile boolean running;
    private Thread reader;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream(RATE * 4);
    private long startedAt;

    /** result of one take (already trimmed) */
    public static final class Take {
        public float[] mono = new float[0];
        public int sampleRate = RATE;
        public int ms;
        public double peakDb = -120, rmsDb = -120;
        /** share of samples above 0.99 full scale (clipping) */
        public double clipRatio;
        /** share of 20 ms frames quieter than -50 dBFS */
        public double silenceRatio;
        /** estimated fundamental in Hz, 0 when unvoiced/silent */
        public int f0;
        /** human readable verdict: 可用 / 偏小声 / 爆音 / 太短 / 几乎无声 */
        public String quality = "";
        public boolean usable;
        public byte[] wav;
    }

    public boolean isSupported() {
        try {
            return AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** opens the mic and starts collecting; throws when the device/permission refuses */
    public void start() throws Exception {
        if (running) return;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("设备不支持 44.1kHz 单声道录音");
        int buf = Math.max(min * 2, RATE / 4 * 2);
        rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, buf);
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            try { rec.release(); } catch (Throwable ignored) { }
            rec = null;
            throw new IllegalStateException("麦克风被占用或没有权限");
        }
        pcm.reset();
        running = true;
        startedAt = System.currentTimeMillis();
        try {
            rec.startRecording();
        } catch (Throwable t) {
            running = false;
            try { rec.release(); } catch (Throwable ignored) { }
            rec = null;
            throw t instanceof Exception ? (Exception) t : new IllegalStateException(String.valueOf(t));
        }
        reader = new Thread(new Runnable() {
            public void run() {
                final short[] buf = new short[2048];
                while (running) {
                    int n;
                    try {
                        n = rec.read(buf, 0, buf.length);
                    } catch (Throwable t) {
                        break;
                    }
                    if (n <= 0) continue;
                    synchronized (pcm) {
                        for (int i = 0; i < n; i++) {
                            int v = buf[i];
                            pcm.write(v & 0xFF);
                            pcm.write((v >> 8) & 0xFF);
                        }
                    }
                    if (System.currentTimeMillis() - startedAt > MAX_MS) break;
                }
            }
        }, "mic-reader");
        reader.start();
    }

    /** stops the mic and returns the trimmed take with its quality verdict */
    public Take stop() {
        running = false;
        try {
            if (rec != null) rec.stop();
        } catch (Throwable ignored) { }
        try {
            if (reader != null) reader.join(400);
        } catch (Throwable ignored) { }
        try {
            if (rec != null) rec.release();
        } catch (Throwable ignored) { }
        rec = null;
        byte[] raw;
        synchronized (pcm) { raw = pcm.toByteArray(); }
        int n = raw.length / 2;
        float[] mono = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = raw[2 * i] & 0xFF;
            int hi = raw[2 * i + 1];
            short s = (short) ((hi << 8) | lo);
            mono[i] = s / 32768f;
        }
        return analyze(mono, RATE);
    }

    /** aborts without building a take */
    public void cancel() {
        running = false;
        try { if (rec != null) rec.stop(); } catch (Throwable ignored) { }
        try { if (rec != null) rec.release(); } catch (Throwable ignored) { }
        rec = null;
        pcm.reset();
    }

    // ------------------------------------------------------------------ analysis

    /** trims silence, measures level/clipping/silence/f0 and writes the verdict */
    public static Take analyze(float[] in, int sampleRate) {
        Take t = new Take();
        t.sampleRate = sampleRate > 0 ? sampleRate : RATE;
        if (in == null || in.length == 0) {
            t.quality = "没有录到声音";
            return t;
        }
        int first = 0, last = in.length - 1;
        double thr = Math.pow(10.0, TRIM_DB / 20.0);
        for (int i = 0; i < in.length; i++) if (Math.abs(in[i]) > thr) { first = i; break; }
        for (int i = in.length - 1; i >= 0; i--) if (Math.abs(in[i]) > thr) { last = i; break; }
        if (last <= first) {
            t.peakDb = -120;
            t.rmsDb = -120;
            t.silenceRatio = 1.0;
            t.quality = "几乎无声（检查麦克风）";
            t.wav = com.dsh.v4tune.synth.WavWriter.toBytesMono16(in, t.sampleRate);
            return t;
        }
        int pad = t.sampleRate * TRIM_PAD_MS / 1000;
        int from = Math.max(0, first - pad);
        int to = Math.min(in.length, last + pad + 1);
        float[] mono = new float[to - from];
        System.arraycopy(in, from, mono, 0, mono.length);
        t.mono = mono;
        t.ms = (int) Math.round(mono.length * 1000.0 / t.sampleRate);

        double peak = 0, sum = 0;
        int clipped = 0;
        for (float v : mono) {
            double a = Math.abs(v);
            if (a > peak) peak = a;
            if (a > 0.99) clipped++;
            sum += a * a;
        }
        t.peakDb = 20.0 * Math.log10(Math.max(peak, 1e-7));
        t.rmsDb = 20.0 * Math.log10(Math.max(Math.sqrt(sum / mono.length), 1e-7));
        t.clipRatio = clipped / (double) mono.length;
        int frame = Math.max(1, t.sampleRate / 50);
        int quiet = 0, frames = 0;
        for (int p = 0; p + frame <= mono.length; p += frame) {
            double e = 0;
            for (int i = p; i < p + frame; i++) e += mono[i] * mono[i];
            double db = 20.0 * Math.log10(Math.max(Math.sqrt(e / frame), 1e-7));
            if (db < -50) quiet++;
            frames++;
        }
        t.silenceRatio = frames == 0 ? 0 : quiet / (double) frames;
        t.f0 = (int) Math.round(estimateF0(mono, t.sampleRate));
        t.quality = verdict(t);
        t.usable = usable(t);
        t.wav = com.dsh.v4tune.synth.WavWriter.toBytesMono16(mono, t.sampleRate);
        return t;
    }

    private static boolean usable(Take t) {
        return t.ms >= MIN_MS && t.peakDb > -40 && t.clipRatio < 0.02 && t.silenceRatio < 0.9;
    }

    private static String verdict(Take t) {
        if (t.ms < MIN_MS) return "太短（至少 0.4 秒）";
        if (t.peakDb <= -40) return "偏小声（靠近麦克风）";
        if (t.clipRatio >= 0.02) return "爆音（离远一点或降低音量）";
        if (t.silenceRatio >= 0.9) return "几乎无声";
        if (t.f0 == 0) return "可用 · 未测到稳定音高";
        return "可用";
    }

    // ------------------------------------------------------------------ helpers

    /** smallest-period normalised autocorrelation pitch estimate, 0 when unvoiced */
    public static double estimateF0(float[] mono, int sampleRate) {
        if (mono == null || mono.length < 512 || sampleRate <= 0) return 0;
        int n = Math.min(2048, mono.length);
        int off = Math.max(0, (mono.length - n) / 2);
        int maxLag = Math.min(n - 2, (int) (sampleRate / 65.0));
        int minLag = Math.max(2, (int) (sampleRate / 1000.0));
        if (maxLag <= minLag + 2) return 0;
        int span = maxLag - minLag + 1;
        double[] score = new double[span];
        double best = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double num = 0, e1 = 0, e2 = 0;
            for (int i = 0; i + lag < n; i++) {
                double a = mono[off + i], b = mono[off + i + lag];
                num += a * b; e1 += a * a; e2 += b * b;
            }
            double r = (e1 < 1e-9 || e2 < 1e-9) ? 0 : num / Math.sqrt(e1 * e2);
            score[lag - minLag] = r;
            if (r > best) best = r;
        }
        if (best < 0.35) return 0;
        // prefer the shortest period that is almost as good: integer multiples of the
        // true period correlate just as well, which used to halve the reported pitch
        int pick = -1;
        for (int k = 1; k + 1 < span; k++) {
            double r = score[k];
            if (r >= 0.88 * best && r >= score[k - 1] && r >= score[k + 1]) { pick = k; break; }
        }
        if (pick < 0) {
            for (int k = 0; k < span; k++) if (score[k] == best) { pick = k; break; }
        }
        if (pick < 0) return 0;
        double bestLag = minLag + pick;
        double f = sampleRate / bestLag;
        return (f >= 65 && f <= 1000) ? f : 0;
    }

    /** short sine guide tone through the media stream (no permission needed) */
    public static void playTone(double hz, int ms) {
        AudioTrack tr = null;
        try {
            int n = (int) Math.round(RATE * (ms / 1000.0));
            short[] buf = new short[n];
            for (int i = 0; i < n; i++) {
                double env = Math.min(1.0, Math.min(i / (RATE * 0.03), (n - i) / (RATE * 0.06)));
                buf[i] = (short) (Math.sin(2 * Math.PI * hz * i / RATE) * 0.25 * 32767 * env);
            }
            tr = new AudioTrack(android.media.AudioManager.STREAM_MUSIC, RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    buf.length * 2, AudioTrack.MODE_STATIC);
            tr.write(buf, 0, buf.length);
            tr.play();
        } catch (Throwable ignored) {
        } finally {
            if (tr != null) {
                final AudioTrack t = tr;
                new Thread(new Runnable() {
                    public void run() {
                        try { Thread.sleep(ms + 120); t.release(); } catch (Throwable ignored) { }
                    }
                }, "tone-release").start();
            }
        }
    }
}
