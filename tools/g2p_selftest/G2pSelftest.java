import com.dsh.v4tune.phoneme.ChineseG2P;
import com.dsh.v4tune.phoneme.JapaneseG2P;
import com.dsh.v4tune.phoneme.PhonemeDef;
import com.dsh.v4tune.phoneme.PhonemeTable;
import com.dsh.v4tune.phoneme.PinyinDict;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Desktop acceptance test for the V4Tune phoneme/G2P data layer. Not shipped
 * in the APK - it only needs the five classes in com.dsh.v4tune.phoneme.
 *
 * <pre>
 * javac -encoding UTF-8 -d build/g2ptest app/src/com/dsh/v4tune/phoneme/*.java tools/g2p_selftest/G2pSelftest.java
 * java -cp build/g2ptest G2pSelftest app/assets/dict/pinyin.txt.gz
 * </pre>
 *
 * Sections:
 *   (a) exact pinyin -> phoneme mappings taken verbatim from
 *       docs/voicebank-notes.md section 5.2 (proven) plus the rimes derived
 *       from them (eng = @N, ong = UN);
 *   (b) the 62 proven CHN symbols with their acoustic targets;
 *   (c) normalize() rewriting of legacy spellings;
 *   (d) an exhaustive sweep over the whole syllable inventory.
 *
 * Prints "PASS n/n" and exits 0 when every check passes; prints "FAIL n/n",
 * lists the first problems and exits 1 otherwise (2 on usage/setup errors).
 */
public class G2pSelftest {

  private static int checks = 0;
  private static int failures = 0;
  private static final List<String> PROBLEMS = new ArrayList<String>();

  /** The 62 proven CHN symbols, PHDC order (voicebank-notes.md 3.1), used as the reference list. */
  private static final String[] CHN62 = {
    "a", "o", "7", "aI", "ei", "@`", "AU", "@U",
    "a_n", "@_n", "AN", "@N",
    "i\\", "i`", "i", "ia", "iE_r", "iAU", "i@U", "iE_n", "i_n", "iAN", "iN", "iUN",
    "u", "ua", "uo", "uaI", "uei", "ua_n", "u@_n", "uAN", "UN", "u@N",
    "y", "yE_r", "y{_n", "y_n",
    "n", "m", "z`", "l",
    "t", "p", "k", "k_h", "p_h", "t_h",
    "s\\", "f", "x", "s", "s`",
    "ts\\_h", "ts\\", "ts`_h", "ts_h", "ts`", "ts",
    "?", "Sil", "Asp"
  };

