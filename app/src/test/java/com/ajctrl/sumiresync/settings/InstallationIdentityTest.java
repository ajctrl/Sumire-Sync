package com.ajctrl.sumiresync.settings;

import static org.junit.Assert.*;
import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class InstallationIdentityTest {
    @Test public void updatesAndSettingsChangesKeepIdentityButDataRemovalCreatesANewOne() {
        Context context = RuntimeEnvironment.getApplication();
        String original = InstallationIdentity.get(context);
        assertEquals(original, UUID.fromString(original).toString());
        context.getSharedPreferences("settings", 0).edit().clear().commit();
        assertEquals(original, InstallationIdentity.get(context));
        File file = new File(context.getNoBackupFilesDir(), "installation-id");
        assertTrue(file.isFile());
        assertTrue(file.delete());
        String reinstalled = InstallationIdentity.get(context);
        assertNotEquals(original, reinstalled);
        assertEquals(reinstalled, InstallationIdentity.get(context));
    }

    @Test public void corruptIdentityFailsWithoutSilentlyChangingDestination() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File file = new File(context.getNoBackupFilesDir(), "installation-id");
        Files.write(file.toPath(), "invalid".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IllegalStateException.class, () -> InstallationIdentity.get(context));
        assertEquals("invalid", new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII));
    }
}
