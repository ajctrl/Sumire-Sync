package com.ajctrl.sumiresync.settings;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {
    private static final String PREFS = "settings";
    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String webDavUrl() { return prefs.getString("webdav_url", ""); }
    public String webDavUser() { return prefs.getString("webdav_user", ""); }
    public boolean foregroundInferenceEnabled() { return prefs.getBoolean("foreground_inference", false); }
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
