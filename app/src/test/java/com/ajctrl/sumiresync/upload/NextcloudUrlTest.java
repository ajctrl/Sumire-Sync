package com.ajctrl.sumiresync.upload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;

import org.junit.Test;

public final class NextcloudUrlTest {
    private static final String INSTALLATION = "11111111-1111-4111-8111-111111111111";
    @Test public void archiveUrlAddsNextcloudPathUsernameAndSumireFolder() throws Exception {
        assertEquals(
                "https://url.com:8443/remote.php/dav/files/tanaka/Sumire/" + INSTALLATION + "/archive.sqlite",
                NextcloudUrl.archiveUrl("https://url.com:8443", "tanaka", INSTALLATION, "archive.sqlite")
                        .toString());
    }

    @Test public void archiveUrlEncodesUsernameAsOnePathSegment() throws Exception {
        assertEquals(
                "https://url.com/remote.php/dav/files/user%2Fname%40example.com/Sumire/" + INSTALLATION + "/a%20b.sqlite",
                NextcloudUrl.archiveUrl(
                        "https://url.com/", "user/name@example.com", INSTALLATION, "a b.sqlite").toString());
    }

    @Test public void serverOriginMigratesPreviouslySavedFullWebDavUrl() {
        assertEquals(
                "https://cloud.example.com",
                NextcloudUrl.serverOrigin(
                        "https://cloud.example.com/remote.php/dav/files/user/Sumire/"));
    }

    @Test public void serverOriginRejectsHttp() {
        assertThrows(IllegalArgumentException.class,
                () -> NextcloudUrl.serverOrigin("http://url.com:8080"));
    }

    @Test public void archiveUrlRejectsMissingUsername() {
        assertThrows(IOException.class,
                () -> NextcloudUrl.archiveUrl("https://url.com", "  ", INSTALLATION, "archive.sqlite"));
    }

    @Test public void archiveUrlNeverFallsBackToSharedFolderForInvalidIdentity() {
        for (String id : new String[]{null, "", ".", "..", "../other"}) {
            assertThrows(IOException.class,
                    () -> NextcloudUrl.archiveUrl("https://url.com", "user", id, "archive.sqlite"));
        }
    }
}
