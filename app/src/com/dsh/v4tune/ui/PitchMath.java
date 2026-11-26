package com.dsh.v4tune.ui;

import java.util.Locale;

/**
 * Pitch-lane helpers: the PIT curve is stored in cents, but nobody wants to read "+150" and
 * translate it into "a semitone and a half" while drawing.
 *
 * <p>Pure static maths so the desktop self test can cover it without an Android runtime.</p>
 */
public final class PitchMath {

    /** one semitone in PIT cents */
    public static final double SEMITONE = 100.0;
    /** one octave in PIT cents */
    public static final double OCTAVE = 1200.0;

    private PitchMath() { }

    /** snaps a cent value to the nearest semitone when {@code on}; leaves it alone otherwise */
    public static double snap(double cents, boolean on) {
        if (!on) return cents;
        return Math.round(cents / SEMITONE) * SEMITONE;
    }

    /**
     * Formats a cent offset as semitones with a sign and at most two decimals:
     * 0 -> "0", 150 -> "+1.5", -2050 -> "-20.5", 2400 -> "+24".
     *
     * <p>Trailing zeros are trimmed so a plain semitone reads "+1" instead of "+1.00".</p>
     */
    public static String cents(double cents) {
        double st = cents / SEMITONE;
        // kill -0.0 so a value just below zero does not print "-0"
        if (Math.abs(st) < 0.005) return "0";
        String s = String.format(Locale.US, "%.2f", st);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return (st > 0 ? "+" : "") + s;
    }

    /** full readout used by the drag badge: "PIT +1.5 st" */
    public static String label(double cents) {
        return cents(cents) + " st";
    }

    /** true when the cent value is exactly on a semitone (what the grid draws) */
    public static boolean onSemitone(double cents) {
        return Math.abs(cents - Math.round(cents / SEMITONE) * SEMITONE) < 1e-6;
    }
}
