package com.ajctrl.sumiresync.sync;

import android.app.job.JobParameters;
import android.app.job.JobService;

import com.ajctrl.sumiresync.data.SyncStateStore;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        try (SyncStateStore states = new SyncStateStore(this)) {
            long requestedRevision = states.requestRevision();
            BatchOutcome outcome = runBatches(params);
            if (!isStopped(params)) {
                if (outcome.caughtUp) states.markRequestHandled(requestedRevision);
                retry = !outcome.succeeded || !outcome.caughtUp || states.hasPendingRequest();
            }
        } catch (RuntimeException ignored) {
            retry = true;
        } finally {
            SyncScheduler.scheduleUploadIfDirty(this);
            finishUnlessStopped(params, retry);
        }
    }

    private BatchOutcome runBatches(JobParameters params) {
        try (SyncEngine engine = new SyncEngine(this)) {
            for (int batch = 0; batch < 20 && !isStopped(params); batch++) {
                SyncEngine.Result result = engine.runBatch(SumireContract.CATCH_UP_BATCH_SIZE);
                if (!result.success) return new BatchOutcome(false, false);
                if (!result.mayHaveMore) return new BatchOutcome(true, true);
            }
        }
        return new BatchOutcome(true, false);
    }

    private static final class BatchOutcome {
        final boolean succeeded;
        final boolean caughtUp;

        BatchOutcome(boolean succeeded, boolean caughtUp) {
            this.succeeded = succeeded;
            this.caughtUp = caughtUp;
        }
    }
}
