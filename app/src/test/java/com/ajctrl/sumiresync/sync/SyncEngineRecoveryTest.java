package com.ajctrl.sumiresync.sync;

import static org.junit.Assert.*;
import static com.ajctrl.sumiresync.data.StoragePaths.database;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import com.ajctrl.sumiresync.data.ArchiveStore;
import com.ajctrl.sumiresync.data.SyncStateStore;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public final class SyncEngineRecoveryTest {
    private static final String AUTHORITY = "com.example.sumire.syncbridge.clipboard";
    private static final long CREATED_AT = 1790899200000L;
    private Context context;
    private TestProvider provider;
    private String archiveName;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, "sync-state.sqlite"));
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit();
        try (SyncStateStore states = new SyncStateStore(context)) {
            archiveName = new ArchiveStore(context, states).fileNameFor(CREATED_AT);
        }
        android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, archiveName));
        File content = new File(context.getCacheDir(), "provider-review-content.txt");
        Files.write(content.toPath(), new byte[70000]);
        provider = new TestProvider(content);
        ProviderTestSupport.registerTrusted(context, AUTHORITY, provider);
    }

    @Test public void archiveCommittedBeforeDirtyFailureIsRecoveredEvenAfterSourceDeletion() {
        // Fail only the state-DB write after ArchiveStore commits its own separate database.
        try (SyncStateStore states = new SyncStateStore(context)) {
            states.getWritableDatabase().execSQL("CREATE TRIGGER simulate_dirty_write_failure BEFORE INSERT ON dirty_archives BEGIN SELECT RAISE(ABORT, 'simulate state write failure'); END");
        }
        try (SyncEngine engine = new SyncEngine(context)) {
            assertFalse(engine.runBatch(500).success);
        }
        try (SQLiteDatabase archive = SQLiteDatabase.openDatabase(database(context, archiveName).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            assertEquals(1, DatabaseUtils.queryNumEntries(archive, "clipboard_items"));
            assertTrue(DatabaseUtils.queryNumEntries(archive, "clipboard_content_chunks") > 0);
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(0, states.read().lastId);
            assertEquals(0, states.dirtyArchiveCount());
            assertEquals(1, states.pendingArchiveWrites().size());
            states.markSyncCompleted(0);
            assertNull(states.read().lastSyncCompletedAt);
            states.getWritableDatabase().execSQL("DROP TRIGGER simulate_dirty_write_failure");
        }
        provider.itemPresent = false;
        try (SyncEngine engine = new SyncEngine(context)) {
            assertTrue(engine.runBatch(500).success);
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(1, states.read().lastId);
            // A gap describes what this query could not retrieve, not archive loss.
            assertEquals("not returned by provider", DatabaseUtils.stringForQuery(
                    states.getReadableDatabase(), "SELECT reason FROM gaps WHERE source_id=1", null));
            assertEquals(1, states.dirtyArchiveCount());
            assertTrue(states.pendingArchiveWrites().isEmpty());
            states.markSyncCompleted(0);
            assertNull(states.read().lastSyncCompletedAt);
            SyncStateStore.DirtyArchive dirty = states.dirtyArchives().get(0);
            states.markUploaded(dirty.fileName, dirty.revision, 0);
            states.markSyncCompleted(0);
            assertNotNull(states.read().lastSyncCompletedAt);
        }
    }

    @Test public void invalidTimestampDoesNotPoisonRecoveryAfterSourceDeletion() {
        provider.createdAt = 253402300800000L;
        try (SyncEngine engine = new SyncEngine(context)) {
            assertFalse(engine.runBatch(500).success);
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(0, states.read().lastId);
            assertTrue(states.pendingArchiveWrites().isEmpty());
            assertTrue(states.archiveFiles().isEmpty());
        }
        provider.itemPresent = false;
        try (SyncEngine engine = new SyncEngine(context)) {
            assertTrue(engine.runBatch(500).success);
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(1, states.read().lastId);
            assertNull(states.read().lastError);
        }
    }

    @Test public void legacyInvalidReservationIsRemovedAndCorrectedItemCanBeSaved() {
        String invalid = "clipboard-10000-01.sqlite";
        try (SyncStateStore states = new SyncStateStore(context)) {
            // Reproduce metadata written by the old reserve-before-validation order.
            states.getWritableDatabase().execSQL("INSERT INTO archive_files VALUES(?,0)", new Object[]{invalid});
            states.getWritableDatabase().execSQL("INSERT INTO archive_item_locations VALUES('source',1,1,?)",
                    new Object[]{invalid});
            states.beginArchiveWrite(invalid);
        }
        try (SyncEngine engine = new SyncEngine(context)) {
            SyncEngine.Result result = engine.runBatch(500);
            assertTrue(result.error, result.success);
            assertEquals(1, result.saved);
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(1, states.read().lastId);
            assertTrue(states.pendingArchiveWrites().isEmpty());
            assertEquals(java.util.Collections.singletonList(archiveName), states.archiveFiles());
            // A worker that read the old pending list before recovery can finish harmlessly.
            states.discardInvalidArchiveReservation(invalid);
            assertEquals(archiveName, states.archiveForItem(new com.ajctrl.sumiresync.data.SyncStatus(1, "source", 1, 1), 1));
            assertEquals(1, states.dirtyArchiveCount());
        }
    }

    @Test public void corruptStateReturnsFailureEvenWhenErrorCannotBePersisted() throws Exception {
        try (SyncStateStore states = new SyncStateStore(context)) { states.requestSync(); }
        File state = database(context, "sync-state.sqlite");
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(state, "rw")) {
            file.write(new byte[32]);
        }
        byte[] damaged = Files.readAllBytes(state.toPath());
        try (SyncEngine engine = new SyncEngine(context)) {
            SyncEngine.Result result = engine.runBatch(500);
            assertFalse(result.success);
            assertTrue(result.error.contains("同期管理DBの破損"));
        }
        assertArrayEquals(damaged, Files.readAllBytes(state.toPath()));
        assertFalse(database(context, archiveName).exists());
    }

    @Test public void providerErrorClearsAfterSuccessfulEmptyAutomaticRetry() {
        try (SyncEngine engine = new SyncEngine(context)) { assertTrue(engine.runBatch(500).success); }
        try (SyncStateStore states = new SyncStateStore(context)) {
            SyncStateStore.DirtyArchive dirty = states.dirtyArchives().get(0);
            states.markUploaded(dirty.fileName, dirty.revision, 0);
        }
        provider.available = false;
        try (SyncEngine engine = new SyncEngine(context)) { assertFalse(engine.runBatch(500).success); }
        provider.available = true;
        try (SyncEngine engine = new SyncEngine(context)) { assertTrue(engine.runBatch(500).success); }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertNull(states.read().lastError);
            states.markSyncCompleted(0);
            assertNotNull(states.read().lastSyncCompletedAt);
        }
    }

    @Test public void retryExpandsLegacyGapBeforeAdvancingPastNewDeletions() {
        try (SyncStateStore states = new SyncStateStore(context)) {
            states.initializeSource(new com.ajctrl.sumiresync.data.SyncStatus(1, "source", 1, 3));
            states.recordGapRange("source", 1, 1, 3, "deleted");
        }
        provider.itemPresent = false;
        provider.sequence = 6;
        try (SyncEngine engine = new SyncEngine(context)) {
            assertTrue(engine.runBatch(500).success);
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(6, states.read().lastId);
            assertEquals(6, DatabaseUtils.longForQuery(states.getReadableDatabase(),
                    "SELECT end_source_id FROM gaps", null));
        }
    }

    private static final class TestProvider extends ContentProvider {
        private final File content;
        boolean available = true;
        boolean itemPresent = true;
        long sequence = 1;
        long createdAt = CREATED_AT;
        TestProvider(File content) { this.content = content; }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            if (!available) throw new IllegalStateException("transient provider failure");
            if ("/status".equals(uri.getPath())) {
                MatrixCursor cursor = new MatrixCursor(new String[]{"apiVersion", "databaseInstanceId", "clipboardGeneration", "currentSequence"});
                cursor.addRow(new Object[]{1, "source", 1, sequence}); return cursor;
            }
            MatrixCursor cursor = new MatrixCursor(new String[]{"id", "itemType", "createdAt", "isPinned", "preview", "contentUri"});
            if (itemPresent && "0".equals(uri.getQueryParameter("afterId"))) cursor.addRow(new Object[]{1L, "TEXT", createdAt, 0, "preview", "content://"+AUTHORITY+"/items/1/content"});
            return cursor;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException { return ParcelFileDescriptor.open(content, ParcelFileDescriptor.MODE_READ_ONLY); }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
