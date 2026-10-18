package com.ajctrl.sumiresync.data;

import android.content.Context;
import android.database.sqlite.SQLiteException;
import java.io.File;

/** Storage boundary for v8. Files in the old databases directory are never imported. */
public final class StoragePaths {
    private StoragePaths() {}

    public static File database(Context context, String name) {
        if (!"sync-state.sqlite".equals(name) && !ArchiveStore.isArchiveFileName(name)) {
            throw new IllegalArgumentException("Invalid database name");
        }
        File directory = new File(context.getNoBackupFilesDir(), "sync-v8");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new SQLiteException("Cannot create v8 database directory");
        }
        return new File(directory, name);
    }
}
