package com.ajctrl.sumiresync.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.DatabaseUtils;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

public final class ArchiveStore {
    private static final int ARCHIVE_VERSION = 3;
    private static final int CONTENT_CHUNK_BYTES = 64 * 1024;
    // Decimal MB. Items remain atomic, so the last item may exceed this threshold.
    public static final long MAX_ARCHIVE_BYTES = 10_000_000L;
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final DatabaseErrorHandler PRESERVE_CORRUPT_ARCHIVE = db -> {
        // Android's default handler deletes the file and retries opening an empty DB.
        throw new SQLiteDatabaseCorruptException("Archive is corrupt; file preserved, upload stopped: "
                + db.getPath());
    };
    private final Context context;
    private final SyncStateStore stateStore;

    public ArchiveStore(Context context, SyncStateStore stateStore) {
        this.context = context.getApplicationContext();
        this.stateStore = stateStore;
    }

    public String fileNameFor(long createdAt) {
        validateCreatedAt(createdAt);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String month = format.format(new Date(createdAt));
        return "clipboard-" + month + ".sqlite";
    }

    public static void validateCreatedAt(long createdAt) {
        // The archive contract uses exactly four year digits (10000-01-01 UTC is excluded).
        if (createdAt < 0 || createdAt >= 253402300800000L) {
            throw new IllegalArgumentException("Provider returned an unsupported createdAt: " + createdAt);
        }
    }

    public void save(SyncStatus status, ClipboardItem item, InputStream content,
                     String foregroundPackage, String foregroundAppName,
                     boolean flagged, Runnable validateSource) throws IOException {
        save(status, item, content, foregroundPackage, foregroundAppName, flagged,
                validateSource, () -> false);
    }

    public void save(SyncStatus status, ClipboardItem item, InputStream content,
                     String foregroundPackage, String foregroundAppName,
                     boolean flagged, Runnable validateSource, BooleanSupplier stopped) throws IOException {
        // Selection and rollover must be serialized as well as writes to each file.
        synchronized (lock("month:" + fileNameFor(item.createdAt))) {
            saveInPart(status, item, content, foregroundPackage, foregroundAppName,
                    flagged, validateSource, stopped);
        }
    }

