package com.ajctrl.sumiresync.upload;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import com.ajctrl.sumiresync.data.ArchiveStore;
import com.ajctrl.sumiresync.data.SyncStateStore;
import com.ajctrl.sumiresync.settings.AppSettings;

import java.io.File;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UploadJobService extends JobService {
    private static final String TAG = "SumireSyncUpload";
    private static final int MAX_DIRTY_PASSES = 20;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<JobParameters> stoppedJobs = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));

    @Override public boolean onStartJob(JobParameters params) {
        stoppedJobs.remove(params);
        executor.execute(() -> runJob(params));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        stoppedJobs.add(params);
        return true;
    }

    private boolean isStopped(JobParameters params) {
        return stoppedJobs.contains(params);
    }

    private void finishUnlessStopped(JobParameters params, boolean retry) {
        synchronized (stoppedJobs) {
            if (!stoppedJobs.contains(params)) jobFinished(params, retry);
            stoppedJobs.remove(params);
        }
    }

    private void runJob(JobParameters params) {
        boolean retry = true;
        try {
            retry = !uploadDirtyArchives(params);
        } catch (Exception error) {
            recordError(error);
        } finally {
            finishUnlessStopped(params, retry);
        }
    }

    private boolean uploadDirtyArchives(JobParameters params) throws Exception {
        try (SyncStateStore states = new SyncStateStore(this)) {
            AppSettings settings = new AppSettings(this);
            AppSettings.UploadSettings destination = settings.uploadSettings();
            if (!states.prepareUploadDestination(destination.generation)) return false;
            if (destination.url.isEmpty()) return true;
            ArchiveStore archives = new ArchiveStore(this, states);
            for (int pass = 0; pass < MAX_DIRTY_PASSES; pass++) {
                if (isStopped(params) || settings.uploadGeneration() != destination.generation) return false;
                List<SyncStateStore.DirtyArchive> dirty = states.dirtyArchives();
                if (dirty.isEmpty()) {
                    return completeSync(params, settings, destination, states);
                }
                for (SyncStateStore.DirtyArchive entry : dirty) {
                    if (isStopped(params) || settings.uploadGeneration() != destination.generation) return false;
                    File snapshot = archives.snapshot(entry.fileName);
                    try {
                        new WebDavClient().put(destination.url, destination.user, destination.password, snapshot);
                        // onStopJob releases this job's lifecycle guarantee. Never acknowledge
                        // an upload from work that Android has already stopped.
                        if (isStopped(params) || settings.uploadGeneration() != destination.generation) return false;
                        states.markUploaded(entry.fileName, entry.revision, destination.generation);
                    } finally {
                        if (snapshot.exists() && !snapshot.delete()) snapshot.deleteOnExit();
                    }
                }
            }
            boolean complete = states.dirtyArchives().isEmpty();
            return complete && completeSync(params, settings, destination, states);
        }
    }

    private boolean completeSync(JobParameters params, AppSettings settings,
                                 AppSettings.UploadSettings destination, SyncStateStore states) {
        synchronized (stoppedJobs) {
            if (isStopped(params) || settings.uploadGeneration() != destination.generation) return false;
            states.clearUploadError();
            states.markSyncCompleted(destination.generation);
            return true;
        }
    }

    private void recordError(Exception error) {
        Log.e(TAG, "Nextcloud upload failed", error);
        try (SyncStateStore states = new SyncStateStore(this)) {
            states.setError("アップロード失敗\n" + ErrorDetails.describe(error));
        }
    }

}
