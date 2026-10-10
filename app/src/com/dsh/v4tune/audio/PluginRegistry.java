package com.dsh.v4tune.audio;

import com.dsh.v4tune.audio.plugins.ChorusPlugin;
import com.dsh.v4tune.audio.plugins.CompressorPlugin;
import com.dsh.v4tune.audio.plugins.ReverbPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Extension point for third party plugins: a tiny id -> factory registry so the UI can offer a
 * plugin picker and projects can persist plugins by {@link Plugin#id()}.
 *
 * <p>The three built-in plugins are registered by the static initialiser. New plugins only have to
 * implement {@link Plugin} and call {@link #register(String, Factory)} once.</p>
 *
 * <p>Plain Java - no android.* dependency.</p>
 */
public final class PluginRegistry {

    /** Creates a fresh instance of one plugin type. */
    public interface Factory {
        Plugin create();
    }

    private static final LinkedHashMap<String, Factory> FACTORIES = new LinkedHashMap<String, Factory>();

    static {
        registerBuiltins();
    }

    private PluginRegistry() {
    }

    /** Register (or replace) a factory under a plugin id. */
    public static synchronized void register(String id, Factory f) {
        if (id == null || f == null) return;
        FACTORIES.put(id, f);
    }

    /** Instantiate a plugin by id, or null when the id is unknown. */
    public static synchronized Plugin create(String id) {
        Factory f = id == null ? null : FACTORIES.get(id);
        return f == null ? null : f.create();
    }

    /** Registered ids, in registration order. */
    public static synchronized String[] ids() {
        return FACTORIES.keySet().toArray(new String[0]);
    }

    /** Registered factories, in registration order. */
    public static synchronized ArrayList<Factory> factories() {
        return new ArrayList<Factory>(FACTORIES.values());
    }

    public static synchronized int size() {
        return FACTORIES.size();
    }

    /** Idempotently register the three built-in plugins. */
    public static synchronized void registerBuiltins() {
        putIfAbsent("builtin.reverb", new Factory() {
            public Plugin create() { return new ReverbPlugin(); }
        });
        putIfAbsent("builtin.chorus", new Factory() {
            public Plugin create() { return new ChorusPlugin(); }
        });
        putIfAbsent("builtin.compressor", new Factory() {
            public Plugin create() { return new CompressorPlugin(); }
        });
    }

    private static void putIfAbsent(String id, Factory f) {
        if (!FACTORIES.containsKey(id)) FACTORIES.put(id, f);
    }
}
