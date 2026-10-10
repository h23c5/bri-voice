package com.dsh.v4tune.audio;

import java.util.ArrayList;

/**
 * An ordered plugin chain (a track insert rack). Slots are processed front to back; a bypassed
 * slot is skipped entirely (its state is left alone, so un-bypassing does not click).
 *
 * <p>The chain is plain Java - no android.* - and is safe to snapshot for the UI through
 * {@link #snapshotParams(int)}, which copies the current parameter values of one slot.</p>
 */
public class PluginChain {

    private final ArrayList<Plugin> plugins = new ArrayList<Plugin>();
    private final ArrayList<Boolean> bypass = new ArrayList<Boolean>();

    /** Append a plugin, returning its new index. */
    public int add(Plugin p) {
        plugins.add(p);
        bypass.add(Boolean.FALSE);
        return plugins.size() - 1;
    }

    /** Remove the plugin at index i, returning it (null when i is out of range). */
    public Plugin remove(int i) {
        if (i < 0 || i >= plugins.size()) return null;
        bypass.remove(i);
        return plugins.remove(i);
    }

    /** Move the plugin at index {@code from} so that it ends up at index {@code to}. */
    public boolean move(int from, int to) {
        int n = plugins.size();
        if (from < 0 || from >= n || to < 0 || to >= n || from == to) return false;
        Plugin p = plugins.remove(from);
        Boolean b = bypass.remove(from);
        plugins.add(to, p);
        bypass.add(to, b);
        return true;
    }

    /** Bypass (true) or re-enable (false) the slot at index i. */
    public boolean setBypass(int i, boolean b) {
        if (i < 0 || i >= plugins.size()) return false;
        bypass.set(i, Boolean.valueOf(b));
        return true;
    }

    public boolean isBypassed(int i) {
        return i >= 0 && i < bypass.size() && bypass.get(i).booleanValue();
    }

    public int size() {
        return plugins.size();
    }

    /** Plugin at index i, or null when i is out of range. */
    public Plugin get(int i) {
        return (i >= 0 && i < plugins.size()) ? plugins.get(i) : null;
    }

    public void clear() {
        plugins.clear();
        bypass.clear();
    }

    /** Index of the first plugin with this id, or -1. */
    public int indexOf(String id) {
        if (id == null) return -1;
        for (int i = 0; i < plugins.size(); i++) {
            Plugin p = plugins.get(i);
            if (p != null && id.equals(p.id())) return i;
        }
        return -1;
    }

    /** First plugin with this id, or null. */
    public Plugin byId(String id) {
        int i = indexOf(id);
        return i < 0 ? null : plugins.get(i);
    }

    /** Prepare every slot (bypassed ones too, so they stay ready). */
    public void prepareAll(int sampleRate, int maxFrames) {
        for (int i = 0; i < plugins.size(); i++) {
            Plugin p = plugins.get(i);
            if (p != null) p.prepare(sampleRate, maxFrames);
        }
    }

    /** Reset every slot. */
    public void resetAll() {
        for (int i = 0; i < plugins.size(); i++) {
            Plugin p = plugins.get(i);
            if (p != null) p.reset();
        }
    }

    /** Run the whole chain in order over one stereo block. Bypassed slots are skipped. */
    public void process(float[] l, float[] r, int frames) {
        if (l == null || r == null || frames <= 0) return;
        int n = frames;
        if (n > l.length) n = l.length;
        if (n > r.length) n = r.length;
        if (n <= 0) return;
        for (int i = 0; i < plugins.size(); i++) {
            if (bypass.get(i).booleanValue()) continue;
            Plugin p = plugins.get(i);
            if (p != null) p.process(l, r, n);
        }
    }

    /** Copy of every parameter of slot i, or null when i is out of range. */
    public double[] snapshotParams(int i) {
        Plugin p = get(i);
        if (p == null) return null;
        int n = p.paramCount();
        double[] out = new double[n];
        for (int k = 0; k < n; k++) out[k] = p.getParam(k);
        return out;
    }

    /** Copy of every parameter of every slot (row per slot), skipping nothing. */
    public double[][] snapshotAll() {
        double[][] out = new double[plugins.size()][];
        for (int i = 0; i < out.length; i++) out[i] = snapshotParams(i);
        return out;
    }
}
