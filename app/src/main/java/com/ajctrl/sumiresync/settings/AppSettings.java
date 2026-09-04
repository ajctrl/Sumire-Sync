package com.ajctrl.sumiresync.settings;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {
    private static final String PREFS = "settings";
    public static final long DEFAULT_FLAG_DURATION_MILLIS = 30 * 60 * 1000L;
    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String webDavUrl() { return prefs.getString("webdav_url", ""); }
    public String webDavUser() { return prefs.getString("webdav_user", ""); }
    public boolean foregroundInferenceEnabled() { return prefs.getBoolean("foreground_inference", false); }
    public long flagDurationMillis() {
        return prefs.getLong("flag_duration_millis", DEFAULT_FLAG_DURATION_MILLIS);
    }

    public boolean flaggingEnabled() {
        long expiresAt = prefs.getLong("flag_expires_at", 0);
        boolean enabled = expiresAt > System.currentTimeMillis();
        if (!enabled && expiresAt != 0) prefs.edit().remove("flag_expires_at").apply();
        return enabled;
    }

    public long flagExpiresAt() {
        return flaggingEnabled() ? prefs.getLong("flag_expires_at", 0) : 0;
    }

    public void setFlaggingEnabled(boolean enabled) {
        SharedPreferences.Editor editor = prefs.edit();
        if (enabled) {
            editor.putLong("flag_expires_at", System.currentTimeMillis() + flagDurationMillis());
        } else {
            editor.remove("flag_expires_at");
        }
        editor.apply();
    }

    public void setFlagDurationMillis(long durationMillis) {
        if (durationMillis <= 0) throw new IllegalArgumentException("Flag duration must be positive");
        boolean wasEnabled = flaggingEnabled();
        SharedPreferences.Editor editor = prefs.edit().putLong("flag_duration_millis", durationMillis);
        if (wasEnabled) {
            editor.putLong("flag_expires_at", System.currentTimeMillis() + durationMillis);
        }
        editor.apply();
    }
    public String connectedProviderAuthority() {
        return prefs.getString("connected_provider_authority", null);
    }

    public void saveConnectedProviderAuthority(String authority) {
        prefs.edit().putString("connected_provider_authority", authority).apply();
    }

    public void save(String url, String user, boolean foregroundInference) {
        prefs.edit().putString("webdav_url", url.trim()).putString("webdav_user", user.trim())
                .putBoolean("foreground_inference", foregroundInference).apply();
    }
}
