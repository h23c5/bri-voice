package com.dsh.v4tune.voicebank;

import java.util.HashMap;
import java.util.Map;

/** CHN phoneme reference formants (F1,F2,F3 in Hz). Generated from tools/voicebank_pack.py
 *  REFERENCE_FORMANTS by tools/gen_reference_formants.py - do not edit by hand. */
public final class ReferenceFormants {
    private static final Map<String, int[]> T = new HashMap<String, int[]>();
    public static int[] get(String symbol) { return T.get(symbol); }
    public static int size() { return T.size(); }
    public static java.util.Set<String> symbols() { return T.keySet(); }
    static {
        T.put("7", new int[]{400, 1100, 2400});
        T.put("?", new int[]{500, 1500, 2500});
        T.put("@N", new int[]{400, 1000, 2300});
        T.put("@U", new int[]{460, 1220, 2400});
        T.put("@_n", new int[]{500, 1400, 2400});
        T.put("@`", new int[]{500, 1400, 1700});
        T.put("AN", new int[]{800, 1100, 2400});
        T.put("AU", new int[]{700, 1080, 2470});
        T.put("Asp", new int[]{500, 1500, 2500});
        T.put("UN", new int[]{400, 800, 2400});
        T.put("a", new int[]{850, 1200, 2500});
        T.put("aI", new int[]{690, 1530, 2650});
        T.put("a_n", new int[]{850, 1200, 2500});
        T.put("ei", new int[]{410, 2090, 2720});
        T.put("f", new int[]{250, 1200, 2200});
        T.put("i", new int[]{300, 2300, 3000});
        T.put("i@U", new int[]{370, 1730, 2700});
        T.put("iAN", new int[]{450, 1940, 2820});
        T.put("iAU", new int[]{475, 1670, 2730});
        T.put("iE_n", new int[]{380, 2180, 2880});
        T.put("iE_r", new int[]{400, 1800, 1700});
        T.put("iN", new int[]{300, 2100, 2800});
        T.put("iUN", new int[]{330, 1850, 2820});
        T.put("i\\", new int[]{350, 1600, 2500});
        T.put("i_n", new int[]{300, 2300, 3000});
        T.put("i`", new int[]{400, 1500, 1800});
        T.put("ia", new int[]{470, 1970, 2850});
        T.put("k", new int[]{250, 1900, 2200});
        T.put("k_h", new int[]{250, 1900, 2200});
        T.put("l", new int[]{350, 1000, 2600});
        T.put("m", new int[]{250, 1100, 2200});
        T.put("n", new int[]{250, 1700, 2600});
        T.put("o", new int[]{500, 800, 2500});
        T.put("p", new int[]{250, 800, 2200});
        T.put("p_h", new int[]{250, 800, 2200});
        T.put("s", new int[]{250, 1700, 2600});
        T.put("s\\", new int[]{250, 2200, 3000});
        T.put("s`", new int[]{250, 1500, 2000});
        T.put("t", new int[]{250, 1700, 2600});
        T.put("t_h", new int[]{250, 1700, 2600});
        T.put("ts", new int[]{250, 1700, 2600});
        T.put("ts\\", new int[]{250, 2200, 3000});
        T.put("ts\\_h", new int[]{250, 2200, 3000});
        T.put("ts_h", new int[]{250, 1700, 2600});
        T.put("ts`", new int[]{250, 1500, 2000});
        T.put("ts`_h", new int[]{250, 1500, 2000});
        T.put("u", new int[]{350, 800, 2400});
        T.put("u@N", new int[]{370, 860, 2370});
        T.put("u@_n", new int[]{400, 980, 2400});
        T.put("uAN", new int[]{485, 890, 2400});
        T.put("ua", new int[]{500, 920, 2430});
        T.put("uaI", new int[]{490, 1220, 2550});
        T.put("ua_n", new int[]{500, 920, 2430});
        T.put("uei", new int[]{370, 1460, 2580});
        T.put("uo", new int[]{400, 800, 2430});
        T.put("x", new int[]{250, 1200, 2200});
        T.put("y", new int[]{300, 1900, 2300});
        T.put("yE_r", new int[]{350, 1750, 1650});
        T.put("y_n", new int[]{300, 1900, 2300});
        T.put("y{_n", new int[]{390, 1840, 2360});
        T.put("z`", new int[]{400, 1400, 1700});
    }
    private ReferenceFormants() { }
}
