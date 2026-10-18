package com.ajctrl.sumiresync.sync;

import android.app.job.JobParameters;
import android.app.job.JobService;

import com.ajctrl.sumiresync.data.SyncStateStore;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class CatchUpJobService extends JobService {
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
        try (SyncStateStore states = new SyncStateStore(this);
             SyncEngine engine = new SyncEngine(this)) {
            retry = !catchUp(params, states,
                    () -> engine.runBatch(SumireContract.CATCH_UP_BATCH_SIZE));
        } catch (RuntimeException ignored) {
            retry = true;
        } finally {
            // Also confirm completion when catch-up found no new data, or an upload
            // finished before this worker acknowledged the local sync request.
            if (!retry) SyncScheduler.scheduleUpload(this);
            else SyncScheduler.scheduleUploadIfDirty(this);
            finishUnlessStopped(params, retry);
        }
    }

    boolean catchUp(JobParameters params, SyncStateStore states,
                    Supplier<SyncEngine.Result> nextBatch) {
        // Periodic jobs bypass requestCatchUp(). Persist a pending request for every
        // execution before reading data, and retain it across partial or stopped runs.
        long requestedRevision = states.requestSync();
        for (int batch = 0; batch < 20 && !isStopped(params); batch++) {
            SyncEngine.Result result = nextBatch.get();
            if (!result.success) return false;
            if (!result.mayHaveMore) {
                if (isStopped(params)) return false;
                states.markRequestHandled(requestedRevision);
                return !states.hasPendingRequest();
            }
        }
        return false;
    }
}
