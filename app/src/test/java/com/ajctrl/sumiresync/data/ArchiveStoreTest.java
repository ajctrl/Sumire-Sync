package com.ajctrl.sumiresync.data;

import static org.junit.Assert.*;
import static com.ajctrl.sumiresync.data.StoragePaths.database;
import android.content.Context;
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
public class ArchiveStoreTest {
    @Test public void unsupportedDatesNeverCreateReservationsOrArchives() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            assertEquals("clipboard-1970-01.sqlite", archives.fileNameFor(0));
            assertEquals("clipboard-9999-12.sqlite", archives.fileNameFor(253402300799999L));
            for (long created : new long[]{-1, 253402300800000L, Long.MAX_VALUE}) {
                assertThrows(IllegalArgumentException.class,
                        () -> save(archives, new SyncStatus(1, "source", 1, 1), created, 1));
            }
            assertTrue(states.archiveFiles().isEmpty());
            assertTrue(states.pendingArchiveWrites().isEmpty());
            assertEquals(0, states.dirtyArchiveCount());
        }
    }

    @Test public void recoveryRetainsInvalidReservationsWithEvidenceOfAnExistingArchive() {
        Context context = RuntimeEnvironment.getApplication();
        String invalid = "clipboard-10000-01.sqlite";
        try (SyncStateStore states = new SyncStateStore(context)) {
            states.getWritableDatabase().execSQL("INSERT INTO archive_files VALUES(?,1)", new Object[]{invalid});
            states.beginArchiveWrite(invalid);
            assertThrows(android.database.sqlite.SQLiteException.class,
                    () -> new ArchiveStore(context, states).recoverPendingWrites());
            assertEquals(java.util.Collections.singletonList(invalid), states.pendingArchiveWrites());
            states.getWritableDatabase().execSQL("UPDATE archive_files SET initialized=0");
            states.markDirty(invalid);
            assertThrows(android.database.sqlite.SQLiteException.class,
                    () -> new ArchiveStore(context, states).recoverPendingWrites());
            assertEquals(1, states.dirtyArchiveCount());
            assertEquals(java.util.Collections.singletonList(invalid), states.pendingArchiveWrites());
        }
    }

    @Test public void alreadyCancelledSaveDoesNotReserveOrCreateAnArchive() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            ClipboardItem item = new ClipboardItem(1, "TEXT", 1790899200000L, false, "preview",
                    Uri.parse("content://source/items/1"));
            assertThrows(java.io.IOException.class, () -> archives.save(new SyncStatus(1, "source", 1, 1),
                    item, new ByteArrayInputStream(new byte[]{1}), null, null, false, () -> {}, () -> true));
            assertTrue(states.archiveFiles().isEmpty());
            assertTrue(states.pendingArchiveWrites().isEmpty());
            assertFalse(database(context, archives.fileNameFor(item.createdAt)).exists());
        }
    }

    @Test public void interruptedInitializationIsDiscardedAndRetriedWithoutPublishingEmptyArchive() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            long created = 1790899200000L;
            String name = archives.fileNameFor(created);
            File staging = new File(database(context, name).getPath() + ".initializing");
            states.beginArchiveWrite(name);
            // A process death rolls back the schema transaction but leaves its file.
            try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(staging, null)) {
                db.beginTransaction();
                try {
                    db.execSQL("CREATE TABLE unfinished (id INTEGER)");
                    db.setVersion(3);
                } finally { db.endTransaction(); }
                assertEquals(0, db.getVersion());
            }
            archives.recoverPendingWrites();
            assertTrue(states.dirtyArchives().isEmpty());
            assertFalse(database(context, name).exists());
            save(archives, new SyncStatus(1, "source", 1, 1), created, 1);
            assertFalse(staging.exists());
            File snapshot = archives.snapshot(name);
            try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(snapshot.getPath(), null, 1)) {
                assertTrue(db.isDatabaseIntegrityOk());
                assertEquals(3, db.getVersion());
                assertEquals(1, android.database.DatabaseUtils.queryNumEntries(db, "clipboard_items"));
            } finally { ArchiveStore.deleteSnapshot(snapshot); }
        }
    }

    @Test public void tenMegabyteRolloverPreservesSealedPartsAndRetriesAcrossRestart() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            long created = 1790899200000L;
            SyncStatus source = new SyncStatus(1, "source", 1, 3);
            String base = archives.fileNameFor(created);
            String part = "clipboard-2026-10-001.sqlite";
            byte[] large = new byte[(int) ArchiveStore.MAX_ARCHIVE_BYTES];
            archives.save(source, new ClipboardItem(1, "TEXT", created, false, "large", Uri.parse("content://source/items/1")),
                    new ByteArrayInputStream(large), null, null, false, () -> {});
            File sealed = database(context, base);
            assertTrue(sealed.length() >= 10_000_000L);
            byte[] sealedBytes = Files.readAllBytes(sealed.toPath());
            archives = new ArchiveStore(context, states); // No in-memory rollover state.
            save(archives, source, created, 2);
            save(archives, source, created, 3);
            assertArrayEquals(sealedBytes, Files.readAllBytes(sealed.toPath()));
            assertTrue(database(context, part).isFile());
            assertFalse(database(context, "clipboard-2026-10-002.sqlite").exists());
            // Retrying an older committed ID must find the sealed file, not duplicate it.
            save(archives, source, created, 1);
            for (String name : new String[]{base, part}) {
                File snapshot = archives.snapshot(name);
                try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(snapshot.getPath(), null, 1)) {
                    assertTrue(db.isDatabaseIntegrityOk());
                    assertEquals(name.equals(base) ? 1 : 2, android.database.DatabaseUtils.queryNumEntries(db, "clipboard_items"));
                    if (name.equals(base)) assertEquals(large.length, android.database.DatabaseUtils.longForQuery(db,
                            "SELECT sum(length(content)) FROM clipboard_content_chunks", null));
                } finally { ArchiveStore.deleteSnapshot(snapshot); }
            }
            assertEquals(2, states.dirtyArchiveCount());
            assertTrue(ArchiveStore.isArchiveFileName(part));
            assertFalse(ArchiveStore.isArchiveFileName(part + ".initializing"));
            assertFalse(ArchiveStore.isArchiveFileName("../" + part));
        }
    }

    @Test public void partFilesRecoverPendingWritesAndCleanAbandonedSnapshots() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            long created = 1790899200000L;
            SyncStatus source = new SyncStatus(1, "source", 1, 1);
            save(archives, source, created, 1);
            String part = "clipboard-2026-10-001.sqlite";
            assertTrue(database(context, archives.fileNameFor(created)).renameTo(database(context, part)));
            states.getWritableDatabase().execSQL("DELETE FROM dirty_archives");
            states.getWritableDatabase().execSQL("UPDATE archive_item_locations SET file_name=?", new Object[]{part});
            states.getWritableDatabase().execSQL("UPDATE archive_files SET file_name=?", new Object[]{part});
            states.beginArchiveWrite(part);
            archives.recoverPendingWrites();
            assertEquals(part, states.dirtyArchives().get(0).fileName);
            states.markUploaded(part, states.dirtyArchives().get(0).revision, 0);
            states.prepareUploadNamespace("installation");
            assertEquals(part, states.dirtyArchives().get(0).fileName);
            // A reserved identity keeps its file even if a retry reports a different timestamp.
            save(archives, source, created - 60_000, 1);
            assertFalse(database(context, "clipboard-2026-09.sqlite").exists());
            File abandoned = archives.snapshot(part);
            archives.clearAbandonedSnapshots();
            assertFalse(abandoned.exists());
            assertFalse(abandoned.getParentFile().exists());
        }
    }

    @Test public void fullNumberedPartRollsOverAgainAndDuplicateKeepsItsOriginalPart() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            SyncStatus source = new SyncStatus(1, "source", 1, 3);
            long created = 1790899200000L;
            byte[] large = new byte[(int) ArchiveStore.MAX_ARCHIVE_BYTES];
            for (int id = 1; id <= 2; id++) {
                archives.save(source, new ClipboardItem(id, "TEXT", created, false, "large", Uri.parse("content://source/items/" + id)),
                        new ByteArrayInputStream(large), null, null, false, () -> {});
            }
            save(archives, source, created, 3);
            save(new ArchiveStore(context, states), source, created, 2);
            for (String name : new String[]{"clipboard-2026-10.sqlite", "clipboard-2026-10-001.sqlite", "clipboard-2026-10-002.sqlite"}) {
                try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(
                        database(context, name).getPath(), null, 1)) {
                    assertEquals(1, android.database.DatabaseUtils.queryNumEntries(db, "clipboard_items"));
                }
            }
            assertFalse(database(context, "clipboard-2026-10-003.sqlite").exists());
            assertEquals(3, states.dirtyArchiveCount());
        }
    }

    @Test public void corruptArchiveIsPreservedAcrossSnapshotAndSaveAttempts() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            SyncStatus source = new SyncStatus(1, "source", 1, 2);
            long created = 1790899200000L;
            save(archives, source, created, 1);
            File file = database(context, archives.fileNameFor(created));
            try (java.io.RandomAccessFile damage = new java.io.RandomAccessFile(file, "rw")) {
                damage.write(new byte[32]);
            }
            byte[] damaged = Files.readAllBytes(file.toPath());
            assertThrows(android.database.sqlite.SQLiteException.class,
                    () -> archives.snapshot(file.getName()));
            assertArrayEquals(damaged, Files.readAllBytes(file.toPath()));
            assertThrows(android.database.sqlite.SQLiteException.class,
                    () -> save(archives, source, created, 2));
            assertArrayEquals(damaged, Files.readAllBytes(file.toPath()));
            assertEquals(1, states.dirtyArchiveCount());
        }
    }

    @Test public void existingEmptyFileIsNotTreatedAsANewArchive() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            long created = 1790899200000L;
            File file = database(context, archives.fileNameFor(created));
            Files.write(file.toPath(), new byte[0]);
            assertThrows(android.database.sqlite.SQLiteException.class,
                    () -> save(archives, new SyncStatus(1, "source", 1, 1), created, 1));
            assertTrue(file.exists());
            assertEquals(0, file.length());
        }
    }

    @Test public void corruptDataPageIsRejectedEvenWhenTheSchemaCanBeRead() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            long created = 1790899200000L;
            save(archives, new SyncStatus(1, "source", 1, 1), created, 1);
            File file = database(context, archives.fileNameFor(created));
            long pageSize, rootPage;
            try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    file.getPath(), null, 1)) {
                pageSize = android.database.DatabaseUtils.longForQuery(db, "PRAGMA page_size", null);
                rootPage = android.database.DatabaseUtils.longForQuery(db,
                        "SELECT rootpage FROM sqlite_master WHERE name='clipboard_items'", null);
            }
            try (java.io.RandomAccessFile damage = new java.io.RandomAccessFile(file, "rw")) {
                damage.seek((rootPage - 1) * pageSize);
                damage.write(255); // Invalid b-tree page type; the database header is still valid.
            }
            byte[] damaged = Files.readAllBytes(file.toPath());
            assertThrows(android.database.sqlite.SQLiteException.class,
                    () -> archives.snapshot(file.getName()));
            assertArrayEquals(damaged, Files.readAllBytes(file.toPath()));
        }
    }

    @Test public void cancellationDuringContentRollsBackChunksAndAllowsRetry() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            long created = 1790899200000L;
            SyncStatus source = new SyncStatus(1, "source", 1, 1);
            ClipboardItem item = new ClipboardItem(1, "TEXT", created, false, "preview",
                    Uri.parse("content://source/items/1"));
            java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
            ByteArrayInputStream input = new ByteArrayInputStream(new byte[8 * 1024 * 1024]) {
                @Override public synchronized int read(byte[] buffer, int offset, int length) {
                    if (reads.incrementAndGet() == 2) stopped.set(true);
                    return super.read(buffer, offset, length);
                }
            };
            assertThrows(java.io.IOException.class, () -> archives.save(source, item, input,
                    null, null, false, () -> {}, stopped::get));
            assertEquals(2, reads.get());
            try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    database(context, archives.fileNameFor(created)).getPath(), null, 1)) {
                assertEquals(0, android.database.DatabaseUtils.queryNumEntries(db, "clipboard_items"));
                assertEquals(0, android.database.DatabaseUtils.queryNumEntries(db, "clipboard_content_chunks"));
            }
            assertEquals(0, states.dirtyArchiveCount());
            save(archives, source, created, 1);
            assertEquals(1, states.dirtyArchiveCount());
        }
    }

    @Test public void interruptedSaveRemainsIdempotentAfterTimezoneChange() throws Exception {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            SyncStatus source = new SyncStatus(1, "source", 1, 1);
            long created = 1790809800000L; // 2026-09-30 23:10 UTC
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            states.getWritableDatabase().execSQL("CREATE TRIGGER fail_dirty BEFORE INSERT ON dirty_archives BEGIN SELECT RAISE(ABORT, 'interrupted'); END");
            assertThrows(android.database.sqlite.SQLiteException.class, () -> save(archives, source, created, 1));
            states.getWritableDatabase().execSQL("DROP TRIGGER fail_dirty");
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            archives.recoverPendingWrites();
            save(archives, source, created, 1);
            assertEquals("clipboard-2026-09.sqlite", archives.fileNameFor(created));
            assertFalse(database(context, "clipboard-2026-10.sqlite").exists());
            try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    database(context, archives.fileNameFor(created)).getPath(), null, 1)) {
                assertEquals(1, android.database.DatabaseUtils.queryNumEntries(db, "clipboard_items"));
            }
        } finally { java.util.TimeZone.setDefault(original); }
    }

    @Test public void snapshotsRemainIndependentAcrossWritesAndDeletion() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            SyncStatus source = new SyncStatus(1, "source", 1, 2);
            long created = 1790899200000L;
            String name = archives.fileNameFor(created);
            save(archives, source, created, 1);
            File first = archives.snapshot(name);
            byte[] original = Files.readAllBytes(first.toPath());
            save(archives, source, created, 2);
            File second = archives.snapshot(name);
            try {
                assertNotEquals(first, second);
                assertEquals(name, second.getName());
                assertFalse(java.util.Arrays.equals(original, Files.readAllBytes(second.toPath())));
                assertArrayEquals(original, Files.readAllBytes(first.toPath()));
                ArchiveStore.deleteSnapshot(second);
                assertArrayEquals(original, Files.readAllBytes(first.toPath()));
            } finally {
                ArchiveStore.deleteSnapshot(first);
                ArchiveStore.deleteSnapshot(second);
            }
        }
    }

    @Test public void missingUploadedLatestPartIsNeverRecreatedOrReusedAfterRestart() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        long created = 1792022400000L;
        SyncStatus source = new SyncStatus(1, "source", 1, 3);
        String missing = "clipboard-2026-10-001.sqlite";
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            archives.save(source, new ClipboardItem(1, "TEXT", created, false, "large", Uri.parse("content://source/items/1")),
                    new ByteArrayInputStream(new byte[(int) ArchiveStore.MAX_ARCHIVE_BYTES]), null, null, false, () -> {});
            save(archives, source, created, 2);
            for (SyncStateStore.DirtyArchive dirty : states.dirtyArchives()) {
                states.markUploaded(dirty.fileName, dirty.revision, 0);
            }
            assertTrue(android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, missing)));
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            save(archives, source, created, 3);
            assertFalse(database(context, missing).exists());
            assertTrue(database(context, "clipboard-2026-10-002.sqlite").exists());
            // Retrying the lost item must not silently create a new, incomplete backup.
            assertThrows(android.database.sqlite.SQLiteException.class, () -> save(archives, source, created, 2));
            assertFalse(database(context, missing).exists());
            assertEquals(2, states.dirtyArchiveCount());
        }
    }

    @Test public void missingOnlyArchiveUsesANewPartInsteadOfTheRemoteBasename() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        long created = 1792022400000L;
        SyncStatus source = new SyncStatus(1, "source", 1, 2);
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            save(archives, source, created, 1);
            assertTrue(android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, archives.fileNameFor(created))));
            save(archives, source, created, 2);
            assertFalse(database(context, archives.fileNameFor(created)).exists());
            assertEquals("clipboard-2026-10-001.sqlite", states.archiveForItem(source, 2));
        }
    }

    @Test public void corruptSealedPartDoesNotBlockHealthyPartOrNextMonth() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SyncStatus source = new SyncStatus(1, "source", 1, 4);
        long created = 1790769600000L; // September 30, noon UTC
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            archives.save(source, new ClipboardItem(1, "TEXT", created, false, "large", Uri.parse("content://source/items/1")),
                    new ByteArrayInputStream(new byte[(int) ArchiveStore.MAX_ARCHIVE_BYTES]), null, null, false, () -> {});
            save(archives, source, created, 2);
            File sealed = database(context, archives.fileNameFor(created));
            try (java.io.RandomAccessFile damage = new java.io.RandomAccessFile(sealed, "rw")) { damage.write(new byte[32]); }
            byte[] damaged = Files.readAllBytes(sealed.toPath());
            save(new ArchiveStore(context, states), source, created, 3);
            save(new ArchiveStore(context, states), source, 1790812800000L, 4); // October 1
            assertEquals("clipboard-2026-09-001.sqlite", states.archiveForItem(source, 3));
            assertEquals("clipboard-2026-10.sqlite", states.archiveForItem(source, 4));
            assertArrayEquals(damaged, Files.readAllBytes(sealed.toPath()));
            assertThrows(android.database.sqlite.SQLiteException.class, () -> save(archives, source, created, 1));
        }
    }

    @Test public void reservationSurvivesInterruptedInitializationAndRetriesTheSameFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SyncStatus source = new SyncStatus(1, "source", 1, 1);
        long created = 1792022400000L;
        String name = "clipboard-2026-10.sqlite";
        try (SyncStateStore states = new SyncStateStore(context)) {
            states.reserveArchiveItem(name, source, 1);
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            archives.recoverPendingWrites();
            save(archives, source, created, 1);
            assertEquals(name, states.archiveForItem(source, 1));
            assertFalse(database(context, "clipboard-2026-10-001.sqlite").exists());
            assertEquals(1, states.dirtyArchiveCount());
        }
    }

    private static void save(ArchiveStore archives, SyncStatus source, long created, int id) throws Exception {
        archives.save(source, new ClipboardItem(id, "TEXT", created, false, "preview",
                        Uri.parse("content://source/items/" + id)),
                new ByteArrayInputStream(new byte[]{(byte) id}), null, null, false, () -> {});
    }

    @Test public void intentWithoutArchiveIsRecoveredWithoutCreatingAnEmptyUpload() {
        Context context = RuntimeEnvironment.getApplication();
        try (SyncStateStore states = new SyncStateStore(context)) {
            states.beginArchiveWrite("clipboard-2000-01.sqlite");
            new ArchiveStore(context, states).recoverPendingWrites();
            assertTrue(states.pendingArchiveWrites().isEmpty());
            assertEquals(0, states.dirtyArchiveCount());
        }
    }
}
