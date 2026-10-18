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
public final class SyncEngineTest {
    private static final String AUTHORITY = "com.example.sumire.syncbridge.clipboard";
    private static final long CREATED_AT = 1790899200000L;
    private Context context;
    private ResettingProvider provider;
    private String archiveName;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, "sync-state.sqlite"));
        try (SyncStateStore states = new SyncStateStore(context)) {
            archiveName = new ArchiveStore(context, states).fileNameFor(CREATED_AT);
        }
        android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, archiveName));
        File content = new File(context.getCacheDir(), "provider-content.txt");
        Files.write(content.toPath(), new byte[70 * 1024]);
        provider = new ResettingProvider(content);
        ProviderTestSupport.registerTrusted(context, AUTHORITY, provider);
    }

    @Test public void resetWhileOpeningContentRollsBackMetadataAndChunks() {
        provider.resetOnOpen = true;
        try (SyncEngine engine = new SyncEngine(context)) {
            SyncEngine.Result result = engine.runBatch(500);
            assertFalse(result.success);
            assertTrue(result.error, result.error.contains("data source changed during content read"));
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(0, states.read().lastId);
            assertEquals(0, states.dirtyArchiveCount());
        }
        try (SQLiteDatabase archive = SQLiteDatabase.openDatabase(
                database(context, archiveName).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            assertEquals(0, DatabaseUtils.queryNumEntries(archive, "clipboard_items"));
            assertEquals(0, DatabaseUtils.queryNumEntries(archive, "clipboard_content_chunks"));
        }
        // The next attempt reconciles the new namespace and can safely archive its item.
        provider.resetOnOpen = false;
        try (SyncEngine engine = new SyncEngine(context)) {
            assertTrue(engine.runBatch(500).success);
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals("new-instance", states.read().instanceId);
            assertEquals(1, states.read().lastId);
        }
    }

    @Test public void missingContentAfterResetIsNotRecordedAsDeletedOldItem() {
        provider.resetOnOpen = true;
        provider.missingOnOpen = true;
        try (SyncEngine engine = new SyncEngine(context)) {
            SyncEngine.Result result = engine.runBatch(500);
            assertFalse(result.success);
            assertTrue(result.error, result.error.contains("data source changed during content read"));
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(0, states.read().lastId);
            assertEquals(0, DatabaseUtils.queryNumEntries(states.getReadableDatabase(), "gaps"));
        }
    }

    @Test public void stableSourceCommitsContentAndProgress() {
        try (SyncEngine engine = new SyncEngine(context)) {
            SyncEngine.Result result = engine.runBatch(500);
            assertTrue(result.error, result.success);
            assertEquals(1, result.saved);
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(1, states.read().lastId);
            assertEquals(1, states.dirtyArchiveCount());
        }
    }

    @Test public void nullStreamAfterProviderCrashRetriesWithoutSkippingExistingItem() throws Exception {
        Uri contentUri = Uri.parse("content://" + AUTHORITY + "/items/1/content");
        provider.nullOnOpen = true;
        assertNull(context.getContentResolver().openInputStream(contentUri));
        try (SyncEngine engine = new SyncEngine(context)) {
            assertFalse(engine.runBatch(500).success);
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(0, states.read().lastId);
            assertEquals(0, DatabaseUtils.queryNumEntries(states.getReadableDatabase(), "gaps"));
        }
        provider.nullOnOpen = false;
        try (SyncEngine engine = new SyncEngine(context)) {
            SyncEngine.Result recovered = engine.runBatch(500);
            assertTrue(recovered.error, recovered.success);
            assertEquals(1, recovered.saved);
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(1, states.read().lastId);
            assertEquals(1, states.dirtyArchiveCount());
        }
    }

    private static final class ResettingProvider extends ContentProvider {
        private final File content;
        boolean resetOnOpen;
        boolean missingOnOpen;
        boolean nullOnOpen;
        String instance = "old-instance";

        ResettingProvider(File content) { this.content = content; }
        @Override public boolean onCreate() { return true; }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                                      String[] args, String order) {
            if ("/status".equals(uri.getPath())) {
                MatrixCursor cursor = new MatrixCursor(new String[]{"apiVersion", "databaseInstanceId",
                        "clipboardGeneration", "currentSequence"});
                cursor.addRow(new Object[]{1, instance, 1, 1L});
                return cursor;
            }
            MatrixCursor cursor = new MatrixCursor(new String[]{"id", "itemType", "createdAt",
                    "isPinned", "preview", "contentUri"});
            if ("0".equals(uri.getQueryParameter("afterId"))) {
                cursor.addRow(new Object[]{1L, "TEXT", CREATED_AT, 0, "preview",
                        "content://" + AUTHORITY + "/items/1/content"});
            }
            return cursor;
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (nullOnOpen) return null;
            if (resetOnOpen) instance = "new-instance";
            if (missingOnOpen) throw new FileNotFoundException("reset during read");
            return ParcelFileDescriptor.open(content, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
