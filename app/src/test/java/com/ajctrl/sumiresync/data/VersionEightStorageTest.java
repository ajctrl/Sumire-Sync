package com.ajctrl.sumiresync.data;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class VersionEightStorageTest {
    @Test public void oldProgressErrorsAndArchivesAreUntouchedAndNeverImported() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String name = "clipboard-2026-10.sqlite";
        File oldState = context.getDatabasePath("sync-state.sqlite");
        oldState.getParentFile().mkdirs();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(oldState, null)) {
            db.execSQL("CREATE TABLE sync_state(last_id INTEGER,upload_error TEXT)");
            db.execSQL("INSERT INTO sync_state VALUES(100,?)", new Object[]{"失".repeat(1_000_000)});
            db.execSQL("CREATE TABLE dirty_archives(file_name TEXT,revision INTEGER,uploaded_revision INTEGER)");
            db.execSQL("INSERT INTO dirty_archives VALUES(?,1,1)", new Object[]{name});
            db.execSQL("CREATE TABLE pending_archive_writes(file_name TEXT)");
            db.execSQL("INSERT INTO pending_archive_writes VALUES(?)", new Object[]{"clipboard-2026-09.sqlite"});
            db.setVersion(7);
        }
        byte[] oldStateBytes = Files.readAllBytes(oldState.toPath());
        File oldArchive = context.getDatabasePath(name);
        Files.write(oldArchive.toPath(), new byte[]{1, 2, 3}); // Even unreadable old files must not block v8.
        File staging = new File(context.getDatabasePath("clipboard-2026-09.sqlite") + ".initializing");
        Files.write(staging.toPath(), new byte[]{4, 5, 6});
        SyncStatus source = new SyncStatus(1, "source", 1, 1);
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(8, states.getReadableDatabase().getVersion());
            assertEquals(0, states.read().lastId);
            assertNull(states.read().instanceId);
            assertNull(states.read().lastError);
            assertTrue(states.archiveFiles().isEmpty());
            assertTrue(states.pendingArchiveWrites().isEmpty());
            states.prepareUploadNamespace("installation");
            assertTrue(states.dirtyArchives().isEmpty());
            ArchiveStore archives = new ArchiveStore(context, states);
            archives.recoverPendingWrites();
            states.initializeSource(source);
            archives.save(source, new ClipboardItem(1, "TEXT", 1790899200000L, false, "new",
                            Uri.parse("content://source/items/1")),
                    new ByteArrayInputStream(new byte[]{42}), null, null, false, () -> {});
            states.advance(1);
            assertEquals(name, states.archiveForItem(source, 1));
            assertTrue(StoragePaths.database(context, name).isFile());
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertEquals(1, states.read().lastId);
            assertEquals(1, states.archiveFiles().size());
            assertEquals(1, states.dirtyArchiveCount());
            assertTrue(states.pendingArchiveWrites().isEmpty());
        }
        assertArrayEquals(oldStateBytes, Files.readAllBytes(oldState.toPath()));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(oldArchive.toPath()));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(staging.toPath()));
    }
}
