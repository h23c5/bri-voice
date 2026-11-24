package com.dsh.v4tune.voicebank;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording guide data for the v11.0.1 custom-voicebank wizard.
 *
 * <p>The phone flow mirrors what a VOCALOID/UTAU bank needs, but shrinks it to what a singer can do
 * in a couple of minutes:</p>
 * <ul>
 *   <li>a fixed take list, one syllable per take (UTAU file naming: {@code la.wav}, {@code a.wav}),
 *       grouped into two levels - the five clean vowels, or those plus eleven common CV syllables
 *       that also give the renderer real consonant noise;</li>
 *   <li>a reference pitch played before every take, so all takes land in one pitch cluster instead
 *       of smearing into each other (VOCALOID's "pitch guide" BGM idea);</li>
 *   <li>a machine-readable {@code record.json} manifest with the take list, pitch and measured
 *       quality, so a pack says how it was made and can be re-recorded later.</li>
 * </ul>
 */
public final class RecordGuide {

    public static final int LEVEL_QUICK = 0;
    public static final int LEVEL_STANDARD = 1;

    /** reference pitches offered by the wizard (label -> Hz) */
    public static final String[] PITCH_NAMES = {"C3", "C4", "G4", "C5"};
    public static final double[] PITCH_HZ = {130.81, 261.63, 392.00, 523.25};

    /** one take the creator should record */
    public static final class Item {
        /** file stem that the builder maps to phonemes, e.g. "la" or "a" */
        public final String symbol;
        /** big label in the wizard */
        public final String label;
        /** short instruction */
        public final String hint;

        Item(String symbol, String label, String hint) {
            this.symbol = symbol;
            this.label = label;
            this.hint = hint;
        }
    }

    /** one finished take, kept for record.json */
    public static final class TakeInfo {
        public String symbol = "";
        public String file = "";
        public int ms;
        public double peakDb, rmsDb, silenceRatio;
        public int f0;
        public String quality = "";
    }

    private RecordGuide() { }

    public static String levelName(int level) {
        return level == LEVEL_QUICK ? "快速 · 5 个元音" : "标准 · 16 个音节";
    }

    /**
     * @param lang 0 = 日文, 1 = 英文, 2 = 中文
     */
    public static List<Item> items(int lang, int level) {
        List<Item> out = new ArrayList<Item>();
        // five clean vowels: every take contributes a steady harmonic envelope
        out.add(new Item("a", "a", "啊 · 张大嘴，平稳拖长"));
        out.add(new Item("i", "i", "衣 · 嘴角拉开"));
        out.add(new Item("u", "u", "乌 · 嘴唇收圆"));
        out.add(new Item("e", "e", "诶 · 舌位靠前"));
        out.add(new Item("o", "o", "哦 · 圆唇后舌"));
        if (level == LEVEL_QUICK) return out;
        if (lang == 0) {
            out.add(new Item("ka", "ka", "か · 卡"));
            out.add(new Item("sa", "sa", "さ · 撒"));
            out.add(new Item("ta", "ta", "た · 他"));
            out.add(new Item("na", "na", "な · 那"));
            out.add(new Item("ha", "ha", "は · 哈"));
            out.add(new Item("ma", "ma", "ま · 妈"));
            out.add(new Item("ya", "ya", "や · 呀"));
            out.add(new Item("ra", "ra", "ら · 啦"));
            out.add(new Item("wa", "wa", "わ · 哇"));
            out.add(new Item("ga", "ga", "が · 嘎"));
            out.add(new Item("za", "za", "ざ · 咂"));
        } else if (lang == 1) {
            out.add(new Item("la", "la", "啦"));
            out.add(new Item("ma", "ma", "妈"));
            out.add(new Item("na", "na", "那"));
            out.add(new Item("sa", "sa", "撒"));
            out.add(new Item("ta", "ta", "他"));
            out.add(new Item("da", "da", "打"));
            out.add(new Item("ba", "ba", "八"));
            out.add(new Item("pa", "pa", "趴"));
            out.add(new Item("ga", "ga", "嘎"));
            out.add(new Item("ka", "ka", "卡"));
            out.add(new Item("ha", "ha", "哈"));
        } else {
            out.add(new Item("la", "la", "啦"));
            out.add(new Item("ma", "ma", "妈"));
            out.add(new Item("na", "na", "那"));
            out.add(new Item("sa", "sa", "撒"));
            out.add(new Item("da", "da", "打"));
            out.add(new Item("ta", "ta", "他"));
            out.add(new Item("ba", "ba", "八"));
            out.add(new Item("pa", "pa", "趴"));
            out.add(new Item("ga", "ga", "嘎"));
            out.add(new Item("ka", "ka", "卡"));
            out.add(new Item("ha", "ha", "哈"));
        }
        return out;
    }

    public static double pitchHz(int index) {
        return PITCH_HZ[Math.max(0, Math.min(PITCH_HZ.length - 1, index))];
    }

    public static String pitchName(int index) {
        return PITCH_NAMES[Math.max(0, Math.min(PITCH_NAMES.length - 1, index))];
    }

    public static String langName(int lang) {
        return lang == 0 ? "日文" : (lang == 1 ? "英文" : "中文");
    }

    /** JSON manifest stored as record.json inside the pack (and read back by VoicePack) */
    public static String metaJson(String name, int lang, int level, int pitchIdx, List<TakeInfo> takes) {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("  \"format\": \"brivoice-record/1\",\n");
        b.append("  \"tool\": \"BriVoice 11.0.1 录音向导\",\n");
        b.append("  \"name\": ").append(q(name)).append(",\n");
        b.append("  \"lang\": ").append(q(langName(lang))).append(",\n");
        b.append("  \"level\": ").append(q(levelName(level))).append(",\n");
        b.append("  \"pitch\": ").append(q(pitchName(pitchIdx))).append(",\n");
        b.append("  \"pitchHz\": ").append(round2(pitchHz(pitchIdx))).append(",\n");
        b.append("  \"sampleRate\": ").append(MicRecorder.RATE).append(",\n");
        b.append("  \"takes\": [");
        for (int i = 0; takes != null && i < takes.size(); i++) {
            TakeInfo t = takes.get(i);
            if (t == null) continue;
            b.append(i == 0 ? "\n" : ",\n");
            b.append("    {\"symbol\": ").append(q(t.symbol))
                    .append(", \"file\": ").append(q(t.file))
                    .append(", \"sec\": ").append(round2(t.ms / 1000.0))
                    .append(", \"f0\": ").append(t.f0)
                    .append(", \"peakDb\": ").append(round1(t.peakDb))
                    .append(", \"rmsDb\": ").append(round1(t.rmsDb))
                    .append(", \"silence\": ").append(round2(t.silenceRatio))
                    .append(", \"quality\": ").append(q(t.quality)).append("}");
        }
        b.append(takes != null && !takes.isEmpty() ? "\n  ]\n" : "]\n");
        b.append("}\n");
        return b.toString();
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private static double round1(double v) { return Math.rint(v * 10.0) / 10.0; }

    private static double round2(double v) { return Math.rint(v * 100.0) / 100.0; }
}