  /**
   * (a) PROVEN pinyin -> phoneme mappings. Every row is either quoted verbatim
   * from voicebank-notes.md 5.2 or composed from its proven initial table and
   * proven final table (initial + final, nothing else).
   */
  private static final String[][] PROVEN = {
    // --- the examples quoted verbatim in the document
    {"ba", "p a"},        {"pa", "p_h a"},      {"zha", "ts` a"},     {"chi", "ts`_h i`"},
    {"zi", "ts i\\"},     {"si", "s i\\"},      {"ge", "k 7"},        {"zhe", "ts` 7"},
    {"ji", "ts\\ i"},     {"ju", "ts\\ y"},     {"jue", "ts\\ yE_r"}, {"jun", "ts\\ y_n"},
    {"jiong", "ts\\ iUN"},{"nv", "n y"},        {"lve", "l yE_r"},    {"guang", "k uAN"},
    {"xiong", "s\\ iUN"}, {"quan", "ts\\_h y{_n"},
    {"luo", "l uo"},      {"tian", "t_h iE_n"}, {"yi", "i"},          {"chuan", "ts`_h ua_n"},
    {"lian", "l iE_n"},   {"er", "@`"},         {"zhuang", "ts` uAN"},
    // --- initials x simple finals
    {"ma", "m a"},   {"fa", "f a"},   {"da", "t a"},   {"ta", "t_h a"}, {"na", "n a"},
    {"la", "l a"},   {"ga", "k a"},   {"ka", "k_h a"}, {"ha", "x a"},   {"cha", "ts`_h a"},
    {"sha", "s` a"}, {"za", "ts a"},  {"ca", "ts_h a"},{"sa", "s a"},
    {"bo", "p o"},   {"po", "p_h o"}, {"mo", "m o"},   {"fo", "f o"},
    {"de", "t 7"},   {"te", "t_h 7"}, {"ne", "n 7"},   {"le", "l 7"},   {"ke", "k_h 7"},
    {"he", "x 7"},   {"che", "ts`_h 7"}, {"she", "s` 7"}, {"re", "z` 7"}, {"ze", "ts 7"},
    {"ce", "ts_h 7"},{"se", "s 7"},
    {"bi", "p i"},   {"pi", "p_h i"}, {"mi", "m i"},   {"di", "t i"},   {"ti", "t_h i"},
    {"ni", "n i"},   {"li", "l i"},   {"qi", "ts\\_h i"}, {"xi", "s\\ i"},
    {"bu", "p u"},   {"pu", "p_h u"}, {"mu", "m u"},   {"fu", "f u"},   {"du", "t u"},
    {"tu", "t_h u"}, {"nu", "n u"},   {"lu", "l u"},   {"gu", "k u"},   {"ku", "k_h u"},
    {"hu", "x u"},   {"zhu", "ts` u"},{"chu", "ts`_h u"}, {"shu", "s` u"}, {"ru", "z` u"},
    {"zu", "ts u"},  {"cu", "ts_h u"},{"su", "s u"},
    // --- the apical vowels and the zero-initial rimes
    {"zhi", "ts` i`"}, {"shi", "s` i`"}, {"ri", "z` i`"}, {"ci", "ts_h i\\"},
    {"ai", "aI"}, {"ei", "ei"}, {"ao", "AU"}, {"ou", "@U"}, {"an", "a_n"}, {"en", "@_n"}, {"ang", "AN"},
    {"ya", "ia"}, {"ye", "iE_r"}, {"yao", "iAU"}, {"you", "i@U"}, {"yan", "iE_n"},
    {"yin", "i_n"}, {"yang", "iAN"}, {"ying", "iN"}, {"yong", "iUN"},
    {"wu", "u"}, {"wa", "ua"}, {"wo", "uo"}, {"wai", "uaI"}, {"wei", "uei"},
    {"wan", "ua_n"}, {"wen", "u@_n"}, {"wang", "uAN"}, {"weng", "u@N"},
    {"yu", "y"}, {"yue", "yE_r"}, {"yuan", "y{_n"}, {"yun", "y_n"},
    // --- j/q/x with the u-umlaut rimes
    {"juan", "ts\\ y{_n"}, {"qu", "ts\\_h y"}, {"que", "ts\\_h yE_r"}, {"qun", "ts\\_h y_n"},
    {"xu", "s\\ y"}, {"xue", "s\\ yE_r"}, {"xuan", "s\\ y{_n"}, {"xun", "s\\ y_n"},
    {"lv", "l y"}, {"nve", "n yE_r"},
    // --- mixed syllables
    {"xiao", "s\\ iAU"}, {"shan", "s` a_n"}, {"xing", "s\\ iN"}, {"jing", "ts\\ iN"},
    {"hua", "x ua"}, {"gui", "k uei"}, {"liu", "l i@U"}, {"miao", "m iAU"}, {"bie", "p iE_r"},
    {"bin", "p i_n"}, {"liang", "l iAN"}, {"bing", "p iN"}, {"duan", "t ua_n"}, {"dun", "t u@_n"},
    {"cui", "ts_h uei"}, {"suan", "s ua_n"}, {"nuan", "n ua_n"}, {"shei", "s` ei"}, {"zhei", "ts` ei"}
  };

  /**
   * (a, derived) Syllables whose rime is NOT listed verbatim in the document:
   * eng = @N and ong = UN are forced by the proven weng = u@N / ueng = u@N and
   * yong = iUN / iong = iUN entries. Marked separately on purpose.
   */
  private static final String[][] DERIVED = {
    {"meng", "m @N"},   {"beng", "p @N"},    {"peng", "p_h @N"}, {"feng", "f @N"},
    {"deng", "t @N"},   {"teng", "t_h @N"},  {"neng", "n @N"},   {"leng", "l @N"},
    {"geng", "k @N"},   {"keng", "k_h @N"},  {"heng", "x @N"},   {"zheng", "ts` @N"},
    {"cheng", "ts`_h @N"}, {"sheng", "s` @N"}, {"reng", "z` @N"}, {"zeng", "ts @N"},
    {"ceng", "ts_h @N"}, {"seng", "s @N"},
    {"dong", "t UN"},   {"tong", "t_h UN"},  {"nong", "n UN"},   {"long", "l UN"},
    {"gong", "k UN"},   {"kong", "k_h UN"},  {"hong", "x UN"},   {"zhong", "ts` UN"},
    {"chong", "ts`_h UN"}, {"rong", "z` UN"}, {"zong", "ts UN"}, {"cong", "ts_h UN"},
    {"song", "s UN"},
    {"ng", "@N"}, {"hm", "m"}, {"hng", "@N"}, {"yo", "o"}
  };

