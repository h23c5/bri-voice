import com.dsh.v4tune.io.WavReader;
import com.dsh.v4tune.model.AudioClip;
import com.dsh.v4tune.model.Song;
import java.io.File;

/** does an imported clip really land in the mix? */
public class ClipMixTest {
    public static void main(String[] args) throws Exception {
        WavReader.AudioData d = WavReader.read(new java.io.FileInputStream(args[0]), 44100 * 180);
        System.out.println("ok=" + (d != null && d.ok()) + " frames=" + (d == null ? -1 : d.frames)
                + " sr=" + (d == null ? -1 : d.sampleRate) + " stereo=" + (d != null && d.right != null));
        if (d == null || !d.ok()) return;
        double e = 0;
        for (int i = 0; i < Math.min(d.frames, 44100); i++) e += d.left[i] * d.left[i];
        System.out.printf("source rms=%.4f  first=%.4f%n", Math.sqrt(e / 44100), d.left[0]);

        Song s = Song.empty();
        AudioClip c = AudioClip.fromWav(d, 3840, "tone1500.wav");
        System.out.println("clip startFrame=" + c.startFrame(s) + " gain=" + c.gain + " mute=" + c.mute);
        float[] buf = new float[44100 * 8];
        int n = c.mixInto(buf, null, 0);
        double e2 = 0;
        for (float v : buf) e2 += v * v;
        System.out.println("mixed frames=" + n + " buffer rms=" + Math.sqrt(e2 / buf.length));
        int at = c.startFrame(s);
        float[] dst = new float[44100 * 8];
        for (int i = 0; i < n; i++) { int k = at + i; if (k >= 0 && k < dst.length) dst[k] += buf[i]; }
        double e3 = 0;
        for (float v : dst) e3 += v * v;
        System.out.println("placed at " + at + " -> dst rms=" + Math.sqrt(e3 / dst.length));
    }
}