import com.dsh.v4tune.i18n.L;
public class KeyCheck {
    public static void main(String[] a) {
        String[] keys = {"status.stream.start", "status.stream.fail", "status.play.done", "set.offset", "clip.start.title"};
        for (String k : keys) {
            System.out.printf("%-22s has=%s  zh=%s%n", k, L.has(k), L.t(k, "X"));
        }
        System.out.println("catalog size = " + L.allKeys().size());
    }
}