  /** (c) legacy spelling -> canonical symbol, checked through normalize(). */
  private static final String[][] LEGACY = {
    {"sh a", "s` a"},          {"zh a", "ts` a"},        {"ch a", "ts`_h a"},
    {"z a", "ts a"},           {"c a", "ts_h a"},        {"j i", "ts\\ i"},
    {"q i", "ts\\_h i"},       {"v", "y"},               {"uu", "y"},
    {"er", "@`"},              {"ii", "i\\"},            {"iii", "i`"},
    {"ng", "N"},               {"n'", "N"},              {"sil", "Sil"},
    {"sp", "Sil"},             {"pau", "Sil"},           {"rest", "Sil"},
    {"br", "Asp"},             {"schwa", "a"},
    {"clos", "cl"},            {"closure", "cl"},        {"SH", "s`"},
    {"ZH III", "ts` i`"},      {"  \u00dc   SP  ", "y Sil"}, {"TS`_H  A", "ts`_h a"},
    {"s\\", "s\\"},            {"@`", "@`"},             {"7", "7"},
    {"zzz", "zzz"}
  };

  private static final String[] JP_SAMPLES = {
    "\u3042", "\u3044", "\u3046", "\u3048", "\u304a", "\u304b", "\u304d", "\u304f", "\u3051", "\u3053",
    "\u3055", "\u3057", "\u3059", "\u305f", "\u3061", "\u3064", "\u306a", "\u306b", "\u306c", "\u306d", "\u306e",
    "\u306f", "\u3072", "\u3075", "\u3078", "\u307b", "\u307e", "\u307f", "\u3080", "\u3081", "\u3082",
    "\u3084", "\u3086", "\u3088", "\u3089", "\u308a", "\u308b", "\u308c", "\u308d", "\u308f", "\u3092",
    "\u304c", "\u304e", "\u3050", "\u3052", "\u3054", "\u3056", "\u3058", "\u305a", "\u305c", "\u305e",
    "\u3060", "\u3062", "\u3065", "\u3067", "\u3069", "\u3070", "\u3073", "\u3076", "\u3079", "\u307c",
    "\u3071", "\u3074", "\u3077", "\u307a", "\u307d", "\u3093", "\u3063", "\u3094",
    "\u304d\u3083", "\u304d\u3085", "\u304d\u3087", "\u304e\u3083", "\u3057\u3083", "\u3057\u3085", "\u3057\u3087",
    "\u3058\u3083", "\u3058\u3085", "\u3058\u3087", "\u3061\u3083", "\u3061\u3085", "\u3061\u3087",
    "\u306b\u3083", "\u306b\u3085", "\u306b\u3087", "\u3072\u3083", "\u3073\u3083", "\u3074\u3083",
    "\u307f\u3083", "\u308a\u3083", "\u308a\u3085", "\u308a\u3087"
  };

  private static final String[] JP_ROMAJI = {
    "ka", "shi", "tsu", "n", "Q", "kyou", "gakkou", "shinjuku", "tokyo", "matsuri",
    "sakura", "ramen", "gakusei", "nippon", "senpai", "zasshi", "chotto", "ryokou", "byouin", "ja"
  };

  /** Hanzi whose reading exercises a specific rime; all must resolve. */
  private static final String HANZI = "\u6d1b\u5929\u4f9d\u4e2d\u56fd\u5973\u7eff\u7231\u6625\u6d77\u661f"
      + "\u4f60\u597d\u4e16\u754c\u5b66\u4e60\u98ce\u4e91\u6708\u4eae\u82b1\u513f\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341";

  /** Mora that legitimately contain no vowel. */
  private static final Set<String> NO_VOWEL_OK = new HashSet<String>(
      Arrays.asList("n", "ng", "hm", "hng", "N", "cl", "nn", "Q", "m",
          "\u3093", "\u3063", "\u30f3", "\u30c3"));

  public static void main(String[] args) throws Exception {
    System.out.println("=== V4Tune phoneme/G2P self-test ===");
    if (args.length < 1) {
      System.out.println("usage: G2pSelftest <path/to/pinyin.txt.gz>");
      System.exit(2);
    }
    File gz = new File(args[0]);
    if (!gz.isFile()) {
      System.out.println("FAIL: dictionary not found: " + gz.getAbsolutePath());
      System.exit(2);
    }

    long t0 = System.currentTimeMillis();
    InputStream in = new FileInputStream(gz);
    try {
      ChineseG2P.init(in);
    } finally {
      in.close();
    }
    System.out.println("dictionary   : " + gz.getAbsolutePath());
    System.out.println("entries      : " + PinyinDict.size() + " (" + (System.currentTimeMillis() - t0) + " ms)");
    check("dictionary is ready", ChineseG2P.isReady());
    check("dictionary is non-trivial (>= 2000 hanzi)", PinyinDict.size() >= 2000);
    System.out.println();

    sectionInventory();
    sectionProvenMappings();
    sectionDerivedMappings();
    sectionNormalize();
    sectionSweep();
    sectionHanzi();
    sectionJapanese();

    System.out.println();
    int passed = checks - failures;
    if (failures == 0) {
      System.out.println("PASS " + passed + "/" + checks);
      System.exit(0);
    }
    System.out.println("FAIL " + passed + "/" + checks);
    System.out.println("first problems:");
    for (int i = 0; i < PROBLEMS.size() && i < 30; i++) System.out.println("  - " + PROBLEMS.get(i));
    System.exit(1);
  }

