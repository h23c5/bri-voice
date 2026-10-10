package com.dsh.v4tune.phoneme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Japanese grapheme-to-phoneme conversion: kana or romaji -> VOCALOID-style
 * phoneme symbols.
 *
 * <h3>Provenance (read this before trusting a value)</h3>
 * The installed voicebanks are Chinese only, so the JPN phoneme inventory could
 * NOT be extracted from a .ddi (docs/voicebank-notes.md section 6 marks the
 * complete kana -> phoneme table as [unknown]). What IS proven from
 * g2pa4_JPN.dll is: the consonant+vowel sequences k a, k' i, k M, k e, k o,
 * s a, s M, tS i, ts M, t a, t e, t o; the standalone marks N, N', N\, M, 4, J,
 * Sil; and the symbol frequencies a i u e o M N C S dZ dz ts tS j w y r m n p b
 * t d k g h f v (with ' marking unaspirated/voiced variants).
 * <b>Everything else in this class is best-effort</b>, i.e. the standard
 * five-vowel analysis of Japanese (Keating &amp; Huffman 1984; Vance 2008,
 * <i>The Sounds of Japanese</i>) mapped onto the symbols of the shared
 * {@link PhonemeTable}. Each best-effort rule is marked [best-effort] below.
 *
 * <h3>Symbol choices that follow from the shared (CHN-first) table</h3>
 * <ul>
 * <li>shi / chi / ji are the alveolo-palatal fricative and affricates, which
 *     the CHN inventory spells "s\", "ts\" and the JPN attestation spells
 *     "tS"/"dZ": this class emits s\ i, ts\ i, dZ i (voicing kept);</li>
 * <li>za/zu/ze/zo and zu are voiced "dz" (attested in the JPN DLL);</li>
 * <li>the y-glide is written "i" (as in the CHN medials ia/ie/iao), because
 *     the shared table's "y" is the CHN close front rounded vowel /y/;</li>
 * <li>wa keeps "w" (a real labiovelar glide in the table);</li>
 * <li>the moraic nasal is "N" and the sokuon is the stop closure "cl";</li>
 * <li>the long vowel mark \u30fc repeats the preceding vowel target
 *     ("\u30ab\u30fc" -> k a a).</li>
 * </ul>
 */
public final class JapaneseG2P {

  /** single kana -> phonemes (hiragana; katakana is folded first) */
  private static final HashMap<Character,String[]> SINGLE = new HashMap<Character,String[]>();
  /** two-character digraphs (youon + foreign sounds) -> phonemes */
  private static final HashMap<String,String[]> DIGRAPH = new HashMap<String,String[]>();
  /** romaji (longest match) -> kana */
  private static final HashMap<String,String> ROMAJI = new HashMap<String,String>();
  private static final int MAX_ROMAJI = 3;

  private JapaneseG2P() { }

  static {
    // gojuuon [best-effort: standard analysis, symbols from the shared table]
    single('\u3042', "a"); single('\u3044', "i"); single('\u3046', "u"); single('\u3048', "e"); single('\u304a', "o");
    single('\u304b', "k", "a"); single('\u304d', "k", "i"); single('\u304f', "k", "u"); single('\u3051', "k", "e"); single('\u3053', "k", "o");
    single('\u3055', "s", "a"); single('\u3057', "s\\", "i"); single('\u3059', "s", "u"); single('\u305b', "s", "e"); single('\u305d', "s", "o");
    single('\u305f', "t", "a"); single('\u3061', "ts\\", "i"); single('\u3064', "ts", "u"); single('\u3066', "t", "e"); single('\u3068', "t", "o");
    single('\u306a', "n", "a"); single('\u306b', "n", "i"); single('\u306c', "n", "u"); single('\u306d', "n", "e"); single('\u306e', "n", "o");
    single('\u306f', "h", "a"); single('\u3072', "h", "i"); single('\u3075', "f", "u"); single('\u3078', "h", "e"); single('\u307b', "h", "o");
    single('\u307e', "m", "a"); single('\u307f', "m", "i"); single('\u3080', "m", "u"); single('\u3081', "m", "e"); single('\u3082', "m", "o");
    single('\u3084', "i", "a"); single('\u3086', "i", "u"); single('\u3088', "i", "o");
    single('\u3089', "r", "a"); single('\u308a', "r", "i"); single('\u308b', "r", "u"); single('\u308c', "r", "e"); single('\u308d', "r", "o");
    single('\u308f', "w", "a"); single('\u3092', "o");
    // voiced / semi-voiced
    single('\u304c', "g", "a"); single('\u304e', "g", "i"); single('\u3050', "g", "u"); single('\u3052', "g", "e"); single('\u3054', "g", "o");
    single('\u3056', "dz", "a"); single('\u3058', "dZ", "i"); single('\u305a', "dz", "u"); single('\u305c', "dz", "e"); single('\u305e', "dz", "o");
    single('\u3060', "d", "a"); single('\u3062', "dZ", "i"); single('\u3065', "dz", "u"); single('\u3067', "d", "e"); single('\u3069', "d", "o");
    single('\u3070', "b", "a"); single('\u3073', "b", "i"); single('\u3076', "b", "u"); single('\u3079', "b", "e"); single('\u307c', "b", "o");
    single('\u3071', "p", "a"); single('\u3074', "p", "i"); single('\u3077', "p", "u"); single('\u307a', "p", "e"); single('\u307d', "p", "o");
    single('\u3094', "b", "u");                                  // \u3094 (vu): Japanese [v] is [b]-like [best-effort]
    single('\u3090', "i"); single('\u3091', "e");                  // obsolete wi / we
    single('\u3093', "N");                                        // moraic nasal (proven mark N)
    single('\u3063', "cl");                                       // sokuon = stop closure
    // small vowels and small kana
    single('\u3041', "a"); single('\u3043', "i"); single('\u3045', "u"); single('\u3047', "e"); single('\u3049', "o");
    single('\u3083', "i", "a"); single('\u3085', "i", "u"); single('\u3087', "i", "o");
    single('\u308e', "w", "a");
    single('\u3095', "k", "a"); single('\u3096', "k", "e");

    // youon [best-effort]: palatalised consonants keep the i glide ...
    digraph("\u304d\u3083", "k", "i", "a"); digraph("\u304d\u3085", "k", "i", "u"); digraph("\u304d\u3087", "k", "i", "o");
    digraph("\u304e\u3083", "g", "i", "a"); digraph("\u304e\u3085", "g", "i", "u"); digraph("\u304e\u3087", "g", "i", "o");
    digraph("\u306b\u3083", "n", "i", "a"); digraph("\u306b\u3085", "n", "i", "u"); digraph("\u306b\u3087", "n", "i", "o");
    digraph("\u3072\u3083", "h", "i", "a"); digraph("\u3072\u3085", "h", "i", "u"); digraph("\u3072\u3087", "h", "i", "o");
    digraph("\u3073\u3083", "b", "i", "a"); digraph("\u3073\u3085", "b", "i", "u"); digraph("\u3073\u3087", "b", "i", "o");
    digraph("\u3074\u3083", "p", "i", "a"); digraph("\u3074\u3085", "p", "i", "u"); digraph("\u3074\u3087", "p", "i", "o");
    digraph("\u307f\u3083", "m", "i", "a"); digraph("\u307f\u3085", "m", "i", "u"); digraph("\u307f\u3087", "m", "i", "o");
    digraph("\u308a\u3083", "r", "i", "a"); digraph("\u308a\u3085", "r", "i", "u"); digraph("\u308a\u3087", "r", "i", "o");
    // ... while the sibilants are already palatal, so no glide is added
    digraph("\u3057\u3083", "s\\", "a"); digraph("\u3057\u3085", "s\\", "u"); digraph("\u3057\u3087", "s\\", "o"); digraph("\u3057\u3047", "s\\", "e");
    digraph("\u3058\u3083", "dZ", "a");  digraph("\u3058\u3085", "dZ", "u");  digraph("\u3058\u3087", "dZ", "o");  digraph("\u3058\u3047", "dZ", "e");
    digraph("\u3061\u3083", "ts\\", "a"); digraph("\u3061\u3085", "ts\\", "u"); digraph("\u3061\u3087", "ts\\", "o"); digraph("\u3061\u3047", "ts\\", "e");
    digraph("\u3062\u3083", "dZ", "a");  digraph("\u3062\u3085", "dZ", "u");  digraph("\u3062\u3087", "dZ", "o");
    // foreign-sound digraphs
    digraph("\u3066\u3043", "t", "i"); digraph("\u3067\u3043", "d", "i");
    digraph("\u3068\u3045", "t", "u"); digraph("\u3069\u3045", "d", "u");
    digraph("\u3075\u3041", "f", "a"); digraph("\u3075\u3043", "f", "i");
    digraph("\u3075\u3047", "f", "e"); digraph("\u3075\u3049", "f", "o");
    digraph("\u3046\u3043", "w", "i"); digraph("\u3046\u3047", "w", "e"); digraph("\u3046\u3049", "w", "o");
    digraph("\u3046\u3041", "w", "a");
    digraph("\u3064\u3041", "ts", "a"); digraph("\u3064\u3043", "ts", "i");
    digraph("\u3064\u3047", "ts", "e"); digraph("\u3064\u3049", "ts", "o");
    digraph("\u3094\u3041", "b", "a"); digraph("\u3094\u3043", "b", "i");
    digraph("\u3094\u3047", "b", "e"); digraph("\u3094\u3049", "b", "o");
    digraph("\u3044\u3047", "i", "e");

    // ------------------------------------------------------------- romaji
    // Hepburn first, with the common Kunrei/Nihon variants accepted as input.
    romaji("kya", "\u304d\u3083"); romaji("kyu", "\u304d\u3085"); romaji("kyo", "\u304d\u3087");
    romaji("gya", "\u304e\u3083"); romaji("gyu", "\u304e\u3085"); romaji("gyo", "\u304e\u3087");
    romaji("sha", "\u3057\u3083"); romaji("shu", "\u3057\u3085"); romaji("sho", "\u3057\u3087"); romaji("she", "\u3057\u3047");
    romaji("shi", "\u3057");       romaji("chi", "\u3061");       romaji("tsu", "\u3064");
    romaji("cha", "\u3061\u3083"); romaji("chu", "\u3061\u3085"); romaji("cho", "\u3061\u3087"); romaji("che", "\u3061\u3047");
    romaji("ja", "\u3058\u3083");  romaji("ju", "\u3058\u3085");  romaji("jo", "\u3058\u3087");  romaji("je", "\u3058\u3047");
    romaji("jya", "\u3058\u3083"); romaji("jyu", "\u3058\u3085"); romaji("jyo", "\u3058\u3087");
    romaji("cya", "\u3061\u3083"); romaji("cyu", "\u3061\u3085"); romaji("cyo", "\u3061\u3087");
    romaji("sya", "\u3057\u3083"); romaji("syu", "\u3057\u3085"); romaji("syo", "\u3057\u3087");
    romaji("tya", "\u3061\u3083"); romaji("tyu", "\u3061\u3085"); romaji("tyo", "\u3061\u3087");
    romaji("nya", "\u306b\u3083"); romaji("nyu", "\u306b\u3085"); romaji("nyo", "\u306b\u3087");
    romaji("hya", "\u3072\u3083"); romaji("hyu", "\u3072\u3085"); romaji("hyo", "\u3072\u3087");
    romaji("bya", "\u3073\u3083"); romaji("byu", "\u3073\u3085"); romaji("byo", "\u3073\u3087");
    romaji("pya", "\u3074\u3083"); romaji("pyu", "\u3074\u3085"); romaji("pyo", "\u3074\u3087");
    romaji("mya", "\u307f\u3083"); romaji("myu", "\u307f\u3085"); romaji("myo", "\u307f\u3087");
    romaji("rya", "\u308a\u3083"); romaji("ryu", "\u308a\u3085"); romaji("ryo", "\u308a\u3087");
    romaji("dzu", "\u3065");        romaji("dya", "\u3062\u3083"); romaji("dyu", "\u3062\u3085"); romaji("dyo", "\u3062\u3087");
    romaji("fa", "\u3075\u3041");  romaji("fi", "\u3075\u3043");  romaji("fe", "\u3075\u3047");  romaji("fo", "\u3075\u3049");
    romaji("va", "\u3094\u3041");  romaji("vi", "\u3094\u3043");  romaji("vu", "\u3094");        romaji("ve", "\u3094\u3047"); romaji("vo", "\u3094\u3049");
    romaji("wi", "\u3046\u3043");  romaji("we", "\u3046\u3047");  romaji("wo", "\u3092");
    romaji("ye", "\u3044\u3047");
    romaji("ka", "\u304b"); romaji("ki", "\u304d"); romaji("ku", "\u304f"); romaji("ke", "\u3051"); romaji("ko", "\u3053");
    romaji("ga", "\u304c"); romaji("gi", "\u304e"); romaji("gu", "\u3050"); romaji("ge", "\u3052"); romaji("go", "\u3054");
    romaji("sa", "\u3055"); romaji("su", "\u3059"); romaji("se", "\u305b"); romaji("so", "\u305d");
    romaji("za", "\u3056"); romaji("zu", "\u305a"); romaji("ze", "\u305c"); romaji("zo", "\u305e");
    romaji("ta", "\u305f"); romaji("te", "\u3066"); romaji("to", "\u3068");
    romaji("da", "\u3060"); romaji("di", "\u3062"); romaji("du", "\u3065"); romaji("de", "\u3067"); romaji("do", "\u3069");
    romaji("na", "\u306a"); romaji("ni", "\u306b"); romaji("nu", "\u306c"); romaji("ne", "\u306d"); romaji("no", "\u306e");
    romaji("ha", "\u306f"); romaji("hi", "\u3072"); romaji("fu", "\u3075"); romaji("he", "\u3078"); romaji("ho", "\u307b");
    romaji("ba", "\u3070"); romaji("bi", "\u3073"); romaji("bu", "\u3076"); romaji("be", "\u3079"); romaji("bo", "\u307c");
    romaji("pa", "\u3071"); romaji("pi", "\u3074"); romaji("pu", "\u3077"); romaji("pe", "\u307a"); romaji("po", "\u307d");
    romaji("ma", "\u307e"); romaji("mi", "\u307f"); romaji("mu", "\u3080"); romaji("me", "\u3081"); romaji("mo", "\u3082");
    romaji("ya", "\u3084"); romaji("yu", "\u3086"); romaji("yo", "\u3088");
    romaji("ra", "\u3089"); romaji("ri", "\u308a"); romaji("ru", "\u308b"); romaji("re", "\u308c"); romaji("ro", "\u308d");
    romaji("wa", "\u308f");
    romaji("a", "\u3042"); romaji("i", "\u3044"); romaji("u", "\u3046"); romaji("e", "\u3048"); romaji("o", "\u304a");
    romaji("n", "\u3093");
    ROMAJI.put("nn", "\u3093");
    ROMAJI.put("n'", "\u3093");
  }

  private static void single(char kana, String... phonemes) {
    SINGLE.put(Character.valueOf(kana), phonemes);
  }

  private static void digraph(String kana, String... phonemes) {
    DIGRAPH.put(kana, phonemes);
  }

  private static void romaji(String r, String kana) {
    ROMAJI.put(r, kana);
  }

  // -------------------------------------------------------------- public API

  /** "ka"->["k","a"], "shi"->["s\","i"], "n"->["N"], "Q"/"\u3063"->["cl"]. */
  public static List<String> moraToPhonemes(String mora) {
    if (mora == null) return Collections.emptyList();
    String m = mora.trim();
    if (m.length() == 0) return Collections.emptyList();
    if (m.equals("Q") || m.equals("q") || m.equals("\u3063")) {
      return Collections.singletonList("cl");
    }
    if (m.equals("N")) return Collections.singletonList("N");
    String kana = containsKana(m) ? m : romajiToKana(m);
    if (kana == null || kana.length() == 0) return Collections.emptyList();
    List<String> ph = kanaToPhonemes(kana);
    return ph == null ? Collections.<String>emptyList() : ph;
  }

  /** "kyou" -> "\u304d\u3087\u3046"-style kana, best effort, for display/round-trip. */
  public static String romajiToKana(String romaji) {
    if (romaji == null) return null;
    String s = romaji.trim().toLowerCase(Locale.US);
    if (s.length() == 0) return "";
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == ' ') { i++; continue; }
      if (c == '-') { out.append('\u30fc'); i++; continue; }        // long vowel mark
      // geminate consonant: kk, tt, pp, ss ... -> sokuon + the rest
      if (i + 1 < s.length() && c != 'n' && c == s.charAt(i + 1) && "kstpcdgbz".indexOf(c) >= 0) {
        out.append('\u3063');
        i++;
        continue;
      }
      if (c == 't' && i + 2 < s.length() && s.charAt(i + 1) == 'c' && s.charAt(i + 2) == 'h') {
        out.append('\u3063');                                           // tch -> \u3063\u3061
        i++;
        continue;
      }
      boolean matched = false;
      for (int len = MAX_ROMAJI; len >= 1; len--) {
        if (i + len > s.length()) continue;
        String piece = s.substring(i, i + len);
        String kana = ROMAJI.get(piece);
        if (kana == null) continue;
        // "n" is syllabic only before a non-vowel, non-y sound or at the end
        if (piece.equals("n") && i + 1 < s.length()) {
          char nxt = s.charAt(i + 1);
          if ("aiueoy".indexOf(nxt) >= 0) continue;                      // na/ni/... handled by 2-letter entries
        }
        out.append(kana);
        i += len;
        matched = true;
        break;
      }
      if (!matched) {
        if ("aiueo".indexOf(c) >= 0) { out.append(c == 'a' ? '\u3042' : c == 'i' ? '\u3044' : c == 'u' ? '\u3046' : c == 'e' ? '\u3048' : '\u304a'); }
        i++;                                                            // skip anything unconvertible
      }
    }
    return out.toString();
  }

  /** kana OR romaji OR a single kana -> phonemes; null when nothing usable. */
  public static List<String> lyricToPhonemes(String lyric) {
    if (lyric == null) return null;
    String s = lyric.trim();
    if (s.length() == 0) return null;
    List<String> ph = containsKana(s) ? kanaToPhonemes(s) : moraToPhonemes(s);
    return (ph == null || ph.isEmpty()) ? null : ph;
  }

  /** true for hiragana, katakana and the long-vowel/iteration marks. */
  public static boolean isKana(char c) {
    return (c >= 0x3041 && c <= 0x3096) || (c >= 0x309D && c <= 0x309E)
        || (c >= 0x30A1 && c <= 0x30FA) || (c >= 0x30FC && c <= 0x30FE);
  }

  // ---------------------------------------------------------------- internal

  /** Converts every katakana character to its hiragana counterpart. */
  public static String toHiragana(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= 0x30A1 && c <= 0x30F6) sb.append((char) (c - 0x60));
      else sb.append(c);
    }
    return sb.toString();
  }

  private static boolean containsKana(String s) {
    for (int i = 0; i < s.length(); i++) if (isKana(s.charAt(i))) return true;
    return false;
  }

  /** Kana string -> phonemes; unknown glyphs (punctuation, latin) are skipped. */
  public static List<String> kanaToPhonemes(String kana) {
    String s = toHiragana(kana);
    ArrayList<String> out = new ArrayList<String>();
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\u30fc') {                          // long vowel mark: hold the last vowel
        String v = lastVowel(out);
        if (v != null) out.add(v);
        i++;
        continue;
      }
      if (i + 1 < s.length()) {
        String[] two = DIGRAPH.get(s.substring(i, i + 2));
        if (two != null) {
          out.addAll(Arrays.asList(two));
          i += 2;
          continue;
        }
      }
      String[] one = SINGLE.get(Character.valueOf(c));
      if (one != null) {
        out.addAll(Arrays.asList(one));
        i++;
        continue;
      }
      i++;                                            // punctuation or unsupported glyph
    }
    return out;
  }

  private static String lastVowel(List<String> out) {
    for (int i = out.size() - 1; i >= 0; i--) {
      if (PhonemeTable.isVowel(out.get(i))) return out.get(i);
    }
    return null;
  }
}
