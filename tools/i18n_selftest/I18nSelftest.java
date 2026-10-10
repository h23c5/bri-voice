import com.dsh.v4tune.i18n.L;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Desktop acceptance test for the BriVoice trilingual string layer,
 * com.dsh.v4tune.i18n.L (Chinese / English / Japanese).
 *
 * <p>Pure Java, no android.*, no external libraries - it needs L.java and nothing else:
 *
 * <pre>
 * javac -encoding UTF-8 -d build/i18ntest app/src/com/dsh/v4tune/i18n/L.java tools/i18n_selftest/I18nSelftest.java
 * java -cp build/i18ntest I18nSelftest app/src/com/dsh/v4tune/i18n/L.java
 * </pre>
 *
 * <p>The optional argument is L.java itself: when given, the catalog is re-read from the
 * source file and compared row by row with what the compiled class returns, so a stale
 * build or a mangled escape sequence cannot pass.
 *
 * <p>Checks:
 *   (1) key shape (lowercase dotted) and uniqueness;
 *   (2) every key exists in ZH/EN/JA, non-empty, no leading/trailing whitespace;
 *   (3) placeholder multisets identical across the three languages, only %s / %d / %%;
 *   (4) every pattern really formats (argument markers survive, no '%' left over);
 *   (5) per-group key counts and exact spot-check strings for representative keys;
 *   (6) API edge cases (unknown key, null, invalid setLang, copies, immutability,
 *       wrong argument count must not throw);
 *   (7) source table identical to the runtime table (only with the file argument).
 *
 * <p>Prints "PASS n/n" and exits 0 when every check passes; prints "FAIL n/n", lists the
 * first problems and exits 1 otherwise.
 */
public class I18nSelftest {

  /** Catalog size this build is expected to ship. */
  private static final int EXPECTED_KEYS = 301;

  /** Markers that cannot occur in any translation, used to prove formatting ran. */
  private static final String S_MARK = "@@S@@";
  private static final String D_MARK = "12345";