  // --------------------------------------------------------- (b) inventory

  private static void sectionInventory() {
    System.out.println("--- (b) CHN phoneme inventory ---");
    List<String> chn = PhonemeTable.chineseSymbols();
    check("PhonemeTable.chineseSymbols() has exactly 62 entries", chn.size() == 62);
    boolean orderOk = chn.size() == CHN62.length;
    for (int i = 0; orderOk && i < CHN62.length; i++) orderOk = CHN62[i].equals(chn.get(i));
    check("the 62 symbols are the proven PHDC list in PHDC order", orderOk);
    for (int i = 0; i < CHN62.length; i++) {
      PhonemeDef d = PhonemeTable.get(CHN62[i]);
      System.out.println(String.format("%3d %-8s %-7s f=%-13s zero=%.2f v=%.2f n=%.2f fc=%-5.0f dur=%.2f",
          Integer.valueOf(i + 1), CHN62[i], typeName(d), formants(d),
          Double.valueOf(d == null ? -1 : d.nasalZero), Double.valueOf(d == null ? -1 : d.voiced),
          Double.valueOf(d == null ? -1 : d.noise), Double.valueOf(d == null ? -1 : d.noiseFc),
          Double.valueOf(d == null ? -1 : d.durDef)));
      if (d == null) fail("CHN symbol missing from table: " + CHN62[i]);
      else pass();
    }

    int vowelCount = 0;
    for (int i = 0; i < CHN62.length; i++) {
      PhonemeDef d = PhonemeTable.get(CHN62[i]);
      if (d == null || d.type != PhonemeDef.T_VOWEL) continue;
      vowelCount++;
      boolean rhotic = CHN62[i].equals("@`") || CHN62[i].equals("iE_r") || CHN62[i].equals("yE_r");
      if (!(d.f1 > 0 && d.f2 > 0 && d.f3 > 0 && d.f4 > 0 && d.f5 > 0)) {
        fail("vowel " + CHN62[i] + " has a zero formant: " + formants(d));
      } else if (!(d.f1 < d.f2)) {
        fail("vowel " + CHN62[i] + " F1 >= F2: " + formants(d));
      } else if (rhotic && !(d.f3 < 2000 && (d.f3 - d.f2) < 400)) {
        fail("rhotacised vowel " + CHN62[i] + " must have a lowered F3 close to F2: " + formants(d));
      } else if (!rhotic && !(d.f2 < d.f3)) {
        fail("vowel " + CHN62[i] + " F2 >= F3: " + formants(d));
      } else if (!(d.voiced > 0.5 && d.durMin > 0 && d.durDef >= d.durMin)) {
        fail("vowel " + CHN62[i] + " has no voicing/duration: " + d);
      } else {
        pass();
      }
    }
    check("38 CHN vowels with non-zero formants", vowelCount == 38);

    String[] nasalised = {"a_n","@_n","iE_n","i_n","ua_n","u@_n","y{_n","y_n","AN","@N","iN","uAN","UN","u@N","iAN","iUN"};
    for (int i = 0; i < nasalised.length; i++) {
      PhonemeDef d = PhonemeTable.get(nasalised[i]);
      check("nasal rime " + nasalised[i] + " has nasalZero > 0", d != null && d.nasalZero > 0);
    }

    String[][] fric = {
      {"s\\", "4000", "5500"}, {"f", "2500", "6000"}, {"x", "1000", "3000"},
      {"s", "5000", "7000"}, {"s`", "2500", "3500"}
    };
    for (int i = 0; i < fric.length; i++) {
      PhonemeDef d = PhonemeTable.get(fric[i][0]);
      double lo = Double.parseDouble(fric[i][1]), hi = Double.parseDouble(fric[i][2]);
      check("fricative " + fric[i][0] + " noise>0 and noiseFc " + fric[i][1] + ".." + fric[i][2] + " Hz",
          d != null && d.type == PhonemeDef.T_FRIC && d.noise > 0 && d.noiseBw > 0
              && d.noiseFc >= lo && d.noiseFc <= hi);
    }
    String[][] affr = {
      {"ts", "5000", "7000", "0"}, {"ts_h", "5000", "7000", "1"},
      {"ts`", "2500", "3500", "0"}, {"ts`_h", "2500", "3500", "1"},
      {"ts\\", "4000", "5500", "0"}, {"ts\\_h", "4000", "5500", "1"}
    };
    for (int i = 0; i < affr.length; i++) {
      PhonemeDef d = PhonemeTable.get(affr[i][0]);
      double lo = Double.parseDouble(affr[i][1]), hi = Double.parseDouble(affr[i][2]);
      boolean wantAsp = affr[i][3].equals("1");
      check("affricate " + affr[i][0] + " noiseFc " + affr[i][1] + ".." + affr[i][2]
              + " Hz, aspirated=" + wantAsp,
          d != null && d.type == PhonemeDef.T_AFFRIC && d.noise > 0 && d.noiseFc >= lo && d.noiseFc <= hi
              && d.aspirated == wantAsp);
    }
    String[] stopsUnasp = {"p", "t", "k"};
    String[] stopsAsp = {"p_h", "t_h", "k_h"};
    for (int i = 0; i < 3; i++) {
      PhonemeDef a = PhonemeTable.get(stopsUnasp[i]), b = PhonemeTable.get(stopsAsp[i]);
      check("stop " + stopsUnasp[i] + " unaspirated / " + stopsAsp[i] + " aspirated",
          a != null && b != null && a.type == PhonemeDef.T_STOP && b.type == PhonemeDef.T_STOP
              && !a.aspirated && b.aspirated && b.noise > a.noise && b.durDef > a.durDef);
    }
    check("bilabial locus F2 ~ 800", Math.abs(PhonemeTable.get("p").f2 - 800) <= 100);
    check("alveolar locus F2 ~ 1700", Math.abs(PhonemeTable.get("t").f2 - 1700) <= 150);
    check("velar pinch F2 ~ F3", Math.abs(PhonemeTable.get("k").f2 - PhonemeTable.get("k").f3) <= 400);

    check("nasal n murmur with anti-resonance", PhonemeTable.get("n").nasalZero > 0 && PhonemeTable.get("n").f1 <= 300);
    check("nasal m murmur with anti-resonance", PhonemeTable.get("m").nasalZero > 0 && PhonemeTable.get("m").f2 < 1300);
    PhonemeDef l = PhonemeTable.get("l");
    check("lateral l ~ F1 350 / F2 1000 / F3 2600",
        Math.abs(l.f1 - 350) <= 50 && Math.abs(l.f2 - 1000) <= 100 && Math.abs(l.f3 - 2600) <= 100);
    PhonemeDef zr = PhonemeTable.get("z`");
    check("retroflex approximant z` is voiced with a low F3", zr.voiced > 0.5 && zr.f3 < 2000);
    check("glottal stop ? exists", PhonemeTable.get("?") != null && PhonemeTable.get("?").type == PhonemeDef.T_STOP);
    check("Sil is the silence definition", PhonemeTable.SIL != null && PhonemeTable.SIL.isSilence()
        && PhonemeTable.SIL.sym.equals("Sil") && PhonemeTable.get("Sil") == PhonemeTable.SIL);
    check("Asp is an aspiration phoneme", PhonemeTable.get("Asp") != null
        && PhonemeTable.get("Asp").type == PhonemeDef.T_ASPIR && PhonemeTable.get("Asp").noise > 0.5);

    int bad = 0;
    for (String sym : PhonemeTable.symbols()) {
      PhonemeDef d = PhonemeTable.get(sym);
      if (d == null || d.durDef <= 0 || d.durMin <= 0 || d.durMin > d.durDef) {
        fail("inventory symbol " + sym + " has no sane duration");
        bad++;
      }
    }
    check("every table symbol resolves with a sane duration", bad == 0);
    check("legacy symbols are still present (nothing that used to work broke)",
        PhonemeTable.get("cl") != null && PhonemeTable.get("sil") != null && PhonemeTable.get("N") != null
            && PhonemeTable.get("ng") != null && PhonemeTable.get("v") != null && PhonemeTable.get("sh") != null
            && PhonemeTable.get("zh") != null && PhonemeTable.get("ch") != null && PhonemeTable.get("j") != null
            && PhonemeTable.get("q") != null && PhonemeTable.get("x") != null && PhonemeTable.get("r") != null
            && PhonemeTable.get("z") != null && PhonemeTable.get("c") != null && PhonemeTable.get("h") != null
            && PhonemeTable.get("b") != null && PhonemeTable.get("d") != null && PhonemeTable.get("g") != null
            && PhonemeTable.get("e") != null && PhonemeTable.get("y") != null && PhonemeTable.get("w") != null
            && PhonemeTable.get("er") != null && PhonemeTable.get("ii") != null && PhonemeTable.get("iii") != null);
    check("getOrDefault never null", PhonemeTable.getOrDefault("zzz") != null && PhonemeTable.getOrDefault(null) != null);
    check("getOrDefault falls back to silence spellings", PhonemeTable.getOrDefault("SP").isSilence());
    check("get is case-insensitive", PhonemeTable.get(" SH ") != null && PhonemeTable.get("Sil") != null);
    System.out.println();
  }

