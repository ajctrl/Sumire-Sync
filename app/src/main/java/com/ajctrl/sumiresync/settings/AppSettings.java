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

    public String webDavUrl() {
        synchronized (UPLOAD_SETTINGS_LOCK) { return prefs.getString("webdav_url", ""); }
    }
    public String webDavUser() {
        synchronized (UPLOAD_SETTINGS_LOCK) { return prefs.getString("webdav_user", ""); }
    }
    public boolean foregroundInferenceEnabled() {
        synchronized (UPLOAD_SETTINGS_LOCK) { return prefs.getBoolean("foreground_inference", false); }
    }
    public void setForegroundInferenceEnabled(boolean enabled) {
        synchronized (UPLOAD_SETTINGS_LOCK) {
            prefs.edit().putBoolean("foreground_inference", enabled).apply();
        }
    }
    public long flagDurationMillis() {
        return prefs.getLong("flag_duration_millis", DEFAULT_FLAG_DURATION_MILLIS);
    }

    public boolean flaggingEnabled() {
        long expiresAt = prefs.getLong("flag_expires_at", 0);
        // A stale background read must never remove a newer expiry saved by the UI.
        return expiresAt > System.currentTimeMillis();
    }

    public long flagExpiresAt() {
        return flaggingEnabled() ? prefs.getLong("flag_expires_at", 0) : 0;
    }

    public void setFlaggingEnabled(boolean enabled) {
        synchronized (UPLOAD_SETTINGS_LOCK) {
            SharedPreferences.Editor editor = prefs.edit();
            if (enabled) {
                editor.putLong("flag_expires_at", System.currentTimeMillis() + flagDurationMillis());
            } else {
                editor.remove("flag_expires_at");
            }
            editor.apply();
        }
    }

    public void setFlagDurationMillis(long durationMillis) {
        synchronized (UPLOAD_SETTINGS_LOCK) {
            if (durationMillis <= 0) throw new IllegalArgumentException("Flag duration must be positive");
            boolean wasEnabled = flaggingEnabled();
            SharedPreferences.Editor editor = prefs.edit().putLong("flag_duration_millis", durationMillis);
            if (wasEnabled) {
                editor.putLong("flag_expires_at", System.currentTimeMillis() + durationMillis);
            }
            editor.apply();
        }
    }
    public String connectedProviderAuthority() {
        return prefs.getString("connected_provider_authority", null);
    }

    public void saveConnectedProviderAuthority(String authority) {
        synchronized (UPLOAD_SETTINGS_LOCK) {
            prefs.edit().putString("connected_provider_authority", authority).apply();
        }
    }

    public long uploadGeneration() {
        synchronized (UPLOAD_SETTINGS_LOCK) { return prefs.getLong("upload_generation", 0); }
    }

    /** Keep destination changes atomic with a worker's acknowledgement or error update. */
    public boolean ifUploadGeneration(long generation, Runnable action) {
        synchronized (UPLOAD_SETTINGS_LOCK) {
            if (uploadGeneration() != generation) return false;
            action.run();
            return true;
        }
    }

    public UploadSettings uploadSettings() throws Exception {
        synchronized (UPLOAD_SETTINGS_LOCK) {
            return new UploadSettings(webDavUrl(), webDavUser(),
                    webDavUrl().isEmpty() ? "" : new SecretStore(context).getPassword(), uploadGeneration(),
                    InstallationIdentity.get(context));
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
            SharedPreferences.Editor rollback = prefs.edit()
                    .putString("webdav_url", prefs.getString("webdav_url", null))
                    .putString("webdav_user", prefs.getString("webdav_user", null))
                    .putString(SecretStore.PASSWORD, prefs.getString(SecretStore.PASSWORD, null))
                    .putLong("upload_generation", uploadGeneration())
                    .putBoolean("foreground_inference", foregroundInferenceEnabled());
            boolean committed = false;
            try {
                committed = prefs.edit().putString("webdav_url", normalizedUrl)
                        .putString("webdav_user", normalizedUser)
                        .putString(SecretStore.PASSWORD, encryptedPassword)
                        .putLong("upload_generation", generation)
                        .putBoolean("foreground_inference", foregroundInference).commit();
            } finally {
                // commit() publishes to memory before attempting disk I/O. Even if
                // this second disk write fails, it restores the old in-memory values.
                // All writers share the lock so unrelated apply() calls cannot persist
                // the rejected destination while it is temporarily in preferences.
                if (!committed) rollback.commit();
            }
            if (!committed) {
                throw new IllegalStateException("Cannot persist upload settings");
            }
        }
    }

    public static final class UploadSettings {
        public final String url;
        public final String user;
        public final String password;
        public final long generation;
        public final String installationId;

        UploadSettings(String url, String user, String password, long generation, String installationId) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.generation = generation;
            this.installationId = installationId;
        }
    }
}
