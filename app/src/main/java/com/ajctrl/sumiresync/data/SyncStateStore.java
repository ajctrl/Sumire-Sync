package com.ajctrl.sumiresync.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class SyncStateStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "sync-state.sqlite";
    private static final int DB_VERSION = 8;
    public static final int MAX_ERROR_CHARS = 2_000;
    private static final DatabaseErrorHandler PRESERVE_CORRUPT_STATE = db -> {
        // Recreating this DB would lose the archive catalog and pending uploads.
        throw new SQLiteDatabaseCorruptException(
                "同期管理DBの破損を検出しました。ファイルを保持して同期を停止しています。自動復元は行いません。");
    };
    private final Context context;

    public SyncStateStore(Context context) {
        super(context.getApplicationContext(), StoragePaths.database(context, DB_NAME).getPath(),
                null, DB_VERSION, PRESERVE_CORRUPT_STATE);
        this.context = context.getApplicationContext();
    }

    @Override public synchronized SQLiteDatabase getReadableDatabase() {
        checkMissingState();
        return super.getReadableDatabase();
    }

    @Override public synchronized SQLiteDatabase getWritableDatabase() {
        checkMissingState();
        return super.getWritableDatabase();
    }

    private void checkMissingState() {
        File file = StoragePaths.database(context, DB_NAME);
        // SQLite treats a missing or zero-length file as a new DB without invoking
        // DatabaseErrorHandler. Check before opening so even that file is preserved.
        if (!file.exists() || file.length() == 0) requireNoExistingArchives();
    }

    private void requireNoExistingArchives() {
        File directory = StoragePaths.database(context, DB_NAME).getParentFile();
        String[] archives = directory.list((dir, name) -> ArchiveStore.isArchiveFileName(name));
        if (archives == null) throw new SQLiteException("Cannot inspect v8 archive directory");
        if (archives.length != 0) {
            throw new SQLiteDatabaseCorruptException(
                    "保存済みアーカイブに対応する同期管理情報がありません。ファイルを保持して同期を停止しています。自動復元は行いません。");
        }
    }

    @Override public void onCreate(SQLiteDatabase db) {
        // Also reject an existing SQLite file with user_version=0 and no state schema.
        // Only inspect v8 storage; legacy archives never participate in this check.
        requireNoExistingArchives();
        db.execSQL("CREATE TABLE sync_state (singleton INTEGER PRIMARY KEY CHECK(singleton=1), " +
                "api_version INTEGER, database_instance_id TEXT, clipboard_generation INTEGER, " +
                "last_id INTEGER NOT NULL DEFAULT 0, last_error TEXT, last_sync_at INTEGER, " +
                "request_revision INTEGER NOT NULL DEFAULT 0, " +
                "handled_request_revision INTEGER NOT NULL DEFAULT 0, " +
                "upload_generation INTEGER NOT NULL DEFAULT 0, last_sync_completed_at INTEGER, upload_error TEXT, " +
                "upload_namespace TEXT)");
        db.execSQL("INSERT INTO sync_state(singleton,last_id) VALUES(1,0)");
        db.execSQL("CREATE TABLE gaps (database_instance_id TEXT NOT NULL, generation INTEGER NOT NULL, " +
                "source_id INTEGER NOT NULL, end_source_id INTEGER NOT NULL, " +
                "reason TEXT NOT NULL, detected_at INTEGER NOT NULL, " +
                "PRIMARY KEY(database_instance_id,generation,source_id))");
        db.execSQL("CREATE TABLE dirty_archives (file_name TEXT PRIMARY KEY, revision INTEGER NOT NULL, " +
                "uploaded_revision INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE pending_archive_writes (file_name TEXT PRIMARY KEY)");
        createArchiveCatalog(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v8 starts in a separate directory; old schemas are intentionally unsupported.
        throw new android.database.sqlite.SQLiteException("Unsupported sync database version: " + oldVersion);
    }

    private void createArchiveCatalog(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE archive_files (file_name TEXT PRIMARY KEY, initialized INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE archive_item_locations (database_instance_id TEXT NOT NULL, "
                + "generation INTEGER NOT NULL, source_id INTEGER NOT NULL, file_name TEXT NOT NULL, "
                + "PRIMARY KEY(database_instance_id,generation,source_id))");
    }

    public synchronized List<String> archiveFiles() {
        List<String> names = new ArrayList<>();
        try (Cursor files = getReadableDatabase().rawQuery("SELECT file_name FROM archive_files", null)) {
            while (files.moveToNext()) names.add(files.getString(0));
        }
        return names;
    }

    public synchronized boolean archiveInitialized(String fileName) {
        return archiveFlag(fileName, "initialized");
    }

    private boolean archiveFlag(String fileName, String column) {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT " + column
                + " FROM archive_files WHERE file_name=?", new String[]{fileName})) {
            return row.moveToFirst() && row.getInt(0) != 0;
        }
    }

    public synchronized String archiveForItem(SyncStatus source, long id) {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT file_name FROM archive_item_locations "
                + "WHERE database_instance_id=? AND generation=? AND source_id=?",
                new String[]{source.databaseInstanceId, Integer.toString(source.clipboardGeneration), Long.toString(id)})) {
            return row.moveToFirst() ? row.getString(0) : null;
        }
    }

    public synchronized void reserveArchiveItem(String fileName, SyncStatus source, long id) {
        if (!ArchiveStore.isArchiveFileName(fileName)) throw new IllegalArgumentException("Invalid archive file name");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO archive_files(file_name,initialized) VALUES(?,0)",
                    new Object[]{fileName});
            db.execSQL("INSERT OR IGNORE INTO archive_item_locations VALUES(?,?,?,?)",
                    new Object[]{source.databaseInstanceId, source.clipboardGeneration, id, fileName});
            if (!fileName.equals(archiveForItem(source, id))) {
                throw new IllegalStateException("Archive item already reserved in another file");
            }
            beginArchiveWrite(fileName);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void discardInvalidArchiveReservation(String fileName) {
        if (ArchiveStore.isArchiveFileName(fileName)) throw new IllegalArgumentException("Archive name is valid");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Older code reserved unsupported years before rejecting the filename on open.
            // Only discard that unfinished metadata; never resolve an invalid filesystem path.
            try (Cursor row = db.rawQuery("SELECT initialized FROM archive_files WHERE file_name=?",
                    new String[]{fileName})) {
                if (!row.moveToFirst()) {
                    // Another recovery worker may already have removed this reservation.
                    if (DatabaseUtils.longForQuery(db,
                            "SELECT count(*) FROM pending_archive_writes WHERE file_name=?",
                            new String[]{fileName}) != 0) {
                        throw new SQLiteException("Invalid archive reservation has no catalog entry");
                    }
                    db.setTransactionSuccessful();
                    return;
                }
                if (row.getInt(0) != 0) {
                    throw new SQLiteException("Invalid archive is not an uninitialized reservation");
                }
            }
            try (Cursor row = db.rawQuery("SELECT 1 FROM dirty_archives WHERE file_name=?",
                    new String[]{fileName})) {
                if (row.moveToFirst()) throw new SQLiteException("Invalid archive has upload history");
            }
            String[] args = {fileName};
            db.delete("archive_item_locations", "file_name=?", args);
            db.delete("pending_archive_writes", "file_name=?", args);
            db.delete("archive_files", "file_name=?", args);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void markArchiveInitialized(String fileName) {
        getWritableDatabase().execSQL("UPDATE archive_files SET initialized=1 WHERE file_name=?",
                new Object[]{fileName});
    }

    public synchronized void requeueUnavailableArchive(String fileName) {
        // Keep the fault visible even if this file had already been uploaded.
        getWritableDatabase().execSQL("INSERT OR IGNORE INTO dirty_archives VALUES(?,1,0)", new Object[]{fileName});
        getWritableDatabase().execSQL("UPDATE dirty_archives SET revision=revision+1 "
                + "WHERE file_name=? AND revision=uploaded_revision", new Object[]{fileName});
    }

    public synchronized State read() {
        // Bound legacy errors in the SELECT, without writes or loading huge CursorWindow values.
        String[] columns = {"api_version", "database_instance_id", "clipboard_generation", "last_id",
                boundedErrorColumn("last_error"), boundedErrorColumn("upload_error"),
                "last_sync_at", "last_sync_completed_at", "request_revision", "handled_request_revision"};
        try (Cursor cursor = getReadableDatabase().query("sync_state", columns, "singleton=1",
                null, null, null, null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("sync state is missing");
            return new State(nullableInt(cursor, "api_version"),
                    cursor.getString(cursor.getColumnIndexOrThrow("database_instance_id")),
                    nullableInt(cursor, "clipboard_generation"),
                    cursor.getLong(cursor.getColumnIndexOrThrow("last_id")),
                    combinedError(cursor),
                    nullableLong(cursor, "last_sync_at"),
                    nullableLong(cursor, "last_sync_completed_at"),
                    cursor.getLong(cursor.getColumnIndexOrThrow("request_revision")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("handled_request_revision")),
                    dirtyArchiveCount());
        }
    }

    private static String boundedErrorColumn(String column) {
        return "CASE WHEN length(" + column + ")>" + MAX_ERROR_CHARS
                + " THEN substr(" + column + ",1," + (MAX_ERROR_CHARS - 1) + ") || '…' ELSE "
                + column + " END AS " + column;
    }

    public synchronized void initializeSource(SyncStatus status) {
        ContentValues values = new ContentValues();
        values.put("api_version", status.apiVersion);
        values.put("database_instance_id", status.databaseInstanceId);
        values.put("clipboard_generation", status.clipboardGeneration);
        values.put("last_id", 0);
        getWritableDatabase().update("sync_state", values, "singleton=1", null);
    }

    public synchronized void advance(long id) {
        ContentValues values = new ContentValues();
        values.put("last_id", id);
        values.put("last_sync_at", System.currentTimeMillis());
        getWritableDatabase().update("sync_state", values, "singleton=1 AND last_id<?",
                new String[]{Long.toString(id)});
    }

    public synchronized void setError(String message) {
        // Compatibility for callers using the old error API.
        if (message != null && (message.startsWith("アップロード失敗")
                || message.startsWith("Upload:") || message.startsWith("Upload job"))) {
            setUploadError(message);
            return;
        }
        setLocalError(message);
    }

    public synchronized void setLocalError(String message) {
        ContentValues values = new ContentValues();
        values.put("last_error", boundedError(message));
        getWritableDatabase().update("sync_state", values, "singleton=1", null);
    }

    public synchronized void setUploadError(String message) {
        ContentValues values = new ContentValues();
        values.put("upload_error", boundedError(message));
        getWritableDatabase().update("sync_state", values, "singleton=1", null);
    }

    private static String boundedError(String message) {
        if (message == null || message.codePointCount(0, message.length()) <= MAX_ERROR_CHARS) return message;
        return message.substring(0, message.offsetByCodePoints(0, MAX_ERROR_CHARS - 1)) + "…";
    }

    private static String combinedError(Cursor cursor) {
        String local = cursor.getString(cursor.getColumnIndexOrThrow("last_error"));
        String upload = cursor.getString(cursor.getColumnIndexOrThrow("upload_error"));
        return local == null ? upload : upload == null ? local : local + "\n" + upload;
    }

    public synchronized long requestSync() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE sync_state SET request_revision=request_revision+1 WHERE singleton=1");
        try (Cursor cursor = db.rawQuery(
                "SELECT request_revision FROM sync_state WHERE singleton=1", null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("sync state is missing");
            return cursor.getLong(0);
        }
    }

    public synchronized long requestManualSync() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE sync_state SET request_revision=request_revision+1,last_error=NULL,upload_error=NULL "
                + "WHERE singleton=1");
        try (Cursor cursor = db.rawQuery(
                "SELECT request_revision FROM sync_state WHERE singleton=1", null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("sync state is missing");
            return cursor.getLong(0);
        }
    }

    public synchronized long requestRevision() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT request_revision FROM sync_state WHERE singleton=1", null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("sync state is missing");
            return cursor.getLong(0);
        }
    }

    public synchronized void markRequestHandled(long revision) {
        getWritableDatabase().execSQL(
                "UPDATE sync_state SET handled_request_revision=max(handled_request_revision,?) " +
                        "WHERE singleton=1",
                new Object[]{revision});
    }

    public synchronized boolean hasPendingRequest() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT request_revision>handled_request_revision FROM sync_state WHERE singleton=1",
                null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("sync state is missing");
            return cursor.getInt(0) != 0;
        }
    }

    public synchronized void markSyncCompleted(long generation) {
        // Check all completion conditions in one statement, including writes by other workers.
        getWritableDatabase().execSQL(
                "UPDATE sync_state SET last_sync_completed_at=? WHERE singleton=1 "
                        + "AND upload_generation=? AND database_instance_id IS NOT NULL "
                        + "AND request_revision=handled_request_revision AND last_error IS NULL "
                        + "AND upload_error IS NULL AND NOT EXISTS (SELECT 1 FROM pending_archive_writes) "
                        + "AND NOT EXISTS (SELECT 1 FROM dirty_archives WHERE revision>uploaded_revision)",
                new Object[]{System.currentTimeMillis(), generation});
    }

    public synchronized void recordGap(String instanceId, int generation, long sourceId, String reason) {
        recordGapRange(instanceId, generation, sourceId, sourceId, reason);
    }

    public synchronized void recordGapRange(String instanceId, int generation, long startSourceId,
                                            long endSourceId, String reason) {
        if (endSourceId < startSourceId) throw new IllegalArgumentException("Invalid gap range");
        // Also recover a wider range left by versions that committed the gap before progress.
        getWritableDatabase().execSQL("INSERT INTO gaps(database_instance_id,generation,source_id,"
                + "end_source_id,reason,detected_at) VALUES(?,?,?,?,?,?) "
                + "ON CONFLICT(database_instance_id,generation,source_id) DO UPDATE SET "
                + "end_source_id=excluded.end_source_id,reason=excluded.reason,detected_at=excluded.detected_at "
                + "WHERE excluded.end_source_id>gaps.end_source_id",
                new Object[]{instanceId, generation, startSourceId, endSourceId, reason, System.currentTimeMillis()});
    }

    public synchronized void recordGapRangeAndAdvance(String instanceId, int generation,
                                                       long startSourceId, long endSourceId, String reason) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            recordGapRange(instanceId, generation, startSourceId, endSourceId, reason);
            advance(endSourceId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized long markDirty(String fileName) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO dirty_archives(file_name,revision,uploaded_revision) VALUES(?,0,0)",
                new Object[]{fileName});
        db.execSQL("UPDATE dirty_archives SET revision=revision+1 WHERE file_name=?",
                new Object[]{fileName});
        try (Cursor cursor = db.rawQuery("SELECT revision FROM dirty_archives WHERE file_name=?",
                new String[]{fileName})) {
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }

    public synchronized void beginArchiveWrite(String fileName) {
        getWritableDatabase().execSQL("INSERT OR IGNORE INTO pending_archive_writes VALUES(?)",
                new Object[]{fileName});
    }

    public synchronized List<String> pendingArchiveWrites() {
        List<String> files = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT file_name FROM pending_archive_writes", null)) {
            while (cursor.moveToNext()) files.add(cursor.getString(0));
        }
        return files;
    }

    public synchronized void finishArchiveWrite(String fileName, boolean archiveExists) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (archiveExists) markDirty(fileName);
            db.delete("pending_archive_writes", "file_name=?", new String[]{fileName});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<DirtyArchive> dirtyArchives() {
        List<DirtyArchive> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT file_name,revision FROM dirty_archives WHERE revision>uploaded_revision ORDER BY file_name",
                null)) {
            while (cursor.moveToNext()) result.add(new DirtyArchive(cursor.getString(0), cursor.getLong(1)));
        }
        return result;
    }

    public synchronized int dirtyArchiveCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT count(*) FROM dirty_archives WHERE revision>uploaded_revision", null)) {
            if (!cursor.moveToFirst()) return 0;
            return cursor.getInt(0);
        }
    }

    public synchronized void clearUploadError() {
        setUploadError(null);
    }

    public synchronized boolean prepareUploadDestination(long generation) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long current;
            try (Cursor cursor = db.rawQuery(
                    "SELECT upload_generation FROM sync_state WHERE singleton=1", null)) {
                if (!cursor.moveToFirst()) throw new IllegalStateException("sync state is missing");
                current = cursor.getLong(0);
            }
            // An older worker must not switch the upload state back to its destination.
            if (generation < current) return false;
            if (generation > current) {
                db.execSQL("UPDATE dirty_archives SET uploaded_revision=0");
                db.execSQL("UPDATE sync_state SET upload_generation=? WHERE singleton=1",
                        new Object[]{generation});
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void prepareUploadNamespace(String installationId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String previous;
            try (Cursor cursor = db.rawQuery("SELECT upload_namespace FROM sync_state WHERE singleton=1", null)) {
                if (!cursor.moveToFirst()) throw new IllegalStateException("sync state is missing");
                previous = cursor.getString(0);
            }
            if (!installationId.equals(previous)) {
                // Only v8 archives belong to this namespace; never scan legacy storage.
                for (String file : archiveFiles()) {
                    if (!ArchiveStore.isArchiveFileName(file)) {
                        discardInvalidArchiveReservation(file);
                        continue;
                    }
                    // A reservation can survive a crash before any archive was created.
                    if (archiveInitialized(file) || StoragePaths.database(context, file).isFile()) markDirty(file);
                }
                db.execSQL("UPDATE dirty_archives SET uploaded_revision=0");
                db.execSQL("UPDATE sync_state SET upload_namespace=?,last_sync_completed_at=NULL WHERE singleton=1",
                        new Object[]{installationId});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void markUploaded(String fileName, long revision, long generation) {
        getWritableDatabase().execSQL(
                "UPDATE dirty_archives SET uploaded_revision=? WHERE file_name=? AND revision=? " +
                        "AND EXISTS (SELECT 1 FROM sync_state WHERE singleton=1 AND upload_generation=?)",
                new Object[]{revision, fileName, revision, generation});
    }

    private static Integer nullableInt(Cursor c, String column) {
        int i = c.getColumnIndexOrThrow(column);
        return c.isNull(i) ? null : c.getInt(i);
    }

    private static Long nullableLong(Cursor c, String column) {
        int i = c.getColumnIndexOrThrow(column);
        return c.isNull(i) ? null : c.getLong(i);
    }

    public static final class State {
        public final Integer apiVersion;
        public final String instanceId;
        public final Integer generation;
        public final long lastId;
        public final String lastError;
        public final Long lastSyncAt;
        public final Long lastSyncCompletedAt;
        public final long requestRevision;
        public final long handledRequestRevision;
        public final int dirtyArchiveCount;

        State(Integer apiVersion, String instanceId, Integer generation, long lastId,
              String lastError, Long lastSyncAt, Long lastSyncCompletedAt, long requestRevision,
              long handledRequestRevision, int dirtyArchiveCount) {
            this.apiVersion = apiVersion;
            this.instanceId = instanceId;
            this.generation = generation;
            this.lastId = lastId;
            this.lastError = lastError;
            this.lastSyncAt = lastSyncAt;
            this.lastSyncCompletedAt = lastSyncCompletedAt;
            this.requestRevision = requestRevision;
            this.handledRequestRevision = handledRequestRevision;
            this.dirtyArchiveCount = dirtyArchiveCount;
        }
    }

    public static final class DirtyArchive {
        public final String fileName;
        public final long revision;
        DirtyArchive(String fileName, long revision) {
            this.fileName = fileName;
            this.revision = revision;
        }
    }
}
