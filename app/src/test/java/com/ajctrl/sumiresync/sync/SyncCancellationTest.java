package com.ajctrl.sumiresync.sync;

import static org.junit.Assert.*;
import static com.ajctrl.sumiresync.data.StoragePaths.database;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import com.ajctrl.sumiresync.TestJobParameters;
import com.ajctrl.sumiresync.data.ArchiveStore;
import com.ajctrl.sumiresync.data.SyncStateStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30, shadows = SyncCancellationTest.AssetStreams.class)
public class SyncCancellationTest {
    private static final String AUTHORITY = "com.example.sumire.syncbridge.clipboard";
    private static final long CREATED = 1790899200000L;
    private Context context;
    private BlockingProvider provider;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        AssetStreams.blocked = null;
        File body = new File(context.getCacheDir(), "cancellation-content");
        Files.write(body.toPath(), new byte[]{99});
        provider = new BlockingProvider(body);
        ProviderTestSupport.registerTrusted(context, AUTHORITY, provider);
    }

    @Test public void jobStopClosesBlockedContentAndNextRunCanSaveTheItem() throws Exception {
        BlockingStream stream = new BlockingStream(provider.body);
        AssetStreams.blocked = stream;
        ExecutorService nextWorker = Executors.newSingleThreadExecutor();
        var controller = Robolectric.buildService(CatchUpJobService.class).create();
        try {
            var params = TestJobParameters.create(7101);
            assertTrue(controller.get().onStartJob(params));
            assertTrue("worker reaches the blocked second read", stream.reading.await(10, TimeUnit.SECONDS));
            assertTrue(controller.get().onStopJob(TestJobParameters.copy(params)));
            assertTrue("stop closes the active stream", stream.closed.await(10, TimeUnit.SECONDS));
            AssetStreams.blocked = null;
            SyncEngine.Result retried = nextWorker.submit(() -> {
                try (SyncEngine engine = new SyncEngine(context)) { return engine.runBatch(500); }
            }).get(10, TimeUnit.SECONDS);
            assertTrue(retried.error, retried.success);
            assertEquals(1, retried.saved);
            try (SyncStateStore states = new SyncStateStore(context)) {
                assertEquals(1, states.read().lastId);
                assertEquals(0, DatabaseUtils.queryNumEntries(states.getReadableDatabase(), "gaps"));
                String name = new ArchiveStore(context, states).fileNameFor(CREATED);
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database(context, name).getPath(), null, 1);
                     Cursor content = db.rawQuery("SELECT content FROM clipboard_content_chunks ORDER BY chunk_index", null)) {
                    assertTrue(content.moveToFirst());
                    assertArrayEquals(new byte[]{99}, content.getBlob(0));
                    assertFalse("partial chunks from the stopped run were rolled back", content.moveToNext());
                }
            }
        } finally {
            stream.close();
            AssetStreams.blocked = null;
            controller.destroy();
            nextWorker.shutdownNow();
        }
    }

    // ContentResolver wraps the Provider's descriptor. Inject a controlled blocking stream
    // at the client boundary while retaining real Provider queries and descriptor opening.
    @Implements(AssetFileDescriptor.class)
    public static class AssetStreams {
        static volatile BlockingStream blocked;
        @RealObject AssetFileDescriptor descriptor;

        @Implementation protected FileInputStream createInputStream() throws IOException {
            BlockingStream current = blocked;
            if (current == null) return Shadow.directlyOn(descriptor, AssetFileDescriptor.class, "createInputStream");
            current.asset = descriptor;
            return current;
        }
    }

    @Test public void cancelInterruptsProviderQueryWithoutAdvancingOrRecordingAnError() throws Exception {
        provider.blockQuery = true;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (SyncEngine engine = new SyncEngine(context)) {
            Future<SyncEngine.Result> result = worker.submit(() -> engine.runBatch(500));
            try {
                assertTrue(provider.queryStarted.await(10, TimeUnit.SECONDS));
                engine.cancel();
                assertFalse(result.get(10, TimeUnit.SECONDS).success);
                assertEquals(0, provider.queryCancelled.getCount());
                try (SyncStateStore states = new SyncStateStore(context)) {
                    assertEquals(0, states.read().lastId);
                    assertNull(states.read().lastError);
                }
            } finally {
                engine.cancel();
                worker.shutdownNow();
                worker.awaitTermination(10, TimeUnit.SECONDS);
            }
        }
    }

    private static final class BlockingStream extends FileInputStream {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        int reads;
        AssetFileDescriptor asset;

        BlockingStream(File file) throws FileNotFoundException { super(file); }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (reads++ == 0) { bytes[offset] = 42; return 1; }
            reading.countDown();
            try {
                if (!closed.await(10, TimeUnit.SECONDS)) throw new IOException("test read timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            }
            throw new IOException("stream closed");
        }

        @Override public void close() throws IOException {
            closed.countDown();
            super.close();
            if (asset != null) asset.close();
        }
    }

    private static final class BlockingProvider extends ContentProvider {
        final File body;
        boolean blockQuery;
        final CountDownLatch queryStarted = new CountDownLatch(1);
        final CountDownLatch queryCancelled = new CountDownLatch(1);

        BlockingProvider(File body) { this.body = body; }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args,
                                      String order, CancellationSignal signal) {
            if (blockQuery) {
                signal.setOnCancelListener(queryCancelled::countDown);
                queryStarted.countDown();
                try {
                    if (!queryCancelled.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test query timed out");
                } catch (InterruptedException error) { throw new IllegalStateException(error); }
                signal.throwIfCanceled();
            }
            return query(uri, projection, selection, args, order);
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            if ("/status".equals(uri.getPath())) {
                MatrixCursor cursor = new MatrixCursor(new String[]{"apiVersion", "databaseInstanceId", "clipboardGeneration", "currentSequence"});
                cursor.addRow(new Object[]{1, "source", 1, 1L});
                return cursor;
            }
            MatrixCursor cursor = new MatrixCursor(new String[]{"id", "itemType", "createdAt", "isPinned", "preview", "contentUri"});
            if ("0".equals(uri.getQueryParameter("afterId"))) {
                cursor.addRow(new Object[]{1L, "TEXT", CREATED, 0, "preview", "content://" + AUTHORITY + "/items/1/content"});
            }
            return cursor;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(body, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