  // ------------------------------------------------------------ (a) mappings

  private static void sectionProvenMappings() {
    System.out.println("--- (a) proven pinyin -> phoneme mappings (" + PROVEN.length + ") ---");
    for (int i = 0; i < PROVEN.length; i++) {
      String syl = PROVEN[i][0];
      String want = PROVEN[i][1];
      List<String> ph = ChineseG2P.syllableToPhonemes(syl);
      String got = PhonemeTable.join(ph);
      boolean ok = got.equals(want) && allChinese(ph);
      System.out.println(String.format("%-8s -> %-18s %s", syl, got, ok ? "PASS" : "FAIL (expected " + want + ")"));
      if (ok) pass(); else fail("proven \"" + syl + "\" -> \"" + got + "\", expected \"" + want + "\"");
    }
    check("at least 60 proven mappings asserted", PROVEN.length >= 60);
    System.out.println();
  }

  private static void sectionDerivedMappings() {
    System.out.println("--- (a, derived) syllables using eng=@N / ong=UN (" + DERIVED.length + ") ---");
    for (int i = 0; i < DERIVED.length; i++) {
      String syl = DERIVED[i][0];
      String want = DERIVED[i][1];
      List<String> ph = ChineseG2P.syllableToPhonemes(syl);
      String got = PhonemeTable.join(ph);
      boolean derived = ChineseG2P.isDerived(syl);
      boolean ok = got.equals(want) && allChinese(ph) && derived;
      System.out.println(String.format("%-8s -> %-18s %s", syl, got, ok ? "PASS (derived)" : "FAIL (expected " + want + ")"));
      if (ok) pass(); else fail("derived \"" + syl + "\" -> \"" + got + "\", expected \"" + want + "\"");
    }
    check("proven syllables are not reported as derived",
        !ChineseG2P.isDerived("luo") && !ChineseG2P.isDerived("tian") && !ChineseG2P.isDerived("weng")
            && !ChineseG2P.isDerived("yong") && !ChineseG2P.isDerived("shan"));
    System.out.println();
  }

