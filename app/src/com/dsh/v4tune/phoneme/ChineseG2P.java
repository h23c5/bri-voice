package com.dsh.v4tune.phoneme;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Mandarin grapheme-to-phoneme conversion: pinyin syllable -> VOCALOID4
 * Chinese phoneme symbols, plus hanzi lookup through {@link PinyinDict}.
 *
 * <h3>Authority</h3>
 * The initial and final tables below are the 449 pinyin -> phoneme mappings
 * proven from g2pa4_CHS.dll (docs/voicebank-notes.md section 5.2). Every
 * syllable is an optional initial (shengmu) plus a final (yunmu); the final
 * table is keyed by the rime spelling and carries the proven phoneme symbols,
 * so e.g. "tian" = t_h + iE_n, "xiao" = s\ + iAU, "chuan" = ts`_h + ua_n.
 * The output symbols are exactly the 62 CHN symbols of
 * {@link PhonemeTable#chineseSymbols()} and never a legacy alias.
 *
 * <h3>Orthography handled here</h3>
 * <ul>
 * <li>zero-initial spellings are just another way of writing the same rime:
 *     ya=ia, ye=ie, yao=iao, you=iu, yan=ian, yin=in, yang=iang, ying=ing,
 *     yong=iong, yi=i, yu=v, yue=ve, yuan=van, yun=vn, wu=u, wa=ua, wo=uo,
 *     wai=uai, wei=uei, wan=uan, wen=un, wang=uang, weng=ueng (all proven);</li>
 * <li>the apical vowels: the final written "i" after zh/ch/sh/r is i`
 *     (retroflex) and after z/c/s is i\ (alveolar) - proven by zhi/chi/zi/si;</li>
 * <li>j/q/x write u for the u-umlaut: ju=ts\ y, jue=ts\ yE_r, juan=ts\ y{_n,
 *     jun=ts\ y_n (proven);</li>
 * <li>"u:" (u with diaeresis written in a .vsqx lyric) folds to "v", so
 *     "lu:e" -> lve -> l yE_r (proven);</li>
 * <li>"nue"/"lue" written without the diaeresis are accepted as nue/lue =
 *     nue(umlaut)/lue(umlaut) [derived, orthographic convenience];</li>
 * <li>the syllabic interjections n, m, ng, hm, hng are accepted.</li>
 * </ul>
 *
 * <p>All methods are static and thread-safe after {@link #init(InputStream)}.
 */
public final class ChineseG2P {

  /** Two-letter initials, matched before the single-letter ones. */
  private static final String[] INITIALS_2 = { "zh", "ch", "sh" };
  /** Single-letter initials (no y/w: those spellings are zero-initial forms). */
  private static final String INITIALS_1 = "bpmfdtnlgkhjqxrzcs";

  /** PROVEN initial -> phoneme symbol (voicebank-notes.md 5.2). */
  private static final String[][] INITIALS_PROVEN = {
    {"b",  "p"},     {"p",  "p_h"},   {"m",  "m"},     {"f",  "f"},
    {"d",  "t"},     {"t",  "t_h"},   {"n",  "n"},     {"l",  "l"},
    {"g",  "k"},     {"k",  "k_h"},   {"h",  "x"},
    {"j",  "ts\\"},  {"q",  "ts\\_h"},{"x",  "s\\"},
    {"zh", "ts`"},   {"ch", "ts`_h"}, {"sh", "s`"},   {"r",  "z`"},
    {"z",  "ts"},    {"c",  "ts_h"},  {"s",  "s"}
  };

  /** rime key used for the two apical vowels, so "i" after z/c/s and zh/ch/sh/r differs. */
  private static final String APICAL_ALVEOLAR = "=i-alveolar";
  private static final String APICAL_RETROFLEX = "=i-retroflex";

  /**
   * PROVEN final -> phoneme symbol(s) (voicebank-notes.md 5.2). A rime is one
   * phoneme here because VOCALOID4 CHN writes diphthongs, triphthongs and
   * nasal rimes as a single symbol (uo, iE_n, iAU, uAN, ...).
   */
  private static final String[][] FINALS_PROVEN = {
    {"a",   "a"},    {"o",   "o"},    {"e",   "7"},    {"i",   "i"},    {"u",   "u"},
    {"v",   "y"},    {"er",  "@`"},   {"ai",  "aI"},   {"ei",  "ei"},
    {"ao",  "AU"},   {"ou",  "@U"},   {"an",  "a_n"},  {"en",  "@_n"},  {"ang", "AN"},
    {"ia",  "ia"},   {"ie",  "iE_r"}, {"iao", "iAU"},  {"iu",  "i@U"},
    {"ian", "iE_n"}, {"in",  "i_n"},  {"iang","iAN"},  {"ing", "iN"},  {"iong","iUN"},
    {"ua",  "ua"},   {"uo",  "uo"},   {"uai", "uaI"},  {"ui",  "uei"},
    {"uan", "ua_n"}, {"un",  "u@_n"}, {"uang","uAN"},  {"ueng","u@N"},
    {"ve",  "yE_r"}, {"van", "y{_n"}, {"vn",  "y_n"},
    {APICAL_ALVEOLAR, "i\\"}, {APICAL_RETROFLEX, "i`"}
  };

  /**
   * DERIVED rimes: not listed as such in the document, but forced by proven
   * entries that decompose over them.
   * <ul>
   * <li>eng = @N, because weng = u@N and ueng = u@N are proven, i.e. the eng
   *     rime after the u medial is @N;</li>
   * <li>ong = UN, because yong = iUN and iong = iUN are proven, i.e. the ong
   *     rime after the i medial is UN.</li>
   * </ul>
   * Every syllable built on these two rimes is reported by
   * {@link #isDerived(String)} and printed as "derived" by the self-test.
   */
  private static final String[][] FINALS_DERIVED = {
    {"eng", "@N"},
    {"ong", "UN"}
  };

  /**
   * Zero-initial spelling -> rime key. Proven pairs (ya=ia ... weng=ueng)
   * plus the two derived conveniences "yo" -> o (proven o) and the
   * interjections.
   */
  private static final String[][] ZERO_ORTHO = {
    {"a","a"}, {"ai","ai"}, {"an","an"}, {"ang","ang"}, {"ao","ao"},
    {"e","e"}, {"ei","ei"}, {"en","en"}, {"eng","eng"}, {"er","er"},
    {"o","o"}, {"ou","ou"},
    {"yi","i"},   {"ya","ia"},  {"ye","ie"},   {"yao","iao"}, {"you","iu"},
    {"yan","ian"},{"yin","in"}, {"yang","iang"},{"ying","ing"},{"yong","iong"},
    {"yo","o"},   {"yu","v"},   {"yue","ve"},  {"yuan","van"},{"yun","vn"},
    {"wu","u"},   {"wa","ua"},  {"wo","uo"},   {"wai","uai"}, {"wei","ui"},
    {"wan","uan"},{"wen","un"}, {"wang","uang"},{"weng","ueng"}
  };

  /** Syllables with no initial at all; the value is the full phoneme list. */
  private static final String[][] SPECIAL_SYLLABLES = {
    {"n",   "n"},   // syllabic alveolar nasal
    {"m",   "m"},   // syllabic bilabial nasal
    {"ng",  "@N"},  // interjection: velar nasal rime (derived, same as eng)
    {"hm",  "m"},   // interjection
    {"hng", "@N"}   // interjection
  };

  /** final -> phoneme targets */
  private static final HashMap<String,List<String>> FINALS = new HashMap<String,List<String>>();
  /** derived final keys (eng, ong) */
  private static final HashSet<String> DERIVED_FINALS = new HashSet<String>();
  /** zero-initial spelling -> rime key */
  private static final HashMap<String,String> ORTHO = new HashMap<String,String>();
  /** syllable -> full phoneme list (specials only) */
  private static final HashMap<String,List<String>> SPECIAL = new HashMap<String,List<String>>();
  /** initial -> phoneme symbol */
  private static final HashMap<String,String> INITIAL_PH = new HashMap<String,String>();
  /** derived syllables that are not covered by a final key */
  private static final HashSet<String> DERIVED_SYLLABLES = new HashSet<String>(
      Arrays.asList("ng", "hm", "hng", "yo"));
  /** memoized syllable -> phonemes */
  private static final HashMap<String,List<String>> CACHE = new HashMap<String,List<String>>();
  /** every toneless syllable accepted by {@link #isSyllable(String)} */
  private static final HashSet<String> SYLLABLES = new HashSet<String>();

  /**
   * The standard Mandarin syllable inventory (410-odd forms), which is also the
   * set of distinct kMandarin readings found in the Unihan database; plus the
   * interjections n/hm/hng and the colloquial zhei/lo.
   */
  private static final String INVENTORY =
      "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu "
    + "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou "
    + "chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo "
    + "da dai dan dang dao de den deng di dia dian diao die ding diu dong dou du duan dui dun duo "
    + "e ei en eng er fa fan fang fei fen feng fiao fo fou fu "
    + "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo "
    + "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo "
    + "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun "
    + "ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo "
    + "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou "
    + "lu luan lun luo lv lve ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu "
    + "na nai nan nang nao ne nei nen neng ng ni nian niang niao nie nin ning niu nong nou "
    + "nu nuan nun nuo nv nve o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu "
    + "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun "
    + "ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo "
    + "sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou "
    + "shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo "
    + "ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo "
    + "wa wai wan wang wei wen weng wo wu "
    + "xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun "
    + "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun "
    + "za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi "
    + "zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo "
    + "n hm hng";

  /** accented pinyin vowels, parallel to {@link #PLAIN} */
  private static final String ACCENTED =
      "\u0101\u00e1\u01ce\u00e0\u0113\u00e9\u011b\u00e8\u012b\u00ed\u01d0\u00ec"
    + "\u014d\u00f3\u01d2\u00f2\u016b\u00fa\u01d4\u00f9\u00fc\u01d6\u01d8\u01da\u01dc"
    + "\u00ea\u0144\u0148\u01f9";
  /** plain replacements (u-umlaut folds to "v") */
  private static final String PLAIN = "aaaaeeeeiiiioooouuuuvvvvvennn";

  private static volatile boolean ready = false;

  private ChineseG2P() { }

  static {
    for (int i = 0; i < INITIALS_PROVEN.length; i++) {
      INITIAL_PH.put(INITIALS_PROVEN[i][0], INITIALS_PROVEN[i][1]);
    }
    for (int i = 0; i < FINALS_PROVEN.length; i++) {
      fin(FINALS_PROVEN[i][0], FINALS_PROVEN[i][1]);
    }
    for (int i = 0; i < FINALS_DERIVED.length; i++) {
      fin(FINALS_DERIVED[i][0], FINALS_DERIVED[i][1]);
      DERIVED_FINALS.add(FINALS_DERIVED[i][0]);
    }
    for (int i = 0; i < ZERO_ORTHO.length; i++) {
      ORTHO.put(ZERO_ORTHO[i][0], ZERO_ORTHO[i][1]);
      SYLLABLES.add(ZERO_ORTHO[i][0]);
    }
    for (int i = 0; i < SPECIAL_SYLLABLES.length; i++) {
      String syl = SPECIAL_SYLLABLES[i][0];
      SPECIAL.put(syl, Collections.unmodifiableList(
          new ArrayList<String>(Arrays.asList(SPECIAL_SYLLABLES[i][1]))));
      SYLLABLES.add(syl);
    }
    for (String s : INVENTORY.split(" ")) {
      if (s.length() > 0) SYLLABLES.add(s);
    }
  }

  private static void fin(String name, String... phonemes) {
    FINALS.put(name, Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(phonemes))));
  }

  // -------------------------------------------------------------- conversion

  /**
   * "luo" -> ["l","uo"], "tian" -> ["t_h","iE_n"], "yi" -> ["i"] ...
   * returns canonical VOCALOID4 CHN phoneme symbols; empty list when not a
   * syllable.
   */
  public static List<String> syllableToPhonemes(String pinyin) {
    String syl = stripTone(pinyin);
    if (syl.length() == 0) return Collections.emptyList();
    List<String> hit = CACHE.get(syl);
    if (hit != null) return hit;
    if (!SYLLABLES.contains(syl)) return Collections.emptyList();
    List<String> ph = split(syl);
    if (ph == null) return Collections.emptyList();
    List<String> ro = Collections.unmodifiableList(ph);
    CACHE.put(syl, ro);
    return ro;
  }

  /** true when s (tone digits and spaces stripped) is a valid Mandarin pinyin syllable. */
  public static boolean isSyllable(String s) {
    String syl = stripTone(s);
    return syl.length() > 0 && SYLLABLES.contains(syl);
  }

  /** "luo" / "\u6d1b" / "luo4" / "LA" -> phoneme list; null when nothing usable. */
  public static List<String> lyricToPhonemes(String lyric) {
    if (lyric == null) return null;
    String s = lyric.trim();
    if (s.length() == 0) return null;
    String syl = stripTone(s);
    if (syl.length() > 0 && SYLLABLES.contains(syl)) {
      List<String> ph = syllableToPhonemes(syl);
      return ph.isEmpty() ? null : ph;
    }
    ArrayList<String> out = new ArrayList<String>();
    boolean any = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c) || (c >= '0' && c <= '9')) continue;
      if (!isHan(c)) continue;                       // punctuation / latin leftovers
      String py = hanziToPinyin(c);
      if (py == null) return null;                   // unknown hanzi: whole lyric unusable
      List<String> ph = syllableToPhonemes(py);
      if (ph.isEmpty()) return null;
      out.addAll(ph);
      any = true;
    }
    return any ? out : null;
  }

  /** Hanzi -> toneless pinyin ("\u6d1b" -> "luo"); null when unknown. */
  public static String hanziToPinyin(char c) {
    return PinyinDict.lookup(c);
  }

  /**
   * Tone number 1-5 stored in the file lyric (VOCALOID CHN stores e.g. "luo4")
   * is stripped and the syllable returned; accented pinyin is folded too, so
   * "lu\u00f2" -> "luo", "l\u01dc" -> "lv" and "nu:" -> "nv".
   */
  public static String stripTone(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= 'A' && c <= 'Z') { sb.append((char) (c + 32)); continue; }
      if (c >= 'a' && c <= 'z') {
        if (c == 'u' && i + 1 < s.length() && s.charAt(i + 1) == ':') { sb.append('v'); i++; continue; }
        sb.append(c);
        continue;
      }
      if (c >= '0' && c <= '9') continue;                 // tone digit
      int k = ACCENTED.indexOf(c);
      if (k >= 0) { sb.append(PLAIN.charAt(k)); continue; }
      if (c == '\u00fc') { sb.append('v'); continue; }      // u-umlaut without tone
      // everything else (spaces, apostrophes, hanzi) is dropped
    }
    return sb.toString();
  }

  // ------------------------------------------------------------------ loader

  /** Loads the gzipped hanzi dictionary; failures are logged, never thrown. */
  public static void init(InputStream pinyinGz) {
    try {
      PinyinDict.load(pinyinGz);
      ready = PinyinDict.size() > 0;
      if (!ready) System.err.println("ChineseG2P.init: pinyin dictionary empty, hanzi lookup off");
    } catch (RuntimeException e) {
      ready = false;
      System.err.println("ChineseG2P.init: " + e);
    }
  }

  /** true once a usable hanzi dictionary has been loaded. */
  public static boolean isReady() {
    return ready;
  }

  /** The complete syllable inventory (sorted), handy for exhaustive self-tests. */
  public static List<String> allSyllables() {
    ArrayList<String> all = new ArrayList<String>(SYLLABLES);
    Collections.sort(all);
    return all;
  }

  /** The 21 initials, longest first, as used by the splitter. */
  public static List<String> initials() {
    ArrayList<String> all = new ArrayList<String>();
    all.addAll(Arrays.asList(INITIALS_2));
    for (int i = 0; i < INITIALS_1.length(); i++) all.add(INITIALS_1.substring(i, i + 1));
    return all;
  }

  /**
   * true when the syllable is built with a rime that the document does not
   * list verbatim (eng = @N, ong = UN, see {@link #FINALS_DERIVED}); such
   * syllables are derived by composition from the proven entries.
   */
  public static boolean isDerived(String syllable) {
    String syl = stripTone(syllable);
    if (syl.length() == 0) return false;
    if (DERIVED_SYLLABLES.contains(syl)) return true;
    String key = finalKeyOf(syl);
    return key != null && DERIVED_FINALS.contains(key);
  }

  // ---------------------------------------------------------------- internal

  /** The rime key a syllable uses, or null when the syllable is malformed. */
  static String finalKeyOf(String syl) {
    String ortho = ORTHO.get(syl);
    if (ortho != null) return ortho;
    if (SPECIAL.containsKey(syl)) return null;
    String initial = initialOf(syl);
    if (initial == null) return null;
    String rest = syl.substring(initial.length());
    if (rest.length() == 0) return null;
    return rimeKey(initial, rest);
  }

  private static String initialOf(String syl) {
    for (int i = 0; i < INITIALS_2.length; i++) {
      if (syl.startsWith(INITIALS_2[i])) return INITIALS_2[i];
    }
    if (syl.length() > 1 && INITIALS_1.indexOf(syl.charAt(0)) >= 0) return syl.substring(0, 1);
    return null;
  }

  /**
   * Applies the orthographic rules that change the rime after certain
   * initials, then checks the rime against the proven/derived tables.
   */
  private static String rimeKey(String initial, String rest) {
    boolean palatal = initial.equals("j") || initial.equals("q") || initial.equals("x");
    boolean retroflex = initial.equals("zh") || initial.equals("ch") || initial.equals("sh") || initial.equals("r");
    boolean dental = initial.equals("z") || initial.equals("c") || initial.equals("s");
    if (rest.equals("i")) {
      if (retroflex) return APICAL_RETROFLEX;   // zhi chi shi ri: apical retroflex i
      if (dental) return APICAL_ALVEOLAR;       // zi ci si: apical alveolar i
      return "i";
    }
    if (palatal) {                              // j/q/x write u for the u-umlaut
      if (rest.equals("u")) return "v";
      if (rest.equals("ue")) return "ve";
      if (rest.equals("uan")) return "van";
      if (rest.equals("un")) return "vn";
    }
    if ((initial.equals("n") || initial.equals("l")) && rest.equals("ue")) return "ve"; // nue/lue
    return FINALS.containsKey(rest) ? rest : null;
  }

  private static List<String> split(String syl) {
    List<String> special = SPECIAL.get(syl);
    if (special != null) return new ArrayList<String>(special);

    String ortho = ORTHO.get(syl);
    if (ortho != null) return phonemesOfFinal(ortho);

    String initial = initialOf(syl);
    if (initial == null) return null;
    String rest = syl.substring(initial.length());
    if (rest.length() == 0) return null;
    String fin = rimeKey(initial, rest);
    if (fin == null) return null;

    ArrayList<String> out = new ArrayList<String>();
    out.add(INITIAL_PH.get(initial));
    out.addAll(phonemesOfFinal(fin));
    return out;
  }

  private static List<String> phonemesOfFinal(String key) {
    List<String> ph = FINALS.get(key);
    return ph == null ? null : new ArrayList<String>(ph);
  }

  private static boolean isHan(char c) {
    return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
        || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x3007 && c <= 0x3007);
  }

  /** Case-folds a pinyin unit without allocating when it is already plain. */
  static String lower(String s) {
    return s == null ? null : s.toLowerCase(Locale.US);
  }
}
