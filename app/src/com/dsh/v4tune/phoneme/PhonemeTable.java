package com.dsh.v4tune.phoneme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The V4Tune phoneme inventory: one shared table for Mandarin (Chinese) and
 * Japanese so the synthesizer only ever sees one symbol space.
 *
 * <h3>Symbol space</h3>
 * The <b>canonical symbols are the 62 proven VOCALOID4 Chinese (CHN)
 * phonemes</b>, copied byte-exactly from the PHDC phoneme records of the
 * installed Luotianyi_CHN_Meng.ddi and MIKU_V4_Chinese.ddi
 * (docs/voicebank-notes.md sections 3.1/3.2). Those are the strings a .vsqx
 * written for a real VOCALOID4 CHN voicebank contains, so {@link ChineseG2P}
 * emits nothing else. The notation is X-SAMPA derived: "7" = close-mid back
 * unrounded, "@" = schwa, "{" = near-open front, "i\" = close central
 * unrounded (apical alveolar), "i`" = apical retroflex, "s\" = alveolo-palatal
 * fricative, "ts\" = alveolo-palatal affricate, "s`" = retroflex fricative,
 * "ts`" = retroflex affricate, "z`" = retroflex approximant, "_n" = nasalised,
 * "_h" = aspirated.
 *
 * <p>Two further groups live in the same table so the Japanese path and
 * existing projects keep working:
 * <ul>
 * <li><b>aliases</b> - legacy plain spellings ("sh", "zh", "ch", "z", "c",
 *     "j", "q", "v", "er", "ii", "iii", "ng", "sil", "sp") registered through
 *     {@code alt[]} and rewritten by {@link #normalize(String)};</li>
 * <li><b>extra symbols</b> - "cl" (stop closure), "e" (Japanese /e/), "b",
 *     "d", "g", "dz", "dZ" (voiced Japanese obstruents), "h" (glottal
 *     aspiration), "r" (Japanese tap), "w" (labiovelar glide) and "N"
 *     (Japanese moraic nasal). None of them is part of the CHN inventory and
 *     {@link ChineseG2P} never emits them.</li>
 * </ul>
 * Where a legacy spelling collides with a canonical CHN symbol the canonical
 * meaning wins: "x" is the Mandarin velar fricative of pinyin h and "y" is the
 * close front rounded vowel /y/ (pinyin u-umlaut), not the old alveolo-palatal
 * fricative and palatal glide. Where a legacy spelling has no canonical
 * counterpart ("b","d","g","e","h","r","w","cl","N") it is kept as its own
 * symbol, because the Japanese path needs the distinction (voiced vs
 * voiceless obstruents) and dropping it would break Japanese lyrics.
 *
 * <h3>Where the numbers come from</h3>
 * Formant frequencies are measured steady-state values (vowels, glides,
 * liquids, nasal murmurs) and consonantal <i>loci</i> for stops/affricates,
 * taken from the standard literature:
 * <ul>
 * <li>Peterson &amp; Barney (1952), JASA 24:175-184 - reference F1/F2/F3 for
 *     the cardinal vowels of a large speaker sample.</li>
 * <li>Fant (1960), <i>Acoustic Theory of Speech Production</i> - formant
 *     bandwidths (B1 ~ 50-90 Hz, B2 ~ 90-120 Hz, B3 ~ 120-180 Hz).</li>
 * <li>Howie (1976), <i>Acoustical Studies of Mandarin Vowels and Tones</i>,
 *     and Wu Zongji / Lin Maocan (1989), <i>Experimental Phonetics Summary</i>
 *     - Mandarin vowel formants.</li>
 * <li>Keating &amp; Huffman (1984), Phonetica 41:191-207; Nishi et al. (2008),
 *     JASA 124:3590-3600; Vance (2008), <i>The Sounds of Japanese</i> -
 *     Japanese vowel formants (including the compressed /u/).</li>
 * <li>Stevens (1998), <i>Acoustic Phonetics</i>, ch. 6-8 - fricative noise
 *     bands, stop loci, nasal anti-resonances.</li>
 * <li>Kewley-Port (1982), JASA 72:379-389 - stop consonant F2/F3 loci.</li>
 * <li>Kent &amp; Read (2002), <i>The Acoustic Analysis of Speech</i>, tables
 *     6-1/6-2 - consonant formant loci and fricative spectral peaks.</li>
 * </ul>
 * F4/F5 are held at neutral values (3200-3600 / 4200-4500 Hz) because they are
 * not distinctive in Mandarin and are rarely reported; only F1-F3 drive the
 * Mandarin contrasts. Diphthongs are single VOCALOID symbols, so their stored
 * target is a weighted centroid of the elements (0.7 onset + 0.3 offset for a
 * two-element rime, 0.5 + 0.3 + 0.2 for a three-element rime): the segment
 * then starts on the first element and the Renderer's cross-phoneme blending
 * carries the audible glide. Every entry below states its computation.
 */
public final class PhonemeTable {

  private static final LinkedHashMap<String, PhonemeDef> TABLE = new LinkedHashMap<String, PhonemeDef>();
  private static final HashMap<String, String> ALT = new HashMap<String, String>();
  /**
   * Case-folded canonical index. Symbols that differ only in case (n vs N,
   * dz vs dZ) keep their exact spelling: the case-folding never merges them,
   * it only lets "AI" or "IE_R" reach "aI" / "iE_r".
   */
  private static final HashMap<String, String> LOWER = new HashMap<String, String>();
  /** every symbol of the table, canonical CHN first, then the extras */
  private static final List<String> SYMBOLS;
  /** exactly the 62 proven VOCALOID4 Chinese phonemes, in PHDC order */
  private static final List<String> CHN;
  /** the extra (Japanese / legacy) symbols, in table order */
  private static final List<String> EXTRA;

  /** The silence definition (canonical symbol "Sil", type T_SIL). */
  public static final PhonemeDef SIL;

  /** Fallback for unknown symbols: a neutral schwa-like "a" variant. */
  private static final PhonemeDef NEUTRAL;

  /**
   * The 62 proven CHN symbols in PHDC order (voicebank-notes.md 3.1):
   * indices 1-8 monophthongs and diphthongs, 9-12 nasal rimes, 13-24 i-series,
   * 25-34 u-series, 35-38 u-umlaut series, 39-42 syllabic nasals / retroflex
   * approximant / lateral, 43-48 stops, 49-53 fricatives, 54-59 affricates,
   * 60-62 glottal stop, silence, aspiration.
   *
   * <p>In Java source a single backslash is written with a double backslash, so
   * "i\\" below is the two-character symbol i + backslash and "ts\\_h" is
   * t s backslash underscore h.
   */
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

  private PhonemeTable() { }

  static {
    // =====================================================================
    // 1. THE 62 PROVEN VOCALOID4 CHN PHONEMES (PHDC order, byte-exact).
    // =====================================================================

    // ---------------------------------------------------------- monophthongs
    // Mandarin monophthong formants (Hz), Howie (1976) / Wu & Lin (1989).
    vowel("a",  850, 1200, 2500, 3500, 4500);  // /a/: literature F1 850 F2 1200 F3 2500
    vowel("o",  500,  800, 2500, 3400, 4400);  // /o/: rounded back, F1 500 F2 800 F3 2500
    vowel("7",  400, 1100, 2400, 3300, 4300);  // close-mid back unrounded (ge/ke/he): F1 400 F2 1100 F3 2400
    vowel("i",  300, 2300, 3000, 3600, 4500);  // /i/: F1 300 F2 2300 F3 3000
    vowel("u",  350,  800, 2400, 3300, 4300);  // /u/: F1 350 F2 800 F3 2400
    vowel("y",  300, 1900, 2300, 3200, 4200);  // /y/ (pinyin u-umlaut): F1 300 F2 1900 F3 2300
    vowel("e",  500, 1900, 2600, 3500, 4500);  // EXTRA (not CHN): Japanese /e/ (Keating & Huffman 1984)

    // The two apical vowels: the "buzzed" vowels of zi/ci/si and zhi/chi/shi/ri.
    // Both are high central vowels with a very close constriction; the
    // retroflex one has the lower F3 because the tongue tip is curled back
    // (Howie 1976; Wu & Lin 1989). Kept apart because VOCALOID does.
    vowel("i\\", 350, 1600, 2500, 3400, 4400); // apical alveolar: F1 350 F2 1600 F3 2500
    vowel("i`", 400, 1500, 1800, 3200, 4200);  // apical retroflex: F1 400 F2 1500 F3 1800 (lowered by retroflexion)

    // Rhotacised vowels: the defining cue is the lowered F3 (Kent & Read 2002,
    // ch. 6). 1600-1800 Hz is the reported band for Mandarin er.
    vowel("@`", 500, 1400, 1700, 3300, 4300);  // er: rhotacised central vowel F3 1700
    vowel("iE_r", 400, 1800, 1700, 3300, 4300); // ye rime [je] + rhotic: F2 from E, F3 pulled to 1700
    vowel("yE_r", 350, 1750, 1650, 3200, 4200); // yue rime [ye] + rhotic: F1 350 F2 1750 F3 1650

    // ------------------------------------------------------------ diphthongs
    // Stored target = 0.7 * first element + 0.3 * second element, using the
    // monophthong targets above (a 850/1200/2500, o 500/800/2500,
    // i 300/2300/3000, u 350/800/2400, schwa 500/1400/2400, e 450/2000/2600).
    // Example aI: F1 = .7*850 + .3*300 = 685, F2 = .7*1200 + .3*2300 = 1530,
    // F3 = .7*2500 + .3*3000 = 2650.
    vowel("aI",   690, 1530, 2650, 3500, 4500); // ai  = a -> i
    vowel("ei",   410, 2090, 2720, 3600, 4500); // ei  = e -> i
    vowel("AU",   700, 1080, 2470, 3300, 4300); // ao  = a -> u
    vowel("@U",   460, 1220, 2400, 3300, 4300); // ou  = schwa -> u
    vowel("ia",   470, 1970, 2850, 3600, 4500); // ia  = i -> a
    vowel("ua",   500,  920, 2430, 3300, 4300); // ua  = u -> a
    vowel("uo",   400,  800, 2430, 3300, 4300); // uo  = u -> o (F2 stays: both back rounded)
    vowel("uaI",  490, 1220, 2550, 3400, 4400); // uai = u -> a -> i, centroid .5/.3/.2
    vowel("uei",  370, 1460, 2580, 3500, 4400); // ui  = u -> e -> i, centroid .5/.3/.2
    // Triphthongs: 0.5 * first + 0.3 * middle + 0.2 * last (i a u / i schwa u).
    vowel("iAU",  475, 1670, 2730, 3450, 4400); // iao = i -> a -> u
    vowel("i@U",  370, 1730, 2700, 3400, 4400); // iu  = i -> schwa -> u

    // -------------------------------------------------- nasalised vowels (_n)
    // The "_n" rimes are realised with the nasal anti-resonance: the velum is
    // open, so a zero near 800-1200 Hz notches the spectrum (Stevens 1998,
    // ch. 6). Formants stay those of the oral vowel, slightly damped.
    nasalVowel("a_n",  850, 1200, 2500, 3500, 4500, 0.50); // an  = nasalised a
    nasalVowel("@_n",  500, 1400, 2400, 3300, 4300, 0.55); // en  = nasalised schwa
    nasalVowel("iE_n", 380, 2180, 2880, 3500, 4500, 0.45); // ian = [jE] nasalised (.7*i + .3*E, E 550/1900/2600)
    nasalVowel("i_n",  300, 2300, 3000, 3600, 4500, 0.45); // in  = nasalised i
    nasalVowel("ua_n", 500,  920, 2430, 3300, 4300, 0.45); // uan = ua target nasalised
    nasalVowel("u@_n", 400,  980, 2400, 3300, 4300, 0.50); // uen = u -> schwa nasalised (.7*u + .3*schwa)
    nasalVowel("y{_n", 390, 1840, 2360, 3300, 4300, 0.45); // uan(umlaut) = [y{] nasalised
    nasalVowel("y_n",  300, 1900, 2300, 3200, 4200, 0.45); // un(umlaut) = nasalised y

    // ------------------------------------------- vowel + velar nasal (AN ...)
    // These rimes are a vowel plus ng inside one symbol: the velar
    // constriction lowers F2/F3 towards the velar locus (F2 ~ 1000-1200,
    // F3 ~ 2300, Kewley-Port 1982) and the open velum adds the nasal zero.
    velarVowel("AN",  800, 1100, 2400, 3300, 4300, 0.50); // ang  = a + ng, F2 pulled 1200 -> 1100
    velarVowel("@N",  400, 1000, 2300, 3200, 4200, 0.55); // eng  = 7 + ng
    velarVowel("iN",  300, 2100, 2800, 3400, 4400, 0.45); // ing  = i + ng, F2 pulled 2300 -> 2100
    velarVowel("uAN", 485,  890, 2400, 3200, 4200, 0.50); // uang = u + ang (.7*u + .3*AN)
    velarVowel("UN",  400,  800, 2400, 3200, 4200, 0.55); // ong  = [u] + ng
    velarVowel("u@N", 370,  860, 2370, 3200, 4200, 0.55); // ueng = u + eng (.7*u + .3*@N)
    velarVowel("iAN", 450, 1940, 2820, 3400, 4400, 0.45); // iang = i + ang (.7*i + .3*AN)
    velarVowel("iUN", 330, 1850, 2820, 3400, 4400, 0.50); // iong = i + ong (.7*i + .3*UN)

    // ------------------------------------------------------ syllabic nasals
    // Nasal murmur: F1 ~ 250-300 plus an anti-resonance near 800-1200 Hz that
    // notches the spectrum (Kent & Read 2002, ch. 6); hence nasalZero + tilt.
    nasal("n", 250, 1700, 2600, 0.90);  // alveolar murmur (syllabic n, every -n coda)
    nasal("m", 250, 1100, 2200, 0.90);  // bilabial murmur (syllabic m)

    // ------------------------------------------------- retroflex approximant
    // Mandarin r (zhi/chi/shi/ri family): a voiced retroflex approximant with
    // a little turbulence; the low F3 (~1700) is the retroflex cue. PHDC's
    // PHG2 list classifies it under VoicedFricatives, so it is a T_FRIC here.
    fricative("z`", 400, 1400, 1700, 3300, 2500, 2000, 0.10, 1.00); // voiced, F3 lowered to 1700

    // --------------------------------------------------------------- liquid
    // Lateral approximant: low F2 (tongue body retracted, tip up) and a high
    // F3, F1 ~ 350 (Kent & Read 2002, table 6-2).
    liquid("l", 350, 1000, 2600, 3400, 3000, 1800, 0.02, 1.00); // F1 350 F2 1000 F3 2600

    // ---------------------------------------------------------------- stops
    // Voiceless stops are stored as place loci (the transition target the
    // formants move to) plus a burst: bilabial F2 ~ 800, alveolar F2 ~ 1700,
    // velar pinch F2 ~ F3 ~ 1900-2200 (Kewley-Port 1982; Stevens 1998 ch. 8).
    // Unaspirated (pinyin b/d/g) have a short-lag VOT: a weak burst only.
    // Aspirated (_h, pinyin p/t/k) add 30-60 ms of broadband aspiration noise.
    stop("p",   250,  800, 2200, 3300, 4300, false, 1000, 3000, 0.10, 0.00); // pinyin b
    stop("p_h", 250,  800, 2200, 3300, 4300, true,  1500, 3500, 0.35, 0.00); // pinyin p
    stop("t",   250, 1700, 2600, 3400, 4400, false, 3500, 3000, 0.10, 0.00); // pinyin d
    stop("t_h", 250, 1700, 2600, 3400, 4400, true,  2000, 3500, 0.35, 0.00); // pinyin t
    stop("k",   250, 1900, 2200, 3200, 4200, false, 2000, 2500, 0.12, 0.00); // pinyin g, velar pinch
    stop("k_h", 250, 1900, 2200, 3200, 4200, true,  1800, 3500, 0.35, 0.00); // pinyin k

    // ------------------------------------------------------------ fricatives
    // Noise centre/bandwidth from Stevens (1998) ch. 6 and Kent & Read (2002):
    // alveolar s peaks at 5-8 kHz, retroflex s` at 2.5-3.5 kHz, alveolo-palatal
    // s\ at 4-5.5 kHz, labiodental f is broadband and weak, velar x at 1-2.5 kHz.
    fricative("s\\", 250, 2200, 3000, 3600, 4800, 2500, 0.75, 0.00); // pinyin x, alveolo-palatal
    fricative("f",   250, 1200, 2200, 3200, 4000, 4000, 0.60, 0.00); // labiodental, broadband
    fricative("x",   250, 1200, 2200, 3200, 1800, 2500, 0.65, 0.00); // pinyin h, velar
    fricative("s",   250, 1700, 2600, 3400, 6500, 2000, 0.80, 0.00); // alveolar groove, peak ~6.5 kHz
    fricative("s`", 250, 1500, 2000, 3200, 3000, 2200, 0.75, 0.00);  // pinyin sh, retroflex

    // ------------------------------------------------------------ affricates
    // Stop locus of the homorganic place plus the fricative release band above.
    affricate("ts\\_h", 250, 2200, 3000, 3600, 4500, 4800, 2500, 0.70, 0.00, true);  // pinyin q
    affricate("ts\\",   250, 2200, 3000, 3600, 4500, 4800, 2500, 0.55, 0.00, false); // pinyin j
    affricate("ts`_h", 250, 1500, 2000, 3200, 4200, 3000, 2000, 0.70, 0.00, true);   // pinyin ch
    affricate("ts_h",   250, 1700, 2600, 3400, 4400, 6000, 2500, 0.70, 0.00, true);  // pinyin c
    affricate("ts`",   250, 1500, 2000, 3200, 4200, 3000, 2000, 0.55, 0.00, false);  // pinyin zh
    affricate("ts",     250, 1700, 2600, 3400, 4400, 6000, 2000, 0.55, 0.00, false); // pinyin z

    // ------------------------------------------------- other CHN phonemes
    // "?" is the glottal plosive VOCALOID uses for a hard onset: a short
    // closure with a weak burst and no oral constriction, so the formants are
    // the neutral schwa values.
    stop("?", 500, 1500, 2500, 3500, 4500, false, 1200, 3000, 0.05, 0.00);
    TABLE.get("?").durMin = 0.01; TABLE.get("?").durDef = 0.03; TABLE.get("?").gain = 0.80;

    // "Sil" is true silence (PHG2 group Silence, member 1) and "Asp" is the
    // breath/aspiration phoneme of the same group: no voicing, all noise.
    PhonemeDef sil = new PhonemeDef("Sil", PhonemeDef.T_SIL);
    sil.f1 = sil.f2 = sil.f3 = sil.f4 = sil.f5 = 0;
    sil.b1 = sil.b2 = sil.b3 = sil.b4 = sil.b5 = 0;
    sil.voiced = 0; sil.noise = 0; sil.gain = 0;
    sil.durMin = 0.05; sil.durDef = 0.30;
    add(sil);
    SIL = sil;

    PhonemeDef asp = new PhonemeDef("Asp", PhonemeDef.T_ASPIR);
    asp.f1 = 500; asp.f2 = 1500; asp.f3 = 2500; asp.f4 = 3500; asp.f5 = 4500;
    asp.voiced = 0; asp.noise = 0.90; asp.noiseFc = 2000; asp.noiseBw = 4000;
    asp.durMin = 0.04; asp.durDef = 0.10; asp.gain = 0.90;
    add(asp);

    // =====================================================================
    // 2. EXTRA SYMBOLS (Japanese path + legacy fallbacks; never emitted by
    //    ChineseG2P). Kept so that nothing that used to work breaks.
    // =====================================================================

    // "cl" is the silent stop closure VOCALOID uses to separate a stop from
    // the preceding phoneme (Japanese sokuon): no excitation at all.
    PhonemeDef cl = new PhonemeDef("cl", PhonemeDef.T_STOP);
    cl.f1 = 250; cl.f2 = 1200; cl.f3 = 2200; cl.f4 = 3200; cl.f5 = 4300;
    cl.voiced = 0; cl.noise = 0; cl.gain = 0;
    cl.durMin = 0.02; cl.durDef = 0.05;
    cl.alt = new String[] { "clos", "closure" };
    add(cl);

    // Voiced stops for Japanese (ga/da/ba). Mandarin has no voiced stops, so
    // these are outside the CHN 62; the values are the old V4Tune compromise:
    // the same place loci as p/t/k with partial voicing (0.55) during closure.
    stop("b", 250,  800, 2200, 3300, 4300, false, 1000, 3000, 0.05, 0.55);
    stop("d", 250, 1700, 2600, 3400, 4400, false, 1500, 3000, 0.05, 0.55);
    stop("g", 250, 1900, 2200, 3200, 4200, false, 1200, 3000, 0.05, 0.55);

    // Voiced affricates for Japanese za/zu/ze/zo and ji/ja/ju/jo. The JPN
    // symbol table (voicebank-notes.md 6) attests both "dz" and "dZ"; using
    // them keeps Japanese voicing while "z" and "j" stay free as CHN aliases.
    affricate("dz", 250, 1700, 2600, 3400, 4400, 6000, 2000, 0.30, 1.00, false); // JPN za
    affricate("dZ", 250, 2200, 3000, 3600, 4500, 4800, 2500, 0.30, 1.00, false); // JPN ji/ja

    // Japanese tap r: a short voiced alveolar liquid. Kept as "r" because the
    // CHN retroflex approximant already owns "z`" and Japanese needs the tap
    // colour (same low F3, much shorter).
    liquid("r", 350, 1400, 1700, 3300, 2500, 1500, 0.08, 1.00);

    // Labiovelar glide w (Japanese wa). Mandarin writes the same sound as the
    // medial "u" (ua/uo/wu), so "w" is only reachable from Japanese or from an
    // old project; the values are the old table's.
    glide("w", 320, 750, 2300, 3300);

    // Japanese glottal aspiration h (ha/hi/he/ho). The Mandarin velar
    // fricative is "x" (pinyin h) and keeps that spelling; "h" stays a glottal
    // aspirative so Japanese ha does not turn into a velar fricative.
    aspirative("h", 250, 1200, 2200, 3200, 1800, 3500, 0.70, 0.15);

    // Japanese moraic nasal: place assimilates, uvular/velar default, held
    // about twice as long as a normal nasal murmur.
    nasal("N", 250, 1100, 2300, 0.90);
    TABLE.get("N").durMin = 0.06; TABLE.get("N").durDef = 0.12;

    // =====================================================================
    // 3. ALIASES: legacy plain spellings -> canonical CHN symbols. Every one
    //    of them still resolves through get()/getOrDefault(); normalize()
    //    rewrites them to the canonical spelling so that old projects,
    //    hand-written phoneme strings and the new X-SAMPA names agree.
    // =====================================================================
    alias("s`",     "sh");     // pinyin sh = retroflex fricative
    alias("ts`",    "zh");     // pinyin zh = retroflex affricate
    alias("ts`_h",  "ch");     // pinyin ch = aspirated retroflex affricate
    alias("ts",     "z");      // pinyin z  = unaspirated alveolar affricate
    alias("ts_h",   "c");      // pinyin c  = aspirated alveolar affricate
    alias("ts\\",   "j");      // pinyin j  = alveolo-palatal affricate
    alias("ts\\_h", "q");      // pinyin q  = aspirated alveolo-palatal affricate
    alias("y",      "v");      // pinyin u-umlaut /y/
    alias("y",      "uu");
    alias("y",      "\u00fc"); // u with diaeresis, as typed in a lyric field
    alias("@`",     "er");     // pinyin er = rhotacised central vowel
    alias("i\\",    "ii");     // old table's name for the apical alveolar vowel
    alias("i`",     "iii");    // old table's name for the apical retroflex vowel
    alias("N",      "ng");     // velar nasal
    alias("N",      "n'");
    alias("Sil",    "sil");
    alias("Sil",    "sp");
    alias("Sil",    "pau");
    alias("Sil",    "rest");
    alias("Asp",    "br");
    alias("a",      "schwa");

    NEUTRAL = new PhonemeDef("a", PhonemeDef.T_VOWEL);
    NEUTRAL.f1 = 500; NEUTRAL.f2 = 1500; NEUTRAL.f3 = 2500;
    NEUTRAL.durMin = 0.05; NEUTRAL.durDef = 0.12;

    // finally expose every per-entry alt spelling through the same map
    for (PhonemeDef d : TABLE.values()) {
      for (int i = 0; i < d.alt.length; i++) registerAlt(d.sym, d.alt[i]);
    }
    // case-folded index; an all-lowercase canonical spelling always wins, so
    // "n" stays the alveolar nasal and "dz" the voiced alveolar affricate
    for (PhonemeDef d : TABLE.values()) {
      String low = d.sym.toLowerCase(Locale.US);
      String have = LOWER.get(low);
      if (have == null || d.sym.equals(low)) LOWER.put(low, d.sym);
    }

    ArrayList<String> all = new ArrayList<String>(TABLE.keySet());
    SYMBOLS = Collections.unmodifiableList(all);
    ArrayList<String> chn = new ArrayList<String>(CHN62.length);
    for (int i = 0; i < CHN62.length; i++) {
      PhonemeDef d = TABLE.get(CHN62[i]);
      if (d == null) throw new IllegalStateException("missing CHN phoneme: " + CHN62[i]);
      chn.add(CHN62[i]);
    }
    CHN = Collections.unmodifiableList(chn);
    ArrayList<String> extra = new ArrayList<String>(all);
    extra.removeAll(chn);
    EXTRA = Collections.unmodifiableList(extra);
  }

  // ------------------------------------------------------------- table helpers

  private static PhonemeDef add(PhonemeDef d) {
    TABLE.put(d.sym, d);
    return d;
  }

  /** Registers "spelling" as an alternative for the canonical symbol "sym". */
  private static void alias(String sym, String spelling) {
    registerAlt(sym, spelling);
  }

  private static void registerAlt(String sym, String spelling) {
    String key = spelling.toLowerCase(Locale.US);
    if (TABLE.containsKey(key) || TABLE.containsKey(spelling)) return; // never shadow a real symbol
    ALT.put(key, sym);
  }

  /** A vocalic nucleus: full voicing, steady-state formants held ~ 120 ms. */
  private static PhonemeDef vowel(String sym, double f1, double f2, double f3, double f4, double f5) {
    PhonemeDef d = new PhonemeDef(sym, PhonemeDef.T_VOWEL);
    d.f1 = f1; d.f2 = f2; d.f3 = f3; d.f4 = f4; d.f5 = f5;
    d.voiced = 1.0;
    d.durMin = 0.05; d.durDef = 0.12;
    return add(d);
  }

  /** A nasalised vowel: the vowel target plus the nasal anti-resonance. */
  private static PhonemeDef nasalVowel(String sym, double f1, double f2, double f3, double f4, double f5,
                                       double nasalZero) {
    PhonemeDef d = vowel(sym, f1, f2, f3, f4, f5);
    d.nasalZero = nasalZero;
    d.tilt = -2.0;                     // extra damping of the nasalised spectrum
    d.durMin = 0.06; d.durDef = 0.15;
    return d;
  }

  /** A vowel + velar nasal rime: lowered F2/F3 plus the nasal anti-resonance. */
  private static PhonemeDef velarVowel(String sym, double f1, double f2, double f3, double f4, double f5,
                                       double nasalZero) {
    PhonemeDef d = vowel(sym, f1, f2, f3, f4, f5);
    d.nasalZero = nasalZero;
    d.tilt = -4.0;                     // velar constriction darkens the spectrum further
    d.durMin = 0.06; d.durDef = 0.16;
    return d;
  }

  /** A glide: vowel-like targets but shorter and slightly darker. */
  private static PhonemeDef glide(String sym, double f1, double f2, double f3, double f4) {
    PhonemeDef d = new PhonemeDef(sym, PhonemeDef.T_GLIDE);
    d.f1 = f1; d.f2 = f2; d.f3 = f3; d.f4 = f4;
    d.voiced = 1.0; d.durMin = 0.035; d.durDef = 0.07; d.tilt = -1.5;
    return add(d);
  }

  /** A stop: place locus plus an optional aspiration release. */
  private static PhonemeDef stop(String sym, double f1, double f2, double f3, double f4, double f5,
                                 boolean aspirated, double noiseFc, double noiseBw, double noise, double voiced) {
    PhonemeDef d = new PhonemeDef(sym, PhonemeDef.T_STOP);
    d.f1 = f1; d.f2 = f2; d.f3 = f3; d.f4 = f4; d.f5 = f5;
    d.voiced = voiced;
    d.aspirated = aspirated;
    d.noise = noise; d.noiseFc = noiseFc; d.noiseBw = noiseBw;
    d.durMin = aspirated ? 0.03 : 0.02;
    d.durDef = aspirated ? 0.08 : 0.05;
    d.tilt = -3.0;
    return add(d);
  }

  /** A nasal: murmur formants, strong anti-resonance, dark spectrum. */
  private static PhonemeDef nasal(String sym, double f1, double f2, double f3, double nasalZero) {
    PhonemeDef d = new PhonemeDef(sym, PhonemeDef.T_NASAL);
    d.f1 = f1; d.f2 = f2; d.f3 = f3;
    d.f4 = 3300; d.f5 = 4300;
    d.voiced = 1.0; d.nasalZero = nasalZero;
    d.durMin = 0.04; d.durDef = 0.09; d.tilt = -6.0;
    return add(d);
  }

  /** A liquid/approximant: voiced, with a small amount of turbulent noise. */
  private static PhonemeDef liquid(String sym, double f1, double f2, double f3, double f4,
                                   double noiseFc, double noiseBw, double noise, double voiced) {
    PhonemeDef d = new PhonemeDef(sym, PhonemeDef.T_LIQUID);
    d.f1 = f1; d.f2 = f2; d.f3 = f3; d.f4 = f4; d.f5 = 4300;
    d.voiced = voiced; d.noise = noise; d.noiseFc = noiseFc; d.noiseBw = noiseBw;
    d.durMin = 0.03; d.durDef = 0.06; d.tilt = -2.0;
    return add(d);
  }

  /** A fricative: band-limited noise through the fricative branch. */
  private static PhonemeDef fricative(String sym, double f1, double f2, double f3, double f4,
                                      double noiseFc, double noiseBw, double noise, double voiced) {
    PhonemeDef d = new PhonemeDef(sym, PhonemeDef.T_FRIC);
    d.f1 = f1; d.f2 = f2; d.f3 = f3; d.f4 = f4; d.f5 = 4400;
    d.voiced = voiced; d.noise = noise; d.noiseFc = noiseFc; d.noiseBw = noiseBw;
    d.durMin = 0.04; d.durDef = 0.10; d.tilt = -1.0;
    return add(d);
  }

  /** Glottal/velar aspiration: broadband noise, no oral constriction. */
  private static PhonemeDef aspirative(String sym, double f1, double f2, double f3, double f4,
                                       double noiseFc, double noiseBw, double noise, double voiced) {
    PhonemeDef d = new PhonemeDef(sym, PhonemeDef.T_ASPIR);
    d.f1 = f1; d.f2 = f2; d.f3 = f3; d.f4 = f4; d.f5 = 4400;
    d.voiced = voiced; d.noise = noise; d.noiseFc = noiseFc; d.noiseBw = noiseBw;
    d.durMin = 0.04; d.durDef = 0.09;
    return add(d);
  }

  /** An affricate: stop locus followed by a homorganic fricative release. */
  private static PhonemeDef affricate(String sym, double f1, double f2, double f3, double f4, double f5,
                                      double noiseFc, double noiseBw, double noise, double voiced, boolean aspirated) {
    PhonemeDef d = new PhonemeDef(sym, PhonemeDef.T_AFFRIC);
    d.f1 = f1; d.f2 = f2; d.f3 = f3; d.f4 = f4; d.f5 = f5;
    d.voiced = voiced; d.noise = noise; d.noiseFc = noiseFc; d.noiseBw = noiseBw;
    d.aspirated = aspirated;
    d.durMin = 0.035; d.durDef = aspirated ? 0.11 : 0.09;
    d.tilt = -2.0;
    return add(d);
  }

  // -------------------------------------------------------------- public API

  /** Case-insensitive lookup of a phoneme symbol, trimmed; null when unknown. */
  public static PhonemeDef get(String sym) {
    if (sym == null) return null;
    String key = sym.trim();
    if (key.length() == 0) return null;
    PhonemeDef d = TABLE.get(key);
    if (d != null) return d;
    key = key.toLowerCase(Locale.US);
    d = TABLE.get(key);
    if (d != null) return d;
    String canon = LOWER.get(key);
    if (canon == null) canon = ALT.get(key);
    return canon == null ? null : TABLE.get(canon);
  }

  /**
   * Never returns null. Unknown symbols fall back to a neutral schwa-like "a"
   * variant, and silence-like spellings resolve to {@link #SIL}.
   */
  public static PhonemeDef getOrDefault(String sym) {
    PhonemeDef d = get(sym);
    if (d != null) return d;
    String key = sym == null ? "" : sym.trim().toLowerCase(Locale.US);
    if (key.equals("sil") || key.equals("sp") || key.equals("pau") || key.equals("rest")) return SIL;
    return NEUTRAL;
  }

  /** All known symbols, canonical CHN first then the Japanese/legacy extras. */
  public static List<String> symbols() {
    return SYMBOLS;
  }

  /** Exactly the 62 proven VOCALOID4 Chinese phonemes, in PHDC order. */
  public static List<String> chineseSymbols() {
    return CHN;
  }

  /** The non-CHN symbols kept for the Japanese path and old projects. */
  public static List<String> extraSymbols() {
    return EXTRA;
  }

  /** true when the symbol is one of the 62 proven CHN phonemes. */
  public static boolean isChineseSymbol(String sym) {
    PhonemeDef d = get(sym);
    return d != null && CHN.contains(d.sym);
  }

  /** true when the symbol is a vowel (a possible syllable nucleus). */
  public static boolean isVowel(String sym) {
    PhonemeDef d = get(sym);
    return d != null && d.isVowel();
  }

  /**
   * Collapses whitespace, lowercases every token and rewrites alternative
   * spellings to their canonical symbols. Unknown tokens are kept as-is so the
   * caller can still report them.
   */
  public static String normalize(String phonemeSequence) {
    if (phonemeSequence == null) return "";
    StringBuilder sb = new StringBuilder(phonemeSequence.length());
    int i = 0, n = phonemeSequence.length();
    while (i < n) {
      while (i < n && Character.isWhitespace(phonemeSequence.charAt(i))) i++;
      int start = i;
      while (i < n && !Character.isWhitespace(phonemeSequence.charAt(i))) i++;
      if (i > start) {
        String raw = phonemeSequence.substring(start, i);
        String canon = TABLE.containsKey(raw) ? raw : null;   // exact spelling first: N stays N
        if (canon == null) {
          String tok = raw.toLowerCase(Locale.US);
          canon = TABLE.containsKey(tok) ? tok : LOWER.get(tok);
          if (canon == null) canon = ALT.get(tok);
          if (canon == null) {
            PhonemeDef d = get(raw);
            canon = d == null ? tok : d.sym;
          }
        }
        if (sb.length() > 0) sb.append(' ');
        sb.append(canon);
      }
    }
    return sb.toString();
  }

  /** Convenience: the canonical symbols of a phoneme list, space separated. */
  public static String join(List<String> phonemes) {
    if (phonemes == null || phonemes.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < phonemes.size(); i++) {
      if (i > 0) sb.append(' ');
      sb.append(phonemes.get(i));
    }
    return sb.toString();
  }

  /** Raw view of the table; used by tests and by future table loaders. */
  static Map<String, PhonemeDef> rawTable() { return TABLE; }

  /** Raw view of the alias map (spelling -> canonical symbol). */
  static Map<String, String> rawAliases() { return ALT; }

  /** The fixed CHN symbol array as a list; documents the immutable order. */
  static List<String> sourceOrder() { return Arrays.asList(CHN62); }
}
