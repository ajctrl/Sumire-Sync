package com.ajctrl.sumiresync.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ArchiveStore {
    private static final int ARCHIVE_VERSION = 2;
    private static final int CONTENT_CHUNK_BYTES = 64 * 1024;
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private final Context context;
    private final SyncStateStore stateStore;

    public ArchiveStore(Context context, SyncStateStore stateStore) {
        this.context = context.getApplicationContext();
        this.stateStore = stateStore;
    }

    public String fileNameFor(long createdAt) {
        String month = new SimpleDateFormat("yyyy-MM", Locale.ROOT).format(new Date(createdAt));
        return "clipboard-" + month + ".sqlite";
    }

    public void save(SyncStatus status, ClipboardItem item, InputStream content,
                     String foregroundPackage, String foregroundAppName) throws IOException {
        String fileName = fileNameFor(item.createdAt);
        synchronized (lock(fileName)) {
            SQLiteDatabase db = open(fileName);
            try {
                db.beginTransaction();
                try {
                    ContentValues values = new ContentValues();
                    values.put("database_instance_id", status.databaseInstanceId);
                    values.put("clipboard_generation", status.clipboardGeneration);
                    values.put("source_id", item.id);
                    values.put("item_type", item.itemType);
                    values.put("created_at", item.createdAt);
                    values.put("is_pinned", item.pinned ? 1 : 0);
                    values.put("preview", item.preview);
                    values.putNull("content");
                    values.put("content_storage", "CHUNKS");
                    values.put("content_size", 0);
                    values.put("foreground_package", foregroundPackage);
                    values.put("foreground_app_name", foregroundAppName);
                    values.put("archived_at", System.currentTimeMillis());
                    long inserted = db.insertWithOnConflict("clipboard_items", null, values,
                            SQLiteDatabase.CONFLICT_IGNORE);
                    if (inserted == -1) {
                        if (!itemExists(db, status, item.id)) {
                            throw new SQLiteConstraintException("Clipboard archive insert was rejected");
                        }
                    } else {
                        long contentSize = insertChunks(db, status, item.id, content);
                        ContentValues size = new ContentValues();
                        size.put("content_size", contentSize);
                        int updated = db.update("clipboard_items", size,
                                "database_instance_id=? AND clipboard_generation=? AND source_id=?",
                                identityArgs(status, item.id));
                        if (updated != 1) throw new SQLiteException("Cannot finalize clipboard content");
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                // Also mark an idempotent retry dirty. The archive insert and sync-state update
                // live in separate SQLite files; this closes the crash window between them.
                stateStore.markDirty(fileName);
            } finally {
                db.close();
            }
        }
    }

    public File snapshot(String fileName) throws IOException {
        validateFileName(fileName);
        synchronized (lock(fileName)) {
            SQLiteDatabase db = open(fileName);
            db.close();
            File source = context.getDatabasePath(fileName);
            // Keep the remote-visible basename exactly as specified by the monthly contract.
            File target = new File(context.getCacheDir(), fileName);
            try (FileChannel input = new FileInputStream(source).getChannel();
                 FileChannel output = new FileOutputStream(target, false).getChannel()) {
                long position = 0;
                while (position < input.size()) {
                    long transferred = input.transferTo(position, input.size() - position, output);
                    if (transferred <= 0) throw new IOException("Cannot complete archive snapshot");
                    position += transferred;
                }
                output.force(true);
            }
            return target;
        }
    }

    private SQLiteDatabase open(String fileName) {
        validateFileName(fileName);
        File file = context.getDatabasePath(fileName);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLiteException("Cannot create database directory");
        }
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            int version = db.getVersion();
            if (version == 0) {
                createArchiveSchema(db);
            } else if (version == 1) {
                migrateArchiveFromV1(db);
            } else if (version != ARCHIVE_VERSION) {
                throw new SQLiteException("Unsupported archive database version: " + version);
            }
            return db;
        } catch (RuntimeException error) {
            db.close();
            throw error;
        }
    }

    private static void createArchiveSchema(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            createArchiveTables(db);
            db.setVersion(ARCHIVE_VERSION);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void createArchiveTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS clipboard_items (" +
                "database_instance_id TEXT NOT NULL, clipboard_generation INTEGER NOT NULL, " +
                "source_id INTEGER NOT NULL, item_type TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "is_pinned INTEGER NOT NULL, preview TEXT, content BLOB, " +
                "content_storage TEXT NOT NULL, content_size INTEGER NOT NULL, " +
                "foreground_package TEXT, foreground_app_name TEXT, archived_at INTEGER NOT NULL, " +
                "PRIMARY KEY(database_instance_id,clipboard_generation,source_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_clipboard_created_at ON clipboard_items(created_at)");
        db.execSQL("CREATE TABLE IF NOT EXISTS clipboard_content_chunks (" +
                "database_instance_id TEXT NOT NULL, clipboard_generation INTEGER NOT NULL, " +
                "source_id INTEGER NOT NULL, chunk_index INTEGER NOT NULL, content BLOB NOT NULL, " +
                "PRIMARY KEY(database_instance_id,clipboard_generation,source_id,chunk_index))");
    }

    private static void migrateArchiveFromV1(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            db.execSQL("ALTER TABLE clipboard_items RENAME TO clipboard_items_v1");
            db.execSQL("DROP INDEX IF EXISTS idx_clipboard_created_at");
            createArchiveTables(db);
            db.execSQL("INSERT INTO clipboard_items(" +
                    "database_instance_id,clipboard_generation,source_id,item_type,created_at," +
                    "is_pinned,preview,content,content_storage,content_size,foreground_package," +
                    "foreground_app_name,archived_at) SELECT database_instance_id," +
                    "clipboard_generation,source_id,item_type,created_at,is_pinned,preview,content," +
                    "'INLINE',length(content),foreground_package,foreground_app_name,archived_at " +
                    "FROM clipboard_items_v1");
            db.execSQL("DROP TABLE clipboard_items_v1");
            db.setVersion(ARCHIVE_VERSION);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static long insertChunks(SQLiteDatabase db, SyncStatus status, long sourceId,
                                     InputStream input) throws IOException {
        return ContentChunker.copy(input, CONTENT_CHUNK_BYTES, (chunkIndex, chunk) -> {
            ContentValues values = new ContentValues();
            values.put("database_instance_id", status.databaseInstanceId);
            values.put("clipboard_generation", status.clipboardGeneration);
            values.put("source_id", sourceId);
            values.put("chunk_index", chunkIndex++);
            values.put("content", chunk);
            if (db.insertOrThrow("clipboard_content_chunks", null, values) == -1) {
                throw new SQLiteException("Cannot save clipboard content chunk");
            }
        });
    }

    private static boolean itemExists(SQLiteDatabase db, SyncStatus status, long sourceId) {
        return DatabaseUtils.longForQuery(db,
                "SELECT count(*) FROM clipboard_items WHERE database_instance_id=? " +
                        "AND clipboard_generation=? AND source_id=?",
                identityArgs(status, sourceId)) == 1;
    }

    private static String[] identityArgs(SyncStatus status, long sourceId) {
        return new String[]{status.databaseInstanceId,
                Integer.toString(status.clipboardGeneration), Long.toString(sourceId)};
    }

    private static Object lock(String fileName) {
        return LOCKS.computeIfAbsent(fileName, ignored -> new Object());
    }

    private static void validateFileName(String fileName) {
        if (!fileName.matches("clipboard-[0-9]{4}-[0-9]{2}\\.sqlite")) {
            throw new IllegalArgumentException("Invalid archive file name");
        }
    }
}
