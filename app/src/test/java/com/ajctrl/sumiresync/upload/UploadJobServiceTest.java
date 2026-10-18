package com.ajctrl.sumiresync.upload;

import static org.junit.Assert.*;
import static com.ajctrl.sumiresync.data.StoragePaths.database;

import android.app.job.JobParameters;
import android.content.Context;
import android.net.Uri;
import com.ajctrl.sumiresync.data.ArchiveStore;
import com.ajctrl.sumiresync.TestJobParameters;
import com.ajctrl.sumiresync.data.ClipboardItem;
import com.ajctrl.sumiresync.data.SyncStateStore;
import com.ajctrl.sumiresync.data.SyncStatus;
import com.ajctrl.sumiresync.settings.AppSettings;
import com.ajctrl.sumiresync.settings.SecretStore;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30, shadows = {
        UploadJobServiceTest.TestSecrets.class,
        UploadJobServiceTest.ControlledWebDav.class})
public class UploadJobServiceTest {
    @Test public void disablingUploadsWhileOldCallFailsDoesNotRestoreItsError() throws Exception {
        assertOldDestinationFailureIsIgnored("");
    }

    @Test public void changingDestinationWhileOldCallFailsDoesNotRestoreItsError() throws Exception {
        assertOldDestinationFailureIsIgnored("https://new.example.test");
    }

