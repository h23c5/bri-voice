package com.dsh.v4tune.io;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a pasted block of lyrics into one syllable per note.
 *
 * <p>Splitting rules (this is what users expect when they paste a line of lyrics):</p>
 * <ul>
 *   <li>whitespace, commas, full stops, slashes and dashes separate words;</li>
 *   <li>a run of CJK characters is split per character;</li>
 *   <li>a Latin word stays one syllable unless it contains a separator;</li>
 *   <li>everything is lower-cased so "Luo" and "luo" behave the same.</li>
 * </ul>
 *
 * <p>The class is deliberately free of android imports so the splitter can be unit tested on the
 * desktop; the caller passes in a converter for CJK characters (the app uses the pinyin map in
 * {@code ChineseG2P}).</p>
 */
public final class LyricFill {

    /** converts one CJK character to something singable (pinyin); may return null */
    public interface Hanzi {
        String convert(char c);
    }

    private LyricFill() {
    }

    public static boolean isCjk(char c) {
        return (c >= 0x3400 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0x3040 && c <= 0x30FF);      // kana are kept as their own syllable
    }

    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || c == ',' || c == '.' || c == ';' || c == ':'
                || c == '/' || c == '\\' || c == '-' || c == '_' || c == '|' || c == '+'
                || c == '，' || c == '。' || c == '、' || c == '；' || c == '：' || c == '！'
                || c == '？' || c == '…' || c == '—' || c == '～' || c == '~' || c == '!'
                || c == '?' || c == '"' || c == '\'' || c == '“' || c == '”' || c == '‘'
                || c == '’' || c == '(' || c == ')' || c == '（' || c == '）';
    }

    /**
     * Splits a pasted line into one segment per note, <b>by whitespace first</b> (v12.0.1).
     *
     * <p>This is the rule users actually state: "一 闪 一 闪 亮 晶 晶 …" is fourteen notes because
     * there are fourteen space-separated pieces, and "洛天依" typed as one piece is ONE note, not
     * three. The per-character splitting below is only the fallback for a line that has no
     * whitespace at all (someone pasting "一闪一闪亮晶晶" straight in).</p>
     */
    public static List<String> segments(String text) {
        ArrayList<String> out = new ArrayList<String>();
        if (text == null) return out;
        String norm = text.replace('\r', '\n');
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (Character.isWhitespace(c)) {
                flushRaw(word, out);
                continue;
            }
            word.append(c);
        }
        flushRaw(word, out);
        if (out.size() == 1) {
            String only = out.get(0);
            // no whitespace at all: fall back to one note per character, but only when the piece is
            // actually multi-character CJK. A single Latin word stays one note.
            boolean allCjk = only.length() > 1;
            for (int i = 0; allCjk && i < only.length(); i++) {
                if (!isCjk(only.charAt(i))) allCjk = false;
            }
            if (allCjk) {
                out.clear();
                for (int i = 0; i < only.length(); i++) out.add(String.valueOf(only.charAt(i)));
            }
        }
        return out;
    }

    /** keeps the segment exactly as typed - no lower-casing, no pinyin conversion */
    private static void flushRaw(StringBuilder word, List<String> out) {
        if (word.length() == 0) return;
        String w = word.toString().trim();
        word.setLength(0);
        if (!w.isEmpty()) out.add(w);
    }

    /** @return one lyric per note, in order; never null, may be shorter than the note list */
    public static List<String> split(String text, Hanzi hanzi) {
        ArrayList<String> out = new ArrayList<String>();
        if (text == null) return out;
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isSeparator(c)) {
                flush(word, out);
                continue;
            }
            if (isCjk(c)) {
                flush(word, out);                       // a CJK run never joins a Latin word
                String py = (hanzi == null) ? null : hanzi.convert(c);
                out.add(py == null || py.isEmpty() ? String.valueOf(c) : py.toLowerCase());
                continue;
            }
            word.append(c);
        }
        flush(word, out);
        return out;
    }

    private static void flush(StringBuilder word, List<String> out) {
        if (word.length() == 0) return;
        String w = word.toString().trim().toLowerCase();
        word.setLength(0);
        if (!w.isEmpty()) out.add(w);
    }

    /** the notes that the fill should touch when nothing is selected: every note of the part */
    public static int fillCount(List<String> syllables) {
        return syllables == null ? 0 : syllables.size();
    }

    /**
     * Where the fill starts (v11.1.2).
     *
     * <p>The lyric has to land on the note the user picked, then run forward, which is the manual's
     * "select a note, type the words" flow. The old code always used the playback cursor, so filling
     * after selecting a note put the text somewhere else. It also wrapped to the FIRST note when the
     * cursor sat past the last note (the scan never found a match and left the index at 0), which is
     * what made a fill appear to do nothing at the end of a phrase.</p>
     *
     * @param positions      note start ticks, ascending
     * @param selectedIndex  index of the selected note, or -1 when nothing is selected
     * @param anchorTick     playback / edit cursor, used only when nothing is selected
     * @return the index to start at, or -1 when there are no notes
     */
    public static int startIndex(long[] positions, int selectedIndex, long anchorTick) {
        if (positions == null || positions.length == 0) return -1;
        if (selectedIndex >= 0 && selectedIndex < positions.length) return selectedIndex;
        int first = -1;
        for (int i = 0; i < positions.length; i++) {
            if (positions[i] >= anchorTick) { first = i; break; }
        }
        // the cursor sits past the last note: there is nothing left to fill. Returning the last
        // index would silently overwrite it, and returning 0 (the old behaviour) moved the whole
        // line to the top of the phrase - both are wrong, so report "nothing here" instead.
        return first;
    }
}
