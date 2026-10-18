package com.ajctrl.sumiresync.settings;

import static org.junit.Assert.*;

import android.content.Context;
import com.ajctrl.sumiresync.data.SyncStateStore;
import java.io.File;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30, shadows = AppSettingsTest.TestSecrets.class)
public final class AppSettingsTest {
    private AppSettings settings;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit();
        settings = new AppSettings(context);
    }

    @Test public void eachDestinationChangeCreatesNewGenerationIncludingReturnToOldServer() throws Exception {
        settings.save("https://a.example.com", "alice", false, "a");
        long first = settings.uploadGeneration();
        settings.save("https://b.example.com", "alice", false, "b");
        assertEquals(first + 1, settings.uploadGeneration());
        settings.save("https://a.example.com", "alice", false, "a");
        assertEquals(first + 2, settings.uploadGeneration());
        settings.save("https://a.example.com", "bob", false, "c");
        assertEquals(first + 3, settings.uploadGeneration());
    }

    @Test public void passwordAndInferenceChangesDoNotResendCompletedArchives() throws Exception {
        settings.save("https://a.example.com", "alice", false, "a");
        long generation = settings.uploadGeneration();
        settings.save(" https://a.example.com ", " alice ", true, "new-password");
        assertEquals(generation, settings.uploadGeneration());
    }

    @Test public void runningUploadRetainsCoherentCredentialsAfterSettingsChange() throws Exception {
        settings.save("https://a.example.com", "alice", false, "old-password");
        AppSettings.UploadSettings running = settings.uploadSettings();
        settings.save("https://b.example.com", "bob", false, "new-password");
        AppSettings.UploadSettings next = new AppSettings(RuntimeEnvironment.getApplication()).uploadSettings();
        assertEquals("https://a.example.com", running.url);
        assertEquals("alice", running.user);
        assertEquals("old-password", running.password);
        assertEquals("https://b.example.com", next.url);
        assertEquals("bob", next.user);
        assertEquals("new-password", next.password);
        assertEquals(running.installationId, next.installationId);
        assertTrue(next.generation > running.generation);
    }

    @Test public void inferenceTogglePreservesConnectionCredentialsAndUploadGeneration() throws Exception {
        settings.save("https://a.example.com", "alice", false, "password");
        long generation = settings.uploadGeneration();
        settings.setForegroundInferenceEnabled(true);
        AppSettings reloaded = new AppSettings(RuntimeEnvironment.getApplication());
        assertTrue(reloaded.foregroundInferenceEnabled());
        assertEquals("https://a.example.com", reloaded.uploadSettings().url);
        assertEquals("alice", reloaded.uploadSettings().user);
        assertEquals("password", reloaded.uploadSettings().password);
        assertEquals(generation, reloaded.uploadGeneration());
        reloaded.setForegroundInferenceEnabled(false);
        assertFalse(settings.foregroundInferenceEnabled());
    }

    @Test public void failedSaveRestoresCredentialsAndGenerationBeforeAnyWorkerCanUseThem() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        settings.save("https://old.example.test", "alice", false, "old-password");
        long generation = settings.uploadGeneration();
        File file = new File(context.getApplicationInfo().dataDir, "shared_prefs/settings.xml");
        byte[] original = Files.readAllBytes(file.toPath());
        File directory = file.getParentFile();
        assertTrue(directory.setWritable(false, false));
        try {
            assertThrows(IllegalStateException.class,
                    () -> settings.save("https://new.example.test", "bob", true, "new-password"));
            AppSettings.UploadSettings worker = new AppSettings(context).uploadSettings();
            assertEquals("https://old.example.test", worker.url);
            assertEquals("alice", worker.user);
            assertEquals("old-password", worker.password);
            assertEquals(generation, worker.generation);
            assertFalse(settings.foregroundInferenceEnabled());
            assertArrayEquals(original, Files.readAllBytes(file.toPath()));
            try (SyncStateStore states = new SyncStateStore(context)) {
                assertTrue(states.prepareUploadDestination(worker.generation));
                // Recreate the preferences cache to model a fresh process reading disk.
                org.robolectric.util.ReflectionHelpers.setStaticField(
                        Class.forName("android.app.ContextImpl"), "sSharedPrefsCache", null);
                AppSettings restarted = new AppSettings(context);
                assertEquals(generation, restarted.uploadGeneration());
                assertTrue(states.prepareUploadDestination(restarted.uploadGeneration()));
            }
        } finally {
            assertTrue(directory.setWritable(true, false));
        }
        settings = new AppSettings(context);
        settings.save("https://new.example.test", "bob", true, "new-password");
        assertEquals(generation + 1, settings.uploadGeneration());
        assertEquals("new-password", settings.uploadSettings().password);
    }

    @Test public void failedFirstSaveDoesNotLeaveNewCredentialsOrBreakLegacyPasswordFallback() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        // Ensure a real preferences file exists, without new-style credentials.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString("webdav_url", "https://old.example.test").putString("webdav_user", "alice").commit();
        File directory = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        assertTrue(directory.setWritable(false, false));
        try {
            assertThrows(IllegalStateException.class,
                    () -> settings.save("https://new.example.test", "bob", false, "password"));
            assertFalse(context.getSharedPreferences("settings", Context.MODE_PRIVATE).contains(SecretStore.PASSWORD));
            assertEquals("https://old.example.test", settings.webDavUrl());
            assertEquals(0, settings.uploadGeneration());
        } finally { assertTrue(directory.setWritable(true, false)); }
    }

    // Android Keystore is unavailable on the host JVM; only replace encryption here.
    @Implements(value = SecretStore.class, isInAndroidSdk = false)
    public static class TestSecrets {
        @Implementation public String encryptPassword(String password) { return "encrypted:" + password; }
        @Implementation public String getPassword() {
            return RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getString(SecretStore.PASSWORD, "encrypted:").substring("encrypted:".length());
        }
    }
}