  private static final Pattern KEY_RE = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z0-9]+)+");
  private static final Pattern PH =
      Pattern.compile("%(?:([1-9]\\d*)\\$)?([-#+ 0,(]*)(\\d+)?(?:\\.(\\d+))?([a-zA-Z%])");
  private static final Pattern SRC_ROW = Pattern.compile(
      "\\s*\\{\"([^\"]+)\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\},");

  private static int checks = 0;
  private static int failures = 0;
  private static final List<String> PROBLEMS = new ArrayList<String>();

  private static String secName = "";
  private static int secStart = 0;
  private static int secFailStart = 0;

  public static void main(String[] args) throws Exception {
    List<String> keys = L.allKeys();
    String[] names = L.languageNames();
    System.out.println("=== BriVoice i18n self test (com.dsh.v4tune.i18n.L) ===");
    System.out.println("catalog: " + keys.size() + " keys x 3 languages = "
        + (keys.size() * 3) + " strings (" + names[0] + " / " + names[1] + " / " + names[2] + ")");
    System.out.println();

    naming(keys);
    presence(keys);
    placeholders(keys);
    formatting(keys);
    groups(keys);
    spotChecks();
    api();
    sourceScan(args, keys);
    samples();

    System.out.println();
    System.out.println("=== RESULT: " + (failures == 0 ? "PASS " : "FAIL ")
        + (checks - failures) + "/" + checks + " checks, " + keys.size() + " keys ===");
    if (failures > 0) {
      System.out.println("first problems:");
      for (String p : PROBLEMS) System.out.println("  - " + p);
      System.exit(1);
    }
  }

  // ------------------------------------------------------------------ (1)

  private static void naming(List<String> keys) {
    begin("(1) key shape + uniqueness");
    check(keys.size() == EXPECTED_KEYS,
        "catalog size " + keys.size() + " != expected " + EXPECTED_KEYS);
    Set<String> uniq = new HashSet<String>(keys);
    check(uniq.size() == keys.size(),
        "duplicate keys: " + (keys.size() - uniq.size()) + " colliding");
    for (String k : keys) {
      check(k != null && KEY_RE.matcher(k).matches(), "key not lowercase-dotted: " + k);
    }
    end();
  }

  // ------------------------------------------------------------------ (2)

  private static void presence(List<String> keys) {
    begin("(2) zh/en/ja present, non-empty, trimmed");
    for (String k : keys) {
      String[] v = new String[3];
      StringBuilder why = new StringBuilder();
      for (int i = 0; i < 3; i++) {
        L.setLang(i);
        v[i] = L.t(k);
        if (v[i] == null || v[i].length() == 0) why.append(" empty@").append(i);
        else if (v[i].equals(k)) why.append(" missing@").append(i);
        else if (!v[i].equals(v[i].trim())) why.append(" edgeSpace@").append(i);
      }
      L.setLang(L.ZH);
      boolean ok = why.length() == 0;
      check(ok, "key " + k + why);
      System.out.println(String.format(Locale.US, "  %s %-27s zh=%-19s en=%-19s ja=%s",
          ok ? "PASS" : "FAIL", k, clip(v[0], 19), clip(v[1], 19), clip(v[2], 19)));
    }
    end();
  }

  // ------------------------------------------------------------------ (3)

  private static void placeholders(List<String> keys) {
    begin("(3) placeholder sets identical in zh/en/ja");
    for (String k : keys) {
      String zh = val(k, L.ZH), en = val(k, L.EN), ja = val(k, L.JA);
      String pz = phKey(zh), pe = phKey(en), pj = phKey(ja);
      check(pz.equals(pe) && pz.equals(pj),
          "placeholder mismatch " + k + ": zh" + pz + " en" + pe + " ja" + pj);
      for (int i = 0; i < 3; i++) {
        String s = val(k, i);
        check(supported(s), "unsupported placeholder in " + k + " lang " + i + ": " + s);
      }
    }
    end();
  }

  // ------------------------------------------------------------------ (4)

  private static void formatting(List<String> keys) {
    begin("(4) every pattern formats without leftovers");
    for (String k : keys) {
      List<String> ph = placeholders(val(k, L.ZH));
      Object[] a = args(ph);
      check(ph.size() == placeholders(val(k, L.EN)).size()
          && ph.size() == placeholders(val(k, L.JA)).size(), "arg count differs: " + k);
      for (int i = 0; i < 3; i++) {
        L.setLang(i);
        String s = L.t(k, a);
        boolean ok = s.indexOf('%') < 0;
        for (String p : ph) {
          if (!ok) break;
          if ("d".equals(p)) ok = s.contains(D_MARK);
          else if ("s".equals(p)) ok = s.contains(S_MARK);
        }
        check(ok, "format did not apply for " + k + " lang " + i + " -> " + clip(s, 60));
      }
    }
    L.setLang(L.ZH);
    end();
  }

  // ------------------------------------------------------------------ (5)

  private static void groups(List<String> keys) {
    begin("(5) per-group key counts");
    Map<String, Integer> want = new LinkedHashMap<String, Integer>();
want.put("ai", Integer.valueOf(30));
want.put("set", Integer.valueOf(11));
    want.put("app", Integer.valueOf(2));
    want.put("toolbar", Integer.valueOf(10));
    want.put("transport", Integer.valueOf(14));
    want.put("param", Integer.valueOf(15));
    want.put("status", Integer.valueOf(88));
    want.put("dlg", Integer.valueOf(62));
    want.put("about.line", Integer.valueOf(15));
    want.put("menu", Integer.valueOf(22));
    want.put("pack.info", Integer.valueOf(5));
    want.put("singer", Integer.valueOf(3));
    want.put("mixer", Integer.valueOf(6));
    want.put("plug", Integer.valueOf(5));
    want.put("track", Integer.valueOf(1));
    want.put("clip", Integer.valueOf(4));
    want.put("toast", Integer.valueOf(8));
    int sum = 0;
    for (Map.Entry<String, Integer> e : want.entrySet()) {
      int n = 0;
      for (String k : keys) if (k.startsWith(e.getKey() + ".")) n++;
      sum += n;
      check(n == e.getValue().intValue(),
          "group " + e.getKey() + ": " + n + " keys, expected " + e.getValue());
      System.out.println(String.format(Locale.US, "  %-12s %3d keys (expected %3d)",
          e.getKey() + ".*", Integer.valueOf(n), e.getValue()));
    }
    check(sum == EXPECTED_KEYS, "group sum " + sum + " != " + EXPECTED_KEYS);
    end();
  }

  // ------------------------------------------------------------------ (6)

  /** {key, zh, en, ja} - exact strings prove the language routing, not just presence. */
  private static final String[][] SPOT = {
      {"app.name", "BriVoice", "BriVoice", "BriVoice"},
      {"app.untitled", "未命名", "Untitled", "無題"},
      {"toolbar.open", "打开", "Open", "開く"},
      {"toolbar.save.as", "另存为", "Save As", "名前を付けて保存"},
      {"toolbar.voicebank", "声库", "Voicebanks", "声庫"},
      {"toolbar.about", "关于", "About", "このアプリについて"},
      {"transport.play", "▶ 播放", "▶ Play", "▶ 再生"},
      {"transport.snap", "吸附 1/%d", "Snap 1/%d", "スナップ 1/%d"},
      {"param.dyn", "力度", "Dynamics", "ダイナミクス"},
      {"status.dict.loaded", "拼音词典已载入", "Pinyin dictionary loaded", "ピンイン辞書を読み込みました"},
      {"status.packs.ready", "BriVoice · 已安装 %d 个声库：%s",
          "BriVoice · %d voicebanks installed: %s", "BriVoice · 声庫 %d 個をインストール済み：%s"},
      {"status.saved.to", "已保存到 %s", "Saved to %s", "%s に保存しました"},
      {"dlg.cancel", "取消", "Cancel", "キャンセル"},
      {"dlg.no.voice.title", "还没有声库", "No voicebank yet", "声庫がありません"},
      {"dlg.lyric.clear.lock", "清空音素锁定", "Clear phoneme lock", "音素ロックを解除"},
      {"menu.pack.delete", "删除已导入的声库（%d）", "Delete an Imported Voicebank (%d)",
          "読み込んだ声庫を削除（%d）"},
      {"toast.bad.tempo", "请输入 20-400 之间的数字", "Enter a number between 20 and 400",
          "20〜400 の数値を入力してください"},
      {"pack.info.join", "%s，%s", "%s, %s", "%s、%s"},
      {"mixer.volume", "音量", "Volume", "音量"},
      {"plug.add", "添加插件", "Add Plugin", "プラグインを追加"},
      {"track.name", "音轨 %d", "Track %d", "トラック %d"},
      {"menu.tools.lang", "语言 / Language / 言語", "语言 / Language / 言語", "语言 / Language / 言語"},
      {"about.line.dir", "声库目录：%s", "Voicebank folder: %s", "声庫フォルダ: %s"},
  };

  private static void spotChecks() {
    begin("(6) exact spot checks (zh/en/ja routing)");
    for (int i = 0; i < SPOT.length; i++) {
      String[] row = SPOT[i];
      for (int li = 0; li < 3; li++) {
        String got = val(row[0], li);
        check(got.equals(row[li + 1]), "spot " + row[0] + " lang " + li
            + ": got [" + got + "] want [" + row[li + 1] + "]");
      }
    }
    System.out.println("  " + SPOT.length + " keys x 3 languages compared verbatim");
    end();
  }

  // ------------------------------------------------------------------ (7)

  private static void api() {
    begin("(7) API contract / edge cases");
    check(L.ZH == 0 && L.EN == 1 && L.JA == 2, "language constants changed");
    check(L.t(null).equals(""), "t(null) should be \"\"");
    check(L.t("no.such.key").equals("no.such.key"), "unknown key must return the key");
    check(L.t("no.such.key", 1, "x").equals("no.such.key"), "unknown key must not be formatted");
    check(L.has("toolbar.open") && !L.has("nope.nope") && !L.has(null), "has() contract");
    check(val("status.packs.ready", L.ZH).indexOf("%d") > 0, "raw pattern readable");
    check(L.t("status.packs.ready", Integer.valueOf(3), "Miku")
            .equals("BriVoice · 已安装 3 个声库：Miku"),
        "zh format: " + L.t("status.packs.ready", Integer.valueOf(3), "Miku"));
    L.setLang(L.EN);
    check(L.t("status.packs.ready", Integer.valueOf(3), "Miku")
            .equals("BriVoice · 3 voicebanks installed: Miku"), "en format");
    L.setLang(L.JA);
    check(L.t("status.packs.ready", Integer.valueOf(3), "Miku")
            .equals("BriVoice · 声庫 3 個をインストール済み：Miku"), "ja format");
    check(L.lang() == L.JA, "setLang(JA) not applied");
    L.setLang(99);
    check(L.lang() == L.JA, "setLang(99) must be ignored");
    L.setLang(-1);
    check(L.lang() == L.JA, "setLang(-1) must be ignored");
    L.setLang(L.ZH);
    check(L.lang() == L.ZH, "setLang(ZH) not applied");

    String bad = L.t("status.undo", "not-a-number");
    check(bad.equals("已撤销（可重做 %d 步）"), "wrong arg type must fall back to the pattern: " + bad);
    String none = L.t("dlg.ok", Integer.valueOf(1), Integer.valueOf(2));
    check(none.equals("确定"), "extra args must not throw: " + none);

    String[] n1 = L.languageNames();
    String[] n2 = L.languageNames();
    check(Arrays.asList(n1).equals(Arrays.asList("中文", "English", "日本語")),
        "languageNames() = " + Arrays.asList(n1));
    n1[0] = "X";
    check(L.languageNames()[0].equals("中文"), "languageNames() must return a copy");
    check(n2.length == 3, "languageNames() length");

    List<String> k1 = L.allKeys();
    check(k1.size() == EXPECTED_KEYS, "allKeys() size");
    check(k1 == L.allKeys(), "allKeys() must be stable");
    boolean immutable;
    try {
      k1.add("boom");
      immutable = false;
    } catch (UnsupportedOperationException expected) {
      immutable = true;
    }
    check(immutable, "allKeys() must be immutable");
    end();
  }

  // ------------------------------------------------------------------ (8)

  private static void sourceScan(String[] args, List<String> keys) {
    begin("(8) source table == runtime table");
    if (args.length == 0) {
      System.out.println("  SKIP: pass the path of L.java to enable the source cross-check");
      end();
      return;
    }
    File f = new File(args[0]);
    check(f.isFile(), "source file not found: " + f.getAbsolutePath());
    if (!f.isFile()) {
      end();
      return;
    }
    String text = "";
    List<String[]> rows = new ArrayList<String[]>();
    try {
      List<String> lines = Files.readAllLines(f.toPath(), Charset.forName("UTF-8"));
      StringBuilder all = new StringBuilder();
      for (String line : lines) {
        all.append(line).append('\n');
        Matcher m = SRC_ROW.matcher(line);
        if (m.matches()) {
          rows.add(new String[] {m.group(1), unescape(m.group(2)), unescape(m.group(3)),
              unescape(m.group(4))});
        }
      }
      text = all.toString();
    } catch (Exception e) {
      check(false, "cannot read source as UTF-8: " + e);
      end();
      return;
    }
    check(rows.size() == keys.size(),
        "source rows " + rows.size() + " != runtime keys " + keys.size());
    int n = Math.min(rows.size(), keys.size());
    for (int i = 0; i < n; i++) {
      String[] r = rows.get(i);
      check(r[0].equals(keys.get(i)),
          "row " + i + ": source " + r[0] + " != runtime " + keys.get(i));
      for (int li = 0; li < 3; li++) {
        String got = val(r[0], li);
        check(r[li + 1].equals(got), "row " + i + " (" + r[0] + ") lang " + li
            + ": source [" + clip(r[li + 1], 40) + "] != runtime [" + clip(got, 40) + "]");
      }
    }
    check(text.indexOf('\uFFFD') < 0, "U+FFFD replacement char in source: not valid UTF-8");
    check(text.length() > 0 && text.charAt(0) != '\uFEFF', "source starts with a BOM");
    check(text.indexOf("未命名") >= 0 && text.indexOf("開く") >= 0,
        "expected CJK literals missing from the source file");
    System.out.println("  " + rows.size() + " rows re-read from " + f.getName()
        + " and compared with the compiled table");
    end();
  }

  // ------------------------------------------------------------ samples

  private static void samples() {
    System.out.println();
    System.out.println("--- sample: the three languages side by side (raw patterns) ---");
    String[] pick = {"app.untitled", "toolbar.open", "toolbar.voicebank", "transport.play",
        "transport.snap", "param.dyn", "status.packs.ready", "status.saved.to",
        "dlg.no.voice.title", "menu.pack.delete", "toast.bad.tempo", "about.line.tip1"};
    for (int i = 0; i < pick.length; i++) {
      System.out.println("  " + pick[i]);
      System.out.println("    ZH  " + clip(val(pick[i], L.ZH), 96));
      System.out.println("    EN  " + clip(val(pick[i], L.EN), 96));
      System.out.println("    JA  " + clip(val(pick[i], L.JA), 96));
    }
    System.out.println();
    System.out.println("--- sample: rendered with arguments ---");
    render("status.packs.ready", Integer.valueOf(3), "初音ミク");
    render("status.opened", "song.vsqx", Integer.valueOf(2), Integer.valueOf(128));
    render("status.undo", Integer.valueOf(4));
    render("dlg.export.ok.msg", "/sdcard/BriVoice/out.wav", "12.3");
    render("about.line.pack", "Miku", Integer.valueOf(40), Integer.valueOf(120));
    render("pack.info.join", "EQ 3", "Formant 5");

    int withArgs = 0, total = 0, maxLen = 0, maxPlace = 0;
    for (String k : L.allKeys()) {
      List<String> p = placeholders(val(k, L.ZH));
      if (!p.isEmpty()) withArgs++;
      total += p.size();
      maxPlace = Math.max(maxPlace, p.size());
      for (int i = 0; i < 3; i++) maxLen = Math.max(maxLen, val(k, i).length());
    }
    System.out.println();
    System.out.println("--- numbers ---");
    System.out.println("  keys                    : " + L.allKeys().size());
    System.out.println("  translated strings      : " + (L.allKeys().size() * 3));
    System.out.println("  keys with placeholders  : " + withArgs);
    System.out.println("  placeholder occurrences : " + total);
    System.out.println("  max placeholders / key  : " + maxPlace);
    System.out.println("  longest string (chars)  : " + maxLen);
  }

  private static void render(String key, Object... args) {
    StringBuilder b = new StringBuilder("  " + key + "(");
    for (int i = 0; i < args.length; i++) b.append(i == 0 ? "" : ", ").append(args[i]);
    b.append(")");
    System.out.println(b);
    for (int li = 0; li < 3; li++) {
      String[] tag = {"ZH", "EN", "JA"};
      L.setLang(li);
      System.out.println("    " + tag[li] + "  " + L.t(key, args));
    }
    L.setLang(L.ZH);
  }

  // ------------------------------------------------------------- helpers

  private static void begin(String name) {
    secName = name;
    secStart = checks;
    secFailStart = failures;
  }

  private static void end() {
    boolean ok = failures == secFailStart;
    System.out.println(String.format(Locale.US, "%-44s %s  (%d checks)", secName,
        ok ? "PASS" : "FAIL", Integer.valueOf(checks - secStart)));
    System.out.println();
  }

  private static void check(boolean ok, String what) {
    checks++;
    if (!ok) {
      failures++;
      if (PROBLEMS.size() < 40) PROBLEMS.add(what);
    }
  }

  private static String val(String key, int lang) {
    L.setLang(lang);
    String s = L.t(key);
    L.setLang(L.ZH);
    return s;
  }

  private static List<String> placeholders(String s) {
    List<String> out = new ArrayList<String>();
    Matcher m = PH.matcher(s);
    while (m.find()) {
      if (!"%".equals(m.group(5))) out.add(m.group(5));
    }
    return out;
  }

  private static String phKey(String s) {
    List<String> p = placeholders(s);
    Collections.sort(p);
    return p.toString();
  }

  private static boolean supported(String s) {
    Matcher m = PH.matcher(s);
    while (m.find()) {
      if (m.group(1) != null) return false;
      String conv = m.group(5);
      if (!"s".equals(conv) && !"d".equals(conv) && !"f".equals(conv) && !"%".equals(conv)) return false;
    }
    return true;
  }

  private static Object[] args(List<String> ph) {
    Object[] a = new Object[ph.size()];
    for (int i = 0; i < a.length; i++) {
      String p = ph.get(i);
      a[i] = "d".equals(p) ? (Object) Integer.valueOf(12345)
          : "f".equals(p) ? (Object) Double.valueOf(1.5) : (Object) S_MARK;
    }
    return a;
  }

  private static String unescape(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != '\\' || i + 1 >= s.length()) {
        b.append(c);
        continue;
      }
      char n = s.charAt(++i);
      if (n == 'n') b.append('\n');
      else if (n == 't') b.append('\t');
      else if (n == 'r') b.append('\r');
      else if (n == 'u') {
        b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
        i += 4;
      } else b.append(n);
    }
    return b.toString();
  }

  private static String clip(String s, int max) {
    if (s == null) return "null";
    String t = s.replace("\n", "\\n").replace("\t", "\\t");
    return t.length() <= max ? t : t.substring(0, max - 1) + "\u2026";
  }
}
