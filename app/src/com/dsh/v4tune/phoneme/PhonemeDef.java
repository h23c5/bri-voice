package com.dsh.v4tune.phoneme;

/**
 * One entry of the V4Tune phoneme inventory: a compact articulatory/acoustic
 * description of a single VOCALOID-style phoneme symbol.
 *
 * <p>The model behind it is a standard source-filter / Klatt-style cascade:
 * a voicing source ({@link #voiced}) and a fricative noise source
 * ({@link #noise}, band-limited by {@link #noiseFc}/{@link #noiseBw}) excite a
 * formant filter bank ({@link #f1}..{@link #f5}, {@link #b1}..{@link #b5}).
 * Formant values are steady-state targets for vowels and glides, and
 * frequency "loci" for stops (the place-cue that formant transitions move to,
 * Kewley-Port 1982; Stevens 1998, <i>Acoustic Phonetics</i>, ch. 8).
 * Nasals and nasalised vowels additionally place an anti-resonance
 * ({@link #nasalZero}).
 *
 * <p>All values are in Hz for frequencies and seconds for durations. Nothing
 * in this class is Android-specific so it can be unit-tested on the desktop.
 */
public class PhonemeDef {
  /** Phoneme classes; see the per-symbol comments in {@link PhonemeTable}. */
  public static final int T_VOWEL=0, T_STOP=1, T_FRIC=2, T_AFFRIC=3, T_NASAL=4, T_LIQUID=5, T_GLIDE=6, T_ASPIR=7, T_SIL=8;

  /** lowercase VOCALOID-style phoneme symbol, e.g. "a","s\\","ts\_h","Sil" */
  public String sym;
  /** one of the T_* constants */
  public int type;
  /** formant targets, Hz (steady state, or locus for consonants) */
  public double f1,f2,f3,f4,f5;
  /** formant bandwidths, Hz */
  public double b1,b2,b3,b4,b5;
  /** 0..1 voicing excitation amount */
  public double voiced;
  /** 0..1 noise excitation amount through the fricative branch */
  public double noise;
  /** fricative noise band centre / bandwidth, Hz */
  public double noiseFc, noiseBw;
  /** seconds; durMin = minimum, durDef = natural duration */
  public double durMin, durDef;
  /** relative amplitude, 1.0 = nominal */
  public double gain;
  /** 0..1, amount of anti-resonance for nasals and nasalised vowels */
  public double nasalZero;
  /** spectral tilt, dB per octave of extra rolloff (negative = darker) */
  public double tilt;
  /** stop released with aspiration (p_h,t_h,k_h and the _h affricates) */
  public boolean aspirated;
  /** alternative spellings that map to this symbol (e.g. "v" -> "y") */
  public String[] alt;

  /**
   * Creates a definition with sane neutral defaults: a schwa-like formant set
   * (F1 500 / F2 1500 / F3 2500), bandwidths 60/90/120/150/200 Hz, full
   * voicing, no noise, unity gain and a 30..80 ms duration range. Table code
   * overrides the fields it cares about.
   */
  public PhonemeDef(String sym, int type) {
    this.sym = sym;
    this.type = type;
    this.f1 = 500; this.f2 = 1500; this.f3 = 2500; this.f4 = 3500; this.f5 = 4500;
    this.b1 = 60; this.b2 = 90; this.b3 = 120; this.b4 = 150; this.b5 = 200;
    this.voiced = 1.0;
    this.noise = 0.0;
    this.noiseFc = 2000; this.noiseBw = 2000;
    this.durMin = 0.03; this.durDef = 0.08;
    this.gain = 1.0;
    this.nasalZero = 0.0;
    this.tilt = 0.0;
    this.aspirated = false;
    this.alt = new String[0];
  }

  /** true when this phoneme is a vowel (a syllable nucleus). */
  public boolean isVowel() { return type == T_VOWEL; }

  /** true when this phoneme is the silence symbol. */
  public boolean isSilence() { return type == T_SIL; }

  @Override public String toString() {
    return sym + "[t" + type + " f=" + (int) f1 + "/" + (int) f2 + "/" + (int) f3
        + " v=" + voiced + " n=" + noise + " d=" + durDef + "]";
  }
}