  // ---------------------------------------------------------- (c) normalize

  private static void sectionNormalize() {
    System.out.println("--- (c) normalize() legacy -> canonical (" + LEGACY.length + ") ---");
    for (int i = 0; i < LEGACY.length; i++) {
      String in = LEGACY[i][0];
      String want = LEGACY[i][1];
      String got = PhonemeTable.normalize(in);
      boolean ok = got.equals(want);
      System.out.println(String.format("%-16s -> %-16s %s", quote(in), quote(got), ok ? "PASS" : "FAIL (expected " + quote(want) + ")"));
      if (ok) pass(); else fail("normalize(\"" + in + "\") -> \"" + got + "\", expected \"" + want + "\"");
    }
    int idem = 0;
    for (String sym : PhonemeTable.symbols()) {
      String once = PhonemeTable.normalize(sym);
      if (!once.equals(sym)) {
        fail("canonical symbol \"" + sym + "\" normalizes to \"" + once + "\"");
      } else if (!PhonemeTable.normalize(once).equals(once)) {
        fail("normalize is not idempotent for \"" + sym + "\"");
      } else {
        idem++;
      }
    }
    check("every canonical symbol is a normalize() fixed point (" + idem + ")", idem == PhonemeTable.symbols().size());
    check("normalize(null) is empty", PhonemeTable.normalize(null).equals(""));
    check("normalize collapses whitespace", PhonemeTable.normalize("  a   p  ").equals("a p"));
    System.out.println();
  }

  // ------------------------------------------------------------------ sweep

  private static void sectionSweep() {
    System.out.println("--- (d) exhaustive syllable sweep ---");
    List<String> all = ChineseG2P.allSyllables();
    int proven = 0, derived = 0, bad = 0, nonChinese = 0;
    for (String syl : all) {
      List<String> ph = ChineseG2P.syllableToPhonemes(syl);
      if (ChineseG2P.isDerived(syl)) derived++; else proven++;
      if (ph.isEmpty()) { bad++; fail("sweep: \"" + syl + "\" produced no phonemes"); continue; }
      if (!symbolsKnown(ph)) { bad++; fail("sweep: \"" + syl + "\" has unknown symbols: " + PhonemeTable.join(ph)); continue; }
      if (!NO_VOWEL_OK.contains(syl) && !hasVowel(ph)) {
        bad++; fail("sweep: \"" + syl + "\" has no vowel: " + PhonemeTable.join(ph)); continue;
      }
      if (!allChinese(ph)) {
        nonChinese++;
        fail("sweep: \"" + syl + "\" emits a non-CHN symbol: " + PhonemeTable.join(ph));
      }
    }
    check("swept " + all.size() + " syllables, all with known symbols and a nucleus", bad == 0);
    check("every emitted symbol is one of the 62 CHN phonemes", nonChinese == 0);
    check("syllable inventory covers >= 400 forms", all.size() >= 400);
    System.out.println("syllables    : " + all.size() + " (" + proven + " proven, " + derived + " derived)");
    System.out.println("PROVEN_DERIVED " + proven + " " + derived);
    check("isSyllable positive", ChineseG2P.isSyllable("luo4") && ChineseG2P.isSyllable(" LA ") && ChineseG2P.isSyllable("l\u01dc"));
    check("isSyllable negative", !ChineseG2P.isSyllable("zhuangx") && !ChineseG2P.isSyllable("zzz") && !ChineseG2P.isSyllable(""));
    System.out.println();
  }

