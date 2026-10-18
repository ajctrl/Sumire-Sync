package com.ajctrl.sumiresync.settings;

import android.content.Context;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable across app updates, deliberately excluded from backup and device transfer. */
public final class InstallationIdentity {
    private InstallationIdentity() {}

    public static synchronized String get(Context context) {
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "installation-id"));
        try {
            try {
                return read(file); // openRead also recovers AtomicFile's legacy backup.
            } catch (FileNotFoundException missing) {
                if (file.getBaseFile().exists()
                        || new File(file.getBaseFile().getPath() + ".bak").exists()) throw missing;
            }
            String id = UUID.randomUUID().toString();
            FileOutputStream output = null;
            try {
                output = file.startWrite();
                output.write(id.getBytes(StandardCharsets.US_ASCII));
                file.finishWrite(output);
            } catch (IOException error) {
                if (output != null) file.failWrite(output);
                throw error;
            }
            // Do not upload under an identity which was not durably saved.
            if (!id.equals(read(file))) throw new IOException("Cannot persist installation identity");
            return id;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read or persist installation identity", error);
        }
    }

    private static String read(AtomicFile file) throws IOException {
        String id = new String(file.readFully(), StandardCharsets.US_ASCII);
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Invalid installation identity; refusing to change upload folder");
        }
        return id;
    }
}
