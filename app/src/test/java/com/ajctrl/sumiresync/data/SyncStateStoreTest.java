package com.ajctrl.sumiresync.data;

import static org.junit.Assert.*;

import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class SyncStateStoreTest {
    private SyncStateStore states;
    private final String fileName = "clipboard-2026-09.sqlite";

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("sync-state.sqlite");
        states = new SyncStateStore(context);
    }

    @After public void tearDown() { states.close(); }

    @Test public void destinationChangeRequeuesPreviouslyUploadedMonths() {
        long revision = states.markDirty(fileName);
        states.markUploaded(fileName, revision, 0);
        assertEquals(0, states.dirtyArchiveCount());
        assertTrue(states.prepareUploadDestination(1));
        assertEquals(1, states.dirtyArchiveCount());
        states.markUploaded(fileName, revision, 1);
        assertEquals(0, states.dirtyArchiveCount());
    }

    @Test public void staleWorkerCannotAcknowledgeOrRevertNewDestination() {
        long revision = states.markDirty(fileName);
        assertTrue(states.prepareUploadDestination(2));
        states.markUploaded(fileName, revision, 1);
        assertEquals(1, states.dirtyArchiveCount());
        assertFalse(states.prepareUploadDestination(1));
        states.markUploaded(fileName, revision, 2);
        assertEquals(0, states.dirtyArchiveCount());
    }

    @Test public void sameDestinationDoesNotRequeueCompletedFiles() {
        long revision = states.markDirty(fileName);
        states.prepareUploadDestination(1);
        states.markUploaded(fileName, revision, 1);
        states.prepareUploadDestination(1);
        assertEquals(0, states.dirtyArchiveCount());
    }

    @Test public void savingDuringUploadKeepsArchiveDirty() {
        long revision = states.markDirty(fileName);
        states.markDirty(fileName);
        states.markUploaded(fileName, revision, 0);
        assertEquals(1, states.dirtyArchiveCount());
    }

    @Test public void completionWaitsForLocalCatchUpAndAllUploads() {
        states.initializeSource(new SyncStatus(1, "source", 1, 1));
        long request = states.requestSync();
        long revision = states.markDirty(fileName);
        long otherRevision = states.markDirty("clipboard-2026-10.sqlite");
        states.advance(1);
        states.markSyncCompleted(0);
        assertNull(states.read().lastSyncCompletedAt);

        states.markRequestHandled(request);
        states.markUploaded(fileName, revision, 0);
        states.markSyncCompleted(0);
        assertNull(states.read().lastSyncCompletedAt);

        states.markUploaded("clipboard-2026-10.sqlite", otherRevision, 0);
        states.markSyncCompleted(0);
        Long completedAt = states.read().lastSyncCompletedAt;
        assertNotNull(completedAt);
        states.close();
        states = new SyncStateStore(RuntimeEnvironment.getApplication());
        assertEquals(completedAt, states.read().lastSyncCompletedAt);
    }

    @Test public void uploadFinishingBeforeCatchUpNeedsCompletionAfterAcknowledgement() {
        states.initializeSource(new SyncStatus(1, "source", 1, 1));
        long request = states.requestSync();
        long revision = states.markDirty(fileName);
        states.markUploaded(fileName, revision, 0);
        states.markSyncCompleted(0);
        assertNull(states.read().lastSyncCompletedAt);
        states.markRequestHandled(request);
        states.markSyncCompleted(0);
        assertNotNull(states.read().lastSyncCompletedAt);
    }

    @Test public void incompleteOrFailedSyncKeepsPreviousCompletionTime() {
        states.initializeSource(new SyncStatus(1, "source", 1, 0));
        states.getWritableDatabase().execSQL("UPDATE sync_state SET last_sync_completed_at=1");
        long request = states.requestSync();
        states.markSyncCompleted(0);
        assertEquals(Long.valueOf(1), states.read().lastSyncCompletedAt);
        states.markRequestHandled(request);

        long revision = states.markDirty(fileName);
        states.markDirty(fileName);
        states.markUploaded(fileName, revision, 0);
        states.markSyncCompleted(0);
        assertEquals(Long.valueOf(1), states.read().lastSyncCompletedAt);
        states.markUploaded(fileName, revision + 1, 0);

        states.setError("Upload: failed");
        states.markSyncCompleted(0);
        assertEquals(Long.valueOf(1), states.read().lastSyncCompletedAt);
        states.clearUploadError();
        states.setError("Provider unavailable");
        states.clearUploadError();
        states.markSyncCompleted(0);
        assertEquals(Long.valueOf(1), states.read().lastSyncCompletedAt);
    }

    @Test public void emptySyncRequiresSourceAndCurrentDestination() {
        states.markSyncCompleted(0);
        assertNull(states.read().lastSyncCompletedAt);
        states.initializeSource(new SyncStatus(1, "source", 1, 0));
        states.prepareUploadDestination(1);
        states.markSyncCompleted(0);
        assertNull(states.read().lastSyncCompletedAt);
        states.markSyncCompleted(1);
        assertNotNull(states.read().lastSyncCompletedAt);
        assertNull(states.read().lastSyncAt);

        states.getWritableDatabase().execSQL("UPDATE sync_state SET last_sync_completed_at=1");
        long request = states.requestSync();
        states.markRequestHandled(request);
        states.markSyncCompleted(1);
        assertTrue(states.read().lastSyncCompletedAt > 1);
    }

    @Test public void versionFourMigrationPreservesLocalTimeWithoutInventingCompletionTime() {
        states.initializeSource(new SyncStatus(1, "source", 1, 7));
        states.advance(7);
        Long localTime = states.read().lastSyncAt;
        long revision = states.markDirty(fileName);
        states.markUploaded(fileName, revision, 0);
        states.getWritableDatabase().execSQL("ALTER TABLE sync_state RENAME TO previous_state");
        states.getWritableDatabase().execSQL("CREATE TABLE sync_state AS SELECT singleton, api_version, "
                + "database_instance_id, clipboard_generation, last_id, last_error, last_sync_at, "
                + "request_revision, handled_request_revision, upload_generation FROM previous_state");
        states.getWritableDatabase().execSQL("DROP TABLE previous_state");
        states.getWritableDatabase().setVersion(4);
        states.close();
        states = new SyncStateStore(RuntimeEnvironment.getApplication());
        assertEquals(localTime, states.read().lastSyncAt);
        assertEquals(7, states.read().lastId);
        assertEquals(0, states.dirtyArchiveCount());
        assertNull(states.read().lastSyncCompletedAt);
        states.markSyncCompleted(0);
        assertNotNull(states.read().lastSyncCompletedAt);
    }

    @Test public void legacyDatabaseMigrationPreservesCompletedUploads() {
        states.getWritableDatabase().execSQL("ALTER TABLE sync_state RENAME TO previous_state");
        states.getWritableDatabase().execSQL("CREATE TABLE sync_state AS SELECT singleton, api_version, "
                + "database_instance_id, clipboard_generation, last_id, last_error, last_sync_at, "
                + "request_revision, handled_request_revision FROM previous_state");
        states.getWritableDatabase().execSQL("DROP TABLE previous_state");
        long revision = states.markDirty(fileName);
        states.getWritableDatabase().execSQL("UPDATE dirty_archives SET uploaded_revision=revision");
        states.getWritableDatabase().setVersion(3);
        states.close();
        states = new SyncStateStore(RuntimeEnvironment.getApplication());
        assertNull(states.read().lastSyncCompletedAt);
        assertTrue(states.prepareUploadDestination(0));
        assertEquals(0, states.dirtyArchiveCount());
        states.prepareUploadDestination(1);
        assertEquals(revision, states.dirtyArchives().get(0).revision);
    }
}