  // ------------------------------------------------------------------ hanzi

  private static void sectionHanzi() {
    System.out.println("--- hanzi / lyric ---");
    int ok = 0;
    for (int i = 0; i < HANZI.length(); i++) {
      char c = HANZI.charAt(i);
      String py = ChineseG2P.hanziToPinyin(c);
      List<String> ph = py == null ? null : ChineseG2P.syllableToPhonemes(py);
      boolean good = py != null && ph != null && !ph.isEmpty() && allChinese(ph);
      if (good) ok++; else fail("hanzi U+" + String.format("%04X", Integer.valueOf(c)) + " -> " + py
          + " -> " + PhonemeTable.join(ph));
    }
    check("all " + HANZI.length() + " sampled hanzi resolve to CHN phonemes (" + ok + ")", ok == HANZI.length());
    String[] words = {"\u6d1b", "\u5929\u4f9d", "\u6625\u5929", "\u6708\u4eae", "\u4e2d\u56fd"};
    String[] want = {"l uo", "t_h iE_n i", "ts`_h u@_n t_h iE_n", "yE_r l iAN", "ts` UN k uo"};
    for (int i = 0; i < words.length; i++) {
      String got = PhonemeTable.join(ChineseG2P.lyricToPhonemes(words[i]));
      boolean good = got.equals(want[i]) && allChinese(strList(got));
      System.out.println("lyric " + quote(words[i]) + " -> " + got + (good ? "  PASS" : "  FAIL (expected " + want[i] + ")"));
      if (good) pass(); else fail("lyric \"" + words[i] + "\" -> \"" + got + "\", expected \"" + want[i] + "\"");
    }
    check("lyric \"luo4\" -> l uo", "l uo".equals(PhonemeTable.join(ChineseG2P.lyricToPhonemes("luo4"))));
    check("lyric \"LA\" -> l a", "l a".equals(PhonemeTable.join(ChineseG2P.lyricToPhonemes("LA"))));
    check("lyric \"lu\u00f2\" -> l uo", "l uo".equals(PhonemeTable.join(ChineseG2P.lyricToPhonemes("lu\u00f2"))));
    check("lyric gibberish -> null", ChineseG2P.lyricToPhonemes("qqq") == null);
    check("stripTone", "luo".equals(ChineseG2P.stripTone(" Lu\u00f24 ")) && "lv".equals(ChineseG2P.stripTone("l\u01dc"))
        && "nv".equals(ChineseG2P.stripTone("nu:")));
    System.out.println();
  }

  // --------------------------------------------------------------- japanese

