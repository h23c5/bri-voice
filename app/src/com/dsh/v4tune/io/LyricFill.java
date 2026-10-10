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
}
