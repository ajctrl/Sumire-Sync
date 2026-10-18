package com.ajctrl.sumiresync.data;

import static org.junit.Assert.*;
import static com.ajctrl.sumiresync.data.StoragePaths.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public final class SyncStateStoreTest {
    private SyncStateStore states;
    private final String fileName = "clipboard-2026-09.sqlite";

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, "sync-state.sqlite"));
        states = new SyncStateStore(context);
    }

    @After public void tearDown() { states.close(); }

    @Test public void missingStateCannotBeRecreatedWhenAV8PartRemains() throws Exception {
        assertLostStateStopsWithoutChangingFiles(true);
    }

    @Test public void emptyStateCannotBeReinitializedWhenAV8PartRemains() throws Exception {
        assertLostStateStopsWithoutChangingFiles(false);
    }

    private void assertLostStateStopsWithoutChangingFiles(boolean missing) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SyncStatus source = new SyncStatus(1, "source", 1, 1);
        states.initializeSource(source);
        String part = "clipboard-2026-10-001.sqlite";
        states.reserveArchiveItem(part, source, 1);
        ClipboardItem item = new ClipboardItem(1, "TEXT", 1790899200000L, false, "saved", Uri.EMPTY);
        new ArchiveStore(context, states).save(source, item,
                new ByteArrayInputStream(new byte[]{42}), null, null, false, () -> {});
        assertEquals(1, states.dirtyArchiveCount());
        states.close();
        File archive = database(context, part);
        byte[] archived = Files.readAllBytes(archive.toPath());
        File state = database(context, "sync-state.sqlite");
        if (missing) assertTrue(state.delete());
        else try (RandomAccessFile file = new RandomAccessFile(state, "rw")) { file.setLength(0); }

        for (int attempt = 0; attempt < 2; attempt++) {
            states = new SyncStateStore(context);
            assertThrows(SQLiteDatabaseCorruptException.class, states::getReadableDatabase);
            assertThrows(SQLiteDatabaseCorruptException.class, states::getWritableDatabase);
            assertThrows(SQLiteDatabaseCorruptException.class, () -> states.markSyncCompleted(0));
            assertThrows(SQLiteDatabaseCorruptException.class, () -> new ArchiveStore(context, states).save(
                    source, item, new ByteArrayInputStream(new byte[]{42}), null, null, false, () -> {}));
            states.close();
            assertEquals(!missing, state.exists());
            assertEquals(0, state.length());
            assertArrayEquals(archived, Files.readAllBytes(archive.toPath()));
            assertFalse(database(context, "clipboard-2026-10.sqlite").exists());
        }
    }

    @Test public void firstCreationAndInterruptedEmptyCreationAreAllowedWithoutV8Archives() throws Exception {
        assertEquals(8, states.getReadableDatabase().getVersion());
        assertTrue(states.archiveFiles().isEmpty());
        states.close();
        try (RandomAccessFile file = new RandomAccessFile(
                database(RuntimeEnvironment.getApplication(), "sync-state.sqlite"), "rw")) {
            file.setLength(0);
        }
        states = new SyncStateStore(RuntimeEnvironment.getApplication());
        assertEquals(8, states.getWritableDatabase().getVersion());
        assertEquals(0, states.read().lastId);
    }

    @Test public void versionZeroDatabaseCannotInitializeOverExistingArchives() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File state = database(context, "sync-state.sqlite");
        try (var db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(state, null)) {
            db.execSQL("CREATE TABLE unfinished (id INTEGER)");
        }
        assertTrue(state.length() > 0);
        File archive = database(context, "clipboard-2026-10.sqlite");
        Files.write(archive.toPath(), new byte[]{42});
        assertThrows(SQLiteDatabaseCorruptException.class, states::read);
        try (var db = android.database.sqlite.SQLiteDatabase.openDatabase(
                state.getPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)) {
            assertEquals(0, db.getVersion());
            assertEquals(0, android.database.DatabaseUtils.longForQuery(db,
                    "SELECT count(*) FROM sqlite_master WHERE name='sync_state'", null));
        }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(archive.toPath()));
    }

    @Test public void corruptStateIsPreservedAndCannotResetProgressOrRewriteArchives() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SyncStatus source = new SyncStatus(1, "source", 1, 2);
        states.initializeSource(source);
        ArchiveStore archives = new ArchiveStore(context, states);
        long createdAt = 1790899200000L;
        ClipboardItem first = new ClipboardItem(1, "TEXT", createdAt, false, "first", Uri.EMPTY);
        ClipboardItem second = new ClipboardItem(2, "TEXT", createdAt, false, "second", Uri.EMPTY);
        archives.save(source, first, new ByteArrayInputStream(new byte[(int) ArchiveStore.MAX_ARCHIVE_BYTES]),
                null, null, false, () -> {});
        archives.save(source, second, new ByteArrayInputStream(new byte[]{2}), null, null, false, () -> {});
        states.advance(2);
        assertEquals(2, states.dirtyArchiveCount());
        File base = database(context, "clipboard-2026-10.sqlite");
        File part = database(context, "clipboard-2026-10-001.sqlite");
        byte[] baseBefore = Files.readAllBytes(base.toPath());
        byte[] partBefore = Files.readAllBytes(part.toPath());
        states.close();
        File state = database(context, "sync-state.sqlite");
        try (RandomAccessFile file = new RandomAccessFile(state, "rw")) { file.write(new byte[32]); }
        byte[] damaged = Files.readAllBytes(state.toPath());

        for (int attempt = 0; attempt < 2; attempt++) {
            states = new SyncStateStore(context);
            assertThrows(SQLiteDatabaseCorruptException.class, states::read);
            assertThrows(SQLiteDatabaseCorruptException.class, () -> states.markSyncCompleted(0));
            assertThrows(SQLiteDatabaseCorruptException.class, () -> new ArchiveStore(context, states).save(
                    source, second, new ByteArrayInputStream(new byte[]{2}), null, null, false, () -> {}));
            states.close();
            assertArrayEquals(damaged, Files.readAllBytes(state.toPath()));
            assertArrayEquals(baseBefore, Files.readAllBytes(base.toPath()));
            assertArrayEquals(partBefore, Files.readAllBytes(part.toPath()));
        }
    }

    @Test public void errorWritesAreBoundedWithoutSplittingUnicodeCharacters() {
        String detail = "𠮷".repeat(3_000);
        states.setUploadError(detail);
        String saved = states.read().lastError;
        assertEquals(2_000, saved.codePointCount(0, saved.length()));
        assertEquals("𠮷".repeat(1_999) + "…", saved);
        states.setUploadError(null);
        states.setLocalError(detail);
        assertEquals(saved, states.read().lastError);
    }

    @Test public void oversizedStoredErrorsAreBoundedWithoutWritesOrOversizedCursorWindows() {
        String oversized = "失".repeat(1_000_000);
        states.getWritableDatabase().execSQL("UPDATE sync_state SET upload_error=?,last_error=?",
                new Object[]{oversized, oversized});
        states.close();
        states = new SyncStateStore(RuntimeEnvironment.getApplication());
        assertEquals(4_001, states.read().lastError.length());
        assertEquals(oversized.length(), android.database.DatabaseUtils.longForQuery(states.getReadableDatabase(),
                "SELECT length(upload_error) FROM sync_state", null));
        assertEquals(oversized.length(), android.database.DatabaseUtils.longForQuery(states.getReadableDatabase(),
                "SELECT length(last_error) FROM sync_state", null));
        states.getWritableDatabase().execSQL("CREATE TRIGGER reject_status_writes BEFORE UPDATE ON sync_state "
                + "BEGIN SELECT RAISE(ABORT, 'status reads must not write'); END");
        states.close();
        states = new SyncStateStore(RuntimeEnvironment.getApplication());
        assertEquals(4_001, states.read().lastError.length());
        states.getWritableDatabase().execSQL("DROP TRIGGER reject_status_writes");
        states.setLocalError(null);
        states.clearUploadError();
        assertNull(states.read().lastError);
    }

    @Test public void archiveReservationAndWriteIntentRollBackTogether() {
        SyncStatus source = new SyncStatus(1, "source", 1, 1);
        states.getWritableDatabase().execSQL("CREATE TRIGGER fail_intent BEFORE INSERT ON pending_archive_writes "
                + "BEGIN SELECT RAISE(ABORT, 'interrupted reservation'); END");
        assertThrows(android.database.sqlite.SQLiteException.class,
                () -> states.reserveArchiveItem(fileName, source, 1));
        assertNull(states.archiveForItem(source, 1));
        assertFalse(states.archiveFiles().contains(fileName));
        assertTrue(states.pendingArchiveWrites().isEmpty());
        states.getWritableDatabase().execSQL("DROP TRIGGER fail_intent");
        states.reserveArchiveItem(fileName, source, 1);
        assertEquals(fileName, states.archiveForItem(source, 1));
        assertEquals(java.util.Collections.singletonList(fileName), states.pendingArchiveWrites());
        assertFalse(states.archiveInitialized(fileName));
    }

    @Test public void gapAndProgressRollBackTogetherWhenProgressWriteFails() {
        states.initializeSource(new SyncStatus(1, "source", 1, 6));
        states.getWritableDatabase().execSQL("CREATE TRIGGER fail_progress BEFORE UPDATE OF last_id "
                + "ON sync_state BEGIN SELECT RAISE(ABORT, 'interrupted'); END");
        assertThrows(android.database.sqlite.SQLiteException.class, () ->
                states.recordGapRangeAndAdvance("source", 1, 1, 6, "deleted"));
        assertEquals(0, states.read().lastId);
        assertEquals(0, android.database.DatabaseUtils.queryNumEntries(states.getReadableDatabase(), "gaps"));
        states.getWritableDatabase().execSQL("DROP TRIGGER fail_progress");
        states.recordGapRangeAndAdvance("source", 1, 1, 6, "deleted");
        assertEquals(6, states.read().lastId);
        assertEquals(6, android.database.DatabaseUtils.longForQuery(states.getReadableDatabase(),
                "SELECT end_source_id FROM gaps", null));
    }

    @Test public void legacyInterruptedGapExpandsOnRetryAndNeverShrinks() {
        states.initializeSource(new SyncStatus(1, "source", 1, 6));
        states.recordGapRange("source", 1, 1, 3, "deleted");
        states.recordGapRangeAndAdvance("source", 1, 1, 6, "deleted");
        states.recordGapRange("source", 1, 1, 2, "deleted");
        assertEquals(6, states.read().lastId);
        assertEquals(1, android.database.DatabaseUtils.queryNumEntries(states.getReadableDatabase(), "gaps"));
        assertEquals(6, android.database.DatabaseUtils.longForQuery(states.getReadableDatabase(),
                "SELECT end_source_id FROM gaps", null));
    }

    @Test public void localSuccessDoesNotClearConcurrentUploadFailure() {
        states.setLocalError("Provider unavailable");
        states.setUploadError("Upload failed");
        states.initializeSource(new SyncStatus(1, "source", 1, 1));
        states.advance(1);
        states.setLocalError(null);
        assertEquals("Upload failed", states.read().lastError);
        states.markSyncCompleted(0);
        assertNull(states.read().lastSyncCompletedAt);
        states.clearUploadError();
        states.markSyncCompleted(0);
        assertNotNull(states.read().lastSyncCompletedAt);
    }

    @Test public void pendingArchiveWriteBlocksCompletionUntilQueuedAndUploaded() {
        states.initializeSource(new SyncStatus(1, "source", 1, 0));
        states.beginArchiveWrite(fileName);
        states.markSyncCompleted(0);
        assertNull(states.read().lastSyncCompletedAt);
        states.finishArchiveWrite(fileName, true);
        assertTrue(states.pendingArchiveWrites().isEmpty());
        states.markSyncCompleted(0);
        assertNull(states.read().lastSyncCompletedAt);
        SyncStateStore.DirtyArchive dirty = states.dirtyArchives().get(0);
        states.markUploaded(dirty.fileName, dirty.revision, 0);
        states.markSyncCompleted(0);
        assertNotNull(states.read().lastSyncCompletedAt);
    }

    @Test public void destinationChangeRequeuesPreviouslyUploadedMonths() {
        long revision = states.markDirty(fileName);
        states.markUploaded(fileName, revision, 0);
        assertEquals(0, states.dirtyArchiveCount());
        assertTrue(states.prepareUploadDestination(1));
        assertEquals(1, states.dirtyArchiveCount());
        states.markUploaded(fileName, revision, 1);
        assertEquals(0, states.dirtyArchiveCount());
    }

    @Test public void namespaceChangeRequeuesCataloguedArchivesOnceAndSurvivesReopen() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ArchiveStore archives = new ArchiveStore(context, states);
        String name = archives.fileNameFor(1790899200000L);
        archives.save(new SyncStatus(1, "source", 1, 1),
                new ClipboardItem(1, "TEXT", 1790899200000L, false, "preview",
                        android.net.Uri.parse("content://source/items/1")),
                new java.io.ByteArrayInputStream(new byte[]{1}), null, null, false, () -> {});
        uploadAll(states);
        assertTrue(states.dirtyArchives().isEmpty());
        states.prepareUploadNamespace("installation-a");
        assertEquals(name, states.dirtyArchives().get(0).fileName);
        uploadAll(states);
        states.close();
        states = new SyncStateStore(context);
        states.prepareUploadNamespace("installation-a");
        assertTrue(states.dirtyArchives().isEmpty());
        // A catalogued archive missing from the outbox must be sent to a new namespace.
        states.getWritableDatabase().execSQL("DELETE FROM dirty_archives");
        states.prepareUploadNamespace("installation-b");
        assertEquals(name, states.dirtyArchives().get(0).fileName);
    }

    @Test public void namespaceChangeDoesNotQueueAnArchiveThatWasNeverInitialized() {
        states.reserveArchiveItem(fileName, new SyncStatus(1, "source", 1, 1), 1);
        states.prepareUploadNamespace("installation-a");
        new ArchiveStore(RuntimeEnvironment.getApplication(), states).recoverPendingWrites();
        assertTrue(states.dirtyArchives().isEmpty());
        assertTrue(states.pendingArchiveWrites().isEmpty());
        assertFalse(states.archiveInitialized(fileName));
        assertTrue(states.archiveFiles().contains(fileName));
    }

    private static void uploadAll(SyncStateStore states) {
        for (SyncStateStore.DirtyArchive file : states.dirtyArchives()) {
            states.markUploaded(file.fileName, file.revision, 0);
        }
    }

    @Test public void interruptedNamespaceChangeRollsBackAndRetries() {
        long revision = states.markDirty(fileName);
        states.markUploaded(fileName, revision, 0);
        states.getWritableDatabase().execSQL("CREATE TRIGGER fail_namespace BEFORE UPDATE OF upload_namespace "
                + "ON sync_state BEGIN SELECT RAISE(ABORT, 'interrupted namespace migration'); END");
        assertThrows(android.database.sqlite.SQLiteException.class,
                () -> states.prepareUploadNamespace("installation-a"));
        assertTrue("outbox reset must roll back with namespace update", states.dirtyArchives().isEmpty());
        states.getWritableDatabase().execSQL("DROP TRIGGER fail_namespace");
        states.close();
        states = new SyncStateStore(RuntimeEnvironment.getApplication());
        states.prepareUploadNamespace("installation-a");
        assertEquals(1, states.dirtyArchiveCount());
        uploadAll(states);
        states.prepareUploadNamespace("installation-a");
        assertTrue(states.dirtyArchives().isEmpty());
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

}
