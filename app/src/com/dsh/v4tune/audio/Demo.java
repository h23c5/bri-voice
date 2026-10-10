package com.dsh.v4tune.audio;

import com.dsh.v4tune.model.Note;
import com.dsh.v4tune.model.Part;
import com.dsh.v4tune.model.Song;
import com.dsh.v4tune.model.Track;

/** Demo score used on first launch: a short Mandarin phrase "luo tian yi" (洛天依). */
public class Demo {

    public static Song build() {
        Song s = Song.empty();
        s.masterName = "这里是 BriVoice 欢迎回来";
        Track t = s.tracks.get(0);
        Part p = t.parts.get(0);
        p.name = "示例乐句";
        p.dur = 1920 * 3;
        t.name = "歌姬 1";
        s.voices.get(0).name = "Luotianyi_CHN_Meng";
        s.voices.get(0).compID = "BK8H76TAEHXWSKDB";   // was ...WSZDB: one letter off the real bank id

        String[] lyr = {"这", "里", "是", "Bri", "Voice", "欢", "迎", "回", "来"};
        // phoneme column carries the CHN symbols of the same syllables, so normally
        // written text still sings: a Hanzi lyric alone phonemises to a bare [a].
        String[] ph = {"ts\u0060 7", "l i", "s\u0060 i\u0060", "b z\u0060 aI", "w o i s", "x ua_n", "iN", "x uei", "l aI"};
        int[] keys = {60, 62, 64, 65, 67, 69, 67, 65, 64};
        long t0 = 480;
        for (int i = 0; i < lyr.length; i++) {
            Note n = new Note();
            n.pos = t0 + i * 480L;
            n.dur = 420;
            n.key = keys[i];
            n.vel = 64;
            n.lyric = lyr[i];
            n.phoneme = ph[i];
            p.notes.add(n);
        }
        // a little dynamics and brightness movement
        com.dsh.v4tune.model.ControlCurve dyn = p.ctrl("DYN");
        dyn.putRaw(480, 70);
        dyn.putRaw(1440, 64);
        dyn.putRaw(2880, 78);
        com.dsh.v4tune.model.ControlCurve bre = p.ctrl("BRE");
        bre.putRaw(480, 60);
        bre.putRaw(2880, 72);
        com.dsh.v4tune.model.ControlCurve pit = p.ctrl("PIT");
        pit.def = 0;
        pit.min = -2400;
        pit.max = 2400;
        pit.putRaw(480, 0);
        pit.putRaw(2880, 0);
        return s;
    }

    /** A short Chinese song used to demo longer rendering. */
    public static Song twinkle() {
        Song s = Song.empty();
        s.masterName = "小星星 · 中文";
        Part p = s.tracks.get(0).parts.get(0);
        p.name = "小星星";
        p.dur = 1920 * 8;
        String[] lyr = {"yi", "shan", "yi", "shan", "liang", "jing", "jing",
                        "man", "tian", "dou", "shi", "xiao", "xing", "xing"};
        int[] keys = {60, 60, 67, 67, 69, 69, 67, 65, 65, 64, 64, 62, 62, 60};
        for (int i = 0; i < lyr.length; i++) {
            Note n = new Note();
            n.pos = 480 + i * 480L;
            n.dur = 440;
            n.key = keys[i];
            n.lyric = lyr[i];
            p.notes.add(n);
        }
        return s;
    }
}
