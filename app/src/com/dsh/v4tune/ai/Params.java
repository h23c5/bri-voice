package com.dsh.v4tune.ai;

/** Parameter ranges, so anything an AI or an external script sends can be validated. */
public final class Params {

    private Params() { }

    public static final String[] IDS = {"DYN", "BRE", "BRI", "CLE", "OPE", "GEN", "POR", "PIT", "PBS", "VIB", "XSY", "GWL"};
    public static final double[] MIN = {0, 0, 0, 0, 0, 0, 0, -2400, 0, 0, 0, 0};
    public static final double[] MAX = {127, 127, 127, 127, 127, 127, 127, 2400, 24, 127, 127, 127};

    public static int indexOf(String id) {
        if (id == null) return -1;
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equalsIgnoreCase(id.trim())) return i;
        return -1;
    }

    public static double clamp(int index, double v) {
        if (index < 0 || index >= IDS.length) return v;
        return v < MIN[index] ? MIN[index] : (v > MAX[index] ? MAX[index] : v);
    }
}