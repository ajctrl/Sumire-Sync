package com.ajctrl.sumiresync.upload;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import com.ajctrl.sumiresync.data.ArchiveStore;
import com.ajctrl.sumiresync.data.SyncStateStore;
import com.ajctrl.sumiresync.settings.AppSettings;
import com.ajctrl.sumiresync.sync.JobRuns;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class UploadJobService extends JobService {
    private static final String TAG = "SumireSyncUpload";
    private static final int MAX_DIRTY_PASSES = 20;
    private static final ReentrantLock UPLOAD_LOCK = new ReentrantLock();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    final JobRuns jobs = new JobRuns();

    @Override public boolean onStartJob(JobParameters params) {
        JobRuns.Run run = jobs.start(params);
        executor.execute(() -> runJob(run));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        jobs.stop(params);
        return true;
    }

    @Override public void onDestroy() {
        jobs.destroy();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void runJob(JobRuns.Run run) {
        boolean retry = true;
        AppSettings settings = null;
        Long generation = null;
        try {
            settings = new AppSettings(this);
            generation = settings.uploadGeneration();
            AppSettings.UploadSettings destination = settings.uploadSettings();
            generation = destination.generation;
            retry = !uploadDirtyArchives(run, destination);
        } catch (Exception error) {
            if (generation == null) jobs.ifActive(run, () -> recordError(error));
            else ifCurrent(run, settings, generation, () -> recordError(error));
        } finally {
            boolean needsRetry = retry;
            jobs.finish(run, () -> jobFinished(run.params, needsRetry));
        }
    }

    boolean uploadDirtyArchives(JobRuns.Run run) throws Exception {
        return uploadDirtyArchives(run, new AppSettings(this).uploadSettings());
    }

    private boolean uploadDirtyArchives(JobRuns.Run run, AppSettings.UploadSettings destination) throws Exception {
        // The lock outlives Service instances. A replacement worker waits until the
        // stopped worker has left its network call and released its private snapshot.
        while (!run.isStopped()) {
            if (UPLOAD_LOCK.tryLock(250, TimeUnit.MILLISECONDS)) {
                try {
                    if (run.isStopped()) return false;
                    WebDavClient client = new WebDavClient();
                    jobs.onCancel(run, client::cancel);
                    try { return uploadWithClient(run, client, destination); }
                    finally { jobs.onCancel(run, null); }
                } finally { UPLOAD_LOCK.unlock(); }
            }
        }
        return false;
    }

    private boolean uploadWithClient(JobRuns.Run run, WebDavClient client,
                                     AppSettings.UploadSettings destination) throws Exception {
        try (SyncStateStore states = new SyncStateStore(this)) {
            AppSettings settings = new AppSettings(this);
            if (settings.uploadGeneration() != destination.generation) return false;
            if (!states.prepareUploadDestination(destination.generation)) return false;
            states.prepareUploadNamespace(destination.installationId);
            if (destination.url.isEmpty()) {
                return ifCurrent(run, settings, destination.generation, states::clearUploadError);
            }
            ArchiveStore archives = new ArchiveStore(this, states);
            archives.recoverPendingWrites();
            archives.clearAbandonedSnapshots();
            Set<String> failures = new HashSet<>();
            for (int pass = 0; pass < MAX_DIRTY_PASSES; pass++) {
                if (run.isStopped() || settings.uploadGeneration() != destination.generation) return false;
                List<SyncStateStore.DirtyArchive> dirty = states.dirtyArchives();
                if (dirty.isEmpty()) {
                    return completeSync(run, settings, destination, states);
                }
                boolean attempted = false;
                for (SyncStateStore.DirtyArchive entry : dirty) {
                    if (run.isStopped() || settings.uploadGeneration() != destination.generation) return false;
                    // Retry a failed file on the next job, not on every pass of this job.
                    if (failures.contains(entry.fileName)) continue;
                    attempted = true;
                    File snapshot;
                    try {
                        snapshot = archives.snapshot(entry.fileName);
                    } catch (IOException | SQLiteException error) {
                        if (!recordFileError(run, settings, destination, states, failures, entry.fileName, error)) return false;
                        continue;
                    }
                    try {
                        try {
                            client.put(destination.url, destination.user, destination.password, destination.installationId, snapshot);
                        } catch (ArchiveUploadException error) {
                            if (!recordFileError(run, settings, destination, states, failures, entry.fileName, error)) return false;
                            continue;
                        }
                        // onStopJob releases this job's lifecycle guarantee. Never acknowledge
                        // an upload from work that Android has already stopped.
                        if (settings.uploadGeneration() != destination.generation) return false;
                        if (!ifCurrent(run, settings, destination.generation, () -> states.markUploaded(
                                entry.fileName, entry.revision, destination.generation))) return false;
                    } finally {
                        ArchiveStore.deleteSnapshot(snapshot);
                    }
                }
                if (!attempted) return false;
            }
            boolean complete = states.dirtyArchives().isEmpty();
            return complete && completeSync(run, settings, destination, states);
        }
    }

    private boolean recordFileError(JobRuns.Run run, AppSettings settings,
                                    AppSettings.UploadSettings destination, SyncStateStore states,
                                    Set<String> failures, String fileName, Exception error) {
        if (settings.uploadGeneration() != destination.generation) return false;
        failures.add(fileName);
        return ifCurrent(run, settings, destination.generation, () -> states.setUploadError(failures.size()
                + "ファイルのアップロードに失敗。直近：" + fileName + " — " + ErrorDetails.describe(error)));
    }

    private boolean completeSync(JobRuns.Run run, AppSettings settings,
                                 AppSettings.UploadSettings destination, SyncStateStore states) {
        if (settings.uploadGeneration() != destination.generation) return false;
        return ifCurrent(run, settings, destination.generation, () -> {
            states.clearUploadError();
            states.markSyncCompleted(destination.generation);
        });
    }

    private boolean ifCurrent(JobRuns.Run run, AppSettings settings, long generation, Runnable action) {
        boolean[] applied = {false};
        return jobs.ifActive(run,
                () -> applied[0] = settings.ifUploadGeneration(generation, action)) && applied[0];
    }

    private void recordError(Exception error) {
        Log.e(TAG, "Nextcloud upload failed", error);
        try (SyncStateStore states = new SyncStateStore(this)) {
            states.setUploadError("アップロード失敗\n" + ErrorDetails.describe(error));
        } catch (SQLiteException reportingError) {
            // State corruption also prevents error persistence. Preserve the DB and
            // let the job finish normally; the UI reports the read failure directly.
            Log.e(TAG, "Cannot record upload error", reportingError);
        }
    }

}
