package com.ajctrl.sumiresync.upload;

import static org.junit.Assert.*;
import static com.ajctrl.sumiresync.data.StoragePaths.database;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import com.ajctrl.sumiresync.data.ArchiveStore;
import com.ajctrl.sumiresync.data.ClipboardItem;
import com.ajctrl.sumiresync.data.SyncStateStore;
import com.ajctrl.sumiresync.data.SyncStatus;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class ArchiveUploadRecoveryTest {
    @Test public void savingAfterLocalLossPreservesRemotePartAndUploadsANewName() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Map<String, byte[]> remote = new HashMap<>();
        Map<String, String> etags = new HashMap<>();
        int[] version = {0};
        WebDavClient client = new WebDavClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            String path = request.url().encodedPath();
            int code = 405;
            if (request.method().equals("HEAD")) code = remote.containsKey(path) ? 200 : 404;
            if (request.method().equals("PUT")) {
                boolean allowed = remote.containsKey(path)
                        ? etags.get(path).equals(request.header("If-Match"))
                        : "*".equals(request.header("If-None-Match"));
                code = allowed ? 204 : 412;
                if (allowed) {
                    Buffer body = new Buffer();
                    request.body().writeTo(body);
                    remote.put(path, body.readByteArray());
                    etags.put(path, "\"version-" + (++version[0]) + "\"");
                }
            }
            Response.Builder response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(code).message("test").body(ResponseBody.create(null, ""));
            if (etags.containsKey(path)) response.header("ETag", etags.get(path));
            return response.build();
        }).build());
        String installation = "11111111-1111-4111-8111-111111111111";
        String root = "/remote.php/dav/files/user/Sumire/" + installation + "/";
        String missing = "clipboard-2026-10-001.sqlite";
        byte[] previous;
        SyncStatus source = new SyncStatus(1, "source", 1, 3);
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            save(archives, source, 1, (int) ArchiveStore.MAX_ARCHIVE_BYTES);
            save(archives, source, 2, 1);
            for (SyncStateStore.DirtyArchive dirty : states.dirtyArchives()) {
                upload(client, archives, installation, dirty.fileName);
                states.markUploaded(dirty.fileName, dirty.revision, 0);
            }
            previous = remote.get(root + missing);
            assertNotNull(previous);
            assertTrue(android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, missing)));
        }
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            save(archives, source, 3, 1);
            for (SyncStateStore.DirtyArchive dirty : states.dirtyArchives()) {
                if (dirty.fileName.equals(missing)) {
                    assertThrows(java.io.IOException.class, () -> archives.snapshot(missing));
                } else {
                    upload(client, archives, installation, dirty.fileName);
                }
            }
            assertArrayEquals(previous, remote.get(root + missing));
            assertEquals(3, remote.size());
            File downloaded = new File(context.getCacheDir(), "verify-remote.sqlite");
            for (int id = 2; id <= 3; id++) {
                String name = id == 2 ? missing : "clipboard-2026-10-002.sqlite";
                Files.write(downloaded.toPath(), remote.get(root + name));
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(downloaded.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                    assertEquals(1, DatabaseUtils.longForQuery(db,
                            "SELECT count(*) FROM clipboard_items WHERE source_id=?", new String[]{Integer.toString(id)}));
                    assertEquals(1, DatabaseUtils.queryNumEntries(db, "clipboard_items"));
                }
            }
        }
    }

    private static void upload(WebDavClient client, ArchiveStore archives, String installation, String name) throws Exception {
        File snapshot = archives.snapshot(name);
        try { client.put("https://example.test", "user", "secret", installation, snapshot); }
        finally { ArchiveStore.deleteSnapshot(snapshot); }
    }

    private static void save(ArchiveStore archives, SyncStatus source, int id, int size) throws Exception {
        archives.save(source, new ClipboardItem(id, "TEXT", 1792022400000L, false, "preview",
                        Uri.parse("content://source/items/" + id)),
                new ByteArrayInputStream(new byte[size]), null, null, false, () -> {});
    }
}
