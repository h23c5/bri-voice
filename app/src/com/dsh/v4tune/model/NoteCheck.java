package com.dsh.v4tune.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Note overlap detection and "standardisation".
 *
 * <p>A singer is monophonic, so inside one part two notes must never sound at the same time. The
 * VOCALOID editor enforces that by trimming the earlier note when you drag a later one onto it.
 * Imported files (VSQ2, MIDI, hand-edited VSQX) do contain overlaps, and they are heard as two
 * voices fighting, so BriVoice checks before playing and can normalise them in one undoable step.</p>
 */
public final class NoteCheck {

    private NoteCheck() {
    }

    /** one problem: {@code notes[i]} and {@code notes[j]} collide */
    public static final class Issue {
        public final Note first;      // the note that has to give way (the earlier one)
        public final Note second;     // the note that starts too early / at the same tick
        public final long at;         // tick to jump to when reporting
        public final boolean duplicate;   // both start on the same tick

        Issue(Note first, Note second, boolean duplicate) {
            this.first = first;
            this.second = second;
            this.at = second.pos;
            this.duplicate = duplicate;
        }

        public String describe() {
            return (duplicate ? "同一位置有两个音符" : "音符重叠")
                    + " @ " + TimeUtil.tickToBarBeat(at) + "（" + first.lyric + " / " + second.lyric + "）";
        }
    }

    private static List<Note> sorted(Part p) {
        ArrayList<Note> l = new ArrayList<Note>(p.notes);
        Collections.sort(l, new Comparator<Note>() {
            public int compare(Note a, Note b) {
                if (a.pos != b.pos) return a.pos < b.pos ? -1 : 1;
                if (a.dur != b.dur) return a.dur > b.dur ? -1 : 1;   // longer first at the same tick
                return 0;
            }
        });
        return l;
    }

    /** every collision in one part, in time order */
    public static List<Issue> issues(Part p) {
        ArrayList<Issue> out = new ArrayList<Issue>();
        if (p == null || p.notes.size() < 2) return out;
        List<Note> l = sorted(p);
        for (int i = 1; i < l.size(); i++) {
            Note a = l.get(i - 1), b = l.get(i);
            if (b.pos == a.pos) out.add(new Issue(a, b, true));
            else if (a.pos + a.dur > b.pos) out.add(new Issue(a, b, false));
        }
        return out;
    }

    /** how many notes of the part are involved in a collision */
    public static int countIssues(Song s) {
        int n = 0;
        if (s == null) return 0;
        for (Track t : s.tracks) {
            for (Part p : t.parts) n += issues(p).size();
        }
        return n;
    }

    /**
     * Normalises every part of the song: an overlapping note is shortened so it ends where the next
     * one starts, and a note that starts on the very same tick as a longer one is dropped (keeping
     * the longer note is the only choice that preserves what the user can hear).
     *
     * @return the number of notes changed or removed
     */
    public static int normalize(Song s, Part single) {
        int changed = 0;
        if (single != null) return normalizePart(single);
        if (s == null) return 0;
        for (Track t : s.tracks) {
            for (Part p : t.parts) changed += normalizePart(p);
        }
        return changed;
    }

    private static int normalizePart(Part p) {
        if (p == null || p.notes.size() < 2) return 0;
        int changed = 0;
        List<Note> l = sorted(p);
        for (int i = 1; i < l.size(); i++) {
            Note a = l.get(i - 1), b = l.get(i);
            if (b.pos == a.pos) {
                p.notes.remove(a);                    // same tick: keep the longer note only
                changed++;
            } else if (a.pos + a.dur > b.pos) {
                long d = b.pos - a.pos;
                if (d >= 1) {
                    a.dur = d;
                    changed++;
                }
            }
        }
        return changed;
    }
}