  private static void sectionJapanese() {
    System.out.println("--- Japanese (best effort; not a proven JPN set) ---");
    int bad = 0;
    for (int i = 0; i < JP_SAMPLES.length; i++) {
      List<String> ph = JapaneseG2P.moraToPhonemes(JP_SAMPLES[i]);
      if (ph == null || ph.isEmpty()) { bad++; fail("mora " + codePoints(JP_SAMPLES[i]) + " produced nothing"); continue; }
      if (!symbolsKnown(ph)) { bad++; fail("mora " + codePoints(JP_SAMPLES[i]) + " unknown symbol: " + PhonemeTable.join(ph)); continue; }
      if (!hasVowel(ph) && !NO_VOWEL_OK.contains(JP_SAMPLES[i])) {
        bad++; fail("mora " + codePoints(JP_SAMPLES[i]) + " has no vowel");
      }
    }
    check("all " + JP_SAMPLES.length + " kana mora convert to known symbols", bad == 0);
    int rbad = 0;
    for (int i = 0; i < JP_ROMAJI.length; i++) {
      List<String> ph = JapaneseG2P.moraToPhonemes(JP_ROMAJI[i]);
      if (ph == null || ph.isEmpty() || !symbolsKnown(ph)) { rbad++; fail("romaji \"" + JP_ROMAJI[i] + "\" -> " + PhonemeTable.join(ph)); }
    }
    check("all " + JP_ROMAJI.length + " romaji mora convert to known symbols", rbad == 0);
    check("shi -> s\\ i", "s\\ i".equals(PhonemeTable.join(JapaneseG2P.moraToPhonemes("\u3057"))));
    check("chi -> ts\\ i", "ts\\ i".equals(PhonemeTable.join(JapaneseG2P.moraToPhonemes("\u3061"))));
    check("za -> dz a (voicing kept)", "dz a".equals(PhonemeTable.join(JapaneseG2P.moraToPhonemes("\u3056"))));
    check("ji -> dZ i (voicing kept)", "dZ i".equals(PhonemeTable.join(JapaneseG2P.moraToPhonemes("\u3058"))));
    check("kyou -> \u304d\u3087\u3046", "\u304d\u3087\u3046".equals(JapaneseG2P.romajiToKana("kyou")));
    check("gakkou -> \u304c\u3063\u3053\u3046", "\u304c\u3063\u3053\u3046".equals(JapaneseG2P.romajiToKana("gakkou")));
    check("isKana", JapaneseG2P.isKana('\u3042') && JapaneseG2P.isKana('\u30ab') && JapaneseG2P.isKana('\u30fc') && !JapaneseG2P.isKana('a'));
    check("lyric \u3055\u304f\u3089 -> s a k u r a", "s a k u r a".equals(PhonemeTable.join(JapaneseG2P.lyricToPhonemes("\u3055\u304f\u3089"))));
    check("lyric \u30ab\u30fc\u30c6\u30f3 holds the long vowel", "k a a t e N".equals(PhonemeTable.join(JapaneseG2P.lyricToPhonemes("\u30ab\u30fc\u30c6\u30f3"))));
    check("lyric \u304d\u3087\u3046 -> k i o u", "k i o u".equals(PhonemeTable.join(JapaneseG2P.lyricToPhonemes("\u304d\u3087\u3046"))));
    check("lyric \u304c\u3063\u3053\u3046 -> g a cl k o u", "g a cl k o u".equals(PhonemeTable.join(JapaneseG2P.lyricToPhonemes("\u304c\u3063\u3053\u3046"))));
    check("mora \"Q\" -> cl", "cl".equals(PhonemeTable.join(JapaneseG2P.moraToPhonemes("Q"))));
    check("mora \"n\" -> N", "N".equals(PhonemeTable.join(JapaneseG2P.moraToPhonemes("n"))));
    System.out.println();
  }

  // ---------------------------------------------------------------- helpers

  private static List<String> strList(String spaceSeparated) {
    List<String> out = new ArrayList<String>();
    String[] parts = spaceSeparated.split(" ");
    for (int i = 0; i < parts.length; i++) if (parts[i].length() > 0) out.add(parts[i]);
    return out;
  }

  private static boolean allChinese(List<String> ph) {
    for (int i = 0; i < ph.size(); i++) {
      if (!PhonemeTable.isChineseSymbol(ph.get(i))) return false;
    }
    return true;
  }

  private static boolean symbolsKnown(List<String> ph) {
    for (int i = 0; i < ph.size(); i++) if (PhonemeTable.get(ph.get(i)) == null) return false;
    return true;
  }

  private static boolean hasVowel(List<String> ph) {
    for (int i = 0; i < ph.size(); i++) if (PhonemeTable.isVowel(ph.get(i))) return true;
    return false;
  }

  private static String typeName(PhonemeDef d) {
    if (d == null) return "?";
    switch (d.type) {
      case PhonemeDef.T_VOWEL: return "VOWEL";
      case PhonemeDef.T_STOP: return "STOP";
      case PhonemeDef.T_FRIC: return "FRIC";
      case PhonemeDef.T_AFFRIC: return "AFFRIC";
      case PhonemeDef.T_NASAL: return "NASAL";
      case PhonemeDef.T_LIQUID: return "LIQUID";
      case PhonemeDef.T_GLIDE: return "GLIDE";
      case PhonemeDef.T_ASPIR: return "ASPIR";
      case PhonemeDef.T_SIL: return "SIL";
      default: return "t" + d.type;
    }
  }

  private static String formants(PhonemeDef d) {
    if (d == null) return "-";
    return String.format("%.0f/%.0f/%.0f", Double.valueOf(d.f1), Double.valueOf(d.f2), Double.valueOf(d.f3));
  }

  private static void check(String what, boolean ok) {
    checks++;
    if (ok) {
      System.out.println("  ok   " + what);
    } else {
      failures++;
      PROBLEMS.add(what);
      System.out.println("  FAIL " + what);
    }
  }

  private static void pass() { checks++; }

  private static void fail(String msg) {
    checks++;
    failures++;
    PROBLEMS.add(msg);
    System.out.println("  FAIL " + msg);
  }

  private static String quote(String s) {
    return "\"" + s + "\"";
  }

  /** ASCII-safe rendering of non-ASCII text so consoles with a legacy code page stay readable. */
  private static String codePoints(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      if (i > 0) sb.append(' ');
      sb.append(String.format("U+%04X", Integer.valueOf(s.charAt(i))));
    }
    return sb.toString();
  }
}