    private void saveInPart(SyncStatus status, ClipboardItem item, InputStream content,
                            String foregroundPackage, String foregroundAppName,
                            boolean flagged, Runnable validateSource, BooleanSupplier stopped) throws IOException {
        if (stopped.getAsBoolean()) throw new IOException("Sync cancelled");
        String fileName = fileNameForSave(status, item);
        synchronized (lock(fileName)) {
            // Durable intent precedes the separate archive commit. Recovery does not need
            // the source item to still exist, and completion is blocked while this intent exists.
            stateStore.reserveArchiveItem(fileName, status, item.id);
            SQLiteDatabase db = open(fileName, true);
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
                    values.put("is_flagged", flagged ? 1 : 0);
                    values.put("archived_at", System.currentTimeMillis());
                    long inserted = db.insertWithOnConflict("clipboard_items", null, values,
                            SQLiteDatabase.CONFLICT_IGNORE);
                    if (inserted == -1) {
                        if (!itemExists(db, status, item.id)) {
                            throw new SQLiteConstraintException("Clipboard archive insert was rejected");
                        }
                    } else {
                        long contentSize = insertChunks(db, status, item.id, content, stopped);
                        ContentValues size = new ContentValues();
                        size.put("content_size", contentSize);
                        int updated = db.update("clipboard_items", size,
                                "database_instance_id=? AND clipboard_generation=? AND source_id=?",
                                identityArgs(status, item.id));
                        if (updated != 1) throw new SQLiteException("Cannot finalize clipboard content");
                    }
                    // Content URIs contain only an ID, which can be reused after a reset.
                    // Validate after consuming the stream, while rollback is still possible.
                    validateSource.run();
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                stateStore.finishArchiveWrite(fileName, true);
            } finally {
                db.close();
            }
        }
    }

    private String fileNameForSave(SyncStatus status, ClipboardItem item) {
        String canonical = fileNameFor(item.createdAt);
        // New writes reserve their identity before touching the archive. A retry can
        // find its file even after rollback, process death, rollover, or file loss.
        String reserved = stateStore.archiveForItem(status, item.id);
        if (reserved != null) return reserved;
        List<String> parts = partsFor(canonical);
        if (parts.isEmpty()) return canonical;
        String latest = parts.get(parts.size() - 1);
        File latestFile = StoragePaths.database(context, latest);
        if (!latestFile.isFile() && stateStore.archiveInitialized(latest)) {
            stateStore.requeueUnavailableArchive(latest);
        } else if (latestFile.length() < MAX_ARCHIVE_BYTES) {
            return latest;
        }
        long next = Math.addExact(partNumber(latest), 1);
        return canonical.substring(0, canonical.length() - ".sqlite".length())
                + String.format(Locale.ROOT, "-%03d.sqlite", next);
    }

    private List<String> partsFor(String canonical) {
        String prefix = canonical.substring(0, canonical.length() - ".sqlite".length()) + "-";
        List<String> result = new ArrayList<>();
        for (String name : stateStore.archiveFiles()) {
            if (isArchiveFileName(name) && (name.equals(canonical) || name.startsWith(prefix))) {
                result.add(name);
            }
        }
        result.sort(Comparator.comparingLong(ArchiveStore::partNumber));
        return result;
    }

    private static long partNumber(String name) {
        String stem = name.substring(0, name.length() - ".sqlite".length());
        return stem.length() == "clipboard-YYYY-MM".length() ? 0
                : Long.parseLong(stem.substring("clipboard-YYYY-MM-".length()));
    }

    public void recoverPendingWrites() {
        for (String fileName : stateStore.pendingArchiveWrites()) {
            if (!isArchiveFileName(fileName)) {
                stateStore.discardInvalidArchiveReservation(fileName);
                continue;
            }
            synchronized (lock(fileName)) {
                SQLiteDatabase.deleteDatabase(new File(StoragePaths.database(context, fileName).getPath() + ".initializing"));
                stateStore.finishArchiveWrite(fileName, StoragePaths.database(context, fileName).isFile());
            }
        }
    }

    public File snapshot(String fileName) throws IOException {
        validateFileName(fileName);
        synchronized (lock(fileName)) {
            if (!StoragePaths.database(context, fileName).isFile()) {
                throw new IOException("Archive is missing: " + fileName);
            }
            try (SQLiteDatabase db = open(fileName, false)) {
                if (!db.isDatabaseIntegrityOk()) {
                    throw new SQLiteDatabaseCorruptException("Archive integrity check failed; upload stopped: "
                            + fileName);
                }
            }
            File source = StoragePaths.database(context, fileName);
            // Keep the remote-visible basename exactly as specified by the monthly contract.
            File directory = new File(context.getCacheDir(), "upload-" + UUID.randomUUID());
            if (!directory.mkdir()) throw new IOException("Cannot create snapshot directory");
            File target = new File(directory, fileName);
            try (FileChannel input = new FileInputStream(source).getChannel();
                 FileChannel output = new FileOutputStream(target, false).getChannel()) {
                long position = 0;
                while (position < input.size()) {
                    long transferred = input.transferTo(position, input.size() - position, output);
                    if (transferred <= 0) throw new IOException("Cannot complete archive snapshot");
                    position += transferred;
                }
                output.force(true);
            } catch (IOException | RuntimeException error) {
                deleteSnapshot(target);
                throw error;
            }
            return target;
        }
    }

    public static void deleteSnapshot(File snapshot) {
        if (!snapshot.exists() || snapshot.delete()) snapshot.getParentFile().delete();
    }

    // Only call while holding the process-wide upload lock, before making a new snapshot.
    public void clearAbandonedSnapshots() {
        File[] directories = context.getCacheDir().listFiles(file -> file.isDirectory()
                && file.getName().matches("upload-[0-9a-f-]{36}"));
        if (directories == null) return;
        for (File directory : directories) {
            File[] files = directory.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (isArchiveFileName(file.getName())) file.delete();
            }
            directory.delete();
        }
    }

    private SQLiteDatabase open(String fileName, boolean allowCreate) {
        validateFileName(fileName);
        File file = StoragePaths.database(context, fileName);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLiteException("Cannot create database directory");
        }
        if (!file.exists() && allowCreate) {
            if (stateStore.archiveInitialized(fileName)) {
                stateStore.requeueUnavailableArchive(fileName);
                throw new SQLiteException("Archive is missing; refusing to recreate: " + fileName);
            }
            initializeArchive(file);
        }
        if (file.exists() && file.length() == 0) {
            throw new SQLiteDatabaseCorruptException("Existing archive is empty; file preserved: " + fileName);
        }
        SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE,
                PRESERVE_CORRUPT_ARCHIVE);
        try {
            int version = db.getVersion();
            if (version == 0) {
                throw new SQLiteDatabaseCorruptException("Existing archive has no schema; file preserved: "
                        + fileName);
            } else if (version != ARCHIVE_VERSION) {
                throw new SQLiteException("Unsupported archive database version: " + version);
            }
            stateStore.markArchiveInitialized(fileName);
            return db;
        } catch (RuntimeException error) {
            db.close();
            throw error;
        }
    }

    private static void initializeArchive(File file) {
        // A crash can leave only this private staging file, never an incomplete
        // archive under its final name. Retry discards staging and starts again.
        File staging = new File(file.getPath() + ".initializing");
        SQLiteDatabase.deleteDatabase(staging);
        try {
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(staging.getPath(), null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.CREATE_IF_NECESSARY,
                    PRESERVE_CORRUPT_ARCHIVE)) {
                db.disableWriteAheadLogging();
                createArchiveSchema(db);
            }
            try (RandomAccessFile output = new RandomAccessFile(staging, "rw")) {
                output.getFD().sync();
            }
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            SQLiteException failure = new SQLiteException("Cannot initialize archive: " + file.getName());
            failure.initCause(error);
            throw failure;
        } finally {
            SQLiteDatabase.deleteDatabase(staging);
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
                "foreground_package TEXT, foreground_app_name TEXT, " +
                "is_flagged INTEGER NOT NULL DEFAULT 0, archived_at INTEGER NOT NULL, " +
                "PRIMARY KEY(database_instance_id,clipboard_generation,source_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_clipboard_created_at ON clipboard_items(created_at)");
        db.execSQL("CREATE TABLE IF NOT EXISTS clipboard_content_chunks (" +
                "database_instance_id TEXT NOT NULL, clipboard_generation INTEGER NOT NULL, " +
                "source_id INTEGER NOT NULL, chunk_index INTEGER NOT NULL, content BLOB NOT NULL, " +
                "PRIMARY KEY(database_instance_id,clipboard_generation,source_id,chunk_index))");
    }

    private static long insertChunks(SQLiteDatabase db, SyncStatus status, long sourceId,
                                     InputStream input, BooleanSupplier stopped) throws IOException {
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
        }, stopped);
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
        if (!isArchiveFileName(fileName)) {
            throw new IllegalArgumentException("Invalid archive file name");
        }
    }

    public static boolean isArchiveFileName(String fileName) {
        return fileName != null && fileName.matches("clipboard-[0-9]{4}-[0-9]{2}(?:-[0-9]{3,})?\\.sqlite");
    }
}
