package com.ajctrl.sumiresync.sync;

import android.app.job.JobParameters;
import android.app.job.JobService;

import com.ajctrl.sumiresync.data.SyncStateStore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class CatchUpJobService extends JobService {
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
        try (SyncStateStore states = new SyncStateStore(this);
             SyncEngine engine = new SyncEngine(this)) {
            jobs.onCancel(run, engine::cancel);
            try {
                retry = !catchUp(run, states,
                        () -> engine.runBatch(SumireContract.CATCH_UP_BATCH_SIZE, run::isStopped));
            } finally {
                jobs.onCancel(run, null);
            }
        } catch (RuntimeException ignored) {
            retry = true;
        } finally {
            // Also confirm completion when catch-up found no new data, or an upload
            // finished before this worker acknowledged the local sync request.
            if (!retry) SyncScheduler.scheduleUpload(this);
            else SyncScheduler.scheduleUploadIfDirty(this);
            boolean needsRetry = retry;
            jobs.finish(run, () -> jobFinished(run.params, needsRetry));
        }
    }

    boolean catchUp(JobRuns.Run run, SyncStateStore states,
                    Supplier<SyncEngine.Result> nextBatch) {
        // Periodic jobs bypass requestCatchUp(). Persist a pending request for every
        // execution before reading data, and retain it across partial or stopped runs.
        long requestedRevision = states.requestSync();
        for (int batch = 0; batch < 20 && !run.isStopped(); batch++) {
            SyncEngine.Result result = nextBatch.get();
            if (!result.success) return false;
            if (!result.mayHaveMore) {
                return jobs.ifActive(run, () -> states.markRequestHandled(requestedRevision))
                        && !states.hasPendingRequest();
            }
        }
        return false;
    }
}
