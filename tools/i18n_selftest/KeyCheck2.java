import com.dsh.v4tune.i18n.L;
public class KeyCheck2 {
    public static void main(String[] a) {
        String[] used = {"status.lang.switched","status.mixer.unavailable","status.mixer.reset","status.plugin.added",
            "status.wav.fail","status.wav.added","status.no.clips","status.clip.removed",
            "dlg.cancel","dlg.ok","dlg.close","dlg.mixer.title","dlg.plugin.title","dlg.wav.fail.title","dlg.clips.title",
            "mixer.volume","mixer.pan","mixer.mute","mixer.solo","mixer.master","mixer.reset",
            "plug.add","plug.clear","plug.enable","plug.bypass","plug.remove","clip.remove"};
        int miss = 0;
        for (String k : used) if (!L.has(k)) { System.out.println("MISSING " + k); miss++; }
        System.out.println("missing=" + miss + " of " + used.length);
    }
}