    private void assertOldDestinationFailureIsIgnored(String nextUrl) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AppSettings settings = new AppSettings(context);
        settings.save("https://old.example.test", "alice", false, "secret");
        ControlledWebDav.reset();
        ControlledWebDav.failureAfterRelease = new IOException("old.example.test connection failed");
        var controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            SyncStatus source = new SyncStatus(1, "source", 1, 1);
            states.initializeSource(source);
            add(new ArchiveStore(context, states), source, 1);
            assertTrue(controller.get().onStartJob(TestJobParameters.create(7102)));
            assertTrue(ControlledWebDav.oldStarted.await(10, TimeUnit.SECONDS));
            settings.save(nextUrl, "alice", false, "new-secret");
            states.requestManualSync();
            states.setLocalError("Provider unavailable");
            ControlledWebDav.releaseOld.countDown();
            ExecutorService executor = org.robolectric.util.ReflectionHelpers.getField(controller.get(), "executor");
            executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertEquals("Provider unavailable", states.read().lastError);
            assertEquals(1, states.dirtyArchiveCount());
            assertNull(states.read().lastSyncCompletedAt);
            assertTrue(runUpload(controller.get()));
            assertEquals("Provider unavailable", states.read().lastError);
            assertEquals(nextUrl.isEmpty() ? 1 : 0, states.dirtyArchiveCount());
        } finally {
            ControlledWebDav.releaseOld.countDown();
            controller.destroy();
        }
    }

    @Test public void disabledDestinationClearsOldUploadErrorWithoutCompletingOrClearingLocalError() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        new AppSettings(context).save("", "alice", false, "secret");
        ControlledWebDav.reset();
        var controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            states.initializeSource(new SyncStatus(1, "source", 1, 0));
            states.setUploadError("Old destination failed");
            states.setLocalError("Provider unavailable");
            assertTrue(runUpload(controller.get()));
            assertEquals("Provider unavailable", states.read().lastError);
            assertNull(states.read().lastSyncCompletedAt);
            assertEquals(0, ControlledWebDav.calls.get());
        } finally { controller.destroy(); }
    }

    @Test public void currentDestinationConnectionFailureIsStillReportedByJob() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        new AppSettings(context).save("https://example.test", "alice", false, "secret");
        ControlledWebDav.reset();
        ControlledWebDav.firstFailure = new IOException("current destination connection failed");
        var controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            SyncStatus source = new SyncStatus(1, "source", 1, 1);
            states.initializeSource(source);
            add(new ArchiveStore(context, states), source, 1);
            controller.get().onStartJob(TestJobParameters.create(7102));
            ExecutorService executor = org.robolectric.util.ReflectionHelpers.getField(controller.get(), "executor");
            executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertTrue(states.read().lastError.contains("current destination connection failed"));
            assertEquals(1, states.dirtyArchiveCount());
        } finally { controller.destroy(); }
    }

    @Test public void legacyInvalidReservationDoesNotBlockNamespaceSetupOrHealthyUploads() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        new AppSettings(context).save("https://example.test", "alice", false, "secret");
        ControlledWebDav.reset();
        ControlledWebDav.releaseOld.countDown();
        var controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            SyncStatus source = new SyncStatus(1, "source", 1, 1);
            states.initializeSource(source);
            add(new ArchiveStore(context, states), source, 1);
            String invalid = "clipboard-10000-01.sqlite";
            states.getWritableDatabase().execSQL("INSERT INTO archive_files VALUES(?,0)", new Object[]{invalid});
            states.getWritableDatabase().execSQL("INSERT INTO archive_item_locations VALUES('source',1,2,?)",
                    new Object[]{invalid});
            states.beginArchiveWrite(invalid);
            assertTrue(runUpload(controller.get()));
            assertEquals(1, ControlledWebDav.calls.get());
            assertTrue(states.pendingArchiveWrites().isEmpty());
            assertNull(states.archiveForItem(source, 2));
            assertFalse(states.archiveFiles().contains(invalid));
            assertEquals(0, states.dirtyArchiveCount());
        } finally { controller.destroy(); }
    }

    @Test public void corruptStateStopsUploadAndFinishesEvenWhenErrorRecordingFails() throws Exception {
        assertStateDamageStopsUpload("header");
    }

    @Test public void missingStateStopsUploadWithoutRecreatingTheDatabase() throws Exception {
        assertStateDamageStopsUpload("missing");
    }

    @Test public void emptyStateStopsUploadWithoutReinitializingTheDatabase() throws Exception {
        assertStateDamageStopsUpload("empty");
    }

    private void assertStateDamageStopsUpload(String damage) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        new AppSettings(context).save("https://example.test", "alice", false, "secret");
        try (SyncStateStore states = new SyncStateStore(context)) {
            SyncStatus source = new SyncStatus(1, "source", 1, 1);
            states.initializeSource(source);
            add(new ArchiveStore(context, states), source, 1);
        }
        File state = database(context, "sync-state.sqlite");
        if (damage.equals("missing")) {
            assertTrue(state.delete());
        } else try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(state, "rw")) {
            if (damage.equals("empty")) file.setLength(0);
            else file.write(new byte[32]);
        }
        byte[] damaged = state.exists() ? Files.readAllBytes(state.toPath()) : null;
        ControlledWebDav.reset();
        ServiceController<UploadJobService> controller = Robolectric.buildService(UploadJobService.class).create();
        try {
            var run = controller.get().jobs.start(TestJobParameters.create(7102));
            var method = UploadJobService.class.getDeclaredMethod("runJob", com.ajctrl.sumiresync.sync.JobRuns.Run.class);
            method.setAccessible(true);
            method.invoke(controller.get(), run);
            assertFalse("finished run must no longer acknowledge work", controller.get().jobs.ifActive(run, () -> {}));
            assertEquals(0, ControlledWebDav.calls.get());
            if (damaged == null) assertFalse(state.exists());
            else assertArrayEquals(damaged, Files.readAllBytes(state.toPath()));
        } finally { controller.destroy(); }
    }

    @Test public void thousandsOfLongFailuresKeepOnlyCountAndLatestBoundedDetail() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AppSettings settings = new AppSettings(context);
        settings.save("https://example.test", "alice", false, "secret");
        ServiceController<UploadJobService> controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            var run = controller.get().jobs.start(TestJobParameters.create(7102));
            java.util.Set<String> failures = new java.util.HashSet<>();
            java.lang.reflect.Method record = UploadJobService.class.getDeclaredMethod("recordFileError",
                    com.ajctrl.sumiresync.sync.JobRuns.Run.class, AppSettings.class,
                    AppSettings.UploadSettings.class, SyncStateStore.class,
                    java.util.Set.class, String.class, Exception.class);
            record.setAccessible(true);
            AppSettings.UploadSettings destination = settings.uploadSettings();
            for (int i = 0; i < 1_000; i++) {
                String name = String.format(java.util.Locale.ROOT, "clipboard-2026-10-%03d.sqlite", i);
                assertEquals(true, record.invoke(controller.get(), run, settings, destination, states, failures,
                        name, new ArchiveUploadException("HTTP 413 " + "x".repeat(2_048))));
                assertTrue(states.read().lastError.length() <= 2_000);
            }
            String error = states.read().lastError;
            assertTrue(error.startsWith("1000ファイルのアップロードに失敗。直近：clipboard-2026-10-999.sqlite"));
            assertTrue(error.contains("HTTP 413"));
            assertFalse(error.contains("clipboard-2026-10-000.sqlite"));
            assertTrue(error.endsWith("…"));
            record.invoke(controller.get(), run, settings, destination, states, failures,
                    "clipboard-2026-10-999.sqlite", new ArchiveUploadException("HTTP 412"));
            assertTrue(states.read().lastError.startsWith("1000ファイル"));
            assertTrue(states.read().lastError.endsWith("HTTP 412"));
            assertFalse(states.read().lastError.contains("HTTP 413"));
            controller.get().jobs.stop(run.params);
            assertEquals(false, record.invoke(controller.get(), run, settings, destination, states, failures,
                    "clipboard-2026-11.sqlite", new IOException("stopped")));
            assertFalse(states.read().lastError.contains("stopped"));
        } finally { controller.destroy(); }
    }

    @Test public void corruptArchiveNeverReachesWebDavAndRemainsPending() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        new AppSettings(context).save("https://example.test", "alice", false, "secret");
        ControlledWebDav.reset();
        ControlledWebDav.releaseOld.countDown();
        byte[] previousRemote = new byte[]{1, 2, 3};
        ControlledWebDav.remote.set(previousRemote);
        ServiceController<UploadJobService> controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            SyncStatus source = new SyncStatus(1, "source", 1, 1);
            states.initializeSource(source);
            ArchiveStore archives = new ArchiveStore(context, states);
            add(archives, source, 1);
            File file = database(context, states.dirtyArchives().get(0).fileName);
            try (java.io.RandomAccessFile damage = new java.io.RandomAccessFile(file, "rw")) {
                damage.write(new byte[32]);
            }
            byte[] damaged = Files.readAllBytes(file.toPath());
            assertFalse(runUpload(controller.get()));
            assertEquals(0, ControlledWebDav.calls.get());
            assertArrayEquals(previousRemote, ControlledWebDav.remote.get());
            assertArrayEquals(damaged, Files.readAllBytes(file.toPath()));
            assertEquals(1, states.dirtyArchiveCount());
            assertTrue(states.read().lastError.contains(file.getName()));
            assertNull(states.read().lastSyncCompletedAt);
        } finally { controller.destroy(); }
    }

    @Test public void corruptOlderMonthDoesNotBlockHealthyPartsOrRepeatWithinTheSameJob() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        new AppSettings(context).save("https://example.test", "alice", false, "secret");
        ControlledWebDav.reset();
        ControlledWebDav.releaseOld.countDown();
        ServiceController<UploadJobService> controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            SyncStatus source = new SyncStatus(1, "source", 1, 2);
            states.initializeSource(source);
            long january = 1767225600000L;
            archives.save(source, new ClipboardItem(1, "TEXT", january, false, "old", Uri.parse("content://source/items/1")),
                    new ByteArrayInputStream(new byte[]{1}), null, null, false, () -> {});
            add(archives, source, 2);
            String healthyName = archives.fileNameFor(1_792_000_000_000L);
            String healthyPart = "clipboard-2026-10-001.sqlite";
            assertTrue(database(context, healthyName).renameTo(database(context, healthyPart)));
            states.getWritableDatabase().execSQL("UPDATE archive_files SET file_name=? WHERE file_name=?",
                    new Object[]{healthyPart, healthyName});
            states.getWritableDatabase().execSQL("UPDATE archive_item_locations SET file_name=? WHERE file_name=?",
                    new Object[]{healthyPart, healthyName});
            states.getWritableDatabase().delete("dirty_archives", "file_name=?", new String[]{healthyName});
            states.markDirty(healthyPart);
            File corrupt = database(context, archives.fileNameFor(january));
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(corrupt, "rw")) { file.write(new byte[32]); }
            for (int retry = 0; retry < 2; retry++) {
                assertFalse(runUpload(controller.get()));
                assertEquals("healthy archive is sent once", 1, ControlledWebDav.calls.get());
                assertTrue(ControlledWebDav.oldPath.get().endsWith(healthyPart));
                assertEquals(1, states.dirtyArchiveCount());
                assertTrue(states.read().lastError.contains(corrupt.getName()));
                assertNull(states.read().lastSyncCompletedAt);
            }
        } finally { controller.destroy(); }
    }

    @Test public void missingOlderFileDoesNotBlockHealthyMonth() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        new AppSettings(context).save("https://example.test", "alice", false, "secret");
        ControlledWebDav.reset();
        ControlledWebDav.releaseOld.countDown();
        ServiceController<UploadJobService> controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            SyncStatus source = new SyncStatus(1, "source", 1, 1);
            states.initializeSource(source);
            states.markDirty("clipboard-2026-01.sqlite");
            add(new ArchiveStore(context, states), source, 1);
            assertFalse(runUpload(controller.get()));
            assertEquals(1, ControlledWebDav.calls.get());
            assertEquals(1, states.dirtyArchiveCount());
            assertEquals("clipboard-2026-01.sqlite", states.dirtyArchives().get(0).fileName);
            assertTrue(states.read().lastError.contains("Archive is missing"));
            assertNull(states.read().lastSyncCompletedAt);
        } finally { controller.destroy(); }
    }

    @Test public void archiveSpecificHttpFailureAllowsOtherFilesAndClearsAfterSuccessfulRetry() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        new AppSettings(context).save("https://example.test", "alice", false, "secret");
        ControlledWebDav.reset();
        ControlledWebDav.releaseOld.countDown();
        ControlledWebDav.firstFailure = new ArchiveUploadException("HTTP 412");
        ServiceController<UploadJobService> controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            SyncStatus source = new SyncStatus(1, "source", 1, 2);
            states.initializeSource(source);
            archives.save(source, new ClipboardItem(1, "TEXT", 1767225600000L, false, "old", Uri.parse("content://source/items/1")),
                    new ByteArrayInputStream(new byte[]{1}), null, null, false, () -> {});
            add(archives, source, 2);
            assertFalse(runUpload(controller.get()));
            assertEquals(2, ControlledWebDav.calls.get());
            assertEquals(1, states.dirtyArchiveCount());
            assertTrue(states.read().lastError.contains("clipboard-2026-01.sqlite"));
            assertNull(states.read().lastSyncCompletedAt);
            assertTrue(runUpload(controller.get()));
            assertEquals(3, ControlledWebDav.calls.get());
            assertTrue(states.dirtyArchives().isEmpty());
            assertNull(states.read().lastError);
            assertNotNull(states.read().lastSyncCompletedAt);
        } finally { controller.destroy(); }
    }

    @Test public void connectionFailureStopsTheJobBeforeTryingLaterArchives() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        new AppSettings(context).save("https://example.test", "alice", false, "secret");
        ControlledWebDav.reset();
        ControlledWebDav.releaseOld.countDown();
        ControlledWebDav.firstFailure = new IOException("connection lost");
        ServiceController<UploadJobService> controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            ArchiveStore archives = new ArchiveStore(context, states);
            SyncStatus source = new SyncStatus(1, "source", 1, 2);
            states.initializeSource(source);
            archives.save(source, new ClipboardItem(1, "TEXT", 1767225600000L, false, "old", Uri.parse("content://source/items/1")),
                    new ByteArrayInputStream(new byte[]{1}), null, null, false, () -> {});
            add(archives, source, 2);
            assertThrows(IOException.class, () -> runUpload(controller.get()));
            assertEquals(1, ControlledWebDav.calls.get());
            assertEquals(2, states.dirtyArchiveCount());
        } finally { controller.destroy(); }
    }

    @Test public void namespaceChangeRequeuesCurrentArchiveOnce() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        new AppSettings(context).save("https://example.test", "alice", false, "secret");
        AppSettings.UploadSettings destination = new AppSettings(context).uploadSettings();
        ControlledWebDav.reset();
        ControlledWebDav.releaseOld.countDown();
        ServiceController<UploadJobService> controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            SyncStatus source = new SyncStatus(1, "source", 1, 1);
            states.initializeSource(source);
            add(new ArchiveStore(context, states), source, 1);
            states.prepareUploadDestination(destination.generation);
            for (SyncStateStore.DirtyArchive dirty : states.dirtyArchives()) {
                states.markUploaded(dirty.fileName, dirty.revision, destination.generation);
            }
            assertTrue(states.dirtyArchives().isEmpty());
            assertTrue(runUpload(controller.get()));
            assertEquals(1, ControlledWebDav.calls.get());
            assertEquals(destination.installationId, ControlledWebDav.lastInstallation.get());
            assertTrue(states.dirtyArchives().isEmpty());
            assertTrue(runUpload(controller.get()));
            assertEquals("completed namespace must not migrate again", 1, ControlledWebDav.calls.get());
        } finally { controller.destroy(); }
    }

    @Test public void parcelledStopCancelsOnlyItsRunAndNeverAcknowledgesUpload() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, "sync-state.sqlite"));
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit();
        new AppSettings(context).save("https://example.test", "alice", false, "secret");
        ControlledWebDav.reset();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ServiceController<UploadJobService> controller = Robolectric.buildService(UploadJobService.class).create();
        try (SyncStateStore states = new SyncStateStore(context)) {
            SyncStatus source = new SyncStatus(1, "source", 1, 1);
            states.initializeSource(source);
            add(new ArchiveStore(context, states), source, 1);
            JobParameters start = TestJobParameters.create(7102);
            JobParameters stop = TestJobParameters.copy(start);
            assertNotSame(start, stop);
            var run = controller.get().jobs.start(start);
            var alternate = controller.get().jobs.start(TestJobParameters.create(7105));
            Future<Boolean> upload = worker.submit(() -> controller.get().uploadDirtyArchives(run));
            assertTrue(ControlledWebDav.oldStarted.await(10, TimeUnit.SECONDS));
            controller.get().onStopJob(stop);
            assertEquals(1, ControlledWebDav.cancellations.get());
            assertFalse(alternate.isStopped());
            // Keep Service alive, as when the alternate job is still active.
            ControlledWebDav.releaseOld.countDown();
            assertFalse(upload.get(10, TimeUnit.SECONDS));
            assertEquals(1, states.dirtyArchiveCount());
            assertNull(states.read().lastSyncCompletedAt);
        } finally {
            ControlledWebDav.releaseOld.countDown();
            worker.shutdownNow();
            controller.destroy();
        }
    }

    @Test public void replacementServiceWaitsForStoppedUploadAndSendsNewestRevision() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, "sync-state.sqlite"));
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit();
        new AppSettings(context).save("https://example.test", "alice", false, "secret");
        ControlledWebDav.reset();
        ExecutorService worker = Executors.newFixedThreadPool(2);
        ServiceController<UploadJobService> oldController = Robolectric.buildService(UploadJobService.class).create();
        ServiceController<UploadJobService> newController = null;
        try (SyncStateStore states = new SyncStateStore(context)) {
            SyncStatus source = new SyncStatus(1, "source", 1, 2);
            states.initializeSource(source);
            ArchiveStore archives = new ArchiveStore(context, states);
            add(archives, source, 1);
            Future<Boolean> oldUpload = worker.submit(() -> runUpload(oldController.get()));
            assertTrue("old PUT started", ControlledWebDav.oldStarted.await(10, TimeUnit.SECONDS));
            oldController.get().onStopJob(TestJobParameters.create(7102));
            oldController.destroy();
            Field executorField = UploadJobService.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            assertTrue("service destruction shuts down executor", ((ExecutorService) executorField.get(oldController.get())).isShutdown());
            add(archives, source, 2);
            newController = Robolectric.buildService(UploadJobService.class).create();
            UploadJobService replacement = newController.get();
            CountDownLatch launched = new CountDownLatch(1);
            Future<Boolean> newUpload = worker.submit(() -> { launched.countDown(); return runUpload(replacement); });
            assertTrue(launched.await(5, TimeUnit.SECONDS));
            assertFalse("replacement must wait for the stopped call to drain",
                    ControlledWebDav.newStarted.await(200, TimeUnit.MILLISECONDS));
            assertTrue("stop cancels the active client", ControlledWebDav.cancellations.get() > 0);
            ControlledWebDav.releaseOld.countDown();
            assertFalse("stopped worker does not acknowledge", oldUpload.get(10, TimeUnit.SECONDS));
            assertTrue(newUpload.get(10, TimeUnit.SECONDS));
            assertFalse(Arrays.equals(ControlledWebDav.oldBody.get(), ControlledWebDav.remote.get()));
            assertTrue(states.dirtyArchives().isEmpty());
            assertNotEquals(ControlledWebDav.oldPath.get(), ControlledWebDav.newPath.get());
        } finally {
            ControlledWebDav.releaseOld.countDown();
            worker.shutdownNow();
            if (newController != null) newController.destroy();
        }
    }

    private static boolean runUpload(UploadJobService service) throws Exception {
        return service.uploadDirtyArchives(service.jobs.start(TestJobParameters.create(7102)));
    }

    private static void add(ArchiveStore archives, SyncStatus source, long id) throws Exception {
        archives.save(source, new ClipboardItem(id, "TEXT", 1_792_000_000_000L, false, "entry-" + id,
                        Uri.parse("content://source/items/" + id)),
                new ByteArrayInputStream(("body-" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                null, null, false, () -> {});
    }

    @Implements(value = SecretStore.class, isInAndroidSdk = false)
    public static class TestSecrets {
        @Implementation public String encryptPassword(String password) { return password; }
        @Implementation public String getPassword() { return "secret"; }
    }

    @Implements(value = WebDavClient.class, isInAndroidSdk = false)
    public static class ControlledWebDav {
        static AtomicInteger calls;
        static CountDownLatch oldStarted;
        static CountDownLatch releaseOld;
        static AtomicReference<byte[]> oldBody;
        static AtomicReference<byte[]> remote;
        static AtomicInteger cancellations;
        static CountDownLatch newStarted;
        static AtomicReference<String> oldPath, newPath;
        static AtomicReference<String> lastInstallation;
        static IOException firstFailure;
        static IOException failureAfterRelease;
        static void reset() {
            calls = new AtomicInteger(); oldStarted = new CountDownLatch(1); releaseOld = new CountDownLatch(1);
            oldBody = new AtomicReference<>(); remote = new AtomicReference<>();
            cancellations = new AtomicInteger(); newStarted = new CountDownLatch(1);
            oldPath = new AtomicReference<>(); newPath = new AtomicReference<>();
            lastInstallation = new AtomicReference<>();
            firstFailure = null;
            failureAfterRelease = null;
        }
        @Implementation public void cancel() { cancellations.incrementAndGet(); }
        @Implementation public void put(String server, String user, String pass, String installationId, File file) throws IOException {
            lastInstallation.set(installationId);
            byte[] uploaded = Files.readAllBytes(file.toPath());
            if (calls.incrementAndGet() == 1) {
                if (firstFailure != null) throw firstFailure;
                oldBody.set(uploaded);
                oldPath.set(file.getPath());
                oldStarted.countDown();
                try { if (!releaseOld.await(20, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                if (failureAfterRelease != null) throw failureAfterRelease;
            }
            if (calls.get() > 1) { newPath.set(file.getPath()); newStarted.countDown(); }
            remote.set(uploaded);
        }
    }
}
