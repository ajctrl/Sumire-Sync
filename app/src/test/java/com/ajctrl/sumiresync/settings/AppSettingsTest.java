package com.ajctrl.sumiresync.settings;

import static org.junit.Assert.*;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, shadows = AppSettingsTest.TestSecrets.class)
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
        assertTrue(next.generation > running.generation);
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
