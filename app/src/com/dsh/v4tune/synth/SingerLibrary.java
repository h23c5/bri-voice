package com.dsh.v4tune.synth;

import java.util.ArrayList;
import java.util.List;

/** Built-in singers. Data lives in code so the APK has no external dependency. */
public class SingerLibrary {

    /**
     * BriVoice ships NO built-in voice. Every timbre comes from a voicebank the user installs
     * ("声库" page), so the sound always comes from the user's own licensed data.
     */
    public static List<VoiceModel> builtin() {
        return new ArrayList<VoiceModel>();
    }

    /** true when the app has no voicebank installed at all */
    /** ids of the voicebanks that ship inside the APK (tagged in the UI) */
    private static final java.util.HashSet<String> BUILTIN_IDS = new java.util.HashSet<String>();

    public static void setBuiltinIds(java.util.Collection<String> ids) {
        BUILTIN_IDS.clear();
        if (ids != null) {
            for (String id : ids) if (id != null && !id.isEmpty()) BUILTIN_IDS.add(id);
        }
    }

    public static boolean isBuiltin(VoiceModel m) {
        return m != null && m.id != null && BUILTIN_IDS.contains(m.id);
    }

    public static boolean isEmpty() {
        return userPacks.isEmpty();
    }

    /** 洛天依 V4 中文 · 萌 风格：明亮、年轻、略带气息的女声 */
    public static VoiceModel luoTianyi() {
        VoiceModel v = new VoiceModel();
        v.id = "luotianyi_v4_meng";
        v.name = "洛天依 V4 中文 · 萌";
        v.nameEn = "Luo Tianyi V4 CHN (Meng)";
        v.lang = 2;
        v.compID = "BK8H76TAEHXWSKDB";   // the bank id VOCALOID4 knows 洛天依 CHN Meng by
        v.formantScale = 1.055;      // 女声声道较短 -> 共振峰整体上移
        v.f1Mul = 1.05; v.f2Mul = 1.09; v.f3Mul = 1.04; v.f4Mul = 1.0; v.f5Mul = 1.0;
        v.tiltBias = -0.06;          // 略暗、圆润
        v.breathBias = 0.10;
        v.oqBias = -0.02;
        v.vibratoRate = 5.4;
        v.vibratoDepthCents = 0.0;
        v.gain = 1.0;
        v.consonantGain = 1.0;
        v.breathSoundAmount = 0.35;
        v.description = "内置参数化音色：明亮清透的少女声线，参考洛天依 V4 中文声库的音域与音色走向（非官方采样数据）。";
        return v;
    }

    /** 初音ミク V4 中文：更亮、更靠前、气息更少 */
    public static VoiceModel miku() {
        VoiceModel v = new VoiceModel();
        v.id = "mikuv4_chn";
        v.name = "初音未来 V4 中文";
        v.nameEn = "Hatsune Miku V4 CHN";
        v.lang = 2;
        v.compID = "BNGE7CP7EMTRSNC3";
        v.formantScale = 1.075;
        v.f1Mul = 1.0; v.f2Mul = 1.12; v.f3Mul = 1.06; v.f4Mul = 1.02; v.f5Mul = 1.0;
        v.tiltBias = 0.10;           // 更亮
        v.breathBias = 0.04;
        v.oqBias = -0.05;
        v.vibratoRate = 5.8;
        v.vibratoDepthCents = 0.0;
        v.gain = 0.98;
        v.consonantGain = 1.08;
        v.breathSoundAmount = 0.28;
        v.description = "内置参数化音色：明亮锐利的电子感女声，参考初音未来 V4 中文声库（非官方采样数据）。";
        return v;
    }

    /** 洛天依 · 柔：更低、更气息化的抒情变体 */
    public static VoiceModel tianyiRou() {
        VoiceModel v = luoTianyi();
        v.id = "luotianyi_v4_rou";
        v.name = "洛天依 · 柔（抒情）";
        v.nameEn = "Luo Tianyi Soft";
        v.formantScale = 1.02;
        v.f2Mul = 1.04;
        v.tiltBias = -0.16;
        v.breathBias = 0.26;
        v.oqBias = 0.06;
        v.vibratoRate = 5.0;
        v.gain = 0.94;
        v.breathSoundAmount = 0.6;
        v.description = "内置参数化音色：气息更重、音色更暗的抒情变体，适合慢歌。";
        return v;
    }

    /** singers imported by the user (".v4voice" packs), in load order */
    private static final ArrayList<VoiceModel> userPacks = new ArrayList<VoiceModel>();

    /** built-in singers followed by the imported ones */
    public static List<VoiceModel> all() {
        ArrayList<VoiceModel> l = new ArrayList<VoiceModel>(builtin());
        l.addAll(userPacks);
        return l;
    }

    public static void setUserPacks(List<VoiceModel> packs) {
        userPacks.clear();
        if (packs != null) userPacks.addAll(packs);
    }

    public static int userPackCount() { return userPacks.size(); }

    public static boolean isUserPack(VoiceModel vm) {
        for (VoiceModel v : userPacks) if (v == vm || (v.id != null && v.id.equals(vm.id))) return true;
        return false;
    }

    /** exact match on name AND component id, so an imported pack that shares the official
     *  compID with a built-in singer is still distinguishable */
    public static VoiceModel exact(String compID, String name) {
        for (VoiceModel v : all()) {
            if (v.compID == null || name == null) continue;
            if (v.compID.equalsIgnoreCase(compID) && v.name.equalsIgnoreCase(name)) return v;
        }
        return null;
    }

    public static VoiceModel byId(String id) {
        for (VoiceModel v : all()) if (v.id.equals(id)) return v;
        return null;
    }
}
