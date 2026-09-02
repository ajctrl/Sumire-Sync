package com.ajctrl.sumiresync.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class SyncStateStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "sync-state.sqlite";
    private static final int DB_VERSION = 3;

    public SyncStateStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sync_state (singleton INTEGER PRIMARY KEY CHECK(singleton=1), " +
                "api_version INTEGER, database_instance_id TEXT, clipboard_generation INTEGER, " +
                "last_id INTEGER NOT NULL DEFAULT 0, last_error TEXT, last_sync_at INTEGER, " +
                "request_revision INTEGER NOT NULL DEFAULT 0, " +
                "handled_request_revision INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT INTO sync_state(singleton,last_id) VALUES(1,0)");
        db.execSQL("CREATE TABLE gaps (database_instance_id TEXT NOT NULL, generation INTEGER NOT NULL, " +
                "source_id INTEGER NOT NULL, end_source_id INTEGER NOT NULL, " +
                "reason TEXT NOT NULL, detected_at INTEGER NOT NULL, " +
                "PRIMARY KEY(database_instance_id,generation,source_id))");
        db.execSQL("CREATE TABLE dirty_archives (file_name TEXT PRIMARY KEY, revision INTEGER NOT NULL, " +
                "uploaded_revision INTEGER NOT NULL DEFAULT 0)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE gaps ADD COLUMN end_source_id INTEGER");
            db.execSQL("UPDATE gaps SET end_source_id=source_id WHERE end_source_id IS NULL");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sync_state ADD COLUMN request_revision INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sync_state ADD COLUMN handled_request_revision INTEGER NOT NULL DEFAULT 0");
        }
    }

    public synchronized State read() {
        try (Cursor cursor = getReadableDatabase().query("sync_state", null, "singleton=1",
                null, null, null, null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("sync state is missing");
            return new State(nullableInt(cursor, "api_version"),
                    cursor.getString(cursor.getColumnIndexOrThrow("database_instance_id")),
                    nullableInt(cursor, "clipboard_generation"),
                    cursor.getLong(cursor.getColumnIndexOrThrow("last_id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("last_error")),
                    nullableLong(cursor, "last_sync_at"),
                    cursor.getLong(cursor.getColumnIndexOrThrow("request_revision")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("handled_request_revision")),
                    dirtyArchiveCount());
        }
    }

    public synchronized void initializeSource(SyncStatus status) {
        ContentValues values = new ContentValues();
        values.put("api_version", status.apiVersion);
        values.put("database_instance_id", status.databaseInstanceId);
        values.put("clipboard_generation", status.clipboardGeneration);
        values.put("last_id", 0);
        values.putNull("last_error");
        getWritableDatabase().update("sync_state", values, "singleton=1", null);
    }

    public synchronized void advance(long id) {
        ContentValues values = new ContentValues();
        values.put("last_id", id);
        values.put("last_sync_at", System.currentTimeMillis());
        values.putNull("last_error");
        getWritableDatabase().update("sync_state", values, "singleton=1 AND last_id<?",
                new String[]{Long.toString(id)});
    }

    public synchronized void setError(String message) {
        ContentValues values = new ContentValues();
        values.put("last_error", message);
        getWritableDatabase().update("sync_state", values, "singleton=1", null);
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
        db.execSQL("UPDATE sync_state SET request_revision=request_revision+1,last_error=NULL "
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

    public synchronized void recordGap(String instanceId, int generation, long sourceId, String reason) {
        recordGapRange(instanceId, generation, sourceId, sourceId, reason);
    }

    public synchronized void recordGapRange(String instanceId, int generation, long startSourceId,
                                            long endSourceId, String reason) {
        if (endSourceId < startSourceId) throw new IllegalArgumentException("Invalid gap range");
        ContentValues values = new ContentValues();
        values.put("database_instance_id", instanceId);
        values.put("generation", generation);
        values.put("source_id", startSourceId);
        values.put("end_source_id", endSourceId);
        values.put("reason", reason);
        values.put("detected_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("gaps", null, values,
                SQLiteDatabase.CONFLICT_IGNORE);
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
        getWritableDatabase().execSQL(
                "UPDATE sync_state SET last_error=NULL WHERE singleton=1 AND "
                        + "(last_error LIKE 'アップロード失敗%' "
                        + "OR last_error LIKE 'Upload:%' OR last_error LIKE 'Upload job%')");
    }

    public synchronized void markUploaded(String fileName, long revision) {
        getWritableDatabase().execSQL(
                "UPDATE dirty_archives SET uploaded_revision=? WHERE file_name=? AND revision=?",
                new Object[]{revision, fileName, revision});
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
        public final long requestRevision;
        public final long handledRequestRevision;
        public final int dirtyArchiveCount;

        State(Integer apiVersion, String instanceId, Integer generation, long lastId,
              String lastError, Long lastSyncAt, long requestRevision,
              long handledRequestRevision, int dirtyArchiveCount) {
            this.apiVersion = apiVersion;
            this.instanceId = instanceId;
            this.generation = generation;
            this.lastId = lastId;
            this.lastError = lastError;
            this.lastSyncAt = lastSyncAt;
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
