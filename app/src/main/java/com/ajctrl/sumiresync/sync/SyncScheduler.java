package com.ajctrl.sumiresync.sync;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import com.ajctrl.sumiresync.data.SyncStateStore;
import com.ajctrl.sumiresync.upload.UploadJobService;

public final class SyncScheduler {
    static final int CATCH_UP_JOB_ID = 7101;
    public static final int UPLOAD_JOB_ID = 7102;
    private static final int PERIODIC_CATCH_UP_JOB_ID = 7103;
    static final int RETRY_CATCH_UP_JOB_ID = 7104;
    private static final int RETRY_UPLOAD_JOB_ID = 7105;

    private SyncScheduler() {}

    public static void requestCatchUp(Context context) {
        requestCatchUp(context, false);
    }

    public static void requestManualSync(Context context) {
        requestCatchUp(context, true);
        scheduleUpload(context);
    }

    private static void requestCatchUp(Context context, boolean manual) {
        Context app = context.getApplicationContext();
        try (SyncStateStore states = new SyncStateStore(app)) {
            if (manual) {
                states.requestManualSync();
            } else {
                states.requestSync();
            }
            try {
                if (!scheduleCatchUp(app)) states.setError("Catch-up job could not be scheduled");
            } catch (RuntimeException error) {
                states.setError("Catch-up job could not be scheduled: "
                        + (error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage()));
            }
        }
    }

    private static synchronized boolean scheduleCatchUp(Context context) {
        JobScheduler scheduler = scheduler(context);
        if (scheduler.getPendingJob(CATCH_UP_JOB_ID) != null) {
            return ensureAlternateCatchUp(context, scheduler);
        }
        if (scheduler.schedule(catchUpJob(context, CATCH_UP_JOB_ID, false))
                == JobScheduler.RESULT_SUCCESS) {
            return true;
        }
        return ensureAlternateCatchUp(context, scheduler);
    }

    private static boolean ensureAlternateCatchUp(Context context, JobScheduler scheduler) {
        // Never replace the primary while it is running. One alternate slot is enough:
        // its worker reads the latest durable request revision when execution actually starts.
        if (scheduler.getPendingJob(RETRY_CATCH_UP_JOB_ID) != null) return true;
        return scheduler.schedule(catchUpJob(context, RETRY_CATCH_UP_JOB_ID, true))
                == JobScheduler.RESULT_SUCCESS;
    }

    private static JobInfo catchUpJob(Context context, int jobId, boolean delayed) {
        JobInfo.Builder builder = new JobInfo.Builder(jobId,
                new ComponentName(context, CatchUpJobService.class))
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if (delayed) {
            builder.setMinimumLatency(1_000);
            builder.setOverrideDeadline(30_000);
        }
        return builder.build();
    }

    public static synchronized void schedulePeriodicCatchUp(Context context) {
        Context app = context.getApplicationContext();
        try {
            JobScheduler scheduler = scheduler(app);
            // getPendingJob() also includes a currently executing job. Do not replace it
            // when the activity is opened while a periodic catch-up is running.
            if (scheduler.getPendingJob(PERIODIC_CATCH_UP_JOB_ID) != null) return;
            JobInfo job = new JobInfo.Builder(PERIODIC_CATCH_UP_JOB_ID,
                    new ComponentName(app, CatchUpJobService.class))
                    .setPersisted(true)
                    .setPeriodic(6L * 60L * 60L * 1000L)
                    .build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                recordCatchUpScheduleError(app, "Periodic catch-up job could not be scheduled");
            }
        } catch (RuntimeException error) {
            recordCatchUpScheduleError(app, "Periodic catch-up job could not be scheduled: "
                    + errorDetail(error));
        }
    }

    public static void scheduleUpload(Context context) {
        Context app = context.getApplicationContext();
        try {
            if (!scheduleUploadSlot(app)) recordUploadScheduleError(app, null);
        } catch (RuntimeException error) {
            recordUploadScheduleError(app, error);
        }
    }

    private static synchronized boolean scheduleUploadSlot(Context context) {
        JobScheduler scheduler = scheduler(context);
        if (scheduler.getPendingJob(UPLOAD_JOB_ID) != null) {
            return ensureAlternateUpload(context, scheduler);
        }
        if (scheduler.schedule(uploadJob(context, UPLOAD_JOB_ID, false))
                == JobScheduler.RESULT_SUCCESS) {
            return true;
        }
        return ensureAlternateUpload(context, scheduler);
    }

    private static boolean ensureAlternateUpload(Context context, JobScheduler scheduler) {
        if (scheduler.getPendingJob(RETRY_UPLOAD_JOB_ID) != null) return true;
        return scheduler.schedule(uploadJob(context, RETRY_UPLOAD_JOB_ID, true))
                == JobScheduler.RESULT_SUCCESS;
    }

    public static void scheduleUploadIfDirty(Context context) {
        try (SyncStateStore states = new SyncStateStore(context)) {
            if (!states.dirtyArchives().isEmpty()) scheduleUpload(context);
        } catch (RuntimeException ignored) {
            // dirty_archives is durable; a later catch-up or boot will try again.
        }
    }

    private static JobInfo uploadJob(Context context, int jobId, boolean retry) {
        JobInfo.Builder builder = new JobInfo.Builder(jobId,
                new ComponentName(context, UploadJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if (retry) {
            builder.setMinimumLatency(30_000);
        }
        if (Build.VERSION.SDK_INT >= 28) builder.setEstimatedNetworkBytes(0, 1024 * 1024);
        return builder.build();
    }

    private static void recordUploadScheduleError(Context context, RuntimeException error) {
        try (SyncStateStore states = new SyncStateStore(context)) {
            String detail = error == null ? "" : ": " + errorDetail(error);
            states.setError("Upload job could not be scheduled" + detail);
        } catch (RuntimeException ignored) {
            // dirty_archives remains durable even if error reporting also fails.
        }
    }

    private static void recordCatchUpScheduleError(Context context, String message) {
        try (SyncStateStore states = new SyncStateStore(context)) {
            states.setError(message);
        } catch (RuntimeException ignored) {
            // request_revision remains durable even if error reporting also fails.
        }
    }

    private static String errorDetail(RuntimeException error) {
        return error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static JobScheduler scheduler(Context context) {
        return (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }
}
