package com.dsh.v4tune.ai;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings for the experimental AI assistant and for the external control interface.
 * Both are OFF by default: nothing leaves the device and no port is opened until the user
 * turns them on in Tools.
 */
public final class AiConfig {

    private static final String PREFS = "brivoice_ai";

    /** master switch for the AI tuner */
    public boolean aiEnabled = false;
    /** OpenAI-compatible endpoint, e.g. https://api.openai.com/v1 or http://10.0.2.2:8080/v1 */
    public String baseUrl = "https://api.openai.com/v1";
    public String model = "gpt-4o-mini";
    public String apiKey = "";
    /** how strongly the assistant is allowed to move existing curves (0..1) */
    public float strength = 0.6f;

    /** master switch for the local HTTP control interface */
    public boolean extEnabled = false;
    public int extPort = 8765;

    public static AiConfig load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AiConfig a = new AiConfig();
        a.aiEnabled = p.getBoolean("aiEnabled", false);
        a.baseUrl = p.getString("baseUrl", a.baseUrl);
        a.model = p.getString("model", a.model);
        a.apiKey = p.getString("apiKey", "");
        a.strength = p.getFloat("strength", a.strength);
        a.extEnabled = p.getBoolean("extEnabled", false);
        a.extPort = p.getInt("extPort", a.extPort);
        return a;
    }

    public void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("aiEnabled", aiEnabled)
                .putString("baseUrl", baseUrl)
                .putString("model", model)
                .putString("apiKey", apiKey)
                .putFloat("strength", strength)
                .putBoolean("extEnabled", extEnabled)
                .putInt("extPort", extPort)
                .apply();
    }

    /** the chat-completions URL for the configured base */
    public String chatUrl() {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    public boolean ready() { return aiEnabled && baseUrl != null && baseUrl.trim().length() > 0; }
}