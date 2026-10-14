package com.ajctrl.sumiresync.settings;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {
    private static final String PREFS = "settings";
    private static final Object UPLOAD_SETTINGS_LOCK = new Object();
    public static final long DEFAULT_FLAG_DURATION_MILLIS = 30 * 60 * 1000L;
    private final SharedPreferences prefs;
    private final Context context;

    public AppSettings(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    public long uploadGeneration() { return prefs.getLong("upload_generation", 0); }

    public UploadSettings uploadSettings() throws Exception {
        synchronized (UPLOAD_SETTINGS_LOCK) {
            return new UploadSettings(webDavUrl(), webDavUser(),
                    webDavUrl().isEmpty() ? "" : new SecretStore(context).getPassword(), uploadGeneration());
        }
    }

    public void save(String url, String user, boolean foregroundInference, String password)
            throws Exception {
        synchronized (UPLOAD_SETTINGS_LOCK) {
            String normalizedUrl = url.trim();
            String normalizedUser = user.trim();
            long generation = uploadGeneration();
            if (!normalizedUrl.equals(webDavUrl()) || !normalizedUser.equals(webDavUser())) {
                generation = Math.addExact(generation, 1);
            }
            String encryptedPassword = new SecretStore(context).encryptPassword(password);
            if (!prefs.edit().putString("webdav_url", normalizedUrl)
                    .putString("webdav_user", normalizedUser)
                    .putString(SecretStore.PASSWORD, encryptedPassword)
                    .putLong("upload_generation", generation)
                    .putBoolean("foreground_inference", foregroundInference).commit()) {
                throw new IllegalStateException("Cannot persist upload settings");
            }
        }
    }

    public static final class UploadSettings {
        public final String url;
        public final String user;
        public final String password;
        public final long generation;

        UploadSettings(String url, String user, String password, long generation) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.generation = generation;
        }
    }
}
