package com.ajctrl.sumiresync.sync;

import static org.junit.Assert.*;
import static com.ajctrl.sumiresync.data.StoragePaths.database;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import com.ajctrl.sumiresync.data.SyncStateStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {30, 35})
public class SyncSchedulerTest {
    @Test public void failedSchedulingReturnsFalseAndKeepsTheDurableRequest() {
        Context context = RuntimeEnvironment.getApplication();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        org.robolectric.Shadows.shadowOf(scheduler).failOnJob(SyncScheduler.CATCH_UP_JOB_ID);
        org.robolectric.Shadows.shadowOf(scheduler).failOnJob(SyncScheduler.RETRY_CATCH_UP_JOB_ID);
        assertFalse(SyncScheduler.requestManualSync(context));
        assertNull(scheduler.getPendingJob(SyncScheduler.CATCH_UP_JOB_ID));
        assertNull(scheduler.getPendingJob(SyncScheduler.RETRY_CATCH_UP_JOB_ID));
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertTrue(states.hasPendingRequest());
            assertTrue(states.read().lastError.contains("could not be scheduled"));
        }
    }

    @Test public void corruptStateRejectsRequestsWithoutSchedulingOrDeletingTheDatabase() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        scheduler.cancelAll();
        try (SyncStateStore states = new SyncStateStore(context)) { states.requestSync(); }
        java.io.File state = database(context, "sync-state.sqlite");
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(state, "rw")) {
            file.write(new byte[32]);
        }
        byte[] damaged = java.nio.file.Files.readAllBytes(state.toPath());
        SyncScheduler.requestCatchUp(context);
        assertFalse(SyncScheduler.requestManualSync(context));
        SyncScheduler.scheduleUpload(context);
        SyncScheduler.scheduleUploadIfDirty(context);
        assertTrue(scheduler.getAllPendingJobs().isEmpty());
        assertArrayEquals(damaged, java.nio.file.Files.readAllBytes(state.toPath()));
    }

    @Test public void catchUpRequestSchedulesWorkerOnSupportedAndroidVersions() {
        Context context = RuntimeEnvironment.getApplication();
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        scheduler.cancelAll();
        android.database.sqlite.SQLiteDatabase.deleteDatabase(database(context, "sync-state.sqlite"));

        SyncScheduler.requestCatchUp(context);

        JobInfo job = scheduler.getPendingJob(SyncScheduler.CATCH_UP_JOB_ID);
        assertNotNull(job);
        assertEquals(new ComponentName(context, CatchUpJobService.class), job.getService());
        try (SyncStateStore states = new SyncStateStore(context)) {
            assertTrue(states.hasPendingRequest());
            assertNull(states.read().lastError);
        }
    }
}
