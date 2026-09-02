package com.ajctrl.sumiresync.upload;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import com.ajctrl.sumiresync.data.ArchiveStore;
import com.ajctrl.sumiresync.data.SyncStateStore;
import com.ajctrl.sumiresync.settings.AppSettings;
import com.ajctrl.sumiresync.settings.SecretStore;

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
            String url = settings.webDavUrl();
            if (url.isEmpty()) return true;
            String password = new SecretStore(this).getPassword();
            ArchiveStore archives = new ArchiveStore(this, states);
            for (int pass = 0; pass < MAX_DIRTY_PASSES; pass++) {
                if (isStopped(params)) return false;
                List<SyncStateStore.DirtyArchive> dirty = states.dirtyArchives();
                if (dirty.isEmpty()) {
                    states.clearUploadError();
                    return true;
                }
                for (SyncStateStore.DirtyArchive entry : dirty) {
                    if (isStopped(params)) return false;
                    File snapshot = archives.snapshot(entry.fileName);
                    try {
                        new WebDavClient().put(url, settings.webDavUser(), password, snapshot);
                        // onStopJob releases this job's lifecycle guarantee. Never acknowledge
                        // an upload from work that Android has already stopped.
                        if (isStopped(params)) return false;
                        states.markUploaded(entry.fileName, entry.revision);
                    } finally {
                        if (snapshot.exists() && !snapshot.delete()) snapshot.deleteOnExit();
                    }
                }
            }
            boolean complete = states.dirtyArchives().isEmpty();
            if (complete) states.clearUploadError();
            return complete;
        }
    }

    private void recordError(Exception error) {
        Log.e(TAG, "Nextcloud upload failed", error);
        try (SyncStateStore states = new SyncStateStore(this)) {
            states.setError("アップロード失敗\n" + ErrorDetails.describe(error));
        }
    }

}
